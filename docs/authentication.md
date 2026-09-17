# Unlocking and failed attempts

Choose **Use only biometrics for unlocking** during initial PIN setup or in
Settings. Enabling it requires successful biometric authentication. This mode
hides and rejects AppLock PIN/password/pattern fallback, including admin removal.
Keep biometrics enrolled; unavailable or locked-out biometrics cannot unlock
AppLock. Android may require unlocking the device before biometrics work again.

To turn this mode off, authenticate with biometrics and create a PIN, pattern,
or password. Cancelling setup keeps biometric-only mode enabled.

All protected apps, AppLock itself, and admin removal share a failure counter.
Five incorrect PINs, passwords, patterns, or biometric matches block all unlock
methods for 5 minutes. Further groups of five failures produce 10-, 20-, 40-,
then 60-minute cooldowns. Successful authentication resets the escalation.
Android biometric lockouts also trigger an AppLock cooldown, including biometric
attempts in credential recovery. Successful system credential recovery resets the
same counter and is rejected during cooldowns or in biometric-only mode. Cancelling a prompt
or encountering unavailable hardware does not count as an incorrect match.

The unlock screen displays the remaining time. Counters survive process restarts;
a device reboot restarts an active cooldown. Device clock changes do not shorten
a cooldown. PIN auto-unlock submits at the saved PIN length; existing installations
learn this length after their first successful manual PIN submission.

## Verification

- JVM policy tests cover thresholds, escalation, blocked attempts, success,
  recreation, and reboot behavior (`UnlockAttemptPolicyTest`).
- Device repository tests cover mixed methods, shared persistence, biometric-only
  credentials, and success resets (`UnlockAuthenticationTest`).
- On a device, check fresh biometric-only setup, cancellation/retry, rotation,
  switching back to credentials, admin removal, and unavailable biometrics.
- Check five failures across multiple apps and methods, countdown expiry, process
  restart, reboot, and PIN auto-unlock in both orientations.

Credential recovery uses strong biometrics or the system device credential. On
Android 9–10 it opens the system credential confirmation screen because AndroidX
does not support the combined strong-biometric/device-credential prompt there.
