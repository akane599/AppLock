# Repository audit

## Status and baseline

2026-09-16; started at `8f0a194` with a clean working tree. Existing `AGENTS.md`
preserved. Both review passes, fixes, regression tests, final lint, APK builds and
device probe are complete. A01–A18 are fixed in the working tree.
Verification limits and follow-up scenarios are listed below. Historical reports in `docs/` were treated as context, not current results.

Baseline first failed because the SDK path was unset. With JDK 17 and
`ANDROID_HOME=/home/akane/Android/Sdk`, baseline app/onboarding JVM tests and app lint
passed. Gradle installed platform 37.0 and build-tools 36.0.0. No tracked SDK path,
credentials, signing keys, or device settings were added.

## Coverage checklist

- [x] Authentication, credential storage/migration, navigation, setup, admin UI
- [x] Lock sessions, overlays, Usage Stats, accessibility, Shizuku, receivers/routing
- [x] Preferences, app selection/search, settings, permissions, logs and sharing
- [x] Onboarding and pattern libraries, existing and new tests
- [x] Hidden API integration: used task/package interfaces and compile-only boundary;
      remaining framework declarations inspected structurally, not validated on every API
- [x] Manifests, backup/provider rules, XML resources, localization, themes/icons,
      store descriptions/changelogs and asset inventory (no screenshot visual audit)
- [x] Gradle modules/catalog/wrapper, CI, scripts, ProGuard files, README and prior audits

Review coverage does not imply exhaustive device behavior verification. No dependency
lockfile is checked in. Dependencies were kept stable except the test alignment below.

## Findings and resolutions

All entries below are fixed in the working tree. IDs are stable.

| ID | Severity | Evidence and affected files | Resolution / verification |
| --- | --- | --- | --- |
| A01 | High | `core/navigation/AppNavigator.kt`, `MainActivity.kt`, `NavigationManager.kt`: resume pushes authentication above settings; PIN/pattern Back could pop it without authentication. Credential-bearing setup routes also skipped resume checks. | Back exits the activity for all credential types; setup routes skip checks only without a stored credential. Password screen delegates Back to its owner. Device tests cover all three Back paths and setup resume policy. |
| A02 | High | `patternlock/PatternLock.kt`: `pointerInput(Unit)` retains callback and old geometry across recomposition/resize. Original-code test produced callback phases `[0, 0]` rather than `[0, 1, 2]`. | Use `rememberUpdatedState` for callbacks; restart/reset gestures for geometry/config changes. First draw, callback replacement, intermediate dots and resize pass on device. |
| A03 | High | `core/broadcast/DeviceAdmin.kt`: ordinary admin activation unconditionally called owner-only `setUninstallBlocked`, throwing `SecurityException`. | Removed unsupported owner policy; ordinary active-admin deactivation requirement remains. Device regression invokes activation with no owner rights and passes. |
| A04 | Medium | `AppNavigator.kt`, `features/setpassword/ui/SetPasswordScreen.kt`: setup completion popped to an intro entry that may be absent after restart; landscape PIN save omitted the PIN type; portrait used different completion logic. | Clear setup graph consistently; save credentials before selecting their type in every setup screen. PIN inputs capped at the existing 64-character storage limit. Device test proves setup history is removed. Orientation-specific save paths also inspected. |
| A05 | Medium | `core/utils/AccessibilitySettingsHelper.kt`: `sh -c command1 command2` never executes command2; literal `null` becomes an enabled component; exit status ignored. | Run settings commands as separate argument arrays, check status, normalize/deduplicate components, always set global enable flag. Exact component matching supports the audit application ID. Two device tests use a fake executor, without changing accessibility settings. |
| A06 | High | `core/utils/Shizuku.kt`, `features/antiuninstall/ui/AntiUninstallScreen.kt`: manual add did not apply policy; toggles persisted before throwing Binder calls; `describeContents()` supplied user zero. | Apply policy before saving, report rejection, resolve Binder per operation, derive user ID from UID. Injected-policy device regression verifies manual application and failure leaves no saved protection. Actual owner/work-profile policies not changed on the personal phone. |
| A07 | Medium | `core/utils/LogUtils.kt`: append, deletion and purge ran concurrently; purge could overwrite a newly appended entry. Retention implementation used seven days despite documenting three. | Shared mutex serializes log operations; retention set to three days; removed redundant launcher thread in `AppLockApplication.kt`. Code-path verification plus export regression; no stress-test claim. |
| A08 | Low | `services/AppLockAccessibilityService.kt`: lowercased text compared to mixed-case literals; callback also slept 100 ms on main thread. | Use case-insensitive matching and remove blocking sleep. OEM deactivation-screen behavior remains unverified. |
| A09 | Medium | `features/appintro/ui/AppIntroScreen.kt`, `features/settings/ui/SettingsScreen.kt`: Shizuku version/permission calls could run without a live Binder. | Guard unavailable Binder and show existing feedback; final onboarding check fails closed. Compiled/linted; Binder loss during a permission call remains an OEM/device test case. |
| A10 | Low | `LogUtils.kt`, `SettingsScreen.kt`, `res/xml/file_paths.xml`: logcat export blocked UI; FileProvider covered all files/cache; sharing source audit log exposed later appended data through the same URI. | Export on IO under mutex, copy audit snapshot to `cache/shared_logs/`, restrict provider to that directory, release logcat process. Device test confirms snapshot isolation and rejection of private source paths. |
| A11 | Low | `TriggerExclusionsViewModel.kt`, `AntiUninstallScreen.kt`: finishing async app loading overwrote an already-entered search with the full list. | Reapply current query after load. Direct control-flow review and build/lint validation. |

