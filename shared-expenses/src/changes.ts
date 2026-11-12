// GET /v1/groups/:g/changes?since=<rev>: every row stamped with a revision above `since`, deleted rows included.

import { groupAccess, type Ctx } from "./context";
import { invalid, json } from "./http";
import {
	activityJson, byExpense, expenseJson, groupJson, memberJson, recurringJson, settlementJson,
	type ActivityRow, type ExpenseRow, type GroupRow, type MemberRow, type PayerRow, type RecurringRow, type SettlementRow, type ShareRow,
} from "./rows";

/**
 * Picks the page's upper revision. Rows per revision are counted across members, expenses (with their
 * payers and shares as one row), settlements, recurring and activity; whole revisions are added while the
 * total fits the page size. A single revision larger than a page is still sent whole, so paging always
 * moves forward.
 */
async function pageEnd(db: D1Database, groupId: string, since: number, pageSize: number): Promise<{ upto: number; groupRev: number }> {
	const [g, counts] = await db.batch([
		db.prepare("SELECT rev FROM groups WHERE id = ?").bind(groupId),
		db.prepare(
			`SELECT rev, SUM(n) AS n FROM (
				SELECT rev, COUNT(*) AS n FROM members WHERE group_id = ?1 AND rev > ?2 GROUP BY rev
				UNION ALL SELECT rev, COUNT(*) FROM expenses WHERE group_id = ?1 AND rev > ?2 GROUP BY rev
				UNION ALL SELECT rev, COUNT(*) FROM settlements WHERE group_id = ?1 AND rev > ?2 GROUP BY rev
				UNION ALL SELECT rev, COUNT(*) FROM recurring WHERE group_id = ?1 AND rev > ?2 GROUP BY rev
				UNION ALL SELECT seq, 1 FROM activity WHERE group_id = ?1 AND seq > ?2
			) GROUP BY rev ORDER BY rev LIMIT ?3`,
		).bind(groupId, since, pageSize + 1),
	]);
	const groupRev = (g.results[0] as { rev: number }).rev;
	if (since >= groupRev) return { upto: groupRev, groupRev };
	let upto = since;
	let total = 0;
	for (const { rev, n } of counts.results as { rev: number; n: number }[]) {
		if (total + n > pageSize && upto > since) return { upto, groupRev };
		total += n;
		upto = rev;
	}
	// Every revision has its activity row, so reaching here means every revision up to groupRev fits.
	return { upto: groupRev, groupRev };
}

export async function changes(ctx: Ctx): Promise<Response> {
	const { group, memberId } = await groupAccess(ctx, { allowDeleted: true });
	const raw = ctx.url.searchParams.get("since") ?? "0";
	if (!/^\d{1,15}$/.test(raw)) throw invalid("since", "since must be a revision number");
	const since = Number(raw);
	const { upto, groupRev } = await pageEnd(ctx.db, group.id, since, ctx.options.changesPageSize);
	const db = ctx.db;
	const range = [group.id, since, upto] as const;
	const [g, members, expenses, payers, shares, settlements, recurring, activity] = await db.batch([
		db.prepare("SELECT * FROM groups WHERE id = ?1 AND row_rev > ?2 AND row_rev <= ?3").bind(...range),
		db.prepare("SELECT * FROM members WHERE group_id = ?1 AND rev > ?2 AND rev <= ?3 ORDER BY rev, created_at").bind(...range),
		db.prepare("SELECT * FROM expenses WHERE group_id = ?1 AND rev > ?2 AND rev <= ?3 ORDER BY rev").bind(...range),
		db.prepare("SELECT p.* FROM expense_payers p JOIN expenses e ON e.id = p.expense_id WHERE e.group_id = ?1 AND e.rev > ?2 AND e.rev <= ?3 ORDER BY p.position").bind(...range),
		db.prepare("SELECT s.* FROM expense_shares s JOIN expenses e ON e.id = s.expense_id WHERE e.group_id = ?1 AND e.rev > ?2 AND e.rev <= ?3 ORDER BY s.position").bind(...range),
		db.prepare("SELECT * FROM settlements WHERE group_id = ?1 AND rev > ?2 AND rev <= ?3 ORDER BY rev").bind(...range),
		db.prepare("SELECT * FROM recurring WHERE group_id = ?1 AND rev > ?2 AND rev <= ?3 ORDER BY rev").bind(...range),
		db.prepare("SELECT * FROM activity WHERE group_id = ?1 AND seq > ?2 AND seq <= ?3 ORDER BY seq").bind(...range),
	]);
	const payersBy = byExpense(payers.results as PayerRow[]);
	const sharesBy = byExpense(shares.results as ShareRow[]);
	const groupRow = g.results[0] as GroupRow | undefined;
	return json({
		rev: upto,
		hasMore: upto < groupRev,
		me: memberId,
		...(groupRow ? { group: groupJson(groupRow) } : {}),
		members: (members.results as MemberRow[]).map(memberJson),
		expenses: (expenses.results as ExpenseRow[]).map((e) => expenseJson(e, payersBy.get(e.id) ?? [], sharesBy.get(e.id) ?? [])),
		settlements: (settlements.results as SettlementRow[]).map(settlementJson),
		recurring: (recurring.results as RecurringRow[]).map(recurringJson),
		activity: (activity.results as ActivityRow[]).map(activityJson),
	});
}
