# Shared expenses (design)

Status: **accepted, being built**. The wire contract is in
[shared-expenses-api.md](shared-expenses-api.md). This is a personal-fork
feature (`feat/shared-expenses`); it is not proposed upstream, because upstream
promises no accounts and no server.

Shared expenses let a few people who all run this PennyWise build keep one
ledger of what they paid for each other, the way Splitwise does, and feed each
person's own part into their PennyWise budget. It replaces running Splitwise
beside PennyWise.

## Decisions already made

| Question | Decision |
|---|---|
| Who uses it | Everyone installs this PennyWise build on Android. No web client. |
| Server | A Cloudflare Worker with a D1 database, on the fork owner's Cloudflare account. |
| Privacy | The server stores the ledger as readable rows. Only the fork owner runs it. |
| First version | Groups, expenses, equal / exact / percent / share splits, multiple payers, balances, simplified debts, settle up, recurring expenses, activity feed. |
| Push alerts | Later. The first version refreshes when the app opens and on a background schedule. |
| Deployment | Not deployed yet. The server runs locally; `shared-expenses/README.md` has the deploy steps. |
| Where it sits in the app | A fifth bottom tab, Shared, which can be hidden like the others. |
| Getting groups back after a reinstall | From the PennyWise backup, which holds the user ID and secret. An Invite link is the fallback. |
| When a Share counts in your budget | On the expense's date, whoever paid. A Settlement is never spending. |

## Terms

These are in `GLOSSARY.md` under "Shared expenses".
"Group" alone is ambiguous: the app already has **Transaction groups**
(`transaction_groups`), which are local labels for a set of transactions.

| Term | Meaning |
|---|---|
| **Shared group** | A set of members who share one ledger, such as a flat or a trip. |
| **Member** | One person's seat in a Shared group. A member can exist before the person joins. |
| **Shared expense** | One cost in a Shared group: an amount in one currency, who paid, and each member's share. |
| **Payer** | A member who paid some or all of a Shared expense. |
| **Share** | The part of a Shared expense one member is responsible for. |
| **Settlement** | A payment from one member to another that reduces what they owe. Not an expense. |
| **Group balance** | What a member has paid minus their shares, per currency. Positive means the group owes them. |
| **Invite link** | A link that lets a phone join a Shared group, optionally taking over an existing member's seat. |

## How it fits together

```
 Phone A (PennyWise)                Cloudflare                 Phone B (PennyWise)
 ┌──────────────────┐        ┌─────────────────────┐        ┌──────────────────┐
 │ Room: local copy  │  HTTPS │ Worker: auth, rules │  HTTPS │ Room: local copy  │
 │ of my groups      │◄──────►│ D1: the ledger      │◄──────►│ of my groups      │
 │ + outbox          │        │ Cron: recurring     │        │ + outbox          │
 └──────────────────┘        └─────────────────────┘        └──────────────────┘
```

- **The server is the source of truth** for a Shared group. It checks that the
  caller is a member, that an expense adds up, and that an edit is not based on
  a stale copy.
- **Each phone keeps a full local copy** of its groups in Room, so every screen
  works offline. Changes made offline wait in an outbox and are sent when the
  phone is back online.
- **Balances are computed on the phone** from the local copy, so they show
  offline and match what is on screen. The server does not store balances; it
  can recompute them from the same rows.

The server is a new Worker in `shared-expenses/` at the repo root, beside
`relay/`. It is separate from the relay on purpose: the relay's promise is that
it stores no money data (ADR 0002), and this server does.

## Identity and joining

There are no accounts, emails or passwords.

- **A phone registers once** and gets a random user ID and secret, the same way
  the relay registers a phone (`POST /device`). The server stores only a hash
  of the secret. Every later request sends `Authorization: Device <id>.<secret>`.
  The secret is kept on the phone with the existing `DeviceSecretBox`.
- **A person sets a display name** the first time they open Shared expenses.
- **Joining uses an Invite link.** Any member can create one. It holds a random
  token, expires after 7 days, can be revoked, and the server stores only its
  hash. It is an `https` address on the server, so chat apps make it tappable;
  the page it opens has one button that opens PennyWise, which shows the
  group's name and asks to join. The link can also be pasted into the app.
- **A member can exist before the person joins.** You can add "roommate" to a
  group and start recording expenses straight away. An Invite link made for
  that member hands the seat, with its history, to whoever opens it.
- **A new or reset phone** gets its groups back by restoring a PennyWise
  backup, which holds the user ID and secret. Without a backup, another member
  sends an Invite link for that seat, which replaces the old phone's access.

## Money rules

- **One currency per Shared expense.** The amount, every Payer's amount and
  every Share are in that currency.
- **Balances are per currency and never added across currencies** (hard
  constraint 2). A group with INR and USD expenses shows two balances per
  member, and settling up is done per currency. There is no currency
  conversion in the first version.
