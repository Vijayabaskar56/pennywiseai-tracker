// JSON responses and the error shape from the contract: { error, message, field? }.

export class ApiError extends Error {
	constructor(
		readonly status: number,
		readonly code: string,
		message: string,
		readonly field?: string,
		readonly extra?: Record<string, unknown>,
	) {
		super(message);
	}
}

export const invalid = (field: string, message: string) => new ApiError(422, "invalid", message, field);
export const notFound = (message = "Not found") => new ApiError(404, "not_found", message);
export const forbidden = () => new ApiError(403, "forbidden", "Not a member of this group");
export const gone = () => new ApiError(410, "gone", "This invite expired, was revoked or is used up");
export const limitReached = (field: string, message: string) => new ApiError(422, "limit_reached", message, field);
export const conflict = (current: unknown) =>
	new ApiError(409, "conflict", "Someone changed this first", undefined, { current });

const API_HEADERS = { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };

export const json = (body: unknown, status = 200) => Response.json(body, { status, headers: API_HEADERS });
export const noContent = () => new Response(null, { status: 204, headers: API_HEADERS });

export function errorResponse(e: ApiError): Response {
	const body: Record<string, unknown> = { error: e.code, message: e.message };
	if (e.field) body.field = e.field;
	return json({ ...body, ...e.extra }, e.status);
}

const MAX_BODY = 64 * 1024;

/** Reads a JSON object body. Anything else is `422 invalid` with field "body". */
export async function readJson(request: Request): Promise<Record<string, unknown>> {
	const text = await request.text();
	if (text.length > MAX_BODY) throw invalid("body", "Body is too large");
	let value: unknown;
	try {
		value = text ? JSON.parse(text) : {};
	} catch {
		throw invalid("body", "Body is not valid JSON");
	}
	if (typeof value !== "object" || value === null || Array.isArray(value)) throw invalid("body", "Body must be a JSON object");
	return value as Record<string, unknown>;
}
