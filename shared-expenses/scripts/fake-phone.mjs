#!/usr/bin/env node
// A second "phone" for trying shared groups with one real device: it registers with the
// server, joins a group from an invite link and adds, edits or deletes rows over HTTP.
//
//   node scripts/fake-phone.mjs register "Member B"
//   node scripts/fake-phone.mjs join http://localhost:8790/join/<token>
//   node scripts/fake-phone.mjs show
//   node scripts/fake-phone.mjs expense "Dinner" 900.50 INR --paid-by me --split "Member A=300.50,me=600"
//   node scripts/fake-phone.mjs expense "Snacks" 100 INR --paid-by me            (equal split, everyone)
//   node scripts/fake-phone.mjs edit <expenseId> --description "Dinner B" [--amount 950]
//   node scripts/fake-phone.mjs delete <expenseId>
//   node scripts/fake-phone.mjs settle me "Member A" 199.50 INR
//   node scripts/fake-phone.mjs invite [seatName]        (open invite, or one for a seat)
//   node scripts/fake-phone.mjs rename-group "New name"
//   node scripts/fake-phone.mjs create-group "Trip" [currency]   (and switches to it)
//   node scripts/fake-phone.mjs use <groupId>             (switch the current group)
//
// State (credentials, current group) lives in FAKE_PHONE_STATE or .fake-phone.json; delete it to start over.
import { randomUUID } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const BASE = process.env.SHARED_EXPENSES_URL ?? "http://localhost:8790";
const STATE_FILE = process.env.FAKE_PHONE_STATE ?? ".fake-phone.json";
const state = existsSync(STATE_FILE) ? JSON.parse(readFileSync(STATE_FILE, "utf8")) : {};
const save = () => writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));

async function api(method, path, body) {
	const headers = { "Content-Type": "application/json" };
	if (state.userId) headers.Authorization = `Device ${state.userId}.${state.secret}`;
	const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
	const text = await res.text();
	const data = text ? JSON.parse(text) : null;
	if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
	return data;
}

const today = () => new Date().toISOString().slice(0, 10);
const flag = (args, name) => { const i = args.indexOf(name); return i < 0 ? undefined : args[i + 1]; };

/** Everything in the current group, folded from the changes feed. */
async function group() {
	if (!state.groupId) throw new Error("No group yet: run `join <invite link>` first");
	const all = { members: new Map(), expenses: new Map(), settlements: new Map(), group: null, me: null, rev: 0 };
	let since = 0, more = true;
	while (more) {
		const page = await api("GET", `/v1/groups/${state.groupId}/changes?since=${since}`);
		if (page.group) all.group = page.group;
		all.me = page.me;
		for (const m of page.members) all.members.set(m.id, m);
		for (const e of page.expenses) all.expenses.set(e.id, e);
		for (const s of page.settlements) all.settlements.set(s.id, s);
		since = all.rev = page.rev;
		more = page.hasMore;
	}
	return all;
}

function memberId(g, who) {
	if (who === "me") return g.me;
	const hit = [...g.members.values()].find((m) => !m.removedAt && m.displayName.toLowerCase() === who.toLowerCase());
	if (!hit) throw new Error(`No member named "${who}". Members: ${[...g.members.values()].map((m) => m.displayName).join(", ")}`);
	return hit.id;
}

const digits = (currency) => new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions().maximumFractionDigits;
const toMinor = (amount, currency) => Math.round(Number(amount) * 10 ** digits(currency));
const fromMinor = (minor, currency) => (minor / 10 ** digits(currency)).toFixed(digits(currency));

/** Equal shares in minor units; leftovers go one each in ascending member-ID order, as the app does. */
function equalShares(total, ids, currency) {
	const minor = toMinor(total, currency), sorted = [...ids].sort();
	const each = Math.floor(minor / sorted.length);
	let left = minor - each * sorted.length;
	return sorted.map((id) => ({ memberId: id, amount: fromMinor(each + (left-- > 0 ? 1 : 0), currency), weight: null }));
}

