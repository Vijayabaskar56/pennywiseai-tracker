import { createExecutionContext, createScheduledController, waitOnExecutionContext } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import worker from "../src/index";
import { fractionDigits, formatAmount, parseAmount } from "../src/money";
import { advance } from "../src/recurring";
import { equalExpense, twoPhoneGroup, uuid } from "./helpers";

/** Runs the daily Cron Trigger as if it fired at `isoDate` 00:05 UTC. */
async function runCron(isoDate: string) {
	const ctx = createExecutionContext();
	await worker.scheduled(createScheduledController({ scheduledTime: Date.parse(`${isoDate}T00:05:00Z`), cron: "5 0 * * *" }), env, ctx);
	await waitOnExecutionContext(ctx);
}

async function setup(nextDate: string, interval = "MONTHLY", anchorDay?: number) {
	const g = await twoPhoneGroup();
	const id = uuid();
	const { baseVersion: _v, date: _d, recurringId: _r, ...template } = equalExpense("30000.00", [g.aSeat, g.bSeat], { description: "Rent", category: "Rent" });
	const res = await g.a.put(`/v1/groups/${g.groupId}/recurring/${id}`, { baseVersion: 0, template, interval, nextDate, ...(anchorDay ? { anchorDay } : {}) });
	expect(res.status).toBe(201);
	return { ...g, id, recurring: res.body.recurring };
}

const createdDates = async (g: Awaited<ReturnType<typeof setup>>) =>
	((await g.b.changes(g.groupId)).body.expenses as any[]).filter((e) => e.recurringId === g.id).map((e) => e.date);

describe("recurring expenses", () => {
	it("stores the template and refuses bad input", async () => {
		const g = await setup("2026-12-01");
		expect(g.recurring).toMatchObject({
			groupId: g.groupId, interval: "MONTHLY", anchorDay: 1, nextDate: "2026-12-01", version: 1, rev: 4, deletedAt: null,
			template: { description: "Rent", amount: "30000.00", currency: "INR", category: "Rent", notes: null, splitMethod: "EQUAL", payers: [{ memberId: g.aSeat, amount: "30000.00" }] },
		});
		const body = { baseVersion: 0, template: g.recurring.template, interval: "MONTHLY", nextDate: "2026-12-01" };
		expect((await g.a.put(`/v1/groups/${g.groupId}/recurring/${g.id}`, body)).status).toBe(200); // repeat
		const path = `/v1/groups/${g.groupId}/recurring/${uuid()}`;
		expect((await g.a.put(path, { ...body, interval: "DAILY" })).body.field).toBe("interval");
		expect((await g.a.put(path, { ...body, nextDate: "2026-11-31" })).body.field).toBe("nextDate");
		expect((await g.a.put(path, { ...body, anchorDay: 15 })).body.field).toBe("anchorDay");
		expect((await g.a.put(path, { ...body, template: { ...body.template, amount: "1.00" } })).body.field).toBe("template.payers");
		expect((await g.a.put(path, { ...body, template: undefined })).body.field).toBe("template");
		// An anchor of 31 with a clamped nextDate is accepted.
		expect((await g.a.put(path, { ...body, nextDate: "2026-11-30", anchorDay: 31 })).body.recurring.anchorDay).toBe(31);

		const edit = await g.a.put(`/v1/groups/${g.groupId}/recurring/${g.id}`, { ...body, baseVersion: 1, interval: "WEEKLY" });
		expect(edit.body.recurring).toMatchObject({ interval: "WEEKLY", version: 2 });
		expect((await g.a.del(`/v1/groups/${g.groupId}/recurring/${g.id}`)).status).toBe(204);
		const { body: ch } = await g.a.changes(g.groupId, 4);
		expect(ch.activity.map((x: any) => x.type)).toEqual(["RECURRING_ADDED", "RECURRING_EDITED", "RECURRING_STOPPED"]);
		expect(ch.activity[0].summary).toEqual({ description: "Rent", amount: "30000.00", currency: "INR", interval: "MONTHLY" });
	});

	it("creates the due expense on its date, by the server", async () => {
		const g = await setup("2026-12-01");
		await runCron("2026-11-30");
		expect(await createdDates(g)).toEqual([]);
		await runCron("2026-12-01");
		const { body } = await g.b.changes(g.groupId, 4);
		expect(body.expenses).toHaveLength(1);
		expect(body.expenses[0]).toMatchObject({
			groupId: g.groupId, description: "Rent", amount: "30000.00", date: "2026-12-01", recurringId: g.id,
			createdBy: g.aSeat, version: 1, rev: 5, shares: g.recurring.template.shares,
		});
		expect(body.expenses[0].id).not.toBe(g.id);
		expect(body.recurring).toEqual([expect.objectContaining({ id: g.id, nextDate: "2027-01-01", version: 2, rev: 5 })]);
		expect(body.activity).toEqual([expect.objectContaining({ seq: 5, type: "EXPENSE_ADDED", actorMemberId: null, entityId: body.expenses[0].id })]);
		await runCron("2026-12-01"); // a second run the same day creates nothing
		expect(await createdDates(g)).toEqual(["2026-12-01"]);
	});

	it("catches up at most 12 a run and keeps the month-end anchor", async () => {
		const g = await setup("2025-01-31");
		await runCron("2026-12-05");
		expect(await createdDates(g)).toEqual([
			"2025-01-31", "2025-02-28", "2025-03-31", "2025-04-30", "2025-05-31", "2025-06-30",
			"2025-07-31", "2025-08-31", "2025-09-30", "2025-10-31", "2025-11-30", "2025-12-31",
		]);
		await runCron("2026-12-05");
		const dates = await createdDates(g);
		expect(dates).toHaveLength(23);
		expect(dates.slice(12, 15)).toEqual(["2026-01-31", "2026-02-28", "2026-03-31"]);
		expect(dates.at(-1)).toBe("2026-11-30");
		const { body } = await g.a.changes(g.groupId);
		expect(body.recurring[0].nextDate).toBe("2026-12-31");
		expect(body.activity.map((x: any) => x.seq)).toEqual(Array.from({ length: 4 + 23 }, (_, i) => i + 1));
	});

	it("keeps an anchor of 31 sent with a clamped nextDate", async () => {
		const g = await setup("2026-11-30", "MONTHLY", 31);
		await runCron("2026-12-31");
		expect(await createdDates(g)).toEqual(["2026-11-30", "2026-12-31"]);
	});

	it("skips stopped recurring expenses and deleted groups", async () => {
		const stopped = await setup("2026-12-01");
		await stopped.a.del(`/v1/groups/${stopped.groupId}/recurring/${stopped.id}`);
		const deleted = await setup("2026-12-01");
		await deleted.a.del(`/v1/groups/${deleted.groupId}`);
		await runCron("2026-12-02");
		expect(await createdDates(stopped)).toEqual([]);
		expect(await createdDates(deleted)).toEqual([]);
	});
});

