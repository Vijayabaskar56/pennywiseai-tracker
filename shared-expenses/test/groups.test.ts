import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { BASE, newPhone, send, twoPhoneGroup, uuid } from "./helpers";

describe("identity", () => {
	it("registers a phone and sets its display name", async () => {
		const res = await send("POST", "/v1/device", { headers: { "CF-Connecting-IP": "10.9.9.1" } });
		expect(res.status).toBe(201);
		expect(res.body.userId).toMatch(/^[0-9a-f]{32}$/);
		expect(res.body.secret).toMatch(/^[A-Za-z0-9_-]{43}$/);
		const auth = { Authorization: `Device ${res.body.userId}.${res.body.secret}` };

		expect((await send("GET", "/v1/me", { headers: auth })).body).toEqual({ userId: res.body.userId, displayName: null });
		const put = await send("PUT", "/v1/me", { headers: auth, body: { displayName: "  Member A " } });
		expect(put.body).toEqual({ userId: res.body.userId, displayName: "Member A" });
		expect((await send("GET", "/v1/me", { headers: auth })).body.displayName).toBe("Member A");

		const stored = await env.DB.prepare("SELECT secret_hash FROM users WHERE id = ?").bind(res.body.userId).first<{ secret_hash: string }>();
		expect(stored!.secret_hash).toMatch(/^[0-9a-f]{64}$/);
		expect(stored!.secret_hash).not.toContain(res.body.secret);
	});

	it("refuses missing, malformed and wrong credentials", async () => {
		const phone = await newPhone();
		const cases: Record<string, string>[] = [
			{},
			{ Authorization: "Bearer x" },
			{ Authorization: `Device ${phone.userId}` },
			{ Authorization: `Device ${phone.userId}.${"x".repeat(43)}` },
			{ Authorization: `Device ${"0".repeat(32)}.${phone.secret}` },
			{ Authorization: `Device ${phone.userId.toUpperCase()}.${phone.secret}` },
		];
		for (const headers of cases) {
			const res = await send("GET", "/v1/me", { headers });
			expect(res.status).toBe(401);
			expect(res.body.error).toBe("unauthorized");
		}
		expect((await phone.get("/v1/me")).status).toBe(200);
	});

	it("rate-limits registration to 5 a minute per address", async () => {
		const headers = { "CF-Connecting-IP": "10.250.250.250" };
		const statuses = [];
		for (let i = 0; i < 6; i++) statuses.push((await send("POST", "/v1/device", { headers })).status);
		expect(statuses).toEqual([201, 201, 201, 201, 201, 429]);
	});

	it("validates the display name", async () => {
		const phone = await newPhone();
		for (const displayName of ["", "   ", "x".repeat(41), 5]) {
			const res = await phone.put("/v1/me", { displayName });
			expect(res.status).toBe(422);
			expect(res.body).toMatchObject({ error: "invalid", field: "displayName" });
		}
		expect((await phone.put("/v1/me", { displayName: "x".repeat(40) })).status).toBe(200);
		expect((await phone.req("PUT", "/v1/me", "{not json")).body).toMatchObject({ error: "invalid", field: "body" });
	});
});

