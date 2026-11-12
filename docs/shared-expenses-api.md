# Shared expenses: server contract (v1)

The wire contract between the PennyWise app and the shared-expenses server
(`shared-expenses/`). The design and the reasons are in
[shared-expenses.md](shared-expenses.md). The server and the app are built
against this file; change it first when either side needs something new.

## Conventions

- **Base path:** every API route starts with `/v1`. Bodies are JSON
  (`Content-Type: application/json`), UTF-8.
- **Auth:** `Authorization: Device <userId>.<secret>`. `userId` is 32 lowercase
  hex characters; `secret` is 43 base64url characters. Only `POST /v1/device`
  and `GET /join/:token` take no auth. The server stores a SHA-256 hash of the
  secret.
- **IDs:** lowercase UUID v4 strings. The phone creates the IDs of expenses,
  settlements and recurring expenses; the server creates the rest.
- **Money:** `amount` is a decimal string and always travels with `currency`,
  an uppercase ISO 4217 code. The server stores whole minor units using the
  currency's standard fraction digits (2 for INR, 0 for JPY, 3 for KWD) and
  rejects an amount with more decimals than that. Responses print exactly that
  many decimals (`"1240.00"`).
- **Times:** server times (`createdAt`, `updatedAt`, `deletedAt`, `expiresAt`)
  are epoch milliseconds. An expense `date` is a calendar date, `YYYY-MM-DD`.
- **Revisions:** each group has `rev`, a counter that goes up by exactly one
  with every write to the group. Each write stamps the rows it touched with
  the new `rev` and adds exactly one activity row whose `seq` is that `rev`.
  A write is one atomic D1 batch.
- **Versions:** expenses, settlements and recurring expenses carry `version`,
  starting at 1 and going up by one with every change to that row.
- **Deletes are soft:** a deleted row keeps its data and gets `deletedAt`.

### Errors

```json
{ "error": "invalid", "message": "Shares must add up to the amount", "field": "shares" }
```

| Status | `error` | When |
|---|---|---|
| 401 | `unauthorized` | Missing or wrong credentials |
| 403 | `forbidden` | Not an active member of the group in the path |
| 404 | `not_found` | Unknown group, member, expense, settlement, recurring or invite |
| 409 | `conflict` | `baseVersion` is stale. The body also has `current`, the stored row |
| 409 | `already_member` | Accepting a seat invite while holding another seat in that group |
| 410 | `gone` | The invite expired, was revoked or is used up |
| 422 | `invalid` | Validation failed; `field` names the input |
| 422 | `balance_not_zero` | Removing a member whose balance is not zero in every currency |
| 422 | `limit_reached` | A limit below would be exceeded |
| 429 | `rate_limited` | Too many requests |

### Limits

| What | Limit |
|---|---|
| Active members per group | 50 |
| Groups per user | 50 |
| Group name | 1 to 60 characters |
| Display name | 1 to 40 characters |
| Expense description | 1 to 200 characters |
| Notes | up to 1,000 characters |
| Category | up to 60 characters |
| Amount | more than 0, below 10^12 minor units |
| Invite lifetime | 7 days |
| Uses of an open invite | 20 |
| `changes` page size | 500 rows |

Rate limits: registration 5 a minute per IP address; invite preview and accept
10 a minute per user; everything else 300 a minute per user.

## Objects

