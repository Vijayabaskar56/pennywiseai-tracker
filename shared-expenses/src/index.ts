// PennyWise shared expenses: a Worker + D1 implementing docs/shared-expenses-api.md.
// Logs no request bodies, amounts or names.

import { authenticate, clientIp, registerDevice } from "./auth";
import { changes } from "./changes";
import { DEFAULT_OPTIONS, type Ctx, type Options } from "./context";
import { runRecurring } from "./cron";
import { deleteExpense, putExpense, restoreExpense } from "./expenses";
import { addMember, createGroup, deleteGroup, getMe, myGroups, patchGroup, patchMember, putMe, removeMember } from "./groups";
import { ApiError, errorResponse, json, readJson } from "./http";
import { acceptInvite, createInvite, joinPage, listInvites, previewInvite, revokeInvite } from "./invites";
import { deleteRecurring, putRecurring } from "./recurring";
import { Router } from "./router";
import { deleteSettlement, putSettlement } from "./settlements";

type Handler = (ctx: Ctx) => Promise<Response>;

const api = new Router<Handler>()
	.on("GET", "/v1/me", getMe)
	.on("PUT", "/v1/me", putMe)
	.on("GET", "/v1/me/groups", myGroups)
	.on("POST", "/v1/groups", createGroup)
	.on("PATCH", "/v1/groups/:g", patchGroup)
	.on("DELETE", "/v1/groups/:g", deleteGroup)
	.on("POST", "/v1/groups/:g/members", addMember)
	.on("PATCH", "/v1/groups/:g/members/:m", patchMember)
	.on("DELETE", "/v1/groups/:g/members/:m", removeMember)
	.on("POST", "/v1/groups/:g/invites", createInvite)
	.on("GET", "/v1/groups/:g/invites", listInvites)
	.on("DELETE", "/v1/groups/:g/invites/:id", revokeInvite)
	.on("GET", "/v1/invites/:token", previewInvite)
	.on("POST", "/v1/invites/:token/accept", acceptInvite)
	.on("PUT", "/v1/groups/:g/expenses/:id", putExpense)
	.on("DELETE", "/v1/groups/:g/expenses/:id", deleteExpense)
	.on("POST", "/v1/groups/:g/expenses/:id/restore", restoreExpense)
	.on("PUT", "/v1/groups/:g/settlements/:id", putSettlement)
	.on("DELETE", "/v1/groups/:g/settlements/:id", deleteSettlement)
	.on("PUT", "/v1/groups/:g/recurring/:id", putRecurring)
	.on("DELETE", "/v1/groups/:g/recurring/:id", deleteRecurring)
	.on("GET", "/v1/groups/:g/changes", changes);

const rateLimited = () => errorResponse(new ApiError(429, "rate_limited", "Too many requests, slow down"));

/** The fetch handler with injectable options (tests lower the `changes` page size). */
export async function handleRequest(request: Request, env: Env, options: Options = DEFAULT_OPTIONS): Promise<Response> {
	const url = new URL(request.url);
	const path = url.pathname;
	const now = Date.now();
	try {
		// Tokens are base64url, so the raw segment is the token; anything else gets the "not valid" page.
		if (request.method === "GET" && path.startsWith("/join/")) return joinPage(env, path.slice("/join/".length));

		if (path === "/v1/device" && request.method === "POST") {
			if (!(await env.REGISTER_LIMIT.limit({ key: clientIp(request) })).success) return rateLimited();
			return json(await registerDevice(env.DB, now), 201);
		}

		if (path.startsWith("/v1/")) {
			const userId = await authenticate(request, env.DB);
			if (!userId) return errorResponse(new ApiError(401, "unauthorized", "Missing or wrong credentials"));
			const limiter = path.startsWith("/v1/invites/") ? env.INVITE_LIMIT : env.API_LIMIT;
			if (!(await limiter.limit({ key: userId })).success) return rateLimited();
			const route = api.match(request.method, path);
			if (!route) return errorResponse(new ApiError(404, "not_found", "No such route"));
			let body: Promise<Record<string, unknown>> | undefined;
			const ctx: Ctx = { request, url, env, db: env.DB, userId, now, params: route.params, options, body: () => (body ??= readJson(request)) };
			return await route.handler(ctx);
		}

		if (path === "/") return new Response("PennyWise shared expenses server.\n");
		return errorResponse(new ApiError(404, "not_found", "No such route"));
	} catch (e) {
		if (e instanceof ApiError) return errorResponse(e);
		// The message only: D1 errors name tables and constraints, never the bound values.
		console.error("request failed", request.method, routeName(path), e instanceof Error ? e.message : "error");
		return errorResponse(new ApiError(500, "internal", "Something went wrong"));
	}
}

/** The path with IDs and tokens replaced, so logs never carry an invite token. */
const routeName = (path: string) => path.replace(/\/[^/]{20,}/g, "/:id");

export default {
	fetch: (request, env) => handleRequest(request, env),
	async scheduled(controller, env, ctx) {
		ctx.waitUntil(runRecurring(env.DB, controller.scheduledTime));
	},
} satisfies ExportedHandler<Env>;