describe("groups and members", () => {
	it("creates a group with the creator's seat", async () => {
		const a = await newPhone("Member A");
		const res = await a.post("/v1/groups", { name: "Flat", defaultCurrency: "INR", displayName: "Member A" });
		expect(res.status).toBe(201);
		const { group, me, members } = res.body;
		expect(group).toMatchObject({ name: "Flat", defaultCurrency: "INR", simplifyDebts: true, deletedAt: null, rev: 1 });
		expect(group.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4/);
		expect(members).toEqual([{ id: me, groupId: group.id, displayName: "Member A", joined: true, removedAt: null, rev: 1 }]);
		expect((await a.get("/v1/me/groups")).body).toEqual({ groups: [{ id: group.id, rev: 1, memberId: me }] });
	});

	it("falls back to the caller's display name for the creator's seat, and validates the body", async () => {
		const a = await newPhone("Member A");
		const res = await a.post("/v1/groups", { name: "Trip", defaultCurrency: "JPY" });
		expect(res.body.members[0].displayName).toBe("Member A");
		const anon = await newPhone();
		expect((await anon.post("/v1/groups", { name: "Trip", defaultCurrency: "JPY" })).body.field).toBe("displayName");
		expect((await a.post("/v1/groups", { name: "Trip", defaultCurrency: "XYZ" })).body).toMatchObject({ error: "invalid", field: "defaultCurrency" });
		expect((await a.post("/v1/groups", { name: "Trip", defaultCurrency: "inr" })).body.field).toBe("defaultCurrency");
		expect((await a.post("/v1/groups", { name: "", defaultCurrency: "INR" })).body.field).toBe("name");
		expect((await a.post("/v1/groups", { name: "x".repeat(61), defaultCurrency: "INR" })).body.field).toBe("name");
	});

	it("renames, changes settings and deletes a group, each one write", async () => {
		const { a, b, groupId } = await twoPhoneGroup();
		const renamed = await a.patch(`/v1/groups/${groupId}`, { name: "Flat 2" });
		expect(renamed.status).toBe(200);
		expect(renamed.body.group).toMatchObject({ name: "Flat 2", rev: 4 });
		const settings = await a.patch(`/v1/groups/${groupId}`, { simplifyDebts: false, defaultCurrency: "USD" });
		expect(settings.body.group).toMatchObject({ simplifyDebts: false, defaultCurrency: "USD", rev: 5 });
		const same = await a.patch(`/v1/groups/${groupId}`, { simplifyDebts: false });
		expect(same.body.group.rev).toBe(5); // nothing changed, nothing written
		expect((await a.patch(`/v1/groups/${groupId}`, { simplifyDebts: "no" })).body.field).toBe("simplifyDebts");

		const { body } = await b.changes(groupId, 3);
		expect(body.group.name).toBe("Flat 2");
		expect(body.activity.map((x: any) => [x.type, x.summary])).toEqual([
			["GROUP_RENAMED", { oldName: "Flat", name: "Flat 2" }],
			["GROUP_SETTINGS_CHANGED", { defaultCurrency: "USD", simplifyDebts: false }],
		]);

		expect((await b.del(`/v1/groups/${groupId}`)).status).toBe(204);
		expect((await b.del(`/v1/groups/${groupId}`)).status).toBe(204);
		const after = await a.changes(groupId, 5);
		expect(after.status).toBe(200);
		expect(after.body.group.deletedAt).not.toBeNull();
		expect(after.body.activity.map((x: any) => x.type)).toEqual(["GROUP_DELETED"]);
		expect((await a.get("/v1/me/groups")).body.groups).toContainEqual({ id: groupId, rev: 6, memberId: expect.any(String) });
		// Writes to a deleted group are refused.
		expect((await a.post(`/v1/groups/${groupId}/members`, { displayName: "Roommate" })).status).toBe(404);
		expect((await a.patch(`/v1/groups/${groupId}`, { name: "Again" })).status).toBe(404);
	});

	it("adds and renames a seat", async () => {
		const a = await newPhone("Member A");
		const { groupId } = await a.createGroup();
		const add = await a.post(`/v1/groups/${groupId}/members`, { displayName: "Roommate" });
		expect(add.status).toBe(201);
		expect(add.body.member).toMatchObject({ groupId, displayName: "Roommate", joined: false, removedAt: null, rev: 2 });
		const rename = await a.patch(`/v1/groups/${groupId}/members/${add.body.member.id}`, { displayName: "Roommate 2" });
		expect(rename.body.member).toMatchObject({ displayName: "Roommate 2", rev: 3 });
		expect((await a.patch(`/v1/groups/${groupId}/members/${uuid()}`, { displayName: "X" })).status).toBe(404);
		const { body } = await a.changes(groupId, 1);
		expect(body.activity.map((x: any) => [x.type, x.summary])).toEqual([
			["MEMBER_ADDED", { memberName: "Roommate" }],
			["MEMBER_RENAMED", { oldName: "Roommate", memberName: "Roommate 2" }],
		]);
	});
});

