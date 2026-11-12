// Shared expenses: idempotent PUT keyed by the phone's UUID, delete (always wins), restore.

import { groupAccess, type Ctx } from "./context";
import { conflict, invalid, json, noContent, notFound } from "./http";
import { formatAmount } from "./money";
import { expenseJson, type ExpenseRow, type PayerRow, type ShareRow } from "./rows";
import { checkMembers, loadMembers, readSplit, sortedLines, type SplitInput } from "./split";
import { readBaseVersion, readDate, readOptionalUuid, readUuid } from "./validate";
import { runWrite } from "./write";

export type ExpenseInput = SplitInput & { date: string; recurringId: string | null };
type Expense = ReturnType<typeof expenseJson>;

export async function loadExpense(db: D1Database, id: string): Promise<Expense | null> {
	const [e, p, s] = await db.batch([
		db.prepare("SELECT * FROM expenses WHERE id = ?").bind(id),
		db.prepare("SELECT * FROM expense_payers WHERE expense_id = ? ORDER BY position").bind(id),
		db.prepare("SELECT * FROM expense_shares WHERE expense_id = ? ORDER BY position").bind(id),
	]);
	const row = e.results[0] as ExpenseRow | undefined;
	return row ? expenseJson(row, p.results as PayerRow[], s.results as ShareRow[]) : null;
}

export const expenseSummary = (e: { description: string; amount: string; currency: string }) => ({
	description: e.description, amount: e.amount, currency: e.currency,
});

/** The fields a phone sends, in a form where equal bodies give equal strings. */
function canonical(e: Pick<Expense, "description" | "amount" | "currency" | "date" | "category" | "notes" | "splitMethod" | "payers" | "shares" | "recurringId">) {
	return JSON.stringify([
		e.description, e.amount, e.currency, e.date, e.category, e.notes, e.splitMethod, e.recurringId,
		sortedLines(e.payers).map((p) => [p.memberId, p.amount]),
		sortedLines(e.shares).map((s) => [s.memberId, s.amount, s.weight]),
	]);
}

function inputAsJson(i: ExpenseInput) {
	const fmt = (n: number) => formatAmount(n, i.digits);
	return {
		description: i.description, amount: fmt(i.amount), currency: i.currency, date: i.date, category: i.category,
		notes: i.notes, splitMethod: i.splitMethod, recurringId: i.recurringId,
		payers: i.payers.map((p) => ({ memberId: p.memberId, amount: fmt(p.amount) })),
		shares: i.shares.map((s) => ({ memberId: s.memberId, amount: fmt(s.amount), weight: s.weight })),
	};
}

/** Statements that write the expense row (insert or update) and replace its payers and shares. */
export function expenseStatements(db: D1Database, a: { id: string; groupId: string; input: ExpenseInput; actor: string; now: number; rev: number; edit: boolean }): D1PreparedStatement[] {
	const i = a.input;
	const out: D1PreparedStatement[] = [];
	if (a.edit) {
		out.push(
			db.prepare(
				"UPDATE expenses SET description = ?, amount = ?, currency = ?, date = ?, category = ?, notes = ?, split_method = ?, recurring_id = ?, updated_by = ?, updated_at = ?, version = version + 1, rev = ? WHERE id = ?",
			).bind(i.description, i.amount, i.currency, i.date, i.category, i.notes, i.splitMethod, i.recurringId, a.actor, a.now, a.rev, a.id),
			db.prepare("DELETE FROM expense_payers WHERE expense_id = ?").bind(a.id),
			db.prepare("DELETE FROM expense_shares WHERE expense_id = ?").bind(a.id),
		);
	} else {
		out.push(
			db.prepare(
				"INSERT INTO expenses (id, group_id, description, amount, currency, date, category, notes, split_method, recurring_id, created_by, updated_by, created_at, updated_at, deleted_at, version, rev) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?)",
			).bind(a.id, a.groupId, i.description, i.amount, i.currency, i.date, i.category, i.notes, i.splitMethod, i.recurringId, a.actor, a.actor, a.now, a.now, a.rev),
		);
	}
	i.payers.forEach((p, n) =>
		out.push(db.prepare("INSERT INTO expense_payers (expense_id, member_id, amount, position) VALUES (?, ?, ?, ?)").bind(a.id, p.memberId, p.amount, n)),
	);
	i.shares.forEach((s, n) =>
		out.push(db.prepare("INSERT INTO expense_shares (expense_id, member_id, amount, weight, position) VALUES (?, ?, ?, ?, ?)").bind(a.id, s.memberId, s.amount, s.weight, n)),
	);
	return out;
}

/** PUT /v1/groups/:g/expenses/:id */
export async function putExpense(ctx: Ctx): Promise<Response> {
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx);
		const id = readUuid(ctx.params.id, "id");
		const body = await ctx.body();
		const baseVersion = readBaseVersion(body.baseVersion);
		const input: ExpenseInput = { ...readSplit(body), date: readDate(body.date, "date"), recurringId: readOptionalUuid(body.recurringId, "recurringId") };
		const stored = await loadExpense(ctx.db, id);
		if (stored && stored.groupId !== group.id) throw invalid("id", "This ID is already used");
		if (stored && baseVersion === 0) {
			if (canonical(stored) === canonical(inputAsJson(input))) return { done: json({ expense: stored }) };
			throw conflict(stored);
		}
		if (stored && (stored.deletedAt !== null || stored.version !== baseVersion)) throw conflict(stored);
		if (!stored && baseVersion !== 0) throw notFound("Unknown expense");

		const named = new Set([...(stored?.payers ?? []), ...(stored?.shares ?? [])].map((l) => l.memberId));
		checkMembers(
			[{ field: "payers", memberIds: input.payers.map((p) => p.memberId) }, { field: "shares", memberIds: input.shares.map((s) => s.memberId) }],
			await loadMembers(ctx.db, group.id),
			named,
		);
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => expenseStatements(ctx.db, { id, groupId: group.id, input, actor: memberId, now: ctx.now, rev, edit: stored !== null }),
			activity: { type: stored ? "EXPENSE_EDITED" : "EXPENSE_ADDED", entityId: id, summary: expenseSummary(inputAsJson(input)), actor: memberId },
			respond: async () => json({ expense: await loadExpense(ctx.db, id) }, stored ? 200 : 201),
		};
	});
}

/** DELETE /v1/groups/:g/expenses/:id: no version check, and deleting twice is fine. */
export const deleteExpense = (ctx: Ctx) => setDeleted(ctx, true);

/** POST /v1/groups/:g/expenses/:id/restore */
export const restoreExpense = (ctx: Ctx) => setDeleted(ctx, false);

async function setDeleted(ctx: Ctx, deleted: boolean): Promise<Response> {
	const id = ctx.params.id;
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx);
		const stored = await loadExpense(ctx.db, id);
		if (!stored || stored.groupId !== group.id) throw notFound("Unknown expense");
		const respond = async () => (deleted ? noContent() : json({ expense: await loadExpense(ctx.db, id) }));
		if ((stored.deletedAt !== null) === deleted) return { done: await respond() };
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => [
				ctx.db.prepare("UPDATE expenses SET deleted_at = ?, updated_by = ?, updated_at = ?, version = version + 1, rev = ? WHERE id = ?")
					.bind(deleted ? ctx.now : null, memberId, ctx.now, rev, id),
			],
			activity: { type: deleted ? "EXPENSE_DELETED" : "EXPENSE_RESTORED", entityId: id, summary: expenseSummary(stored), actor: memberId },
			respond: respond,
		};
	});
}
