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