describe("invites", () => {
	it("hands a seat to the phone that accepts a seat invite", async () => {
		const a = await newPhone("Member A");
		const b = await newPhone("Member B");
		const { groupId } = await a.createGroup();
		const seat = await a.addSeat(groupId, "Roommate");
		const inv = await a.invite(groupId, seat);
		expect(inv.url).toBe(`${BASE}/join/${inv.token}`);
		expect(inv.memberId).toBe(seat);
		expect(inv.expiresAt).toBeGreaterThan(Date.now() + 6.9 * 24 * 3600 * 1000);

		const preview = await b.get(`/v1/invites/${inv.token}`);
		expect(preview.body).toEqual({ groupId, groupName: "Flat", memberDisplayName: "Roommate", expiresAt: inv.expiresAt, alreadyMember: false });

		const accept = await b.post(`/v1/invites/${inv.token}/accept`, { displayName: "Member B" });
		expect(accept.status).toBe(200);
		expect(accept.body).toEqual({ groupId, memberId: seat });
		const { body } = await b.changes(groupId);
		expect(body.me).toBe(seat);
		expect(body.members.find((m: any) => m.id === seat)).toMatchObject({ displayName: "Member B", joined: true });
		expect(body.activity.at(-1)).toMatchObject({ type: "MEMBER_JOINED", entityId: seat, actorMemberId: seat, summary: { memberName: "Member B" } });

		// Accepting again gives the same seat back and writes nothing.
		const again = await b.post(`/v1/invites/${inv.token}/accept`, { displayName: "Member B" });
		expect(again).toMatchObject({ status: 200, body: { groupId, memberId: seat } });
		expect((await b.get(`/v1/invites/${inv.token}`)).body.alreadyMember).toBe(true);
		expect((await b.changes(groupId)).body.rev).toBe(3);

		// The seat invite is used up for anyone else.
		const c = await newPhone();
		expect((await c.get(`/v1/invites/${inv.token}`)).status).toBe(410);
		expect((await c.post(`/v1/invites/${inv.token}/accept`, { displayName: "Member C" })).body.error).toBe("gone");
		expect((await a.get(`/v1/groups/${groupId}/invites`)).body.invites).toEqual([]);
	});

	it("gives a new seat for each phone on an open invite, up to 20 uses", async () => {
		const a = await newPhone("Member A");
		const { groupId } = await a.createGroup();
		const inv = await a.invite(groupId);
		expect(inv.memberId).toBeNull();
		const b = await newPhone();
		const c = await newPhone();
		expect((await b.get(`/v1/invites/${inv.token}`)).body.memberDisplayName).toBeNull();
		const bSeat = await b.join(inv.token, "Member B");
		const cSeat = await c.join(inv.token, "Member C");
		expect(bSeat).not.toBe(cSeat);
		expect(await b.join(inv.token, "Member B")).toBe(bSeat); // re-accept: same seat
		expect((await a.get(`/v1/groups/${groupId}/invites`)).body.invites).toEqual([{ id: inv.id, memberId: null, expiresAt: inv.expiresAt, uses: 2 }]);
		const members = (await a.changes(groupId)).body.members;
		expect(members.map((m: any) => m.displayName).sort()).toEqual(["Member A", "Member B", "Member C"]);

		await env.DB.prepare("UPDATE invites SET uses = 20 WHERE id = ?").bind(inv.id).run();
		const d = await newPhone();
		expect((await d.post(`/v1/invites/${inv.token}/accept`, { displayName: "Member D" })).status).toBe(410);
	});

	it("answers already_member when the caller holds a different seat", async () => {
		const { a, b, groupId, aSeat } = await twoPhoneGroup();
		const other = await a.addSeat(groupId, "Roommate 2");
		const seatInvite = await a.invite(groupId, other);
		const res = await b.post(`/v1/invites/${seatInvite.token}/accept`, { displayName: "Member B" });
		expect(res.status).toBe(409);
		expect(res.body.error).toBe("already_member");
		// An open invite just returns the seat the caller holds.
		const open = await a.invite(groupId);
		expect(await a.join(open.token, "Member A")).toBe(aSeat);
	});

	it("refuses expired, revoked and unknown invites", async () => {
		const a = await newPhone("Member A");
		const b = await newPhone();
		const { groupId } = await a.createGroup();
		const expired = await a.invite(groupId);
		await env.DB.prepare("UPDATE invites SET expires_at = ? WHERE id = ?").bind(Date.now() - 1, expired.id).run();
		expect((await b.get(`/v1/invites/${expired.token}`)).status).toBe(410);
		expect((await b.post(`/v1/invites/${expired.token}/accept`, { displayName: "Member B" })).status).toBe(410);

		const revoked = await a.invite(groupId);
		expect((await a.del(`/v1/groups/${groupId}/invites/${revoked.id}`)).status).toBe(204);
		expect((await a.del(`/v1/groups/${groupId}/invites/${uuid()}`)).status).toBe(404);
		expect((await b.post(`/v1/invites/${revoked.token}/accept`, { displayName: "Member B" })).status).toBe(410);
		expect((await a.get(`/v1/groups/${groupId}/invites`)).body.invites).toEqual([]);

		expect((await b.get(`/v1/invites/${"A".repeat(43)}`)).status).toBe(404);
		expect((await b.get("/v1/invites/short")).status).toBe(404);
		expect((await a.post(`/v1/groups/${groupId}/invites`, { memberId: uuid() })).status).toBe(404);
	});

	it("cuts off the old phone when its seat is taken over", async () => {
		const { a, b, groupId, bSeat } = await twoPhoneGroup();
		const newB = await newPhone();
		const { token } = await a.invite(groupId, bSeat);
		expect(await newB.join(token, "Member B")).toBe(bSeat);

		const old = await b.changes(groupId);
		expect(old.status).toBe(403);
		expect(old.body.error).toBe("forbidden");
		expect((await b.get("/v1/me/groups")).body.groups).toEqual([]);
		expect((await b.put(`/v1/groups/${groupId}/expenses/${uuid()}`, {})).status).toBe(403);
		expect((await newB.changes(groupId)).body.me).toBe(bSeat);
	});

	it("rate-limits invite preview and accept to 10 a minute per user", async () => {
		const phone = await newPhone();
		const statuses = [];
		for (let i = 0; i < 11; i++) statuses.push((await phone.get(`/v1/invites/${"B".repeat(43)}`)).status);
		expect(statuses.slice(0, 10).every((s) => s === 404)).toBe(true);
		expect(statuses[10]).toBe(429);
		expect((await phone.get("/v1/me")).status).toBe(200); // other routes have their own limit
	});
});