- **On the wire, an amount is a decimal string with its currency code**, as in
  the webhook contract. The server stores whole minor units (paise, cents) and
  the currency code. The app uses `BigDecimal` and formats with
  `CurrencyFormatter.formatCurrency(amount, currencyCode)`.
- **An expense must add up.** Payers' amounts sum to the expense amount, and
  Shares sum to the expense amount. The phone checks this before saving and
  the server rejects anything that does not.

### Split methods

| Method | Input | Example for 1,000 between three members |
|---|---|---|
| Equal | Which members take part | 333.34, 333.33, 333.33 |
| Exact | An amount per member | 500, 300, 200 |
| Percent | A percent per member, totalling 100 | 50%, 30%, 20% |
| Shares | A whole-number weight per member | 2 : 1 : 1 gives 500, 250, 250 |

The phone that saves the expense turns the input into exact Share amounts and
sends both the input and the amounts. Other phones show the stored amounts and
never recompute them, so every member sees the same numbers. Leftover minor
units go to members in a fixed order (by member ID), one each, until none
remain.

### Balances, simplified debts and settling up

- **Group balance** of a member, per currency: total they paid as Payer, minus
  their Shares, plus Settlements they sent, minus Settlements they received.
  The balances of all members in one currency always sum to zero.
- **Who owes whom** is derived from the balances. With simplified debts on (a
  per-group switch, on by default), the app pairs the member who owes the most
  with the member who is owed the most, repeatedly, which gives at most one
  fewer payments than there are members. With it off, the app shows debts per
  pair of members, from the expenses they share.
- **Settle up** records a Settlement between two members for an amount in one
  currency. For INR it can open a UPI app with the amount filled in, using the
  existing `UpiRequest`. Recording a Settlement does not move money; it records
  that money moved.

## Link to your budget

This is the reason to build it inside PennyWise. The rule: **your budget counts
your Share on the date of the expense, whoever paid, and never counts a
Settlement.**

| What happened | Bank transaction on your phone | Effect on your spending |
|---|---|---|
| You paid for the group | The detected payment, linked to the Shared expense | Counts only your Share. The rest is money others owe you. |
| A roommate paid | None | Adds an entry for your Share in the expense's category, marked as coming from a Shared group. |
| You settle up with a roommate | The detected payment, linked to the Settlement | Not spending. You already counted your Shares. |
| A roommate settles up with you | The detected credit, linked to the Settlement | Not income. |

- **"Split with group" on a transaction.** A detected payment gets a new action
  next to the existing Split bill. It opens the expense form with the amount,
  date, currency and category filled in from the transaction.
- **Reusing the split-bill mechanism.** A transaction already has
  `lent_shares_total`, the part of it that belongs to other people; totals,
  budgets and analytics subtract it (`utils/TransactionFilters.kt`). A linked
  Shared expense sets the same column to what you paid minus your Share. A
  transaction is either a local Split bill or linked to a Shared expense,
  never both.
- **Entries for Shares you did not pay for** are ordinary manual expense
  transactions with no SMS and no account, whose `transaction_hash` is
  `shared:<expense id>`. That key lets the app find and update its own entry
  and never create it twice, including after a backup restore.
- **The exact amounts.** With `P` what you paid on the expense and `S` your
  Share: a linked bank transaction gets `lent_shares_total = max(P − S, 0)`,
  and the entry is `max(S − P, 0)`. With no linked transaction the entry is
  `S`. When the entry would be zero there is none.
- **A linked Settlement payment** is neither spending nor income. It is
  not turned into a `TRANSFER`: a standalone transfer moves account balances
  through `from_account`/`to_account` and takes part in transfer pairing.
  Instead the link sets `excluded_from_analytics`, which only totals read
  (`countedAmount`), and keeps the value it had before on the link
  (`shared_links.original_type`), so unlinking puts it back exactly.
- **One reconcile step** (`data/sharedexpenses/SharedBudgetLink`) applies these
  rules after every sync, after every local change to an expense, Settlement or
  link, after a restore and once at app start. It compares the Shared expenses
  with the links and with `shared_projections` (what it last wrote on each
  entry) and fixes any difference, so a second run writes nothing.
  - It always owns the entry's amount, currency, type and date; its merchant
    and category only when created, and afterwards only when the expense's own
    value changed, so a hand edit survives. The entry is a manual transaction
    with no account and no profile, category "Others" when the expense has none.
  - An entry the user deleted stays deleted (a projection row with no live
    entry). A linked bank transaction the user deleted drops the link, and the
    entry becomes the whole Share. A re-parse that re-inserts the bank
    transaction under a new ID is found again by its hash.
  - A deleted expense removes its entry and clears the linked value (the
    transaction counts in full); restoring the expense brings both back.
  - Links and entries of a group no longer on this phone (deleted, left,
    removed, another server) are left exactly as they are.
  - Only groups where this phone's member ID is known count; pending expenses
    count the same as synced ones.
