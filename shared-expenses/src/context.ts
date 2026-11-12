// What every authenticated handler gets, and the membership check for group routes.

import { forbidden, notFound } from "./http";
import type { GroupRow } from "./rows";
import { isUuid } from "./validate";

export type Options = {
	/** Most rows in one `changes` answer. Tests lower it to exercise paging. */
	changesPageSize: number;
};
export const DEFAULT_OPTIONS: Options = { changesPageSize: 500 };

export type Ctx = {
	request: Request;
	url: URL;
	env: Env;
	db: D1Database;
	userId: string;
	now: number;
	params: Record<string, string>;
	options: Options;
	/** The JSON body, read once however often it is asked for (see readJson). */
	body: () => Promise<Record<string, unknown>>;
};

export type Access = { group: GroupRow; memberId: string };

/**
 * The group in the path and the caller's seat in it: 404 for an unknown group, 403 when the caller holds
 * no active seat. A deleted group answers 404 to everything but reads that pass `allowDeleted`.
 */
export async function groupAccess(ctx: Ctx, opts: { allowDeleted?: boolean } = {}): Promise<Access> {
	const groupId = ctx.params.g;
	if (!isUuid(groupId)) throw notFound("Unknown group");
	const row = await ctx.db
		.prepare("SELECT g.*, (SELECT id FROM members WHERE group_id = g.id AND user_id = ? AND removed_at IS NULL) AS my_member_id FROM groups g WHERE g.id = ?")
		.bind(ctx.userId, groupId)
		.first<GroupRow & { my_member_id: string | null }>();
	if (!row) throw notFound("Unknown group");
	if (!row.my_member_id) throw forbidden();
	if (row.deleted_at !== null && !opts.allowDeleted) throw notFound("This group was deleted");
	const { my_member_id, ...group } = row;
	return { group, memberId: my_member_id };
}

/** Groups the user holds an active seat in, not counting deleted groups. */
export async function countActiveGroups(db: D1Database, userId: string): Promise<number> {
	const row = await db
		.prepare("SELECT COUNT(*) AS n FROM members m JOIN groups g ON g.id = m.group_id WHERE m.user_id = ? AND m.removed_at IS NULL AND g.deleted_at IS NULL")
		.bind(userId)
		.first<{ n: number }>();
	return row?.n ?? 0;
}

export async function countActiveMembers(db: D1Database, groupId: string): Promise<number> {
	const row = await db.prepare("SELECT COUNT(*) AS n FROM members WHERE group_id = ? AND removed_at IS NULL").bind(groupId).first<{ n: number }>();
	return row?.n ?? 0;
}

export const LIMITS = { membersPerGroup: 50, groupsPerUser: 50, groupName: 60, displayName: 40, description: 200, notes: 1000, category: 60 };
