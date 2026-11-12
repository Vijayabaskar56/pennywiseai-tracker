// The one way to write to a group. A write is a single D1 batch (one transaction) that
//   1. raises groups.rev from `baseRev` to `baseRev + 1`,
//   2. writes and stamps the touched rows with that new rev, and
//   3. inserts exactly one activity row with seq = the new rev.
//
// Racing writes: every check a handler makes (baseVersion, balances, invite uses, limits) was read at
// `baseRev`. If another write to the same group commits first, it has already inserted activity
// (group_id, baseRev + 1), so our insert hits that primary key, the batch fails and rolls back as a whole.
// We then run the whole attempt again from a fresh read. This is optimistic locking per group: no
// subqueries, no lost checks, and the rev/activity sequence can never skip or repeat a number. Two phones
// writing to one group in the same instant cost one retry.

import { ApiError } from "./http";

export type Activity = {
	type: string;
	entityId: string;
	summary: Record<string, unknown>;
	/** The acting member; null when the server acted (a recurring expense coming due). */
	actor: string | null;
};

export type WritePlan<T> = {
	groupId: string;
	/** The group's rev the checks were made against. 0 with `newGroup` for the write that creates it. */
	baseRev: number;
	/** True when `statements` insert the group row itself (with rev 1). */
	newGroup?: boolean;
	statements: (rev: number) => D1PreparedStatement[];
	activity: Activity;
	/** Builds the answer once the batch has committed. */
	respond: (rev: number) => Promise<T> | T;
};

/** What one attempt decided: nothing to write (`done`), or a plan to commit. */
export type Attempt<T> = { done: T } | WritePlan<T>;

const MAX_ATTEMPTS = 8;

export async function runWrite<T>(db: D1Database, now: number, attempt: () => Promise<Attempt<T>>): Promise<T> {
	for (let i = 0; i < MAX_ATTEMPTS; i++) {
		const step = await attempt();
		if ("done" in step) return step.done;
		const rev = step.baseRev + 1;
		const stmts: D1PreparedStatement[] = [];
		if (!step.newGroup) stmts.push(db.prepare("UPDATE groups SET rev = ? WHERE id = ? AND rev = ?").bind(rev, step.groupId, step.baseRev));
		stmts.push(...step.statements(rev));
		const a = step.activity;
		stmts.push(
			db.prepare("INSERT INTO activity (group_id, seq, actor_member_id, type, entity_id, summary, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
				.bind(step.groupId, rev, a.actor, a.type, a.entityId, JSON.stringify(a.summary), now),
		);
		try {
			await db.batch(stmts);
		} catch (e) {
			if (isRevCollision(e)) {
				// A short random pause, so writers that collided do not collide again in lockstep.
				await new Promise((r) => setTimeout(r, Math.random() * 10 * (i + 1)));
				continue;
			}
			throw e;
		}
		return step.respond(rev);
	}
	throw new ApiError(503, "busy", "The group is busy, try again");
}

const isRevCollision = (e: unknown) => e instanceof Error && /UNIQUE constraint failed: activity\./.test(e.message);
