// Invite links. A seat invite (memberId set) hands that seat to whoever accepts it, once; an open invite
// makes a new seat for each caller, up to 20 times. Tokens are 256 random bits; only their hash is stored.

import { randomBase64Url, sha256, TOKEN_RE } from "./auth";
import { countActiveGroups, countActiveMembers, groupAccess, LIMITS, type Ctx } from "./context";
import { ApiError, gone, json, limitReached, noContent, notFound } from "./http";
import type { MemberRow } from "./rows";
import { isUuid, readName } from "./validate";
import { runWrite } from "./write";

const LIFETIME_MS = 7 * 24 * 60 * 60 * 1000;
const OPEN_INVITE_USES = 20;

type InviteRow = {
	id: string; group_id: string; member_id: string | null; token_hash: string; created_by: string;
	created_at: number; expires_at: number; uses: number; max_uses: number; revoked_at: number | null;
};

const baseUrl = (env: Env) => (env.BASE_URL as string).replace(/\/+$/, "");
const usable = (i: InviteRow, now: number) => i.revoked_at === null && i.expires_at > now && i.uses < i.max_uses;

/** POST /v1/groups/:g/invites with `{ "memberId" }` for a seat invite or `{}` for an open one. */
export async function createInvite(ctx: Ctx): Promise<Response> {
	const { group, memberId } = await groupAccess(ctx);
	const body = await ctx.body();
	const seat = body.memberId ?? null;
	if (seat !== null) {
		const m = isUuid(seat) ? await ctx.db.prepare("SELECT removed_at FROM members WHERE id = ? AND group_id = ?").bind(seat, group.id).first<{ removed_at: number | null }>() : null;
		if (!m || m.removed_at !== null) throw notFound("Unknown member");
	}
	const id = crypto.randomUUID();
	const token = randomBase64Url(32);
	const expiresAt = ctx.now + LIFETIME_MS;
	await ctx.db
		.prepare("INSERT INTO invites (id, group_id, member_id, token_hash, created_by, created_at, expires_at, uses, max_uses, revoked_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, NULL)")
		.bind(id, group.id, seat, await sha256(token), memberId, ctx.now, expiresAt, seat === null ? OPEN_INVITE_USES : 1)
		.run();
	return json({ id, token, url: `${baseUrl(ctx.env)}/join/${token}`, memberId: seat, expiresAt }, 201);
}

/** GET /v1/groups/:g/invites: the ones that can still be used. */
export async function listInvites(ctx: Ctx): Promise<Response> {
	const { group } = await groupAccess(ctx);
	const { results } = await ctx.db
		.prepare(
			`SELECT i.* FROM invites i LEFT JOIN members m ON m.id = i.member_id
			WHERE i.group_id = ? AND i.revoked_at IS NULL AND i.expires_at > ? AND i.uses < i.max_uses AND m.removed_at IS NULL
			ORDER BY i.created_at`,
		)
		.bind(group.id, ctx.now)
		.all<InviteRow>();
	return json({ invites: results.map((i) => ({ id: i.id, memberId: i.member_id, expiresAt: i.expires_at, uses: i.uses })) });
}

/** DELETE /v1/groups/:g/invites/:id */
export async function revokeInvite(ctx: Ctx): Promise<Response> {
	const { group } = await groupAccess(ctx);
	const found = await ctx.db.prepare("SELECT id FROM invites WHERE id = ? AND group_id = ?").bind(ctx.params.id, group.id).first();
	if (!found) throw notFound("Unknown invite");
	await ctx.db.prepare("UPDATE invites SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").bind(ctx.now, ctx.params.id).run();
	return noContent();
}

type Lookup = { invite: InviteRow; groupName: string; groupRev: number; seat: MemberRow | null; mySeat: string | null };

/** The invite behind a token: 404 when unknown, 410 when its group was deleted. */
async function lookup(ctx: Ctx): Promise<Lookup> {
	const token = ctx.params.token;
	if (!TOKEN_RE.test(token)) throw notFound("Unknown invite");
	const invite = await ctx.db.prepare("SELECT * FROM invites WHERE token_hash = ?").bind(await sha256(token)).first<InviteRow>();
	if (!invite) throw notFound("Unknown invite");
	const [g, seat, mine] = await ctx.db.batch([
		ctx.db.prepare("SELECT name, rev, deleted_at FROM groups WHERE id = ?").bind(invite.group_id),
		ctx.db.prepare("SELECT * FROM members WHERE id = ?").bind(invite.member_id),
		ctx.db.prepare("SELECT id FROM members WHERE group_id = ? AND user_id = ? AND removed_at IS NULL").bind(invite.group_id, ctx.userId),
	]);
	const group = g.results[0] as { name: string; rev: number; deleted_at: number | null };
	if (group.deleted_at !== null) throw gone();
	return {
		invite,
		groupName: group.name,
		groupRev: group.rev,
		seat: (seat.results[0] as MemberRow | undefined) ?? null,
		mySeat: (mine.results[0] as { id: string } | undefined)?.id ?? null,
	};
}

/** Whether the invite can still be used by someone new. */
const stillValid = (l: Lookup, now: number) => usable(l.invite, now) && (l.invite.member_id === null || (l.seat !== null && l.seat.removed_at === null));

