// Small readers for request fields. Each throws `422 invalid` naming the field.

import { invalid } from "./http";

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const isUuid = (v: unknown): v is string => typeof v === "string" && UUID_RE.test(v);

const length = (s: string) => [...s].length;

/** A required name: trimmed, `min` to `max` characters. */
export function readName(value: unknown, field: string, max: number): string {
	if (typeof value !== "string") throw invalid(field, `${field} is required`);
	const s = value.trim();
	if (length(s) < 1 || length(s) > max) throw invalid(field, `${field} must be 1 to ${max} characters`);
	return s;
}

/** An optional text: null, absent or "" give null; otherwise at most `max` characters. */
export function readOptionalText(value: unknown, field: string, max: number): string | null {
	if (value === undefined || value === null || value === "") return null;
	if (typeof value !== "string") throw invalid(field, `${field} must be a string`);
	if (length(value) > max) throw invalid(field, `${field} must be at most ${max} characters`);
	return value;
}

export function readUuid(value: unknown, field: string): string {
	if (!isUuid(value)) throw invalid(field, `${field} must be a lowercase UUID`);
	return value;
}

export function readOptionalUuid(value: unknown, field: string): string | null {
	return value === undefined || value === null ? null : readUuid(value, field);
}

export function readBaseVersion(value: unknown): number {
	if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw invalid("baseVersion", "baseVersion must be a whole number, 0 to create");
	return value;
}

/** A calendar date, YYYY-MM-DD, that exists. */
export function readDate(value: unknown, field: string): string {
	if (typeof value !== "string" || !isDate(value)) throw invalid(field, `${field} must be a date, YYYY-MM-DD`);
	return value;
}

export function isDate(s: string): boolean {
	const m = s.match(/^(\d{4})-(\d{2})-(\d{2})$/);
	if (!m) return false;
	const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
	return mo >= 1 && mo <= 12 && d >= 1 && d <= daysInMonth(y, mo);
}

export const daysInMonth = (year: number, month: number) => new Date(Date.UTC(year, month, 0)).getUTCDate();

export function readEnum<T extends string>(value: unknown, field: string, allowed: readonly T[]): T {
	if (typeof value !== "string" || !allowed.includes(value as T)) throw invalid(field, `${field} must be one of ${allowed.join(", ")}`);
	return value as T;
}
