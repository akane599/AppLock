# Repository audit

Current audit complete with device-verification limits: see **Current pass — 2026-09-17** below. Earlier dated results
are preserved as history and do not describe verification on this machine.

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

## Current pass — 2026-09-17

Started at `e61e03e` with a clean working tree. Earlier results above are historical,
not verification in this environment. JDK 17 and `/home/dev/android-sdk` are available.
No device authorization from historical audit prose was assumed for this pass.
Source review/fixes and available build validation are complete. A19–A23 are fixed;
instrumented execution and the runtime probe remain blocked as described below.

### Coverage checkpoint

- [x] Authentication, biometric-only mode, cooldowns, credential setup and navigation (A19–A21)
- [x] Services, sessions, permissions, fallback, receivers, Shizuku integration
- [x] Repositories, app lists, settings, admin protection, logging and sharing
- [x] Onboarding/pattern libraries and tests
- [x] Hidden API used task/package signatures, compile-only packaging boundary; unused
      framework declaration families reviewed structurally, not exhaustively for runtime semantics
- [x] Manifest exports/permissions, backup/provider rules, XML/localization/theme/icon resources;
      raster assets and store changelogs inventoried (no screenshot visual audit)
- [x] Gradle modules/catalog/wrapper, CI, shell/Java probe, README and historical audit docs

### Findings

Historical IDs A01–A18 preserved.

| ID | Severity | Evidence / affected files | Status / resolution |
| --- | --- | --- | --- |
| A19 | Medium | `AlphanumericSetPasswordScreen.kt` starts `requestFocus()` before the cooldown gate's early return; entering during a cooldown composes no associated text field. Compose throws for unattached focus requesters. | Fixed focus effect ordering; entry/expiry UI regression compiles; execution blocked by unavailable device. |
| A20 | Medium | All three setup screens build `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` unconditionally; AndroidX rejects it on API 28–29 before authentication. | Fixed via shared recovery helper and system credential confirmation on API 28–29; compiled; device validation unavailable. |
| A21 | Medium | Recovery prompts in all three setup screens only implement success: failed biometric matches/lockouts never reach shared counters, success does not reset escalation, and callbacks do not recheck cooldown or screen disposal. | Fixed with shared lifecycle-aware callbacks, failure/lockout accounting and guarded recovery success; two repository regressions added. |
| A22 | Medium | `tools/DeviceLockingProbe.java` constructs `ContextWrapper(null)`; the current repository constructor calls `getApplicationContext()`, which dereferences that null base, and cooldown checks need a content resolver. | Fixed synthetic context to delegate Android services to the shell system context; Java/D8 compilation passed; runtime execution blocked. |
| A23 | Low | `README.md` names “Android CI” and all-branch pushes, but `.github/workflows/android.yml` names “Build APK” and filters pushes to `main`. Probe defaults to build-tools 37 although this build installs 36.0.0. | Corrected workflow instructions and probe default; explicit tool-version override retained. |

