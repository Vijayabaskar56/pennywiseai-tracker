// The daily Cron Trigger: every recurring expense that is due (nextDate on or before today, UTC) becomes
// an expense dated nextDate, and nextDate moves one interval on, until it is after today, at most 12 times
// per recurring expense per run. Each expense is its own group write, by the server (actor null).

import { expenseStatements, expenseSummary, type ExpenseInput } from "./expenses";
import { parseAmount, readCurrency } from "./money";
import { advance, type Interval } from "./recurring";
import type { RecurringRow } from "./rows";
import { runWrite } from "./write";

export const MAX_CATCH_UP = 12;

type Template = {
	description: string; amount: string; currency: string; category: string | null; notes: string | null; splitMethod: string;
	payers: { memberId: string; amount: string }[]; shares: { memberId: string; amount: string; weight: string | null }[];
};

function fromTemplate(t: Template, date: string, recurringId: string): ExpenseInput {
	const { currency, digits } = readCurrency(t.currency, "currency");
	return {
		description: t.description, amount: parseAmount(t.amount, digits, "amount"), currency, digits,
		category: t.category, notes: t.notes, splitMethod: t.splitMethod as ExpenseInput["splitMethod"],
		payers: t.payers.map((p) => ({ memberId: p.memberId, amount: parseAmount(p.amount, digits, "payers"), weight: null })),
		shares: t.shares.map((s) => ({ memberId: s.memberId, amount: parseAmount(s.amount, digits, "shares"), weight: s.weight })),
		date, recurringId,
	};
}

/** Creates the due expenses. Returns how many it created. */
export async function runRecurring(db: D1Database, now: number): Promise<number> {
	const today = new Date(now).toISOString().slice(0, 10);
	const { results: due } = await db
		.prepare("SELECT r.id FROM recurring r JOIN groups g ON g.id = r.group_id WHERE r.deleted_at IS NULL AND g.deleted_at IS NULL AND r.next_date <= ?")
		.bind(today)
		.all<{ id: string }>();
	let created = 0;
	for (const { id } of due) {
		try {
			for (let i = 0; i < MAX_CATCH_UP; i++) {
				const made = await runWrite<boolean>(db, now, async () => {
					const [r, g] = await db.batch([
						db.prepare("SELECT * FROM recurring WHERE id = ?").bind(id),
						db.prepare("SELECT g.rev, g.deleted_at FROM groups g JOIN recurring r ON r.group_id = g.id WHERE r.id = ?").bind(id),
					]);
					const row = r.results[0] as RecurringRow;
					const group = g.results[0] as { rev: number; deleted_at: number | null };
					if (row.deleted_at !== null || group.deleted_at !== null || row.next_date > today) return { done: false };
					const expenseId = crypto.randomUUID();
					const input = fromTemplate(JSON.parse(row.template), row.next_date, row.id);
					const next = advance(row.next_date, row.interval as Interval, row.anchor_day);
					return {
						groupId: row.group_id,
						baseRev: group.rev,
						statements: (rev) => [
							...expenseStatements(db, { id: expenseId, groupId: row.group_id, input, actor: row.created_by, now, rev, edit: false }),
							db.prepare("UPDATE recurring SET next_date = ?, updated_at = ?, version = version + 1, rev = ? WHERE id = ?").bind(next, now, rev, row.id),
						],
						activity: { type: "EXPENSE_ADDED", entityId: expenseId, summary: expenseSummary(JSON.parse(row.template)), actor: null },
						respond: () => true,
					};
				});
				if (!made) break;
				created++;
			}
		} catch (e) {
			// IDs only: never names or amounts.
			console.error("recurring expense failed", id, e instanceof Error ? e.name : "error");
		}
	}
	return created;
}
