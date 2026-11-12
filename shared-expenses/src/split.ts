// The money part of an expense, shared by expenses and recurring templates: amount, currency,
// split method, payers and shares, validated so payers and shares each add up to the amount.

import { invalid } from "./http";
import { formatAmount, parseAmount, readCurrency } from "./money";
import type { MemberRow } from "./rows";
import { LIMITS } from "./context";
import { readEnum, readName, readOptionalText, readUuid } from "./validate";

export const SPLIT_METHODS = ["EQUAL", "EXACT", "PERCENT", "SHARES"] as const;
export type SplitMethod = (typeof SPLIT_METHODS)[number];

export type Line = { memberId: string; amount: number; weight: string | null };

/** Description, money and split of an expense; amounts in minor units. */
export type SplitInput = {
	description: string;
	amount: number;
	currency: string;
	digits: number;
	category: string | null;
	notes: string | null;
	splitMethod: SplitMethod;
	payers: Line[];
	shares: Line[];
};

const WEIGHT_RE = /^\d{1,12}(\.\d{1,6})?$/;

/** Reads the fields an expense and a recurring template share. `prefix` goes in front of field names ("template."). */
export function readSplit(body: Record<string, unknown>, prefix = ""): SplitInput {
	const f = (name: string) => prefix + name;
	const description = readName(body.description, f("description"), LIMITS.description);
	const { currency, digits } = readCurrency(body.currency, f("currency"));
	const amount = parseAmount(body.amount, digits, f("amount"));
	const category = readOptionalText(typeof body.category === "string" ? body.category.trim() : body.category, f("category"), LIMITS.category);
	const notes = readOptionalText(body.notes, f("notes"), LIMITS.notes);
	const splitMethod = readEnum(body.splitMethod, f("splitMethod"), SPLIT_METHODS);
	const payers = readLines(body.payers, f("payers"), amount, digits, null);
	const shares = readLines(body.shares, f("shares"), amount, digits, splitMethod);
	return { description, amount, currency, digits, category, notes, splitMethod, payers, shares };
}

/**
 * A payers or shares list: at least one entry, each above zero, no member twice, summing to `total`.
 * `method` is set for shares: PERCENT and SHARES need the typed `weight`, EQUAL and EXACT must not have one.
 */
function readLines(value: unknown, field: string, total: number, digits: number, method: SplitMethod | null): Line[] {
	if (!Array.isArray(value) || value.length === 0) throw invalid(field, `${field} needs at least one entry`);
	if (value.length > LIMITS.membersPerGroup) throw invalid(field, `${field} has too many entries`);
	const seen = new Set<string>();
	let sum = 0;
	const lines = value.map((item, i): Line => {
		if (typeof item !== "object" || item === null) throw invalid(field, `${field}[${i}] must be an object`);
		const o = item as Record<string, unknown>;
		const memberId = readUuid(o.memberId, field);
		if (seen.has(memberId)) throw invalid(field, `${field} names a member twice`);
		seen.add(memberId);
		const amount = parseAmount(o.amount, digits, field);
		sum += amount;
		let weight: string | null = null;
		if (method === "PERCENT" || method === "SHARES") {
			if (typeof o.weight !== "string" || !WEIGHT_RE.test(o.weight)) throw invalid(field, `${field} need a weight for ${method}`);
			weight = o.weight;
		} else if (method !== null && o.weight !== undefined && o.weight !== null) {
			throw invalid(field, `${field} have no weight for ${method}`);
		}
		return { memberId, amount, weight };
	});
	if (sum !== total) throw invalid(field, `${field === "payers" || field.endsWith(".payers") ? "Payers" : "Shares"} must add up to the amount`);
	return lines;
}

/** Every member named must be a seat in the group; a removed seat only if `allowedRemoved` has it. */
export function checkMembers(ids: { field: string; memberIds: string[] }[], members: MemberRow[], allowedRemoved: Set<string>): void {
	const byId = new Map(members.map((m) => [m.id, m]));
	for (const { field, memberIds } of ids) {
		for (const id of memberIds) {
			const m = byId.get(id);
			if (!m) throw invalid(field, "Names someone who is not a member of this group");
			if (m.removed_at !== null && !allowedRemoved.has(id)) throw invalid(field, "Names a member who was removed from this group");
		}
	}
}

export const loadMembers = (db: D1Database, groupId: string) =>
	db.prepare("SELECT * FROM members WHERE group_id = ?").bind(groupId).all<MemberRow>().then((r) => r.results);

/** The template as stored and sent: amounts as decimal strings, lines in the order the phone sent. */
export function templateJson(s: SplitInput) {
	return {
		description: s.description, amount: formatAmount(s.amount, s.digits), currency: s.currency,
		category: s.category, notes: s.notes, splitMethod: s.splitMethod,
		payers: s.payers.map((p) => ({ memberId: p.memberId, amount: formatAmount(p.amount, s.digits) })),
		shares: s.shares.map((p) => ({ memberId: p.memberId, amount: formatAmount(p.amount, s.digits), weight: p.weight })),
	};
}

/** Order-insensitive form of payers and shares, for "is this the same body" checks. */
export const sortedLines = <T extends { memberId: string }>(lines: T[]) => [...lines].sort((a, b) => (a.memberId < b.memberId ? -1 : 1));