/** GET /v1/invites/:token: what the app shows before joining. */
export async function previewInvite(ctx: Ctx): Promise<Response> {
	const l = await lookup(ctx);
	if (!l.mySeat && !stillValid(l, ctx.now)) throw gone();
	return json({
		groupId: l.invite.group_id,
		groupName: l.groupName,
		memberDisplayName: l.seat?.display_name ?? null,
		expiresAt: l.invite.expires_at,
		alreadyMember: l.mySeat !== null,
	});
}

/**
 * POST /v1/invites/:token/accept. A caller who already holds a seat in the group gets that seat back
 * (200, no write) unless the invite is for a different seat (409 already_member).
 */
export async function acceptInvite(ctx: Ctx): Promise<Response> {
	const displayName = readName((await ctx.body()).displayName, "displayName", LIMITS.displayName);
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const l = await lookup(ctx);
		const { invite } = l;
		if (l.mySeat) {
			if (invite.member_id !== null && invite.member_id !== l.mySeat) {
				throw new ApiError(409, "already_member", "You already hold another seat in this group");
			}
			return { done: json({ groupId: invite.group_id, memberId: l.mySeat }) };
		}
		if (!stillValid(l, ctx.now)) throw gone();
		if ((await countActiveGroups(ctx.db, ctx.userId)) >= LIMITS.groupsPerUser) throw limitReached("groups", `You can be in up to ${LIMITS.groupsPerUser} groups`);
		if (invite.member_id === null && (await countActiveMembers(ctx.db, invite.group_id)) >= LIMITS.membersPerGroup) {
			throw limitReached("members", `A group can have up to ${LIMITS.membersPerGroup} members`);
		}
		const memberId = invite.member_id ?? crypto.randomUUID();
		const useInvite = ctx.db.prepare("UPDATE invites SET uses = uses + 1 WHERE id = ?").bind(invite.id);
		return {
			groupId: invite.group_id,
			baseRev: l.groupRev,
			// Taking a seat replaces its user_id, which cuts off the phone that held it.
			statements: (rev) => [
				invite.member_id !== null
					? ctx.db.prepare("UPDATE members SET user_id = ?, display_name = ?, rev = ? WHERE id = ?").bind(ctx.userId, displayName, rev, memberId)
					: ctx.db.prepare("INSERT INTO members (id, group_id, user_id, display_name, created_at, removed_at, rev) VALUES (?, ?, ?, ?, ?, NULL, ?)")
						.bind(memberId, invite.group_id, ctx.userId, displayName, ctx.now, rev),
				useInvite,
			],
			activity: { type: "MEMBER_JOINED", entityId: memberId, summary: { memberName: displayName }, actor: memberId },
			respond: () => json({ groupId: invite.group_id, memberId }),
		};
	});
}

// ── GET /join/:token: the page an invite link opens. It shows nothing about the group. ──

const escapeHtml = (s: string) =>
	s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

const PAGE_HEADERS = {
	"Content-Type": "text/html; charset=utf-8",
	"Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
	"Referrer-Policy": "no-referrer",
	"X-Content-Type-Options": "nosniff",
	"X-Frame-Options": "DENY",
	"X-Robots-Tag": "noindex, nofollow",
	"Cache-Control": "no-store",
};

export function joinPage(env: Env, token: string): Response {
	if (!TOKEN_RE.test(token)) return page("Invite link not valid", "<p>This invite link is not complete. Ask for a new one.</p>", 404);
	const server = baseUrl(env);
	const link = `${server}/join/${token}`;
	const open = `pennywise://shared/join?server=${encodeURIComponent(server)}&token=${encodeURIComponent(token)}`;
	return page(
		"Join a shared group",
		`<p>You were invited to a shared group in PennyWise.</p>
<p><a class="button" href="${escapeHtml(open)}">Open in PennyWise</a></p>
<p>If the button does nothing, copy this link and paste it in PennyWise under Shared → Join with a link:</p>
<input readonly value="${escapeHtml(link)}" aria-label="Invite link">`,
	);
}

function page(title: string, content: string, status = 200): Response {
	const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer"><meta name="robots" content="noindex, nofollow">
<title>${escapeHtml(title)} · PennyWise</title>
<style>
:root { color-scheme: light dark; --bg: #fafafa; --fg: #1b1b1f; --accent: #3b5bdb; --on-accent: #fff; --line: #c7c7cf; }
@media (prefers-color-scheme: dark) { :root { --bg: #121216; --fg: #e4e4ea; --accent: #9fb3ff; --on-accent: #0b1a55; --line: #46464f; } }
body { margin: 0; background: var(--bg); color: var(--fg); font: 16px/1.5 system-ui, sans-serif; }
main { max-width: 28rem; margin: 0 auto; padding: 48px 16px; }
h1 { font-size: 1.5rem; margin: 0 0 16px; }
.button { display: inline-block; background: var(--accent); color: var(--on-accent); padding: 12px 24px; border-radius: 24px; text-decoration: none; font-weight: 600; }
input { width: 100%; box-sizing: border-box; padding: 8px; border: 1px solid var(--line); border-radius: 8px; background: transparent; color: inherit; font: 14px ui-monospace, monospace; }
</style></head>
<body><main><h1>${escapeHtml(title)}</h1>
${content}
</main></body></html>`;
	return new Response(html, { status, headers: PAGE_HEADERS });
}