describe("join page", () => {
	it("links into the app and shows nothing about the group", async () => {
		const a = await newPhone("Member A");
		const { groupId } = await a.createGroup("Secret Flat Name");
		const { token, url } = await a.invite(groupId);
		const res = await send("GET", new URL(url).pathname);
		expect(res.status).toBe(200);
		const html = res.body as string;
		expect(html).toContain(`href="pennywise://shared/join?server=${encodeURIComponent(BASE)}&amp;token=${token}"`);
		expect(html).toContain(`value="${BASE}/join/${token}"`);
		expect(html).not.toContain("Secret Flat Name");
		expect(res.headers.get("Content-Type")).toContain("text/html");
		expect(res.headers.get("Content-Security-Policy")).toContain("default-src 'none'");
		expect(res.headers.get("Referrer-Policy")).toBe("no-referrer");
		expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
	});

	it("rejects a malformed token without echoing it", async () => {
		const res = await send("GET", "/join/%3Cscript%3Ealert(1)%3C%2Fscript%3E");
		expect(res.status).toBe(404);
		expect(res.body).not.toContain("<script>");
		expect(res.body).not.toContain("%3Cscript");
		expect((await send("GET", "/join/%E0%A4%A")).status).toBe(404);
		const phone = await newPhone();
		expect((await phone.get("/v1/invites/%E0%A4%A")).status).toBe(404);
	});
});

describe("membership", () => {
	it("answers 403 to a non-member on every group route, and 404 for an unknown group", async () => {
		const { groupId, bSeat } = await twoPhoneGroup();
		const expenseId = uuid();
		const outsider = await newPhone("Member C");
		const routes: [string, string, unknown?][] = [
			["PATCH", `/v1/groups/${groupId}`, { name: "X" }],
			["DELETE", `/v1/groups/${groupId}`],
			["POST", `/v1/groups/${groupId}/members`, { displayName: "X" }],
			["PATCH", `/v1/groups/${groupId}/members/${bSeat}`, { displayName: "X" }],
			["DELETE", `/v1/groups/${groupId}/members/${bSeat}`],
			["POST", `/v1/groups/${groupId}/invites`, {}],
			["GET", `/v1/groups/${groupId}/invites`],
			["DELETE", `/v1/groups/${groupId}/invites/${uuid()}`],
			["PUT", `/v1/groups/${groupId}/expenses/${expenseId}`, { baseVersion: 0 }],
			["DELETE", `/v1/groups/${groupId}/expenses/${expenseId}`],
			["POST", `/v1/groups/${groupId}/expenses/${expenseId}/restore`, {}],
			["PUT", `/v1/groups/${groupId}/settlements/${uuid()}`, { baseVersion: 0 }],
			["DELETE", `/v1/groups/${groupId}/settlements/${uuid()}`],
			["PUT", `/v1/groups/${groupId}/recurring/${uuid()}`, { baseVersion: 0 }],
			["DELETE", `/v1/groups/${groupId}/recurring/${uuid()}`],
			["GET", `/v1/groups/${groupId}/changes?since=0`],
		];
		for (const [method, path, body] of routes) {
			// Membership is checked before the body, so even an empty body gets 403.
			const res = await outsider.req(method, path, body);
			expect({ route: `${method} ${path}`, status: res.status, error: res.body?.error }).toEqual({ route: `${method} ${path}`, status: 403, error: "forbidden" });
		}
		expect((await outsider.changes(uuid())).status).toBe(404);
		expect((await outsider.changes("not-a-group")).status).toBe(404);
	});
});
