// Recurring expenses: a template the daily cron turns into expenses (see cron.ts).

import { groupAccess, type Ctx } from "./context";
import { conflict, invalid, json, noContent, notFound } from "./http";
import { recurringJson, type RecurringRow } from "./rows";
import { checkMembers, loadMembers, readSplit, sortedLines, templateJson } from "./split";
import { daysInMonth, readBaseVersion, readDate, readEnum, readUuid } from "./validate";
import { runWrite } from "./write";

export const INTERVALS = ["WEEKLY", "MONTHLY", "YEARLY"] as const;
export type Interval = (typeof INTERVALS)[number];
type Template = ReturnType<typeof templateJson>;
type Recurring = ReturnType<typeof recurringJson>;

/**
 * The date one interval after `date`. MONTHLY and YEARLY land on `anchorDay`, clamped to the month's
 * last day, so anchor 31 gives 30 November and then 31 December.
 */
export function advance(date: string, interval: Interval, anchorDay: number): string {
	const [y, m, d] = date.split("-").map(Number);
	if (interval === "WEEKLY") return new Date(Date.UTC(y, m - 1, d + 7)).toISOString().slice(0, 10);
	const [ny, nm] = interval === "MONTHLY" ? (m === 12 ? [y + 1, 1] : [y, m + 1]) : [y + 1, m];
	const day = Math.min(anchorDay, daysInMonth(ny, nm));
	return `${String(ny).padStart(4, "0")}-${String(nm).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

export const recurringSummary = (t: Template, interval: string) => ({
	description: t.description, amount: t.amount, currency: t.currency, interval,
});

/** Names every member a template's payers and shares mention. */
export const templateMembers = (t: Template) => [...t.payers, ...t.shares].map((l) => l.memberId);

async function loadRecurring(db: D1Database, id: string): Promise<Recurring | null> {
	const row = await db.prepare("SELECT * FROM recurring WHERE id = ?").bind(id).first<RecurringRow>();
	return row ? recurringJson(row) : null;
}

const canonical = (r: { template: Template; interval: string; anchorDay: number; nextDate: string }) =>
	JSON.stringify([
		{ ...r.template, payers: sortedLines(r.template.payers), shares: sortedLines(r.template.shares) },
		r.interval, r.anchorDay, r.nextDate,
	]);

/**
 * PUT /v1/groups/:g/recurring/:id. `anchorDay` is optional: by default it is the day of `nextDate`
 * (or the stored one when `nextDate` did not change). A phone whose first date was the 31st sends it,
 * because a clamped `nextDate` such as 30 November would otherwise lose the anchor.
 */
export async function putRecurring(ctx: Ctx): Promise<Response> {
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx);
		const id = readUuid(ctx.params.id, "id");
		const body = await ctx.body();
		const baseVersion = readBaseVersion(body.baseVersion);
		if (typeof body.template !== "object" || body.template === null || Array.isArray(body.template)) throw invalid("template", "template is required");
		const split = readSplit(body.template as Record<string, unknown>, "template.");
		const template = templateJson(split);
		const interval = readEnum(body.interval, "interval", INTERVALS);
		const nextDate = readDate(body.nextDate, "nextDate");
		const nextDay = Number(nextDate.slice(8));
		if (body.anchorDay !== undefined && body.anchorDay !== null) {
			const a = body.anchorDay;
			if (typeof a !== "number" || !Number.isInteger(a) || a < 1 || a > 31) throw invalid("anchorDay", "anchorDay must be 1 to 31");
			const [y, m] = nextDate.split("-").map(Number);
			if (interval !== "WEEKLY" && Math.min(a, daysInMonth(y, m)) !== nextDay) throw invalid("anchorDay", "nextDate does not fall on anchorDay");
		}

		const stored = await loadRecurring(ctx.db, id);
		const anchorDay = typeof body.anchorDay === "number" ? body.anchorDay : stored && stored.nextDate === nextDate ? stored.anchorDay : nextDay;
		const sent = { template, interval, anchorDay, nextDate };
		if (stored && stored.groupId !== group.id) throw invalid("id", "This ID is already used");
		if (stored && baseVersion === 0) {
			if (canonical(stored) === canonical(sent)) return { done: json({ recurring: stored }) };
			throw conflict(stored);
		}
		if (stored && (stored.deletedAt !== null || stored.version !== baseVersion)) throw conflict(stored);
		if (!stored && baseVersion !== 0) throw notFound("Unknown recurring expense");

		const named = new Set(stored ? templateMembers(stored.template) : []);
		checkMembers(
			[{ field: "template.payers", memberIds: split.payers.map((p) => p.memberId) }, { field: "template.shares", memberIds: split.shares.map((s) => s.memberId) }],
			await loadMembers(ctx.db, group.id),
			named,
		);
		const t = JSON.stringify(template);
		const statement = (rev: number) =>
			stored
				? ctx.db.prepare("UPDATE recurring SET template = ?, interval = ?, anchor_day = ?, next_date = ?, updated_by = ?, updated_at = ?, version = version + 1, rev = ? WHERE id = ?")
					.bind(t, interval, anchorDay, nextDate, memberId, ctx.now, rev, id)
				: ctx.db.prepare("INSERT INTO recurring (id, group_id, template, interval, anchor_day, next_date, created_by, updated_by, created_at, updated_at, deleted_at, version, rev) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?)")
					.bind(id, group.id, t, interval, anchorDay, nextDate, memberId, memberId, ctx.now, ctx.now, rev);
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => [statement(rev)],
			activity: { type: stored ? "RECURRING_EDITED" : "RECURRING_ADDED", entityId: id, summary: recurringSummary(template, interval), actor: memberId },
			respond: async () => json({ recurring: await loadRecurring(ctx.db, id) }, stored ? 200 : 201),
		};
	});
}

/** DELETE /v1/groups/:g/recurring/:id stops it. Stopping twice is fine. */
export async function deleteRecurring(ctx: Ctx): Promise<Response> {
	const id = ctx.params.id;
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx);
		const stored = await loadRecurring(ctx.db, id);
		if (!stored || stored.groupId !== group.id) throw notFound("Unknown recurring expense");
		if (stored.deletedAt !== null) return { done: noContent() };
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => [stopRecurring(ctx.db, id, memberId, ctx.now, rev)],
			activity: { type: "RECURRING_STOPPED", entityId: id, summary: recurringSummary(stored.template, stored.interval), actor: memberId },
			respond: () => noContent(),
		};
	});
}

export const stopRecurring = (db: D1Database, id: string, actor: string, now: number, rev: number) =>
	db.prepare("UPDATE recurring SET deleted_at = ?, updated_by = ?, updated_at = ?, version = version + 1, rev = ? WHERE id = ?").bind(now, actor, now, rev, id);
