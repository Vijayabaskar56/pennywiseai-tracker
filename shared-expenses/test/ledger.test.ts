import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { equalExpense, newPhone, twoPhoneGroup, uuid, type Phone } from "./helpers";

const expensePath = (g: string, id: string) => `/v1/groups/${g}/expenses/${id}`;
const settlementPath = (g: string, id: string) => `/v1/groups/${g}/settlements/${id}`;

function settlement(from: string, to: string, amount: string, over: Record<string, unknown> = {}) {
	return { baseVersion: 0, fromMemberId: from, toMemberId: to, amount, currency: "INR", date: "2026-12-05", notes: null, ...over };
}

/** All activity seqs and the final rev, read through `changes` from 0. */
async function history(phone: Phone, groupId: string) {
	const { body } = await phone.changes(groupId);
	return { rev: body.rev as number, seqs: (body.activity as { seq: number }[]).map((a) => a.seq) };
}
const range = (n: number) => Array.from({ length: n }, (_, i) => i + 1);

describe("expenses", () => {
	it("creates, repeats idempotently, edits and rejects stale edits", async () => {
		const { a, b, groupId, aSeat, bSeat } = await twoPhoneGroup();
		const id = uuid();
		const body = equalExpense("1240.00", [aSeat, bSeat]);

		const created = await a.put(expensePath(groupId, id), body);
		expect(created.status).toBe(201);
		expect(created.body.expense).toEqual({
			id, groupId, description: "Groceries", amount: "1240.00", currency: "INR", date: "2026-11-10",
			category: "Groceries", notes: null, splitMethod: "EQUAL",
			payers: [{ memberId: aSeat, amount: "1240.00" }],
			shares: [{ memberId: aSeat, amount: "620.00", weight: null }, { memberId: bSeat, amount: "620.00", weight: null }],
			recurringId: null, createdBy: aSeat, updatedBy: aSeat,
			createdAt: expect.any(Number), updatedAt: expect.any(Number), deletedAt: null, version: 1, rev: 4,
		});

		// The same send again (an outbox retry), even with the shares reordered and "620" for "620.00".
		const repeat = await a.put(expensePath(groupId, id), { ...body, shares: [...body.shares].reverse().map((s) => ({ ...s, amount: "620" })) });
		expect(repeat.status).toBe(200);
		expect(repeat.body.expense).toEqual(created.body.expense);
		expect((await history(a, groupId)).rev).toBe(4);

		// Same ID, different body, baseVersion 0: conflict with the stored row.
		const clash = await b.put(expensePath(groupId, id), { ...body, description: "Rent" });
		expect(clash.status).toBe(409);
		expect(clash.body.error).toBe("conflict");
		expect(clash.body.current).toEqual(created.body.expense);

		const edit = await b.put(expensePath(groupId, id), { ...body, baseVersion: 1, description: "Vegetables" });
		expect(edit.status).toBe(200);
		expect(edit.body.expense).toMatchObject({ description: "Vegetables", version: 2, rev: 5, createdBy: aSeat, updatedBy: bSeat });

		const stale = await a.put(expensePath(groupId, id), { ...body, baseVersion: 1, description: "Fruit" });
		expect(stale.status).toBe(409);
		expect(stale.body.current).toMatchObject({ description: "Vegetables", version: 2 });

		expect((await a.put(expensePath(groupId, uuid()), { ...body, baseVersion: 3 })).status).toBe(404);
		expect((await a.put(expensePath(groupId, "NOT-A-UUID"), body)).body.field).toBe("id");
	});

	it("stores the typed weight for PERCENT and SHARES", async () => {
		const { a, groupId, aSeat, bSeat } = await twoPhoneGroup();
		const shares = [{ memberId: aSeat, amount: "666.67", weight: "2" }, { memberId: bSeat, amount: "333.33", weight: "1" }];
		const res = await a.put(expensePath(groupId, uuid()), equalExpense("1000.00", [aSeat, bSeat], { splitMethod: "SHARES", shares }));
		expect(res.status).toBe(201);
		expect(res.body.expense.shares).toEqual(shares);
		const noWeight = equalExpense("1000.00", [aSeat, bSeat], { splitMethod: "PERCENT" });
		expect((await a.put(expensePath(groupId, uuid()), noWeight)).body.field).toBe("shares");
	});

	it("rejects expenses that do not add up or name the wrong people", async () => {
		const { a, groupId, aSeat, bSeat } = await twoPhoneGroup();
		const outsider = await newPhone();
		const { me: strangerSeat } = await outsider.createGroup("Other");
		const ok = equalExpense("100.00", [aSeat, bSeat]);
		const cases: [Record<string, unknown>, string][] = [
			[{ shares: [{ memberId: aSeat, amount: "50.00" }, { memberId: bSeat, amount: "49.99" }] }, "shares"],
			[{ payers: [{ memberId: aSeat, amount: "99.00" }] }, "payers"],
			[{ payers: [] }, "payers"],
			[{ shares: [{ memberId: aSeat, amount: "50.00" }, { memberId: aSeat, amount: "50.00" }] }, "shares"],
			[{ shares: [{ memberId: aSeat, amount: "100.00" }, { memberId: bSeat, amount: "0.00" }] }, "shares"],
			[{ shares: [{ memberId: strangerSeat, amount: "100.00" }] }, "shares"],
			[{ payers: [{ memberId: uuid(), amount: "100.00" }] }, "payers"],
			[{ amount: "100.005" }, "amount"],
			[{ amount: "100", currency: "XYZ" }, "currency"],
			[{ amount: "0.00", payers: [{ memberId: aSeat, amount: "0.00" }] }, "amount"],
			[{ amount: "-5.00" }, "amount"],
			[{ amount: 100 }, "amount"],
			[{ amount: "1e3" }, "amount"],
			[{ description: "" }, "description"],
			[{ description: "x".repeat(201) }, "description"],
			[{ notes: "x".repeat(1001) }, "notes"],
			[{ category: "x".repeat(61) }, "category"],
			[{ date: "2026-02-30" }, "date"],
			[{ date: "10/11/2026" }, "date"],
			[{ splitMethod: "ITEMIZED" }, "splitMethod"],
			[{ baseVersion: -1 }, "baseVersion"],
			[{ recurringId: "x" }, "recurringId"],
		];
		for (const [over, field] of cases) {
			const res = await a.put(expensePath(groupId, uuid()), { ...ok, ...over });
			expect({ over, status: res.status, error: res.body.error, field: res.body.field }).toEqual({ over, status: 422, error: "invalid", field });
		}
		// Limits are inclusive.
		const edge = { ...ok, description: "x".repeat(200), notes: "x".repeat(1000), category: "x".repeat(60) };
		expect((await a.put(expensePath(groupId, uuid()), edge)).status).toBe(201);
	});

	it("uses each currency's own decimals", async () => {
		const { a, groupId, aSeat } = await twoPhoneGroup();
		const one = (amount: string, currency: string) =>
			a.put(expensePath(groupId, uuid()), { ...equalExpense(amount, [aSeat]), currency, payers: [{ memberId: aSeat, amount }], shares: [{ memberId: aSeat, amount }] });
		expect((await one("1500", "JPY")).body.expense.amount).toBe("1500");
		expect((await one("1500.5", "JPY")).body.field).toBe("amount");
		expect((await one("1.234", "KWD")).body.expense.amount).toBe("1.234");
		expect((await one("1.2345", "KWD")).body.field).toBe("amount");
		expect((await one("12.5", "INR")).body.expense.amount).toBe("12.50");
		expect((await one("9999999999.99", "INR")).status).toBe(201); // 10^12 − 1 minor units
		expect((await one("10000000000.00", "INR")).body.field).toBe("amount");
	});

	it("deletes (twice is fine), restores, and refuses edits to a deleted expense", async () => {
		const { a, b, groupId, aSeat, bSeat } = await twoPhoneGroup();
		const id = uuid();
		const body = equalExpense("90.00", [aSeat, bSeat]);
		await a.put(expensePath(groupId, id), body);
		expect((await b.del(expensePath(groupId, id))).status).toBe(204);
		expect((await a.del(expensePath(groupId, id))).status).toBe(204);
		expect((await a.del(expensePath(groupId, uuid()))).status).toBe(404);

		const { body: ch } = await a.changes(groupId, 4);
		expect(ch.expenses).toHaveLength(1);
		expect(ch.expenses[0]).toMatchObject({ id, version: 2, rev: 5, updatedBy: bSeat });
		expect(ch.expenses[0].deletedAt).toEqual(expect.any(Number));
		expect(ch.activity.map((x: any) => [x.type, x.summary])).toEqual([["EXPENSE_DELETED", { description: "Groceries", amount: "90.00", currency: "INR" }]]);

		const edit = await a.put(expensePath(groupId, id), { ...body, baseVersion: 2 });
		expect(edit.status).toBe(409);
		expect(edit.body.current.deletedAt).not.toBeNull();

		const restored = await a.post(`${expensePath(groupId, id)}/restore`);
		expect(restored.status).toBe(200);
		expect(restored.body.expense).toMatchObject({ deletedAt: null, version: 3, rev: 6 });
		expect((await a.post(`${expensePath(groupId, id)}/restore`)).body.expense.version).toBe(3); // already live: no write
		expect((await history(a, groupId)).rev).toBe(6);
	});
});