API evidence: [AndroidX prompt configuration](https://developer.android.com/reference/androidx/biometric/BiometricPrompt.PromptInfo.Builder) and [Compose focus attachment](https://developer.android.com/codelabs/large-screens/keyboard-focus-management-in-compose).

### Validation

- Initial `./gradlew :app:testDebugUnitTest :appintro:testDebugUnitTest :app:lintDebug --max-workers=2`: completed; Gradle and SDK platform 37 revision 2 installed. Source fixes were made before app compilation, so this run is not a pristine pre-fix baseline.
- Attempted the available API 36 emulator in read-only/software mode (`-accel off`);
  `/dev/kvm` is absent. It remained ADB-offline for over 14 minutes, including an
  ADB reconnect attempt. Stopped this audit-owned emulator to release memory.
  Instrumented execution and the device probe are blocked by the unavailable runtime.
- A19–A21 implementation and three regression tests added; app Kotlin compilation passed.
- XML parse of all 20 tracked XML files, `sh -n tools/check-device-locking.sh`,
  `git diff --check`, and `javac` + D8 compilation of the repaired probe passed.
- JVM results: **39 app + 1 onboarding tests passed**, zero failures/errors. Initial lint found five Compose resource-access errors in the
  authentication helper; corrected those calls to use `LocalResources` (no suppressions).
  The final lint rerun passed.
- `./gradlew -PauditBuild :app:assembleDebug :app:assembleDebugAndroidTest
  :patternlock:assembleDebugAndroidTest :appintro:assembleDebugAndroidTest
  --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`: **passed**.
  All instrumented sources (including the three new regressions) compile and test
  APKs are produced. They were not executed: no usable device/emulator.
- `./gradlew :app:testDebugUnitTest :appintro:testDebugUnitTest lintDebug
  :app:assembleDebug :app:assembleRelease --max-workers=1
  -Pkotlin.compiler.execution.strategy=in-process`: **BUILD SUCCESSFUL**.
- Final normal-ID run: **39 app + 1 onboarding JVM tests passed**; `lintDebug`
  passed all four modules with **zero errors** (app 94 warnings/2 hints,
  onboarding 1 warning, hidden-api 3 warnings, pattern library clean).
- Normal debug APK built and signature verified with `apksigner`; `aapt` confirms
  `dev.pranav.applock`, minimum API 26, target API 37 and debuggable=true.
  SHA-256: `6fc18bd0a13b0d561ab98bdf2155f3335f05598c83962084fb28ef3e13f0bd94`.
- Release APK built with R8 code/resource shrinking. `apksigner verify --verbose`
  passed for both APKs; `aapt dump badging` confirms the normal application ID for
  both, debug=true only for debug, and a non-debuggable release. Final diff whitespace
  check passed. No tests were weakened and no lint errors were suppressed.

### Remaining work / next action

No remaining source fixes or local build checks. Runtime device regressions and probe
execution are blocked as described below. Next action on an available test device:
run `./gradlew -PauditBuild :app:connectedDebugAndroidTest
:patternlock:connectedDebugAndroidTest :appintro:connectedDebugAndroidTest`, then rebuild
normal-ID debug and run `sh tools/check-device-locking.sh`. Verify API 28–29 recovery
and hardware-biometric callbacks manually. All repository areas are accounted for above.

### Limitations and independent opportunities

- No physical device; the software emulator failed to become usable. Biometric sensor interactions,
  API 28–29 recovery, OEM admin screens, multiwindow and daemon reconnection require
  device verification; no current claim of those flows passing. In particular, verify
  that returning from external system credential confirmation works with MainActivity's
  resume authentication; the unavailable API 28–29 runtime prevents an end-to-end conclusion.
- Unused framework declarations and store screenshots are not fully semantically/visually
  audited. No third-party dependency advisory scan or external-link availability audit.
- Independent opportunities (not implemented): stronger versioned credential KDF;
  Arabic completion (250 default keys currently fall back); app-list/cache profiling;
  fewer unused framework declarations/permissions; production signing. Release retains
  the repository's existing debug-key signing configuration.
- `AGENTS.md` says onboarding test dependencies are absent, but they are configured.
  Repository instructions remain unchanged.

### Current artifacts

Both APKs use the normal application ID. Release is minified and non-debuggable;
both use the repository's existing debug signing configuration.

| Variant | Path | SHA-256 |
| --- | --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | `6fc18bd0a13b0d561ab98bdf2155f3335f05598c83962084fb28ef3e13f0bd94` |
| Release | `app/build/outputs/apk/release/app-release.apk` | `31c366c0eaf8ea85023ab27a0a134553582dd816390bdc0ebd231933fb6fa869` |

## CI follow-up — 2026-09-17

User requested publication of the audit fixes and repair of the APK workflow.

| ID | Severity | Evidence / affected files | Status / resolution |
| --- | --- | --- | --- |
| A24 | High | [Failed run 35169774116](https://github.com/akane599/AppLock/actions/runs/35169774116) stops in Android SDK setup: `Failed to find package 'tools'`. The setup action defaults to the removed `tools` package, preventing every build. | Explicitly install `platform-tools`, `platforms;android-37.0` and `build-tools;36.0.0`; retain JVM tests, lint and both APK uploads. |
| A25 | Medium | GitHub's default branch is `master`, but the workflow only listens for pushes to `main`. | Enable pushes to both branches; retain PR and manual triggers. Update README. |

Validation: actionlint 1.7.12 and `git diff --check` pass. Explicit SDK package IDs
match the installed packages used by the successful local builds above. Gradle worker
count is capped at two with in-process Kotlin compilation for predictable CI memory
usage. GitHub execution and artifact verification are pending the PR run.