A03 API evidence: [Android DevicePolicyManager.setUninstallBlocked](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setUninstallBlocked(android.content.ComponentName,java.lang.String,boolean)).

## Supporting changes from the first pass

- CI now runs JVM tests and lint before assembling artifacts.
- Added an optional `-PauditBuild` debug application ID suffix (`.audit`) so device
  tests can write synthetic preferences without replacing the normal installation.
- Added Compose instrumented testing dependencies to app/pattern modules. Aligned
  `concurrent-futures` at 1.2.0 because AGP rejected runtime 1.1.0 versus test 1.2.0.
- Secured the admin credential activity against screenshots. Added missing Shizuku
  special-use foreground-service subtype metadata.
- Removed unused duplicate boot service routing; corrected pattern model comment.
- README documents validation, isolated tests and existing debug-key release signing;
  removed unsupported absolute security assurances and advice to disable Play Protect.

## First-pass validation and reproducibility

Use JDK 17, set `ANDROID_HOME` to the SDK, and select the desired device using
`ANDROID_SERIAL`. Tests ran on the user-authorized Galaxy S25 Ultra, Android 16/API 36.
Only isolated test apps and synthetic data were used; normal AppLock was not replaced.

| Command / check | Result |
| --- | --- |
| `./gradlew :app:testDebugUnitTest :appintro:testDebugUnitTest` | 31 app + 1 onboarding tests passed, zero failures |
| `./gradlew lintDebug` | All four modules passed: app 0 errors/92 warnings/2 hints; appintro 0 errors/1 warning; hidden-api 0 errors/3 warnings; patternlock no issues |
| `./gradlew -PauditBuild :app:connectedDebugAndroidTest :patternlock:connectedDebugAndroidTest` | 10 app + 1 pattern tests passed, zero failures |
| `./gradlew :appintro:connectedDebugAndroidTest` | 1 onboarding device test passed |
| Negative regression control | Restored only original navigator/pattern files temporarily: four navigation tests and pattern test failed as expected. Fixed sources restored and tests passed. |
| XML parse, `sh -n tools/check-device-locking.sh`, `git diff --check` | Passed |
| `./gradlew :app:assembleDebug :app:assembleRelease` | Passed; both normal-ID APKs produced |
| `ANDROID_BUILD_TOOLS_VERSION=36.0.0 sh tools/check-device-locking.sh` | Passed task query, shell task listener, synthetic credential migration, APK resources and opaque theme on API 36 |
| `apksigner verify`, `aapt dump badging` | Both signatures valid; normal application ID; debug is debuggable, release is non-debuggable |

The negative control intentionally failed; it is not an unresolved product failure.
The first instrumented build failed dependency alignment; the catalog adjustment
resolved it. No tests weakened, lint baseline created, or new error suppressions added.
The existing strings file already suppresses missing translations.