describe("settlements", () => {
	it("records, repeats, edits and deletes a settlement", async () => {
		const { a, b, groupId, aSeat, bSeat } = await twoPhoneGroup();
		const id = uuid();
		const created = await b.put(settlementPath(groupId, id), settlement(bSeat, aSeat, "500"));
		expect(created.status).toBe(201);
		expect(created.body.settlement).toMatchObject({ id, groupId, fromMemberId: bSeat, toMemberId: aSeat, amount: "500.00", currency: "INR", date: "2026-12-05", notes: null, version: 1, rev: 4, createdBy: bSeat });
		expect((await b.put(settlementPath(groupId, id), settlement(bSeat, aSeat, "500.00"))).status).toBe(200);
		expect((await b.put(settlementPath(groupId, id), settlement(bSeat, aSeat, "400.00"))).status).toBe(409);
		const edit = await a.put(settlementPath(groupId, id), settlement(bSeat, aSeat, "450.00", { baseVersion: 1 }));
		expect(edit.body.settlement).toMatchObject({ amount: "450.00", version: 2, updatedBy: aSeat });
		expect((await a.del(settlementPath(groupId, id))).status).toBe(204);
		expect((await a.del(settlementPath(groupId, id))).status).toBe(204);

		const { body } = await a.changes(groupId, 3);
		expect(body.settlements[0]).toMatchObject({ id, version: 3, deletedAt: expect.any(Number) });
		expect(body.activity.map((x: any) => x.type)).toEqual(["SETTLEMENT_ADDED", "SETTLEMENT_EDITED", "SETTLEMENT_DELETED"]);
		expect(body.activity[0].summary).toEqual({ fromMemberId: bSeat, toMemberId: aSeat, amount: "500.00", currency: "INR" });
	});

	it("needs two different members of the group", async () => {
		const { a, groupId, aSeat } = await twoPhoneGroup();
		expect((await a.put(settlementPath(groupId, uuid()), settlement(aSeat, aSeat, "1.00"))).body).toMatchObject({ error: "invalid", field: "toMemberId" });
		expect((await a.put(settlementPath(groupId, uuid()), settlement(aSeat, uuid(), "1.00"))).body).toMatchObject({ error: "invalid", field: "toMemberId" });
		expect((await a.put(settlementPath(groupId, uuid()), settlement(aSeat, uuid(), "1.001"))).body.field).toBe("amount");
	});
});

