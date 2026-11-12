// The caller (/v1/me), groups and member seats. These are online-only on the phone.

import { countActiveGroups, countActiveMembers, groupAccess, LIMITS, type Ctx } from "./context";
import { ApiError, invalid, json, limitReached, noContent, notFound } from "./http";
import { readCurrency } from "./money";
import { groupJson, memberJson, type GroupRow, type MemberRow, type RecurringRow } from "./rows";
import { stopRecurring, templateMembers } from "./recurring";
import { readName } from "./validate";
import { runWrite } from "./write";

const loadGroup = (db: D1Database, id: string) => db.prepare("SELECT * FROM groups WHERE id = ?").bind(id).first<GroupRow>();
const loadMember = (db: D1Database, groupId: string, id: string) =>
	db.prepare("SELECT * FROM members WHERE id = ? AND group_id = ?").bind(id, groupId).first<MemberRow>();

async function me(ctx: Ctx) {
	const row = await ctx.db.prepare("SELECT display_name FROM users WHERE id = ?").bind(ctx.userId).first<{ display_name: string | null }>();
	return { userId: ctx.userId, displayName: row?.display_name ?? null };
}

/** GET /v1/me */
export const getMe = async (ctx: Ctx) => json(await me(ctx));

/** PUT /v1/me */
export async function putMe(ctx: Ctx): Promise<Response> {
	const body = await ctx.body();
	const displayName = readName(body.displayName, "displayName", LIMITS.displayName);
	await ctx.db.prepare("UPDATE users SET display_name = ? WHERE id = ?").bind(displayName, ctx.userId).run();
	return json({ userId: ctx.userId, displayName });
}

/** GET /v1/me/groups: groups where the caller holds an active seat, deleted ones included. */
export async function myGroups(ctx: Ctx): Promise<Response> {
	const { results } = await ctx.db
		.prepare("SELECT g.id, g.rev, m.id AS member_id FROM members m JOIN groups g ON g.id = m.group_id WHERE m.user_id = ? AND m.removed_at IS NULL ORDER BY g.created_at")
		.bind(ctx.userId)
		.all<{ id: string; rev: number; member_id: string }>();
	return json({ groups: results.map((g) => ({ id: g.id, rev: g.rev, memberId: g.member_id })) });
}

/** POST /v1/groups. `displayName` names the creator's seat; it falls back to the caller's own display name. */
export async function createGroup(ctx: Ctx): Promise<Response> {
	const body = await ctx.body();
	const name = readName(body.name, "name", LIMITS.groupName);
	const { currency } = readCurrency(body.defaultCurrency, "defaultCurrency");
	const displayName = readName(body.displayName ?? (await me(ctx)).displayName, "displayName", LIMITS.displayName);
	if ((await countActiveGroups(ctx.db, ctx.userId)) >= LIMITS.groupsPerUser) throw limitReached("groups", `You can be in up to ${LIMITS.groupsPerUser} groups`);

	const groupId = crypto.randomUUID();
	const memberId = crypto.randomUUID();
	return runWrite<Response>(ctx.db, ctx.now, async () => ({
		groupId,
		baseRev: 0,
		newGroup: true,
		statements: (rev) => [
			ctx.db.prepare("INSERT INTO groups (id, name, default_currency, simplify_debts, created_at, updated_at, deleted_at, rev, row_rev) VALUES (?, ?, ?, 1, ?, ?, NULL, ?, ?)")
				.bind(groupId, name, currency, ctx.now, ctx.now, rev, rev),
			ctx.db.prepare("INSERT INTO members (id, group_id, user_id, display_name, created_at, removed_at, rev) VALUES (?, ?, ?, ?, ?, NULL, ?)")
				.bind(memberId, groupId, ctx.userId, displayName, ctx.now, rev),
		],
		activity: { type: "GROUP_CREATED", entityId: groupId, summary: { name }, actor: memberId },
		respond: async () => {
			const [group, member] = await Promise.all([loadGroup(ctx.db, groupId), loadMember(ctx.db, groupId, memberId)]);
			return json({ group: groupJson(group!), me: memberId, members: [memberJson(member!)] }, 201);
		},
	}));
}

