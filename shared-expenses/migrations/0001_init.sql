-- Shared expenses ledger (docs/shared-expenses-api.md).
-- Money is INTEGER minor units of the row's currency. Times are epoch milliseconds.
-- `rev` on a row is the group revision of the write that last touched it.

-- One row per registered phone. Only a SHA-256 hash of the secret is kept.
CREATE TABLE users (
	id TEXT PRIMARY KEY,
	secret_hash TEXT NOT NULL,
	display_name TEXT,
	created_at INTEGER NOT NULL
);

-- `rev` is the group's counter, raised by one with every write to the group.
-- `row_rev` is the revision that last changed the group's own fields.
CREATE TABLE groups (
	id TEXT PRIMARY KEY,
	name TEXT NOT NULL,
	default_currency TEXT NOT NULL,
	simplify_debts INTEGER NOT NULL DEFAULT 1,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	deleted_at INTEGER,
	rev INTEGER NOT NULL,
	row_rev INTEGER NOT NULL
);

-- A seat. `user_id` is the phone that holds it (NULL until someone joins).
CREATE TABLE members (
	id TEXT PRIMARY KEY,
	group_id TEXT NOT NULL REFERENCES groups(id),
	user_id TEXT REFERENCES users(id),
	display_name TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	removed_at INTEGER,
	rev INTEGER NOT NULL
);
CREATE INDEX members_group_rev ON members(group_id, rev);
CREATE INDEX members_user ON members(user_id) WHERE user_id IS NOT NULL;
-- A phone holds at most one active seat per group.
CREATE UNIQUE INDEX members_one_seat ON members(group_id, user_id) WHERE user_id IS NOT NULL AND removed_at IS NULL;

-- `member_id` NULL is an open invite. Only a SHA-256 hash of the token is kept.
CREATE TABLE invites (
	id TEXT PRIMARY KEY,
	group_id TEXT NOT NULL REFERENCES groups(id),
	member_id TEXT REFERENCES members(id),
	token_hash TEXT NOT NULL UNIQUE,
	created_by TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	expires_at INTEGER NOT NULL,
	uses INTEGER NOT NULL DEFAULT 0,
	max_uses INTEGER NOT NULL,
	revoked_at INTEGER
);
CREATE INDEX invites_group ON invites(group_id);

CREATE TABLE expenses (
	id TEXT PRIMARY KEY,
	group_id TEXT NOT NULL REFERENCES groups(id),
	description TEXT NOT NULL,
	amount INTEGER NOT NULL,
	currency TEXT NOT NULL,
	date TEXT NOT NULL,
	category TEXT,
	notes TEXT,
	split_method TEXT NOT NULL,
	recurring_id TEXT,
	created_by TEXT NOT NULL,
	updated_by TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	deleted_at INTEGER,
	version INTEGER NOT NULL,
	rev INTEGER NOT NULL
);
CREATE INDEX expenses_group_rev ON expenses(group_id, rev);

-- `position` keeps the order the phone sent.
CREATE TABLE expense_payers (
	expense_id TEXT NOT NULL REFERENCES expenses(id),
	member_id TEXT NOT NULL,
	amount INTEGER NOT NULL,
	position INTEGER NOT NULL,
	PRIMARY KEY (expense_id, member_id)
);
CREATE INDEX expense_payers_member ON expense_payers(member_id);

CREATE TABLE expense_shares (
	expense_id TEXT NOT NULL REFERENCES expenses(id),
	member_id TEXT NOT NULL,
	amount INTEGER NOT NULL,
	weight TEXT,
	position INTEGER NOT NULL,
	PRIMARY KEY (expense_id, member_id)
);
CREATE INDEX expense_shares_member ON expense_shares(member_id);

CREATE TABLE settlements (
	id TEXT PRIMARY KEY,
	group_id TEXT NOT NULL REFERENCES groups(id),
	from_member_id TEXT NOT NULL,
	to_member_id TEXT NOT NULL,
	amount INTEGER NOT NULL,
	currency TEXT NOT NULL,
	date TEXT NOT NULL,
	notes TEXT,
	created_by TEXT NOT NULL,
	updated_by TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	deleted_at INTEGER,
	version INTEGER NOT NULL,
	rev INTEGER NOT NULL
);
CREATE INDEX settlements_group_rev ON settlements(group_id, rev);

-- `template` is the canonical JSON of the repeating expense fields (amounts as decimal strings).
CREATE TABLE recurring (
	id TEXT PRIMARY KEY,
	group_id TEXT NOT NULL REFERENCES groups(id),
	template TEXT NOT NULL,
	interval TEXT NOT NULL,
	anchor_day INTEGER NOT NULL,
	next_date TEXT NOT NULL,
	created_by TEXT NOT NULL,
	updated_by TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	deleted_at INTEGER,
	version INTEGER NOT NULL,
	rev INTEGER NOT NULL
);
CREATE INDEX recurring_group_rev ON recurring(group_id, rev);
CREATE INDEX recurring_due ON recurring(next_date) WHERE deleted_at IS NULL;

-- One row per write. The primary key makes two writes that claim the same revision collide,
-- which is how a racing write is detected (see src/write.ts).
CREATE TABLE activity (
	group_id TEXT NOT NULL REFERENCES groups(id),
	seq INTEGER NOT NULL,
	actor_member_id TEXT,
	type TEXT NOT NULL,
	entity_id TEXT NOT NULL,
	summary TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	PRIMARY KEY (group_id, seq)
) WITHOUT ROWID;