describe("removing a member", () => {
	it("needs a zero balance in every currency, then stops recurring expenses that name them", async () => {
		const { a, b, groupId, aSeat, bSeat } = await twoPhoneGroup();
		// A paid 100 INR split equally: B owes A 50 INR.
		await a.put(expensePath(groupId, uuid()), equalExpense("100.00", [aSeat, bSeat]));
		// B paid 10 USD for A alone: A owes B 10 USD.
		await b.put(expensePath(groupId, uuid()), { ...equalExpense("10.00", [bSeat]), currency: "USD", shares: [{ memberId: aSeat, amount: "10.00", weight: null }] });

		const refused = await a.del(`/v1/groups/${groupId}/members/${bSeat}`);
		expect(refused.status).toBe(422);
		expect(refused.body.error).toBe("balance_not_zero");

		await b.put(settlementPath(groupId, uuid()), settlement(bSeat, aSeat, "50.00"));
		expect((await a.del(`/v1/groups/${groupId}/members/${bSeat}`)).body.error).toBe("balance_not_zero"); // USD still open
		await a.put(settlementPath(groupId, uuid()), settlement(aSeat, bSeat, "10.00", { currency: "USD" }));

		const template = { ...equalExpense("300.00", [aSeat, bSeat]), baseVersion: undefined, date: undefined, recurringId: undefined };
		const namesB = uuid();
		const onlyA = uuid();
		await a.put(`/v1/groups/${groupId}/recurring/${namesB}`, { baseVersion: 0, template, interval: "MONTHLY", nextDate: "2026-12-01" });
		await a.put(`/v1/groups/${groupId}/recurring/${onlyA}`, {
			baseVersion: 0, interval: "WEEKLY", nextDate: "2026-12-01",
			template: { ...template, shares: [{ memberId: aSeat, amount: "300.00", weight: null }] },
		});
		const before = (await history(a, groupId)).rev;

		expect((await a.del(`/v1/groups/${groupId}/members/${bSeat}`)).status).toBe(204);
		expect((await a.del(`/v1/groups/${groupId}/members/${bSeat}`)).status).toBe(204); // already removed: no write

		const { body } = await a.changes(groupId, before);
		expect(body.rev).toBe(before + 1);
		expect(body.activity).toEqual([expect.objectContaining({ seq: before + 1, type: "MEMBER_REMOVED", entityId: bSeat, summary: { memberName: "Member B" } })]);
		expect(body.members).toEqual([expect.objectContaining({ id: bSeat, removedAt: expect.any(Number), rev: before + 1 })]);
		expect(body.recurring).toEqual([expect.objectContaining({ id: namesB, deletedAt: expect.any(Number), version: 2, rev: before + 1 })]);
		// That one write is 3 rows; a smaller page still returns it whole so paging moves forward.
		const small = await a.changes(groupId, before, 2);
		expect(small.body).toMatchObject({ rev: before + 1, hasMore: false });
		expect(small.body.members.length + small.body.recurring.length + small.body.activity.length).toBe(3);

		// The removed member's phone is cut off; new expenses can't name them, old ones keep them.
		expect((await b.changes(groupId)).status).toBe(403);
		expect((await a.put(expensePath(groupId, uuid()), equalExpense("10.00", [aSeat, bSeat]))).body.field).toBe("shares");
	});

	it("keeps a removed member on an expense that already names them", async () => {
		const { a, groupId, aSeat, bSeat } = await twoPhoneGroup();
		const id = uuid();
		// B paid for both and A settled up: both balances are zero.
		const body = { ...equalExpense("100.00", [bSeat, aSeat]) };
		await a.put(expensePath(groupId, id), body);
		await a.put(settlementPath(groupId, uuid()), settlement(aSeat, bSeat, "50.00"));
		expect((await a.del(`/v1/groups/${groupId}/members/${bSeat}`)).status).toBe(204);
		const edit = await a.put(expensePath(groupId, id), { ...body, baseVersion: 1, description: "Groceries and milk" });
		expect(edit.status).toBe(200);
		expect((await a.put(expensePath(groupId, uuid()), body)).body.field).toBe("payers");
	});

	it("lets a member leave", async () => {
		const { a, b, groupId, bSeat } = await twoPhoneGroup();
		expect((await b.del(`/v1/groups/${groupId}/members/${bSeat}`)).status).toBe(204);
		expect((await b.changes(groupId)).status).toBe(403);
		const { body } = await a.changes(groupId, 3);
		expect(body.activity[0]).toMatchObject({ type: "MEMBER_REMOVED", actorMemberId: bSeat });
	});
});

