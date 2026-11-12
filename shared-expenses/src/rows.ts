// D1 rows and their JSON shapes from the contract.

import { formatMoney } from "./money";

export type GroupRow = {
	id: string; name: string; default_currency: string; simplify_debts: number;
	created_at: number; updated_at: number; deleted_at: number | null; rev: number; row_rev: number;
};
export type MemberRow = {
	id: string; group_id: string; user_id: string | null; display_name: string;
	created_at: number; removed_at: number | null; rev: number;
};
export type ExpenseRow = {
	id: string; group_id: string; description: string; amount: number; currency: string; date: string;
	category: string | null; notes: string | null; split_method: string; recurring_id: string | null;
	created_by: string; updated_by: string; created_at: number; updated_at: number; deleted_at: number | null;
	version: number; rev: number;
};
export type PayerRow = { expense_id: string; member_id: string; amount: number; position: number };
export type ShareRow = PayerRow & { weight: string | null };
export type SettlementRow = {
	id: string; group_id: string; from_member_id: string; to_member_id: string; amount: number; currency: string;
	date: string; notes: string | null; created_by: string; updated_by: string; created_at: number; updated_at: number;
	deleted_at: number | null; version: number; rev: number;
};
export type RecurringRow = {
	id: string; group_id: string; template: string; interval: string; anchor_day: number; next_date: string;
	created_by: string; updated_by: string; created_at: number; updated_at: number; deleted_at: number | null;
	version: number; rev: number;
};
export type ActivityRow = {
	group_id: string; seq: number; actor_member_id: string | null; type: string; entity_id: string; summary: string; created_at: number;
};

export const groupJson = (g: GroupRow) => ({
	id: g.id, name: g.name, defaultCurrency: g.default_currency, simplifyDebts: g.simplify_debts === 1,
	createdAt: g.created_at, updatedAt: g.updated_at, deletedAt: g.deleted_at, rev: g.rev,
});

export const memberJson = (m: MemberRow) => ({
	id: m.id, groupId: m.group_id, displayName: m.display_name, joined: m.user_id !== null, removedAt: m.removed_at, rev: m.rev,
});

export function expenseJson(e: ExpenseRow, payers: PayerRow[], shares: ShareRow[]) {
	return {
		id: e.id, groupId: e.group_id, description: e.description, amount: formatMoney(e.amount, e.currency), currency: e.currency,
		date: e.date, category: e.category, notes: e.notes, splitMethod: e.split_method,
		payers: payers.map((p) => ({ memberId: p.member_id, amount: formatMoney(p.amount, e.currency) })),
		shares: shares.map((s) => ({ memberId: s.member_id, amount: formatMoney(s.amount, e.currency), weight: s.weight })),
		recurringId: e.recurring_id, createdBy: e.created_by, updatedBy: e.updated_by,
		createdAt: e.created_at, updatedAt: e.updated_at, deletedAt: e.deleted_at, version: e.version, rev: e.rev,
	};
}

export const settlementJson = (s: SettlementRow) => ({
	id: s.id, groupId: s.group_id, fromMemberId: s.from_member_id, toMemberId: s.to_member_id,
	amount: formatMoney(s.amount, s.currency), currency: s.currency, date: s.date, notes: s.notes,
	createdBy: s.created_by, updatedBy: s.updated_by, createdAt: s.created_at, updatedAt: s.updated_at,
	deletedAt: s.deleted_at, version: s.version, rev: s.rev,
});

export const recurringJson = (r: RecurringRow) => ({
	id: r.id, groupId: r.group_id, template: JSON.parse(r.template), interval: r.interval, anchorDay: r.anchor_day,
	nextDate: r.next_date, createdBy: r.created_by, updatedBy: r.updated_by, createdAt: r.created_at,
	updatedAt: r.updated_at, deletedAt: r.deleted_at, version: r.version, rev: r.rev,
});

export const activityJson = (a: ActivityRow) => ({
	seq: a.seq, groupId: a.group_id, actorMemberId: a.actor_member_id, type: a.type, entityId: a.entity_id,
	summary: JSON.parse(a.summary), createdAt: a.created_at,
});

/** Groups children by expense ID, keeping the stored order. */
export function byExpense<T extends PayerRow>(rows: T[]): Map<string, T[]> {
	const out = new Map<string, T[]>();
	for (const r of rows) {
		const list = out.get(r.expense_id) ?? [];
		list.push(r);
		out.set(r.expense_id, list);
	}
	return out;
}