const commands = {
	async register([name]) {
		Object.assign(state, await api("POST", "/v1/device"));
		save();
		await api("PUT", "/v1/me", { displayName: name ?? "Member B" });
		state.displayName = name ?? "Member B";
		save();
		console.log(`Registered as ${state.displayName}`);
	},
	async join([link]) {
		const token = link.split("/join/").pop().split(/[?#&]/)[0].replace(/^.*token=/, "");
		console.log("Invite:", await api("GET", `/v1/invites/${token}`));
		const joined = await api("POST", `/v1/invites/${token}/accept`, { displayName: state.displayName });
		state.groupId = joined.groupId;
		save();
		console.log("Joined:", joined);
	},
	async show() {
		const g = await group();
		const name = (id) => (id === g.me ? "me" : g.members.get(id)?.displayName ?? id);
		console.log(`Group "${g.group.name}" rev ${g.rev} simplify=${g.group.simplifyDebts}`);
		console.log("Members:", [...g.members.values()].map((m) => `${m.displayName}${m.joined ? "" : " (not joined)"}${m.removedAt ? " (removed)" : ""}`).join(", "));
		const balances = {};
		const add = (currency, id, minor) => { (balances[currency] ??= {})[id] = (balances[currency][id] ?? 0) + minor; };
		for (const e of g.expenses.values()) {
			console.log(`${e.deletedAt ? "[deleted] " : ""}${e.id} v${e.version} ${e.date} ${e.description} ${e.amount} ${e.currency} paid ${e.payers.map((p) => `${name(p.memberId)}=${p.amount}`).join(",")} shares ${e.shares.map((s) => `${name(s.memberId)}=${s.amount}`).join(",")}`);
			if (e.deletedAt) continue;
			for (const p of e.payers) add(e.currency, p.memberId, toMinor(p.amount, e.currency));
			for (const s of e.shares) add(e.currency, s.memberId, -toMinor(s.amount, e.currency));
		}
		for (const s of g.settlements.values()) {
			console.log(`${s.deletedAt ? "[deleted] " : ""}${s.id} settlement ${name(s.fromMemberId)} -> ${name(s.toMemberId)} ${s.amount} ${s.currency}`);
			if (s.deletedAt) continue;
			add(s.currency, s.fromMemberId, toMinor(s.amount, s.currency));
			add(s.currency, s.toMemberId, -toMinor(s.amount, s.currency));
		}
		for (const [currency, byMember] of Object.entries(balances)) {
			console.log(`Balances ${currency}:`, Object.entries(byMember).map(([id, minor]) => `${name(id)} ${fromMinor(minor, currency)}`).join(", "));
		}
	},
	async expense([description, amount, currency, ...rest]) {
		const g = await group();
		const payer = memberId(g, flag(rest, "--paid-by") ?? "me");
		const split = flag(rest, "--split");
		const active = [...g.members.values()].filter((m) => !m.removedAt).map((m) => m.id);
		const shares = split
			? split.split(",").map((part) => { const [who, value] = part.split("="); return { memberId: memberId(g, who.trim()), amount: fromMinor(toMinor(value, currency), currency), weight: null }; })
			: equalShares(amount, active, currency);
		const body = { baseVersion: 0, description, amount: fromMinor(toMinor(amount, currency), currency), currency, date: flag(rest, "--date") ?? today(), category: flag(rest, "--category") ?? null, notes: null, splitMethod: split ? "EXACT" : "EQUAL", payers: [{ memberId: payer, amount: fromMinor(toMinor(amount, currency), currency) }], shares, recurringId: null };
		const { expense } = await api("PUT", `/v1/groups/${state.groupId}/expenses/${randomUUID()}`, body);
		console.log("Added", expense.id, expense.description, expense.amount, expense.currency);
	},
	async edit([id, ...rest]) {
		const e = (await group()).expenses.get(id);
		if (!e) throw new Error(`No expense ${id}`);
		const amount = flag(rest, "--amount");
		const body = { baseVersion: e.version, description: flag(rest, "--description") ?? e.description, amount: e.amount, currency: e.currency, date: e.date, category: e.category, notes: e.notes, splitMethod: e.splitMethod, payers: e.payers, shares: e.shares, recurringId: e.recurringId };
		if (amount) {
			if (e.payers.length !== 1 || e.splitMethod !== "EQUAL") throw new Error("--amount only works on a one-payer equal split");
			body.amount = fromMinor(toMinor(amount, e.currency), e.currency);
			body.payers = [{ memberId: e.payers[0].memberId, amount: body.amount }];
			body.shares = equalShares(amount, e.shares.map((s) => s.memberId), e.currency);
		}
		const { expense } = await api("PUT", `/v1/groups/${state.groupId}/expenses/${id}`, body);
		console.log("Edited", expense.id, "now v" + expense.version, expense.description, expense.amount);
	},
	async delete([id]) {
		await api("DELETE", `/v1/groups/${state.groupId}/expenses/${id}`);
		console.log("Deleted", id);
	},
	async settle([from, to, amount, currency]) {
		const g = await group();
		const body = { baseVersion: 0, fromMemberId: memberId(g, from), toMemberId: memberId(g, to), amount: fromMinor(toMinor(amount, currency), currency), currency, date: today(), notes: null };
		const { settlement } = await api("PUT", `/v1/groups/${state.groupId}/settlements/${randomUUID()}`, body);
		console.log("Settled", settlement.id, settlement.amount, settlement.currency);
	},
	async invite([seat]) {
		const g = await group();
		console.log(await api("POST", `/v1/groups/${state.groupId}/invites`, seat ? { memberId: memberId(g, seat) } : {}));
	},
	async "create-group"([name, currency]) {
		const made = await api("POST", "/v1/groups", { name, defaultCurrency: currency ?? "INR", displayName: state.displayName });
		state.groupId = made.group.id;
		save();
		console.log("Created", made.group.name, made.group.id);
	},
	async use([groupId]) {
		state.groupId = groupId;
		save();
		console.log("Now using", groupId);
	},
	async "rename-group"([name]) {
		console.log((await api("PATCH", `/v1/groups/${state.groupId}`, { name })).group.name);
	},
};

const [command, ...args] = process.argv.slice(2);
if (!commands[command]) {
	console.error(`Usage: fake-phone.mjs <${Object.keys(commands).join("|")}> ...`);
	process.exit(2);
}
commands[command](args).catch((error) => { console.error(String(error.message ?? error)); process.exit(1); });
