// Money on the wire is a decimal string plus an ISO 4217 code; in D1 it is whole minor units.
// The number of decimals is the currency's standard fraction digits, taken from Intl (2 INR, 0 JPY, 3 KWD).
// Parsing and formatting work on the digits as text, never through floating point.

import { invalid } from "./http";

/** Amounts must stay below this many minor units (10^12), well inside Number.MAX_SAFE_INTEGER. */
export const MAX_MINOR = 1_000_000_000_000;

const digitsCache = new Map<string, number>();
let known: Set<string> | undefined;

/** The currency's fraction digits, or null when the code is not a known ISO 4217 currency. */
export function fractionDigits(code: string): number | null {
	const cached = digitsCache.get(code);
	if (cached !== undefined) return cached;
	if (!/^[A-Z]{3}$/.test(code)) return null;
	known ??= new Set(Intl.supportedValuesOf("currency"));
	if (!known.has(code)) return null;
	const digits = new Intl.NumberFormat("en", { style: "currency", currency: code }).resolvedOptions().maximumFractionDigits ?? 2;
	digitsCache.set(code, digits);
	return digits;
}

/** Validates a currency field and returns its fraction digits. */
export function readCurrency(value: unknown, field: string): { currency: string; digits: number } {
	const digits = typeof value === "string" ? fractionDigits(value) : null;
	if (digits === null) throw invalid(field, "Unknown currency code");
	return { currency: value as string, digits };
}

/** Parses a decimal string such as "1240.5" into minor units (124050 for 2 digits). Must be above zero. */
export function parseAmount(value: unknown, digits: number, field: string): number {
	if (typeof value !== "string" || value.length > 32) throw invalid(field, "Amount must be a decimal string");
	const m = value.match(/^(\d+)(?:\.(\d+))?$/);
	if (!m) throw invalid(field, "Amount must be a decimal string");
	const frac = m[2] ?? "";
	if (frac.length > digits) throw invalid(field, `Amount has more than ${digits} decimals`);
	const minor = BigInt(m[1] + frac.padEnd(digits, "0"));
	if (minor <= 0n) throw invalid(field, "Amount must be above zero");
	if (minor >= BigInt(MAX_MINOR)) throw invalid(field, "Amount is too large");
	return Number(minor);
}

/** Formats minor units with exactly `digits` decimals: 124000 → "1240.00". */
export function formatAmount(minor: number, digits: number): string {
	const sign = minor < 0 ? "-" : "";
	const s = Math.abs(minor).toString().padStart(digits + 1, "0");
	return digits === 0 ? sign + s : `${sign}${s.slice(0, -digits)}.${s.slice(-digits)}`;
}

/** Formats an amount stored in a currency that was validated when it was written. */
export const formatMoney = (minor: number, currency: string) => formatAmount(minor, fractionDigits(currency) ?? 2);
