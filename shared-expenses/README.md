# PennyWise shared expenses server

A Cloudflare Worker with a D1 database that keeps Shared groups: the ledger a few people running
PennyWise share, the way Splitwise does. The design is in
[`docs/shared-expenses.md`](../docs/shared-expenses.md); the wire contract it implements is
[`docs/shared-expenses-api.md`](../docs/shared-expenses-api.md).

## What it stores

Unlike the AI relay, this server stores money data, as readable rows in D1:

- **Phones:** a random 32-hex user ID per phone, a SHA-256 hash of its secret, and the display name
  the person typed.
- **Shared groups:** name, default currency, the simplify-debts switch, and a revision counter.
- **Members:** each seat's display name and which phone holds it.
- **Expenses, settlements and recurring expenses:** descriptions, amounts (whole minor units plus an
  ISO 4217 currency code), dates, categories, notes, who paid and each member's share.
- **Activity:** one row per change, naming who made it and a short summary.
- **Invite links:** a SHA-256 hash of each token, its seat, expiry and use count.

It logs no request bodies, amounts or names (`invocation_logs` is off; errors log only the route and
the error message).

## Run it locally

Needs Node 22.12 or newer.

```sh
cd shared-expenses
npm ci
npm run dev      # applies the migrations to a local D1 database, then serves http://localhost:8790
```

The local database lives in `.wrangler/state/`. To apply migrations on their own, run
`npx wrangler d1 migrations apply DB --local`. Local runs need no Cloudflare account.

To run the daily recurring-expense job by hand while `npm run dev` is up:
`curl "http://localhost:8790/cdn-cgi/local/scheduled?cron=5+0+*+*+*"`.

## Tests

```sh
npm test         # vitest in the Workers runtime: fake phones drive the Worker over HTTP
npm run typecheck
```

## Deploy to your Cloudflare account

You need a Cloudflare account (the free plan is enough) and the Cloudflare CLI, `cf`
(`npm i -g cf@latest`). Deploys read `cloudflare.config.ts`; your deployment's own values go in
`.deploy.env`, which git ignores.

1. `cd shared-expenses && npm ci`
2. `cf auth login`
3. Create the database: `cf d1 create --name pennywise-shared-expenses`.
4. `cp .deploy.env.example .deploy.env` and set `SHARED_EXPENSES_D1_ID` to the printed `uuid`.
   - **Custom domain:** set `SHARED_EXPENSES_DOMAIN` to a hostname on a zone in your account, such
     as `shared.example.com`. The deploy attaches it, turns off the `workers.dev` address, and
     builds invite links from `https://<domain>`.
   - **No custom domain:** leave it empty, deploy once, then set `BASE_URL` to the
     `https://pennywise-shared-expenses.<your-subdomain>.workers.dev` address `cf deploy` printed
     (no trailing slash) and deploy again. Invite links are built from it.
5. The rate-limit namespaces in `cloudflare.config.ts` must be unique within your account; change
   them if they clash with another Worker's (the AI relay uses 840101 to 840103).
6. `npm run deploy`. It creates any missing tables (`npm run migrate`, which runs
   `cf d1 migrations apply`), then runs `cf deploy`.

Run `npm run deploy` again after each update. It applies new files in `migrations/` before it
deploys. With no new migrations, `cf deploy` alone is enough.

Tests and `npm run dev` still use Wrangler and `wrangler.jsonc`, so a binding change goes in both
`wrangler.jsonc` and `cloudflare.config.ts`.

## Point the app at it

- **Build-time default:** add `sharedExpensesUrl=<the Worker's https:// address>` (the custom
  domain, or the `workers.dev` address) to the Android project's `local.properties`, then rebuild.
- **On the phone:** Shared tab → settings → server address.

The address must be `https://`, except `http://localhost` and `http://127.0.0.1` for a local server. An
emulator reaches the dev server on your computer at `http://10.0.2.2:8790`, which the app does not
accept as-is; use `adb reverse tcp:8790 tcp:8790` and `http://localhost:8790` instead. Changing the
address registers the phone afresh with the new server.

## Layout

| File | What it does |
|---|---|
| `src/index.ts` | Entry: routes, auth, rate limits, error handling, the Cron Trigger |
| `src/write.ts` | The one way to write to a group: one batch, rev + 1, one activity row, retry on a race |
| `src/context.ts` | Request context, the membership check, limits |
| `src/auth.ts` | Phone registration and `Authorization: Device <id>.<secret>` |
| `src/money.ts` | Currency fraction digits and decimal string ↔ minor units |
| `src/validate.ts`, `src/split.ts` | Field readers; payers/shares validation |
| `src/groups.ts` | `/v1/me`, groups, member seats, member removal |
| `src/invites.ts` | Invite links and the `/join/:token` page |
| `src/expenses.ts`, `src/settlements.ts`, `src/recurring.ts` | The ledger |
| `src/changes.ts` | The sync feed, paged by whole revisions |
| `src/cron.ts` | Daily job that creates due recurring expenses |
| `migrations/` | D1 schema |
| `cloudflare.config.ts`, `.deploy.env.example` | Deploy configuration for `cf deploy` |
| `wrangler.jsonc` | Local configuration for `npm run dev` and the tests |
| `scripts/migrate.mjs` | Applies `migrations/` to the deployed database |
