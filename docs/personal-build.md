# Personal build

`personal/free-features` keeps the upstream and webhook changes while making
all feature access unconditional. Rules, recurring templates, statement imports,
CSV exports, advanced analytics, account merging, and scheduled folder backups
have no purchase or license requirement. The standard build uses a local billing
gateway with no catalog, purchase flow, or Play billing connection. Settings has
no Pro purchase or license entry.

Build and verify with JDK 21:

```sh
./init.sh app
./gradlew :app:assembleStandardDebug
```

Install `app/build/outputs/apk/standard/debug/app-standard-universal-debug.apk`
using `adb -s <device> install -r <apk>`. The debug application ID is
`com.pennywiseai.tracker.debug`; it updates the existing development install and
preserves its app data. The regular installed app is a separate package.

Keep these changes on this branch when syncing future upstream updates.

## Shared expenses

This branch adds Shared groups: a Splitwise-style ledger a few people running
this build share (the Shared tab). The design is in
[`shared-expenses.md`](shared-expenses.md) and the server lives in
[`../shared-expenses/`](../shared-expenses/README.md).

- **It needs a server you run.** Nothing is deployed by default and the app
  ships with no server address, so the Shared tab does nothing until you set
  one. Deploy steps are in `shared-expenses/README.md` ("Deploy to your
  Cloudflare account").
- **Unlike the rest of PennyWise, this data leaves the phone.** For the Shared
  groups you are in, the server stores group names, member display names,
  expenses (description, amount, currency, date, category, notes, who paid and
  each share), settlements, recurring expenses and the activity feed, as
  readable rows. Your bank transactions, balances and budgets are never sent;
  linking a bank transaction to a Shared expense stays on the phone.
  `PRIVACY.md` describes the upstream app and does not cover this.
- **Build an APK that already knows your server.** Add
  `sharedExpensesUrl=https://<your worker address>` to `local.properties`, then
  build as above. Without it, each person types the address once under Shared
  tab → settings.
- **Everyone installs the same APK.** Send the APK to the people you share
  with. Each person opens the Shared tab, enters a display name, and joins from
  an Invite link.
- **Backups.** A full PennyWise backup carries this phone's Shared identity, so
  restoring it on a new phone brings the Shared groups back after a sync. Treat
  a full backup file like a password for that reason.
- **Trying it with one device.** `shared-expenses/scripts/fake-phone.mjs` acts
  as a second member against a local server (`npm run dev` in
  `shared-expenses/`, then `adb reverse tcp:8790 tcp:8790` and the address
  `http://localhost:8790`).