/**
 * PATCH /v1/groups/:g. One write, so one activity row: GROUP_RENAMED when the name changed (its summary
 * also carries any settings that changed with it), otherwise GROUP_SETTINGS_CHANGED.
 */
export async function patchGroup(ctx: Ctx): Promise<Response> {
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx);
		const body = await ctx.body();
		const name = body.name === undefined ? undefined : readName(body.name, "name", LIMITS.groupName);
		const currency = body.defaultCurrency === undefined ? undefined : readCurrency(body.defaultCurrency, "defaultCurrency").currency;
		if (body.simplifyDebts !== undefined && typeof body.simplifyDebts !== "boolean") throw invalid("simplifyDebts", "simplifyDebts must be true or false");
		const simplify = body.simplifyDebts as boolean | undefined;
		const changes: Record<string, unknown> = {};
		if (currency !== undefined && currency !== group.default_currency) changes.defaultCurrency = currency;
		if (simplify !== undefined && simplify !== (group.simplify_debts === 1)) changes.simplifyDebts = simplify;
		const renamed = name !== undefined && name !== group.name;
		if (!renamed && Object.keys(changes).length === 0) return { done: json({ group: groupJson(group) }) };
		const next = { name: name ?? group.name, currency: currency ?? group.default_currency, simplify: simplify ?? group.simplify_debts === 1 };
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => [
				ctx.db.prepare("UPDATE groups SET name = ?, default_currency = ?, simplify_debts = ?, updated_at = ?, row_rev = ? WHERE id = ?")
					.bind(next.name, next.currency, next.simplify ? 1 : 0, ctx.now, rev, group.id),
			],
			activity: renamed
				? { type: "GROUP_RENAMED", entityId: group.id, summary: { oldName: group.name, name: next.name, ...changes }, actor: memberId }
				: { type: "GROUP_SETTINGS_CHANGED", entityId: group.id, summary: changes, actor: memberId },
			respond: async () => json({ group: groupJson((await loadGroup(ctx.db, group.id))!) }),
		};
	});
}

/** DELETE /v1/groups/:g. Any member can delete; members keep reading `changes`. */
export async function deleteGroup(ctx: Ctx): Promise<Response> {
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx, { allowDeleted: true });
		if (group.deleted_at !== null) return { done: noContent() };
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => [ctx.db.prepare("UPDATE groups SET deleted_at = ?, updated_at = ?, row_rev = ? WHERE id = ?").bind(ctx.now, ctx.now, rev, group.id)],
			activity: { type: "GROUP_DELETED", entityId: group.id, summary: { name: group.name }, actor: memberId },
			respond: () => noContent(),
		};
	});
}

/** POST /v1/groups/:g/members: an empty seat. */
export async function addMember(ctx: Ctx): Promise<Response> {
	const id = crypto.randomUUID();
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx);
		const displayName = readName((await ctx.body()).displayName, "displayName", LIMITS.displayName);
		if ((await countActiveMembers(ctx.db, group.id)) >= LIMITS.membersPerGroup) throw limitReached("members", `A group can have up to ${LIMITS.membersPerGroup} members`);
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => [
				ctx.db.prepare("INSERT INTO members (id, group_id, user_id, display_name, created_at, removed_at, rev) VALUES (?, ?, NULL, ?, ?, NULL, ?)").bind(id, group.id, displayName, ctx.now, rev),
			],
			activity: { type: "MEMBER_ADDED", entityId: id, summary: { memberName: displayName }, actor: memberId },
			respond: async () => json({ member: memberJson((await loadMember(ctx.db, group.id, id))!) }, 201),
		};
	});
}