describe("date and money math", () => {
	it("moves dates by interval, clamping to the month's end", () => {
		expect(advance("2026-01-31", "MONTHLY", 31)).toBe("2026-02-28");
		expect(advance("2026-11-30", "MONTHLY", 31)).toBe("2026-12-31");
		expect(advance("2026-12-15", "MONTHLY", 15)).toBe("2027-01-15");
		expect(advance("2028-01-31", "MONTHLY", 31)).toBe("2028-02-29");
		expect(advance("2028-02-29", "YEARLY", 29)).toBe("2029-02-28");
		expect(advance("2029-02-28", "YEARLY", 29)).toBe("2030-02-28");
		expect(advance("2026-12-29", "WEEKLY", 29)).toBe("2027-01-05");
	});

	it("parses and formats without floating point", () => {
		expect([fractionDigits("INR"), fractionDigits("JPY"), fractionDigits("KWD"), fractionDigits("USD")]).toEqual([2, 0, 3, 2]);
		expect(fractionDigits("XYZ")).toBeNull();
		expect(fractionDigits("usd")).toBeNull();
		expect(parseAmount("0.1", 2, "a") + parseAmount("0.2", 2, "a")).toBe(30);
		expect(parseAmount("1240", 2, "a")).toBe(124000);
		expect(parseAmount("007.5", 2, "a")).toBe(750);
		expect(parseAmount("9999999999.99", 2, "a")).toBe(999_999_999_999);
		expect(formatAmount(124000, 2)).toBe("1240.00");
		expect(formatAmount(5, 3)).toBe("0.005");
		expect(formatAmount(1500, 0)).toBe("1500");
		expect(formatAmount(-750, 2)).toBe("-7.50");
		for (const bad of ["", ".5", "5.", "1,000", " 1", "1.0.0", "Infinity", "0x10"]) expect(() => parseAmount(bad, 2, "a")).toThrow();
	});
});