## Limits and follow-up queue

- No outstanding source/build blocker or confirmed actionable finding in the review
  queue. Final app rerun passed all 19 tests; normal-ID debug and release artifacts
  were rebuilt and reverified after all source changes.
- Permission-loss tests now cover actual Usage Stats revocation, and isolated fallback
  tests cover an unavailable-overlay challenge. Full Shizuku daemon loss/reconnection,
  biometric hardware interaction, screen-off during authentication, OEM admin screens,
  work profiles, API 26–35/37, multiwindow and long-running battery behavior remain
  unverified. Next optional validation: use a disposable configured device and execute
  `docs/locking-audit.md` and `docs/shizuku-fallback.md` checklists.
- Hidden framework interfaces are not stable public APIs. Task-query JVM tests cover
  multiple signatures; this is not verification of every unused stub on every OS.
- Existing Android limitations remain: reactive detection can briefly expose a target;
  target-app Recents thumbnails are not hidden by AppLock's secure windows.
- Release signing uses the existing local debug key. Production signing needs the
  maintainer's key/configuration; no key was generated or committed for distribution.
- Arabic falls back to English for 243 strings; existing UI/API/dependency warnings
  remain. No dependency security advisory scan or external-link availability audit
  was performed. Salted SHA-256 credential storage remains unchanged; stronger offline
  resistance would require a versioned KDF migration and device performance testing.
- `AGENTS.md` still says onboarding test dependencies are missing; actual build files
  already supply them. It was preserved as explicitly requested earlier in the session.

## Final artifacts

Both APKs are signed with the existing debug-key configuration. Release is minified
and non-debuggable. Final build: `BUILD SUCCESSFUL`; APK signatures verified.

| Variant | Path | SHA-256 |
| --- | --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | `e34b192ccfca295abd97dbb7a00d3525e12d73fe8cd25945c25dd361103b26fa` |
| Release | `app/build/outputs/apk/release/app-release.apk` | `dd5e359790a97e1332fc85e094912da48be2e26785dcab155826eb5286b3252b` |

## Second pass — repo-entire-audit-fix

The second pass started with all first-pass changes uncommitted and preserved. A
tracked diff checkpoint was saved outside the repository. The baseline JVM suite
passed (32 tests). No independent enhancement was implemented in this pass.

### Coverage

- [x] Rechecked backend startup, permission loss, fallback ownership, authentication
      callbacks, credential change and related layouts/routes.
- [x] Rechecked log retention/sharing, service cleanup and app-loading error paths.
- [x] Revisited unchanged library, hidden-API, build, script, resource and documentation
      coverage against the first-pass inventory; limitations above still apply.
- [x] Reproduced A12–A16 with seven failing device regressions before fixes; separately
      reproduced A17 and A18 with one failing device regression each.
- [x] All nine new device regressions and existing tests pass after fixes.
- [x] Final lint, normal-ID APK builds, signatures and device probe.

### Confirmed findings

All seven findings are fixed in the working tree.