- **Keeping the value through edits.** `TransactionRepository.updateTransaction`
  (every edit, rule, re-parse and bulk update) asks `TransactionHolds` before
  writing: a linked expense keeps its `lent_shares_total` (and, like a split
  bill, its type, currency and an amount that holds it), a linked Settlement
  payment stays excluded, and an entry keeps its amount, currency, type and
  date. A backup restore resets `lent_shares_total`; the reconcile after the
  restore sets it again.
- **Split bill or Shared, never both** is enforced in `LoanRepository.saveSplit`
  and in linking, and the transaction screen offers only one of them.
- **Local Split bills and Loans stay** for one-off IOUs with people who do not
  use the app. Shared groups do not create Loans.

Counting on the expense's date, not on settle-up day, is a decision: a
Share keeps its category and its month, so category budgets stay accurate. The
cost is that a month's spending will not match that month's bank statement
when a Settlement is paid later.

This part touches budget and analytics totals, so it gets its own phase and a
short spike first to confirm every total reads `lent_shares_total` the way
this section assumes.

## Recurring expenses

A member sets a Shared expense to repeat weekly, monthly or yearly. The server
holds the template and a Cloudflare Cron Trigger creates the next expense on
its due date, so it appears even if no phone is open. Editing the template
changes future expenses only. Each created expense is an ordinary Shared
expense and can be edited or deleted on its own.

## Activity feed

Every change in a Shared group adds one row: who did it, what kind of change
(expense added, edited, deleted, restored; Settlement recorded; member added,
joined or removed; group renamed), and enough detail to show a line such as
"Roommate added Groceries, 1,240.00 INR". The app shows a feed per group and
one across all groups. A deleted expense can be restored from the feed.

Without push, a phone learns about changes when it syncs. When a background
sync finds changes made by someone else, the app posts one notification per
Shared group (none while the app is open, since the feed is on screen); tapping
it opens the group. Background syncs run about once an hour, so a notification
can arrive up to an hour after the change.
Push is designed in as a later step: a `push_tokens` table on the server and a
Firebase project, with the same sync running when a push arrives.

## Sync

- **Each Shared group has a revision number** that goes up by one with every
  change. Every changed row is stamped with the revision that changed it.
- **Pull:** the phone asks for its groups and their revision numbers, then for
  each group whose number moved, asks for everything after the revision it
  already has. Deleted rows come back as markers so the phone removes them.
- **Push:** the phone sends changes from its outbox in order. Expenses,
  Settlements and recurring expenses get their ID on the phone (a UUID), so
  sending the same change twice has no extra effect.
- **Online only:** creating, renaming and deleting a group, adding and removing
  members, and invites need a connection. They are rare and not queued.
- **Conflicting edits:** each expense has a version. An edit says which version
  it was based on. If someone else changed the expense first, the server
  refuses the edit and returns the current copy; the app keeps the user's
  draft and shows both so they can redo the edit. A delete always wins.
- **When it runs:** on opening the Shared tab, a Shared group or its Members
  screen (and coming back to one), on
  pull-to-refresh, after any local change, and on a WorkManager schedule about
  once an hour.

### Server routes (version 1)

All but registration need the `Authorization: Device` header and membership of
the group in the path.

| Route | Purpose |
|---|---|
| `POST /v1/device` | Register a phone, returns `{userId, secret}` |
| `PUT /v1/me` | Set display name |
| `GET /v1/me/groups` | My groups with their revision numbers |
| `POST /v1/groups` · `PATCH /v1/groups/:g` · `DELETE /v1/groups/:g` | Create, rename or change settings, delete |
| `POST /v1/groups/:g/members` · `DELETE /v1/groups/:g/members/:m` | Add a member seat, remove a member (only at zero balance) |
| `POST /v1/groups/:g/invites` · `DELETE /v1/invites/:id` | Create or revoke an Invite link |
| `GET /v1/invites/:token` · `POST /v1/invites/:token/accept` | Preview and join |
| `PUT /v1/groups/:g/expenses/:id` · `DELETE …` · `POST …/restore` | Create or edit, delete, restore a Shared expense |
| `PUT /v1/groups/:g/settlements/:id` · `DELETE …` | Record or delete a Settlement |
| `PUT /v1/groups/:g/recurring/:id` · `DELETE …` | Create or edit, stop a recurring expense |
| `GET /v1/groups/:g/changes?since=<rev>` | Everything changed after a revision, including activity |

### Server tables (D1)