describe("changes", () => {
	it("returns the whole group from 0 and only what changed after that", async () => {
		const { a, b, groupId, aSeat, bSeat } = await twoPhoneGroup();
		const e1 = uuid();
		const e2 = uuid();
		await a.put(expensePath(groupId, e1), equalExpense("100.00", [aSeat, bSeat]));
		await a.put(expensePath(groupId, e2), equalExpense("50.00", [aSeat, bSeat], { description: "Milk" }));

		const all = await b.changes(groupId, 0);
		expect(all.status).toBe(200);
		expect(all.body).toMatchObject({ rev: 5, hasMore: false, me: bSeat, group: { id: groupId, rev: 5 } });
		expect(all.body.members.map((m: any) => m.id).sort()).toEqual([aSeat, bSeat].sort());
		expect(all.body.expenses.map((e: any) => e.id)).toEqual([e1, e2]);
		expect(all.body.expenses[0].payers).toEqual([{ memberId: aSeat, amount: "100.00" }]);
		expect(all.body.activity.map((x: any) => x.type)).toEqual(["GROUP_CREATED", "MEMBER_ADDED", "MEMBER_JOINED", "EXPENSE_ADDED", "EXPENSE_ADDED"]);
		expect(all.body.settlements).toEqual([]);
		expect(all.body.recurring).toEqual([]);

		await a.del(expensePath(groupId, e1));
		const inc = await b.changes(groupId, 5);
		expect(inc.body).toMatchObject({ rev: 6, hasMore: false });
		expect(inc.body.group).toBeUndefined(); // the group row itself did not change
		expect(inc.body.members).toEqual([]);
		expect(inc.body.expenses).toEqual([expect.objectContaining({ id: e1, deletedAt: expect.any(Number) })]);
		expect(inc.body.activity.map((x: any) => x.seq)).toEqual([6]);

		const none = await b.changes(groupId, 6);
		expect(none.body).toMatchObject({ rev: 6, hasMore: false, members: [], expenses: [], activity: [] });
		expect((await b.req("GET", `/v1/groups/${groupId}/changes?since=-1`)).body.field).toBe("since");
	});

	it("pages by whole revisions with hasMore", async () => {
		const { a, b, groupId, aSeat, bSeat } = await twoPhoneGroup();
		const ids = [];
		for (let i = 0; i < 10; i++) {
			const id = uuid();
			ids.push(id);
			await a.put(expensePath(groupId, id), equalExpense(`${i + 1}.00`, [aSeat, bSeat]));
		}
		// rev 1: member + activity; rev 2: activity only (the seat it added was restamped by the join);
		// rev 3: member + activity; revs 4..13: expense + activity. A page of 5 takes revs 1-3 exactly.
		const pageSize = 5;
		let since = 0;
		const pages: { rev: number; hasMore: boolean; rows: number }[] = [];
		const seenExpenses: string[] = [];
		const seqs: number[] = [];
		for (;;) {
			const { body } = await b.changes(groupId, since, pageSize);
			const rows = body.members.length + body.expenses.length + body.settlements.length + body.recurring.length + body.activity.length;
			pages.push({ rev: body.rev, hasMore: body.hasMore, rows });
			expect(rows).toBeLessThanOrEqual(pageSize);
			seenExpenses.push(...body.expenses.map((e: any) => e.id));
			seqs.push(...body.activity.map((x: any) => x.seq));
			if (!body.hasMore) break;
			since = body.rev;
		}
		expect(pages[0]).toEqual({ rev: 3, hasMore: true, rows: 5 });
		expect(pages[1]).toEqual({ rev: 5, hasMore: true, rows: 4 });
		expect(pages.at(-1)).toMatchObject({ rev: 13, hasMore: false });
		expect(seenExpenses).toEqual(ids);
		expect(seqs).toEqual(range(13));
	});
});