| ID | Severity | Evidence and affected files | Resolution / verification |
| --- | --- | --- | --- |
| A12 | High | `MainScreen.kt`, `AppLockServiceStarter.kt`: reopening the dashboard or toggling protection ON leaves a stopped selected service stopped. Both device controls timed out waiting for startup. | Start the selected backend on dashboard resume/enable. Accessibility refreshes its OS-bound instance. Both device regressions pass. |
| A13 | High | `UsageLockService.kt`: usage access checked only at startup, overlay capability not checked, previous grants retained after actual usage app-op revocation, notification always claims protection. | Supervise permissions continuously, clear sessions/cached foreground across availability changes, display paused/unavailable status, remain available for permission recovery. Device revocation and stale-grant regression passes. |
| A14 | Medium | `PasswordOverlayScreen.kt`: auto-submit calls only nullable `onPinAttempt`; MainActivity supplies `onAuthSuccess` instead. Correct PIN yielded zero successful callbacks. | Both layouts validate and invoke MainActivity's callback. Device test rejects the incorrect prefix and accepts the complete PIN exactly once. |
| A15 | Medium | `LogUtils.kt`: line-by-line retention drops expired timestamps but retains their stack traces; logcat timestamps never match the audit parser. Both retention controls failed. | Keep/drop whole audit records, expire exported snapshots using file age, safely replace the retained audit file under the shared mutex. Both tests pass. |
| A16 | Medium | `LogUtils.kt`: repeated exports overwrite the same URI, changing data visible through an existing recipient grant. Before-fix test observed identical URIs. | Create unique export files, include snapshots in retention/clear operations; keep allocation inside the export error handler. Test confirms unique URIs and unchanged earlier snapshot. |
| A17 | High | `ShizukuAppLockService.kt`, `AppLockAccessibilityService.kt`, `BackendFallbackPolicy.kt`: an unfinished challenge with no usable window can pin routing indefinitely. Device control retained the stale token after overlay loss. | Validate window capabilities before deferring handoff, cancel impossible challenges, dismiss/reset on accessibility unbind. Device test rejects the stale token; two JVM tests cover backend capabilities and a fresh fallback challenge. |
| A18 | Medium | `SetPasswordScreen.kt`: portrait change-PIN screen exposes method switches before old-PIN verification, navigating to replacement setup. Device control found “Use Pattern” before verification. | Apply the existing verification/confirmation guard to portrait switches. Device regression proves they are hidden before verification and available afterward. |

### Second-pass validation

All commands use JDK 17 and the SDK/device environment described above.

- `./gradlew -PauditBuild :app:testDebugUnitTest :appintro:testDebugUnitTest
  :app:connectedDebugAndroidTest :patternlock:connectedDebugAndroidTest
  :appintro:connectedDebugAndroidTest --max-workers=2`: passed, **34 JVM tests
  (33 app + 1 onboarding)** and **21 device tests (19 app + 1 pattern + 1 onboarding)**.
- Final app instrumentation rerun after export error-path/test-cleanup refinement:
  **19/19 passed** on the S25 Ultra. Tests alter only the `.audit` package's app ops
  and restore defaults; they do not disable the user's Shizuku server or accessibility.
- XML parsing (19 source files), shell syntax and diff whitespace checks passed.
- `./gradlew lintDebug :app:assembleDebug :app:assembleRelease --max-workers=2`:
  passed. Zero lint errors; app 92 warnings/2 hints, onboarding 1 warning, hidden-API
  3 warnings, pattern library clean. Both APKs use `dev.pranav.applock`; release is
  minified and non-debuggable. Both signatures verify with `apksigner`.
- `ANDROID_BUILD_TOOLS_VERSION=36.0.0 sh tools/check-device-locking.sh`: passed task
  query, shell listener registration/unregistration, synthetic credential migration,
  and normal debug APK resources/opaque theme on API 36.
- Final artifact paths and SHA-256 values above refer to these second-pass builds.

### Suspicions and enhancement opportunities

No additional app-loading defect was established: the examined loaders use
`ApplicationInfo` icon/label APIs, not a package-name lookup with an unchecked
`NameNotFoundException`. Package churn and memory pressure remain useful stress cases.

Independent opportunities, recorded without implementation in this pass: versioned
credential KDF migration, translation completion, app-list/cache performance profiling,
expanded multi-version device coverage, and production signing configuration.

### Remaining work

No implementation or required validation remains. The optional device scenarios and
production-signing requirement are listed under limits. The user subsequently authorized committing and pushing the audit. The existing
all-branch/manual GitHub workflow now explicitly assembles both APK variants and fails
on missing artifacts. Git history records the commit; no app release was published.
`AGENTS.md` remains unchanged.

## Third pass — biometric availability recovery

This pass started at `c15ae98` with a clean tree and reviewed the new biometric-only
and attempt-cooldown change against the previously completed repository-wide coverage.
The existing architecture, manifests, Gradle/CI configuration, services, libraries,
resources, scripts, metadata, documentation, and prior A01–A18 resolutions were
rechecked for drift. The initial validation command was blocked because this container
had neither an Android SDK path nor an installed SDK; a task-local SDK was then installed.

### Coverage checkpoint

- [x] Repository instructions, documentation, tracked-file inventory, history and clean baseline
- [x] New authentication navigation, biometric prompt lifecycle, credential screens,
      persisted cooldown state, settings, admin-disable flow and regression tests