```jsonc
// Group
{ "id": "…", "name": "Flat 4B", "defaultCurrency": "INR", "simplifyDebts": true,
  "createdAt": 0, "updatedAt": 0, "deletedAt": null, "rev": 12 }

// Member: one seat in a group. `joined` is true once a phone holds the seat.
{ "id": "…", "groupId": "…", "displayName": "Roommate", "joined": false,
  "removedAt": null, "rev": 3 }

// Expense
{ "id": "…", "groupId": "…", "description": "Groceries",
  "amount": "1240.00", "currency": "INR", "date": "2026-11-10",
  "category": "Groceries", "notes": null,
  "splitMethod": "EQUAL",               // EQUAL | EXACT | PERCENT | SHARES
  "payers": [ { "memberId": "…", "amount": "1240.00" } ],
  "shares": [ { "memberId": "…", "amount": "620.00", "weight": null },
              { "memberId": "…", "amount": "620.00", "weight": null } ],
  "recurringId": null,
  "createdBy": "<memberId>", "updatedBy": "<memberId>",
  "createdAt": 0, "updatedAt": 0, "deletedAt": null, "version": 1, "rev": 7 }

// Settlement: `fromMemberId` paid `toMemberId`.
{ "id": "…", "groupId": "…", "fromMemberId": "…", "toMemberId": "…",
  "amount": "500.00", "currency": "INR", "date": "2026-12-05", "notes": null,
  "createdBy": "<memberId>", "updatedBy": "<memberId>",
  "createdAt": 0, "updatedAt": 0, "deletedAt": null, "version": 1, "rev": 9 }

// Recurring expense. `template` has the expense fields that repeat.
{ "id": "…", "groupId": "…",
  "template": { "description": "Rent", "amount": "30000.00", "currency": "INR",
                "category": "Rent", "notes": null, "splitMethod": "EQUAL",
                "payers": [ … ], "shares": [ … ] },
  "interval": "MONTHLY",                // WEEKLY | MONTHLY | YEARLY
  "anchorDay": 1,                       // day of month of the first date; MONTHLY and YEARLY keep it
  "nextDate": "2026-12-01",
  "createdBy": "<memberId>", "updatedBy": "<memberId>",
  "createdAt": 0, "updatedAt": 0, "deletedAt": null, "version": 1, "rev": 10 }

// Activity: one per write. `actorMemberId` is null when the server acted
// (a recurring expense coming due).
{ "seq": 7, "groupId": "…", "actorMemberId": "…", "type": "EXPENSE_ADDED",
  "entityId": "…", "summary": { "description": "Groceries", "amount": "1240.00", "currency": "INR" },
  "createdAt": 0 }
```

- `weight` is what the person typed for PERCENT (`"50"`, `"33.33"`) or SHARES
  (`"2"`); it is null for EQUAL and EXACT. The server stores it and does not
  recompute `amount` from it.
- `createdBy` and `updatedBy` are member IDs in the same group.

### Activity types and their `summary`

| `type` | `entityId` | `summary` keys |
|---|---|---|
| `GROUP_CREATED`, `GROUP_DELETED` | group | `name` |
| `GROUP_RENAMED` | group | `oldName`, `name` |
| `GROUP_SETTINGS_CHANGED` | group | `simplifyDebts`, `defaultCurrency` (the changed ones) |
| `MEMBER_ADDED`, `MEMBER_JOINED`, `MEMBER_REMOVED` | member | `memberName` |
| `MEMBER_RENAMED` | member | `oldName`, `memberName` |
| `EXPENSE_ADDED`, `EXPENSE_EDITED`, `EXPENSE_DELETED`, `EXPENSE_RESTORED` | expense | `description`, `amount`, `currency` |
| `SETTLEMENT_ADDED`, `SETTLEMENT_EDITED`, `SETTLEMENT_DELETED` | settlement | `fromMemberId`, `toMemberId`, `amount`, `currency` |
| `RECURRING_ADDED`, `RECURRING_EDITED`, `RECURRING_STOPPED` | recurring | `description`, `amount`, `currency`, `interval` |

## Balance

The balance of member `m` in one currency, over rows that are not deleted:

```
sum(payer amounts of m) − sum(share amounts of m)
  + sum(settlements m sent) − sum(settlements m received)
```

Positive means the group owes `m`. The balances of all members in one currency
sum to zero. The server uses this only to decide whether a member can be
removed; the app computes it for display.

## Routes

### Identity

| Route | Body | Returns |
|---|---|---|
| `POST /v1/device` | none | `201 { "userId", "secret" }` |
| `GET /v1/me` | | `{ "userId", "displayName" }` (`displayName` is null until set) |
| `PUT /v1/me` | `{ "displayName" }` | `{ "userId", "displayName" }` |
| `GET /v1/me/groups` | | `{ "groups": [ { "id", "rev", "memberId" } ] }`, groups where the caller is an active member, deleted ones included |

