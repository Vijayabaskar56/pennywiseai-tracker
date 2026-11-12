// Phone identity, the same scheme as relay/: `Authorization: Device <userId>.<secret>`, where userId is
// 32 hex characters and secret is 43 base64url characters (256 bits). Only a SHA-256 hash of the secret is stored.

export async function sha256(s: string): Promise<string> {
	const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
	return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
export const randomHex = (n: number) => [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, "0")).join("");
export const randomBase64Url = (n: number) =>
	btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(n)))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

/** Shape of a secret or invite token: 32 random bytes in unpadded base64url. */
export const TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;

export async function registerDevice(db: D1Database, now: number): Promise<{ userId: string; secret: string }> {
	const userId = randomHex(16);
	const secret = randomBase64Url(32);
	await db.prepare("INSERT INTO users (id, secret_hash, created_at) VALUES (?, ?, ?)").bind(userId, await sha256(secret), now).run();
	return { userId, secret };
}

/** Returns the caller's user ID, or null when the header is missing or wrong. */
export async function authenticate(request: Request, db: D1Database): Promise<string | null> {
	const m = (request.headers.get("Authorization") ?? "").match(/^Device ([0-9a-f]{32})\.([A-Za-z0-9_-]{43})$/);
	if (!m) return null;
	const row = await db.prepare("SELECT secret_hash FROM users WHERE id = ?").bind(m[1]).first<{ secret_hash: string }>();
	if (!row) return null;
	return timingSafeEqual(row.secret_hash, await sha256(m[2])) ? m[1] : null;
}

function timingSafeEqual(a: string, b: string): boolean {
	if (a.length !== b.length) return false;
	let diff = 0;
	for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
	return diff === 0;
}

export const clientIp = (r: Request) => r.headers.get("CF-Connecting-IP") ?? "unknown";