/** PATCH /v1/groups/:g/members/:m renames a seat. A removed seat is 404. */
export async function patchMember(ctx: Ctx): Promise<Response> {
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx);
		const displayName = readName((await ctx.body()).displayName, "displayName", LIMITS.displayName);
		const member = await loadMember(ctx.db, group.id, ctx.params.m);
		if (!member || member.removed_at !== null) throw notFound("Unknown member");
		if (member.display_name === displayName) return { done: json({ member: memberJson(member) }) };
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => [ctx.db.prepare("UPDATE members SET display_name = ?, rev = ? WHERE id = ?").bind(displayName, rev, member.id)],
			activity: { type: "MEMBER_RENAMED", entityId: member.id, summary: { oldName: member.display_name, memberName: displayName }, actor: memberId },
			respond: async () => json({ member: memberJson((await loadMember(ctx.db, group.id, member.id))!) }),
		};
	});
}

/** Currencies in which the member's balance is not zero (see "Balance" in the contract). */
async function nonZeroBalances(db: D1Database, groupId: string, memberId: string): Promise<string[]> {
	const { results } = await db
		.prepare(
			`SELECT currency FROM (
				SELECT e.currency, p.amount AS x FROM expense_payers p JOIN expenses e ON e.id = p.expense_id
					WHERE e.group_id = ?1 AND e.deleted_at IS NULL AND p.member_id = ?2
				UNION ALL SELECT e.currency, -s.amount FROM expense_shares s JOIN expenses e ON e.id = s.expense_id
					WHERE e.group_id = ?1 AND e.deleted_at IS NULL AND s.member_id = ?2
				UNION ALL SELECT currency, amount FROM settlements WHERE group_id = ?1 AND deleted_at IS NULL AND from_member_id = ?2
				UNION ALL SELECT currency, -amount FROM settlements WHERE group_id = ?1 AND deleted_at IS NULL AND to_member_id = ?2
			) GROUP BY currency HAVING SUM(x) != 0`,
		)
		.bind(groupId, memberId)
		.all<{ currency: string }>();
	return results.map((r) => r.currency);
}

/**
 * DELETE /v1/groups/:g/members/:m. Only at a zero balance in every currency; removing your own seat is
 * leaving. Recurring expenses naming the member are stopped, and invites for the seat revoked, in the same write.
 */
export async function removeMember(ctx: Ctx): Promise<Response> {
	return runWrite<Response>(ctx.db, ctx.now, async () => {
		const { group, memberId } = await groupAccess(ctx);
		const member = await loadMember(ctx.db, group.id, ctx.params.m);
		if (!member) throw notFound("Unknown member");
		if (member.removed_at !== null) return { done: noContent() };
		if ((await nonZeroBalances(ctx.db, group.id, member.id)).length > 0) {
			throw new ApiError(422, "balance_not_zero", "This member's balance is not zero", "memberId");
		}
		const { results: active } = await ctx.db.prepare("SELECT * FROM recurring WHERE group_id = ? AND deleted_at IS NULL").bind(group.id).all<RecurringRow>();
		const stop = active.filter((r) => templateMembers(JSON.parse(r.template)).includes(member.id)).map((r) => r.id);
		return {
			groupId: group.id,
			baseRev: group.rev,
			statements: (rev) => [
				ctx.db.prepare("UPDATE members SET removed_at = ?, rev = ? WHERE id = ?").bind(ctx.now, rev, member.id),
				ctx.db.prepare("UPDATE invites SET revoked_at = ? WHERE member_id = ? AND revoked_at IS NULL").bind(ctx.now, member.id),
				...stop.map((id) => stopRecurring(ctx.db, id, memberId, ctx.now, rev)),
			],
			activity: { type: "MEMBER_REMOVED", entityId: member.id, summary: { memberName: member.display_name }, actor: memberId },
			respond: () => noContent(),
		};
	});
}
