// Fake phones that talk to the Worker over HTTP, the way the app will.

import { env, exports } from "cloudflare:workers";
import { expect } from "vitest";
import { handleRequest } from "../src/index";

export const BASE = "https://shared.test";
export type Res = { status: number; body: any; headers: Headers };

const worker = () => (exports as unknown as { default: { fetch: (r: Request) => Promise<Response> } }).default;

/** Sends a request to the Worker. `pageSize` goes through `handleRequest` with a smaller `changes` page. */
export async function send(method: string, path: string, opts: { headers?: Record<string, string>; body?: unknown; pageSize?: number } = {}): Promise<Res> {
	const init: RequestInit = { method, headers: { ...opts.headers } };
	if (opts.body !== undefined) {
		init.body = typeof opts.body === "string" ? opts.body : JSON.stringify(opts.body);
		(init.headers as Record<string, string>)["Content-Type"] = "application/json";
	}
	const request = new Request(BASE + path, init);
	const res = opts.pageSize ? await handleRequest(request, env, { changesPageSize: opts.pageSize }) : await worker().fetch(request);
	const text = await res.text();
	let body: unknown = text;
	try {
		body = text ? JSON.parse(text) : null;
	} catch {}
	return { status: res.status, body, headers: res.headers };
}

// Each registration from its own address, so the per-IP limit stays out of the way.
export const randomIp = () => `10.${rnd()}.${rnd()}.${rnd()}`;
const rnd = () => Math.floor(Math.random() * 250);

export class Phone {
	constructor(readonly userId: string, readonly secret: string) {}
	get auth() {
		return { Authorization: `Device ${this.userId}.${this.secret}` };
	}
	req(method: string, path: string, body?: unknown, pageSize?: number) {
		return send(method, path, { headers: this.auth, body, pageSize });
	}
	get = (path: string) => this.req("GET", path);
	put = (path: string, body: unknown) => this.req("PUT", path, body);
	post = (path: string, body: unknown = {}) => this.req("POST", path, body);
	patch = (path: string, body: unknown) => this.req("PATCH", path, body);
	del = (path: string) => this.req("DELETE", path);

	/** Creates a group and returns its id and this phone's seat. */
	async createGroup(name = "Flat", currency = "INR", displayName = "Member A") {
		const res = await this.post("/v1/groups", { name, defaultCurrency: currency, displayName });
		expect(res.status).toBe(201);
		return { groupId: res.body.group.id as string, me: res.body.me as string };
	}

	async addSeat(groupId: string, displayName = "Roommate") {
		const res = await this.post(`/v1/groups/${groupId}/members`, { displayName });
		expect(res.status).toBe(201);
		return res.body.member.id as string;
	}

	async invite(groupId: string, memberId?: string) {
		const res = await this.post(`/v1/groups/${groupId}/invites`, memberId ? { memberId } : {});
		expect(res.status).toBe(201);
		return res.body as { id: string; token: string; url: string; memberId: string | null; expiresAt: number };
	}

	/** Joins through a seat invite (or an open one) and returns the seat. */
	async join(token: string, displayName = "Roommate") {
		const res = await this.post(`/v1/invites/${token}/accept`, { displayName });
		expect(res.status).toBe(200);
		return res.body.memberId as string;
	}

	changes(groupId: string, since = 0, pageSize?: number) {
		return this.req("GET", `/v1/groups/${groupId}/changes?since=${since}`, undefined, pageSize);
	}
}

export async function newPhone(displayName?: string): Promise<Phone> {
	const res = await send("POST", "/v1/device", { headers: { "CF-Connecting-IP": randomIp() } });
	expect(res.status).toBe(201);
	const phone = new Phone(res.body.userId, res.body.secret);
	if (displayName) expect((await phone.put("/v1/me", { displayName })).status).toBe(200);
	return phone;
}

/** Two phones in one group: `a` created it, `b` joined through a seat invite. */
export async function twoPhoneGroup(currency = "INR") {
	const a = await newPhone("Member A");
	const b = await newPhone("Member B");
	const { groupId, me: aSeat } = await a.createGroup("Flat", currency);
	const bSeat = await a.addSeat(groupId, "Roommate");
	const { token } = await a.invite(groupId, bSeat);
	await b.join(token, "Member B");
	return { a, b, groupId, aSeat, bSeat };
}

export const uuid = () => crypto.randomUUID();

/** An expense body split equally between `members`, with the first one paying. */
export function equalExpense(amount: string, members: string[], over: Record<string, unknown> = {}) {
	const [whole, frac = ""] = amount.split(".");
	const digits = frac.length;
	const total = BigInt(whole + frac);
	const n = BigInt(members.length);
	const fmt = (v: bigint) => {
		const s = v.toString().padStart(digits + 1, "0");
		return digits ? `${s.slice(0, -digits)}.${s.slice(-digits)}` : s;
	};
	const shares = members.map((m, i) => ({ memberId: m, amount: fmt(total / n + (BigInt(i) < total % n ? 1n : 0n)), weight: null }));
	return {
		baseVersion: 0, description: "Groceries", amount, currency: "INR", date: "2026-11-10", category: "Groceries", notes: null,
		splitMethod: "EQUAL", payers: [{ memberId: members[0], amount }], shares, recurringId: null, ...over,
	};
}