### Groups and members

These work online only; the app does not queue them.

| Route | Body | Returns |
|---|---|---|
| `POST /v1/groups` | `{ "name", "defaultCurrency", "displayName" }` | `201 { "group", "me": "<memberId>", "members": [creator] }`. `displayName` names the creator's seat. |
| `PATCH /v1/groups/:g` | any of `name`, `simplifyDebts`, `defaultCurrency` | `{ "group" }` |
| `DELETE /v1/groups/:g` | | `204`. Any member can delete. Members keep read access to `changes` so their phones learn of it. |
| `POST /v1/groups/:g/members` | `{ "displayName" }` | `201 { "member" }`, an empty seat |
| `PATCH /v1/groups/:g/members/:m` | `{ "displayName" }` | `{ "member" }` |
| `DELETE /v1/groups/:g/members/:m` | | `204`, or `422 balance_not_zero`. Removing your own seat is leaving. Recurring expenses that name the member are stopped in the same write. |

### Invites

| Route | Body | Returns |
|---|---|---|
| `POST /v1/groups/:g/invites` | `{ "memberId": "<seat>" }` or `{}` | `201 { "id", "token", "url", "memberId", "expiresAt" }`. `token` is shown only here. `url` is `<BASE_URL>/join/<token>`. |
| `GET /v1/groups/:g/invites` | | `{ "invites": [ { "id", "memberId", "expiresAt", "uses" } ] }`, active ones |
| `DELETE /v1/groups/:g/invites/:id` | | `204` |
| `GET /v1/invites/:token` | | `{ "groupId", "groupName", "memberDisplayName", "expiresAt", "alreadyMember" }` |
| `POST /v1/invites/:token/accept` | `{ "displayName" }` | `{ "groupId", "memberId" }` |
| `GET /join/:token` (no auth) | | An HTML page with an "Open in PennyWise" link to `pennywise://shared/join?server=<BASE_URL>&token=<token>` and the invite link to copy. It shows nothing about the group. |

- A **seat invite** (`memberId` set) hands that seat to the caller: the seat's
  `displayName` becomes the caller's, any phone that held it loses access, and
  the invite is used up. Activity: `MEMBER_JOINED`.
- An **open invite** (no `memberId`) creates a new seat for the caller and can
  be used 20 times.
- Accepting when the caller already holds a seat in the group returns that
  seat with `200`, unless the invite is for a different seat (`409
  already_member`).
- The server stores the SHA-256 hash of the token.

### Expenses, settlements, recurring

These are idempotent `PUT`s keyed by an ID the phone created, so the app can
queue them offline and send them again safely.

| Route | Body | Returns |
|---|---|---|
| `PUT /v1/groups/:g/expenses/:id` | `{ "baseVersion", "description", "amount", "currency", "date", "category", "notes", "splitMethod", "payers", "shares", "recurringId" }` | `201 { "expense" }` on create, `200 { "expense" }` on edit |
| `DELETE /v1/groups/:g/expenses/:id` | | `204`. No version check: a delete always wins. Deleting twice is fine. |
| `POST /v1/groups/:g/expenses/:id/restore` | | `200 { "expense" }` |
| `PUT /v1/groups/:g/settlements/:id` | `{ "baseVersion", "fromMemberId", "toMemberId", "amount", "currency", "date", "notes" }` | `201` or `200 { "settlement" }` |
| `DELETE /v1/groups/:g/settlements/:id` | | `204` |
| `PUT /v1/groups/:g/recurring/:id` | `{ "baseVersion", "template", "interval", "nextDate" }` | `201` or `200 { "recurring" }` |
| `DELETE /v1/groups/:g/recurring/:id` | | `204` (stops it) |

`baseVersion` rules, the same for all three:

- `0` means create. If the row does not exist it is created with `version` 1.
  If it exists and the body matches what is stored, the stored row is returned
  with `200` and nothing changes (a repeated send). If it exists and differs:
  `409 conflict`.