describe("revisions", () => {
	it("raises rev by one per write and keeps activity seqs 1..N with no gaps", async () => {
		const { a, b, groupId, aSeat, bSeat } = await twoPhoneGroup(); // 3 writes
		const e = uuid();
		const s = uuid();
		const r = uuid();
		const body = equalExpense("10.00", [aSeat, bSeat]);
		const template = { ...body, baseVersion: undefined, date: undefined, recurringId: undefined };
		const writes = [
			() => a.put(expensePath(groupId, e), body),
			() => a.put(expensePath(groupId, e), body), // repeat: no write
			() => b.put(expensePath(groupId, e), { ...body, baseVersion: 1, notes: "Weekly shop" }),
			() => b.put(expensePath(groupId, e), { ...body, baseVersion: 1, notes: "Stale" }), // 409: no write
			() => a.put(settlementPath(groupId, s), settlement(bSeat, aSeat, "5.00")),
			() => a.del(expensePath(groupId, e)),
			() => a.del(expensePath(groupId, e)), // already deleted: no write
			() => a.post(`${expensePath(groupId, e)}/restore`),
			() => a.put(`/v1/groups/${groupId}/recurring/${r}`, { baseVersion: 0, template, interval: "MONTHLY", nextDate: "2026-12-10" }),
			() => a.del(`/v1/groups/${groupId}/recurring/${r}`),
			() => a.patch(`/v1/groups/${groupId}`, { name: "Flat 2" }),
			() => a.patch(`/v1/groups/${groupId}/members/${bSeat}`, { displayName: "Roommate" }),
			() => a.post(`/v1/groups/${groupId}/invites`, {}), // invites are not group writes
		];
		for (const w of writes) await w();
		const N = 3 + 9;
		const h = await history(a, groupId);
		expect(h.rev).toBe(N);
		expect(h.seqs).toEqual(range(N));
		expect((await a.get("/v1/me/groups")).body.groups[0].rev).toBe(N);
		const { results } = await env.DB.prepare("SELECT seq FROM activity WHERE group_id = ? ORDER BY seq").bind(groupId).all<{ seq: number }>();
		expect(results.map((x) => x.seq)).toEqual(range(N));
	});

	it("keeps the invariant when phones write to one group at the same time", async () => {
		const { a, b, groupId, aSeat, bSeat } = await twoPhoneGroup();
		const sends = Array.from({ length: 8 }, (_, i) => (i % 2 ? a : b).put(expensePath(groupId, uuid()), equalExpense("10.00", [aSeat, bSeat])));
		const statuses = (await Promise.all(sends)).map((r) => r.status);
		expect(statuses).toEqual(Array(8).fill(201));
		const h = await history(a, groupId);
		expect(h.rev).toBe(11);
		expect(h.seqs).toEqual(range(11));
		const { body } = await a.changes(groupId);
		expect(new Set(body.expenses.map((e: any) => e.rev))).toEqual(new Set(range(11).slice(3)));
	});

	it("lets only one of two racing edits from the same version win", async () => {
		const { a, b, groupId, aSeat, bSeat } = await twoPhoneGroup();
		const id = uuid();
		const body = equalExpense("10.00", [aSeat, bSeat]);
		await a.put(expensePath(groupId, id), body);
		const results = await Promise.all([
			a.put(expensePath(groupId, id), { ...body, baseVersion: 1, notes: "A" }),
			b.put(expensePath(groupId, id), { ...body, baseVersion: 1, notes: "B" }),
		]);
		expect(results.map((r) => r.status).sort()).toEqual([200, 409]);
		expect((await history(a, groupId)).seqs).toEqual(range(5));
	});
});

describe("limits", () => {
	it("allows 50 active members per group", async () => {
		const a = await newPhone("Member A");
		const { groupId } = await a.createGroup();
		for (let i = 2; i <= 50; i++) await a.addSeat(groupId, `Member ${i}`);
		const res = await a.post(`/v1/groups/${groupId}/members`, { displayName: "One too many" });
		expect(res.status).toBe(422);
		expect(res.body.error).toBe("limit_reached");
		const open = await a.invite(groupId);
		const late = await newPhone();
		expect((await late.post(`/v1/invites/${open.token}/accept`, { displayName: "Late" })).body.error).toBe("limit_reached");
	});

	it("allows 50 groups per user", async () => {
		const a = await newPhone("Member A");
		for (let i = 0; i < 50; i++) await a.createGroup(`Group ${i}`);
		const res = await a.post("/v1/groups", { name: "One too many", defaultCurrency: "INR" });
		expect(res.status).toBe(422);
		expect(res.body.error).toBe("limit_reached");
	});
});
