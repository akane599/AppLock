# Locking audit

## Scope

Reviewed Shizuku and Usage Stats detection, accessibility events, shared unlock state,
password/pattern/biometric completion, overlay lifecycle, credential storage, and recent-app
privacy. This is not a full review of every UI screen, hidden framework stub, or OEM behavior.

## Confirmed defects and changes

- **Shizuku compatibility:** the old implementation called the four-argument `getTasks` on
  Android 12, which exposes three arguments, and used `activity_task` before Android 10.
  `TaskQueryCompat` now resolves the actual signature. Query failures are reported, and the
  monitor retries a lost connection. Task-stack notifications supplement polling.
- **Stale/duplicate unlocks:** backends disagreed about temporary grants, tracked multiple
  visible tasks as foreground, and let old overlay callbacks clear a newer lock. All use
  `LockSessionState`, monotonic time, and a token belonging to one authentication request.
  Leaving an app ends its immediate grant; screen-off clears all grants. A successful
  callback can complete only once. Timed grants and explicit trigger exclusions are retained.
- **Missed foreground events:** Usage Stats discarded foreground state after a three-second
  lookback and treated interaction events as resumed activities. It now consumes resume/pause
  events incrementally and retains the current activity. Accessibility checks the active
  window rather than accepting background content events as app switches.
- **Unsafe dismissal:** Close could simply expose the unauthenticated target; biometric
  cancellation could remove the only protective window. Service backends now share the
  lock view, Close/Back goes Home, and biometric handoff keeps the view attached until the
  opaque authentication activity claims the same token. Cancellation stays on password entry.
- **Credentials:** legacy plaintext patterns migrate to salted hashes after a successful
  match. Hash detection validates salt/digest lengths so colon-containing legacy passwords
  are not misidentified. Credential/backend changes invalidate old grants. Backup/transfer
  rules exclude preferences, preventing credentials and enabled protection from being restored
  onto another device without its permission setup.
- **Build health:** added missing JUnit/AndroidX test dependencies and fixed Compose resource
  reads reported as lint errors. Dependency versions otherwise remain unchanged.

## Recent-app previews: remaining platform limitation

AppLock's own activities and overlay windows use `FLAG_SECURE`; its activities also disable
Recents screenshots where supported. These flags do **not** change a target application's
windows or its existing task snapshots. Android applies snapshot suppression to the activity
identified by its own activity token, not by a package name or task ID:
[AOSP ActivityClientController](https://android.googlesource.com/platform/frameworks/base/+/167c793ed8b332d7d4d89fc9be0f479e3349e3fd/services/core/java/com/android/server/wm/ActivityClientController.java).

Reliable suppression of arbitrary target-app thumbnails remains unresolved with the current
ordinary-app/Shizuku architecture. It needs target-app cooperation or an OEM/system integration;
removing tasks would discard navigation state and is not a substitute for hiding a preview.
Shizuku uses shell privileges, not unrestricted access inside system_server.

## Device validation checklist

- Test PIN, password, and pattern with immediate, timed, and until-screen-off unlock modes.
- Authenticate once; stay in the target; switch to another app or launcher; return.
- Cancel biometrics, use password fallback, rotate, press Home/Back, and turn the screen off
  during authentication. The target must not receive an unlock grant on cancellation.
- Stop/restart Shizuku and revoke/regrant its permission; confirm notification status and recovery.
- Test each enabled backend, including a custom launcher, Recents, split screen, and keyboards.
- Inspect target-app thumbnails separately: they are **not guaranteed private** by this change.

Detection is reactive, so a target can appear briefly before its lock window. OEM restrictions,
apps that block third-party overlays, secondary displays, and biometric hardware require device
validation. No claim of comprehensive prevention of every Android app-lock bypass is made.

## Reproducing verification

With JDK 17+ and Android SDK 37 configured, run:

```sh
./gradlew :app:testDebugUnitTest :appintro:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :appintro:connectedDebugAndroidTest
ANDROID_HOME=/path/to/sdk tools/check-device-locking.sh
```

The device probe runs the debug APK's task-query and credential code under `adb shell`,
using synthetic preferences in a temporary directory. It also checks task-listener
registration and parses APK resources and the opaque theme. It does not install AppLock,
change its real settings, or exercise the full biometric/overlay UI. The probe currently
requires Android 10+; earlier task-query signatures have JVM regression coverage.

This Termux environment uses an existing SDK at
`/data/data/com.termux/files/usr/tmp/batstats-sdk` and a native ARM64 AAPT2 override:

```sh
ANDROID_HOME=/data/data/com.termux/files/usr/tmp/batstats-sdk sh gradlew \
  :app:testDebugUnitTest :appintro:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease :appintro:connectedDebugAndroidTest \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/tmp/batstats-sdk/native/data/data/com.termux/files/usr/bin/aapt2 \
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 --console=plain
```

Release builds retain this repository's existing debug-key signing configuration;
`app-release.apk` is a minified, non-debuggable build, not a production-key-signed release.

## Results (2026-09-15)

- Final combined Gradle run: **BUILD SUCCESSFUL**. Debug and release APKs produced.
- App JVM regressions: **25 passed** (15 session, 6 task-query, 4 credential tests).
- Existing onboarding JVM test: **1 passed**; onboarding Android instrumentation test:
  **1 passed** on the connected Android 16/API 36 device.
- Android Lint: **0 errors**, 93 warnings and 2 hints. Remaining reports primarily concern
  dependency freshness, unused resources, localization, and UI/API guidance; no lint baseline
  or error suppression was added to pass the checks.
- Device probe: production task query, shell task-listener registration, legacy credential
  migration using Android SharedPreferences, and APK resources/opaque theme checks all passed.
  This checks the shell-side task API, not a full Shizuku permission/reconnection UI session.
- Both APKs passed `apksigner verify`; the release manifest is non-debuggable.
- `git diff --check` and shell syntax validation passed. The existing `AGENTS.md` was preserved.
- Full PIN/pattern/biometric interaction, overlay transitions, OEM launchers, multiwindow behavior,
  and Shizuku reconnection still need end-to-end device testing. The installed AppLock app and
  its settings were not replaced or modified.

Artifacts:

| Build | Path | SHA-256 |
| --- | --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | `d177cce99fb2ca9bb084e7238e2d7014680f78cfeb8f28bdddd99c5240f132b8` |
| Release | `app/build/outputs/apk/release/app-release.apk` | `ed1b0bfbbddff74258854fa37df2114fd3f1afb5ccc293451e1dd4baaa37c3cc` |