- Otherwise it must equal the stored `version`, or the answer is `409 conflict`
  with `current`. Editing a deleted row is also `409 conflict`.

Expense validation (`422 invalid`):

- `payers` has at least one entry, each amount above zero, no member twice,
  amounts sum to `amount`.
- `shares` has at least one entry, each amount above zero, no member twice,
  amounts sum to `amount`.
- Every member belongs to the group. A removed member is accepted only if the
  stored expense already names them.
- A settlement's two members differ and belong to the group.

Recurring expenses:

- A Cron Trigger runs once a day. For each recurring expense that is not
  deleted and whose `nextDate` is on or before today (UTC), the server creates
  an expense dated `nextDate` from the template with a new ID and that
  `recurringId`, then moves `nextDate` forward by the interval, and repeats
  until `nextDate` is after today (at most 12 times in one run).
- MONTHLY and YEARLY keep `anchorDay`, clamped to the month's last day:
  anchor 31 gives 30 November, then 31 December.
- The phone creates the first expense itself and sends the recurring expense
  with `nextDate` already one interval later.

### Changes

`GET /v1/groups/:g/changes?since=<rev>` returns everything stamped with a
revision above `since`, deleted rows included.

```jsonc
{ "rev": 12,                 // the revision this answer brings the phone up to
  "hasMore": false,          // true: ask again with since = rev
  "me": "<memberId>",
  "group": { … },            // present when the group row changed after `since`
  "members": [ … ], "expenses": [ … ], "settlements": [ … ],
  "recurring": [ … ], "activity": [ … ] }
```

- `since=0` returns the whole group.
- An answer holds at most 500 rows across all lists. When more remain, `rev` is
  the last revision fully included and `hasMore` is true.
- A caller whose seat was removed or taken over gets `403 forbidden`; the app
  then drops its copy of the group.

## Details settled while building the server

- **Sync cursor:** use the answer's top-level `rev`, never `group.rev`. In a
  partial page the group's `rev` can be ahead of the answer's.
- **`changes` paging** counts an expense with its payers and shares as one
  row and does not count the group. A revision is never split across pages. A
  missing `since` means 0; a `since` above the group's `rev` returns the
  current `rev` and nothing else.
- **Repeated sends:** only a repeated create (`baseVersion` 0, same body) is
  answered `200` unchanged. A repeated edit answers `409 conflict`; the app
  treats it as done when `current` equals what it sent.
- **Deleting and restoring** raise `version`. Restoring a live expense returns
  it unchanged.
- **`anchorDay`** may be sent in the recurring `PUT` body. Without it the
  server uses the day of `nextDate`, or keeps the stored one when `nextDate`
  did not change. When the cron moves `nextDate` it raises the recurring
  expense's `version`.
- **A recurring expense's created expenses** have `createdBy` and `updatedBy`
  set to the recurring expense's `createdBy`; the activity row's
  `actorMemberId` is null.
- **Weights:** `weight` is required for PERCENT and SHARES and must be absent
  or null for EQUAL and EXACT. It matches `^\d{1,12}(\.\d{1,6})?$`.
- **Input:** `amount` must be a JSON string. Names, descriptions and
  categories are trimmed; an empty `category` or `notes` is stored as null.
  Lengths count Unicode code points.
- **IDs:** an expense, settlement or recurring ID that exists in another
  group answers `422 invalid` with `field: "id"`. A `baseVersion` above 0 for
  an unknown ID answers `404`.
- **Invites** are not group writes: creating or revoking one changes no `rev`.
  Accepting one is a write (`MEMBER_JOINED`). Accept always answers `200`.
  Removing a member revokes the invites for that seat.
- **A deleted group** answers `404` on every route except `changes` and a
  repeated `DELETE`; its invites answer `410`.
- **Membership is checked before the body**, so a non-member gets `403` even
  with a malformed body. Malformed JSON answers `422` with `field: "body"`.
- **Other statuses:** `503 busy` when a write lost every retry against
  concurrent writes (send it again), `500 internal`, `404` for an unknown
  route. `429` carries no `Retry-After`. Every API answer is
  `Cache-Control: no-store`.
