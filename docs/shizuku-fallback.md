# Shizuku fallback

Keep **Shizuku** selected in Settings. AppLock chooses one runtime locking backend:

1. Shizuku, after a successful foreground-task query, with overlay permission.
2. Accessibility, if Android has actually connected AppLock's accessibility service.
3. Usage Stats, with both usage access and display-over-other-apps permission.
4. No backend, with an unavailable-protection notification if none can run.

Enable AppLock's accessibility service in Android settings to prepare the first fallback.
For Usage Stats fallback, grant usage access and display-over-other-apps permission.
Permissions are never enabled automatically. The selected backend preference stays Shizuku;
manually choosing Accessibility or Usage Stats keeps that choice.

The Shizuku foreground service supervises recovery and runs Usage Stats fallback internally.
Accessibility participates only while selected as the runtime backend. A successful Shizuku
query restores Shizuku automatically. Changes between available backends wait for an open
PIN, pattern, or biometric challenge to finish, preserving its token and successful unlock.
Losing all backends invalidates earlier unlock grants. The notification identifies fallback,
recovery waiting for authentication, paused protection, or unavailable protection.

## Device verification

Use a debug build on a test device; JVM tests cover routing and shared authentication state,
but do not simulate Android service lifecycles or permission revocation.

- With Accessibility connected, stop Shizuku and open a locked app. Expect one prompt and
  an Accessibility fallback notification. Restart Shizuku and confirm automatic recovery.
- Repeat with Accessibility disabled and usage/overlay permissions granted. Expect Usage
  Stats fallback. Also stop Shizuku while a locked target is already foreground.
- Restart Shizuku while entering a PIN or completing biometrics. Finish authentication;
  the target should stay unlocked without a second challenge.
- Revoke fallback permissions with Shizuku stopped. Expect unavailable protection;
  restoring permissions should resume checking and revoke grants from before the gap.
- Check screen off/on, Home, Recents, service restart, protection off/on, and manual backend
  changes for stale prompts or competing windows.

Fallback detection is asynchronous and inherits Android's background restrictions. Usage
Stats reads recent activity events, so it cannot guarantee zero exposure before a prompt.
This change does not hide other apps' thumbnails in Recents.

## Validation (2026-09-15)

- App JVM tests: 31 passed, including six new fallback/authentication regression tests.
- Onboarding JVM test: passed.
- Debug/release APK assembly and Android Lint: passed. Release remains signed with the
  repository-configured debug key and is non-debuggable.
- Existing compatibility probe on Android API 36: passed task queries, task-listener
  registration, synthetic credential migration, and debug APK resource/theme checks.
- End-to-end fallback service/permission checks above remain manual; the compatibility
  probe does not exercise them or replace the installed app.