- [x] Recheck of unchanged app services/data/UI, library modules, hidden API boundary,
      manifests/resources, Gradle/CI, tools, metadata and documentation against prior coverage
- [x] JVM tests, lint, debug APK and release APK final validation
- [ ] Device-only biometric enrollment/removal transition (blocked: no emulator/device)

### Confirmed finding

| ID | Severity | Evidence and affected files | Status and resolution |
| --- | --- | --- | --- |
| A19 | High | `AuthenticationGate.kt`: a persisted biometric-only setting always hid PIN/password/pattern input, while an unavailable or removed biometric enrollment made the only prompt impossible to start. Every protected entry point using the gate could become unrecoverable. | Fixed: evaluate current `BiometricManager` availability, immediately restore credential input when unavailable, and clear the stale biometric-only preference. Pure policy regressions cover unavailable, available and disabled cases. |

### Validation log

- Baseline `./gradlew :app:testDebugUnitTest :appintro:testDebugUnitTest lintDebug
  --max-workers=2`: blocked before task execution because no Android SDK was configured.
- `ANDROID_HOME=/tmp/android-sdk ./gradlew :app:testDebugUnitTest
  :appintro:testDebugUnitTest lintDebug :app:assembleDebug :app:assembleRelease
  --max-workers=2`: passed after installing task-local platform 37.0/build-tools
  36.0.0. All 41 app and 1 onboarding JVM tests passed; lint had no errors
  (existing warnings remain); both APK variants assembled successfully.
- `git diff --check`: passed.
- Debug APK SHA-256: `3fc97c68bd734b2d006ad406248aabfd0eca10192a166deed1c77e73dc1efddf`.
  Release APK SHA-256: `ea1415795b9abbfdbf6a19332e1025823e9931c8ceee5310fb66f6833cbf0779`.

### Remaining queue, limitations and opportunities

No additional confirmed actionable defect is queued. A19's platform transition could
not be exercised without biometric hardware or an emulator; its decision policy is JVM
tested and the Android call path is compile/lint checked. The earlier device/API/signing,
translation, dependency-audit, stress-testing and hidden-API limitations remain. No
independent enhancement was implemented. Opportunities remain: versioned credential KDF
migration, translation completion, app-list/cache profiling, wider API/device coverage,
and production signing. Concrete next action for a maintainer is to remove all enrolled
biometrics on a disposable device while biometric-only mode is enabled and verify that
the configured app credential appears.

## APK publication follow-up

On 2026-09-17 both APK variants were rebuilt from commit `87bbe20` and copied from the
ignored Gradle output tree into tracked repository paths so they remain downloadable:

| Variant | Tracked path | SHA-256 |
| --- | --- | --- |
| Debug | `app/debug/app-debug.apk` | `62316f1021247ec1851fec52a7c2ba695d67b27f0540d8ab9ed1620f7034185d` |
| Release | `app/release/app-release.apk` | `907a03d6475eb451e570b547d816ebba3b0eba2f9ece4c24167de2572a48518a` |

`ANDROID_HOME=/tmp/android-sdk ./gradlew :app:assembleDebug :app:assembleRelease
--max-workers=2` passed. The release APK retains the existing debug-key signing
configuration and must not be represented as production-signed. Repository-relative
download links were added to the README. The checkout initially had no configured remote
or local `main` branch. Its recorded fetch source identified
`https://github.com/akane599/AppLock`, which was added as `origin`; `git push origin
HEAD:main` was attempted but GitHub authentication is unavailable in this environment.
The prepared commit must therefore be pushed by an authenticated environment.

## Build workflow follow-up

The downloadable APK delivery mechanism was moved to the existing GitHub Actions
pipeline rather than retaining generated binaries in Git. The workflow is named
**Build APK**, runs for `main`, pull requests, and manual dispatch, and uploads separate
`app-debug` and `app-release` artifacts for 14 days. It retains the test/lint gate and
fails when either expected artifact is absent. Concurrency cancellation avoids wasting
CI time on superseded revisions of the same ref. The previously checked-in APKs were
removed; the build output and `app/release/` remain ignored.
