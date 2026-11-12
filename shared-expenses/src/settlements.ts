// Settlements: `fromMemberId` paid `toMemberId`. Idempotent PUT and delete, as for expenses.

import { groupAccess, LIMITS, type Ctx } from "./context";
import { conflict, invalid, json, noContent, notFound } from "./http";
import { formatAmount, parseAmount, readCurrency } from "./money";
import { settlementJson, type SettlementRow } from "./rows";
import { checkMembers, loadMembers } from "./split";
import { readBaseVersion, readDate, readOptionalText, readUuid } from "./validate";
import { runWrite } from "./write";

type Settlement = ReturnType<typeof settlementJson>;

async function loadSettlement(db: D1Database, id: string): Promise<Settlement | null> {
	const row = await db.prepare("SELECT * FROM settlements WHERE id = ?").bind(id).first<SettlementRow>();
	return row ? settlementJson(row) : null;
}

const summary = (s: { fromMemberId: string; toMemberId: string; amount: string; currency: string }) => ({
	fromMemberId: s.fromMemberId, toMemberId: s.toMemberId, amount: s.amount, currency: s.currency,
});

/** PUT /v1/groups/:g/settlements/:id */
export async function putSettlement(ctx: Ctx): Promise<Response> {
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx);
		const id = readUuid(ctx.params.id, "id");
		const body = await ctx.body();
		const baseVersion = readBaseVersion(body.baseVersion);
		const fromMemberId = readUuid(body.fromMemberId, "fromMemberId");
		const toMemberId = readUuid(body.toMemberId, "toMemberId");
		if (fromMemberId === toMemberId) throw invalid("toMemberId", "A settlement is between two different members");
		const { currency, digits } = readCurrency(body.currency, "currency");
		const minor = parseAmount(body.amount, digits, "amount");
		const date = readDate(body.date, "date");
		const notes = readOptionalText(body.notes, "notes", LIMITS.notes);
		const sent = { fromMemberId, toMemberId, amount: formatAmount(minor, digits), currency, date, notes };
		const same = (s: Settlement) => JSON.stringify([s.fromMemberId, s.toMemberId, s.amount, s.currency, s.date, s.notes]) === JSON.stringify(Object.values(sent));
		const stored = await loadSettlement(ctx.db, id);
		if (stored && stored.groupId !== group.id) throw invalid("id", "This ID is already used");
		if (stored && baseVersion === 0) {
			if (same(stored)) return { done: json({ settlement: stored }) };
			throw conflict(stored);
		}
		if (stored && (stored.deletedAt !== null || stored.version !== baseVersion)) throw conflict(stored);
		if (!stored && baseVersion !== 0) throw notFound("Unknown settlement");

		const named = new Set(stored ? [stored.fromMemberId, stored.toMemberId] : []);
		checkMembers([{ field: "fromMemberId", memberIds: [fromMemberId] }, { field: "toMemberId", memberIds: [toMemberId] }], await loadMembers(ctx.db, group.id), named);

		const statement = (rev: number) =>
			stored
				? ctx.db.prepare("UPDATE settlements SET from_member_id = ?, to_member_id = ?, amount = ?, currency = ?, date = ?, notes = ?, updated_by = ?, updated_at = ?, version = version + 1, rev = ? WHERE id = ?")
					.bind(fromMemberId, toMemberId, minor, currency, date, notes, memberId, ctx.now, rev, id)
				: ctx.db.prepare("INSERT INTO settlements (id, group_id, from_member_id, to_member_id, amount, currency, date, notes, created_by, updated_by, created_at, updated_at, deleted_at, version, rev) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?)")
					.bind(id, group.id, fromMemberId, toMemberId, minor, currency, date, notes, memberId, memberId, ctx.now, ctx.now, rev);
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => [statement(rev)],
			activity: { type: stored ? "SETTLEMENT_EDITED" : "SETTLEMENT_ADDED", entityId: id, summary: summary(sent), actor: memberId },
			respond: async () => json({ settlement: await loadSettlement(ctx.db, id) }, stored ? 200 : 201),
		};
	});
}

/** DELETE /v1/groups/:g/settlements/:id */
export async function deleteSettlement(ctx: Ctx): Promise<Response> {
	const id = ctx.params.id;
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx);
		const stored = await loadSettlement(ctx.db, id);
		if (!stored || stored.groupId !== group.id) throw notFound("Unknown settlement");
		if (stored.deletedAt !== null) return { done: noContent() };
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => [
				ctx.db.prepare("UPDATE settlements SET deleted_at = ?, updated_by = ?, updated_at = ?, version = version + 1, rev = ? WHERE id = ?").bind(ctx.now, memberId, ctx.now, rev, id),
			],
			activity: { type: "SETTLEMENT_DELETED", entityId: id, summary: summary(stored), actor: memberId },
			respond: () => noContent(),
		};
	});
}