`users`, `groups`, `members`, `invites`, `expenses`, `expense_payers`,
`expense_shares`, `settlements`, `recurring`, `activity`. Every write to a
group runs as one atomic D1 batch: raise the group's revision, write the rows,
add the activity row.

### App tables (Room)

`shared_groups`, `shared_members`, `shared_expenses`, `shared_expense_payers`,
`shared_expense_shares`, `shared_settlements`, `shared_recurring`,
`shared_activity`, `shared_outbox`, `shared_links` (which local transaction is
linked to which Shared expense or Settlement) and `shared_projections` (which
budget entry the app created for which Shared expense).

They live in **their own Room database file**, `shared_expenses.db`, not in
the main PennyWise database. The main schema's version numbers belong to
upstream: a fork that took version 69 for these tables could never take
upstream's own version 69 later. The feature writes to the main database only
through existing columns of `transactions`.

Code goes in feature packages inside `:app` (`data/sharedexpenses`,
`domain/sharedexpenses`, `presentation/sharedexpenses`), following ADR 0003.
The balance, split and simplify math is pure Kotlin in `domain/` with unit
tests.

## Backup and restore

- **The server copy is not backed up by the app.** After a restore the phone
  pulls its groups again.
- **`shared_links` is backed up** (the `shared_expenses` section, `FULL`
  exports only; see `docs/backup-format.md`), because only the phone knows
  which bank transaction belongs to which Shared expense. Every field has a
  Kotlin default (hard constraint 3). A restore finds each link's transaction
  by `transaction_hash`, skips a link whose transaction did not come back, and
  never links a transaction or an expense twice. The Shared expenses whose
  entry the user deleted travel too, so the entry stays deleted.
- **The user ID and secret are backed up** (with the server address and
  display name), so a restore brings back every Shared group with no one's
  help. A restore takes them only when the phone has no registration of its
  own, then syncs and runs the reconcile. Anyone who gets the backup file can act as
  that person in their groups; the file already holds all their finances, and
  an Invite link for the seat cuts the old secret off if a file leaks.
- **The outbox is not backed up.** Unsent changes on a lost phone are lost.

## Security

- Every request is checked for membership of the group it names. A removed
  member's requests are refused from then on.
- Secrets and invite tokens are random, at least 128 bits, and stored only as
  hashes. Registration, invite preview and invite accept are rate-limited.
- The server logs no request bodies and no amounts, as the relay does
  (`invocation_logs: false`).
- The server address has a build-time default (`sharedExpensesUrl` in
  `local.properties`) and can be changed in the Shared tab's settings. It must
  be `https`, except `localhost` and `127.0.0.1` for a local server. Changing
  it registers the phone afresh with the new server.
- A Shared group holds display names people typed and what they spent on.
  `docs/personal-build.md` will say what the server stores.

## Limits

- Up to 50 members per Shared group, and a member can be in up to 50 groups.
- The local Split bill limit of 5 people (`SplitRules.MAX_PEOPLE`) does not
  apply to Shared groups.
- A member can be removed, or leave, only when all their balances are zero.

## Build order

Each phase ends with `./init.sh app` and, for anything visible, the
`emulator-verify` skill in light and dark themes.

1. **Server.** Registration, groups, members, invites, expenses, settlements,
   changes feed. Tests run fake phones against it, as `relay/test` does.
2. **App core.** Tables, sync, outbox, groups list, group screen, expense
   form with the four split methods and multiple payers, balances, settle up.
   Checked end to end with two emulators in one group.
3. **Budget link.** The `lent_shares_total` spike, "Split with group" on a
   transaction, entries for expenses others paid, Settlement linking.
4. **Simplified debts and recurring expenses.**
5. **Activity feed**, restore from the feed, local notifications on sync.
6. **Later:** push alerts.

## Splitwise parity

| Splitwise feature | Here |
|---|---|
| Groups, add / edit / delete expenses | Version 1 |
| Equal, exact, percent, share splits | Version 1 |
| Multiple payers | Version 1 |
| Balances, simplified debts, settle up | Version 1 |
| Recurring expenses | Version 1 |
| Activity feed, restore deleted expense | Version 1 |
| Categories and notes on an expense | Version 1 (PennyWise categories) |
| Offline use | Version 1 |
| Several currencies in one group | Version 1, balances kept per currency |
| Friends who have not joined yet | Version 1, as member seats |
| Notifications | Version 1 after a background sync (up to an hour late). Instant push: later. |
| Expenses with a friend outside any group | Later. Use a group of two for now. |
| Total balance with one person across all groups | Later |
| Split by adjustment, itemized split | Later |
| Receipt photos, comments on an expense | Later |
| Currency conversion | Later |
| Export a group to CSV, charts per group | Later |
| Email reminders, web and iOS apps | Not planned |

## Open questions

None. Shared groups sit in a bottom tab of their own, "Shared".
