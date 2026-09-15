# Repository Guidelines

## Project Structure & Module Organization

AppLock is an Android app built with Kotlin and Jetpack Compose.

- `app/`: application code in `src/main/java/dev/pranav/applock/`; feature screens live in `features/`, shared UI in `ui/`, and locking services in `services/`. Android resources are in `src/main/res/`.
- `appintro/`: onboarding library, including starter unit and instrumented tests.
- `patternlock/`: reusable Compose pattern-lock UI.
- `hidden-api/`: Android framework stubs used as a compile-only dependency by the app.
- `gradle/libs.versions.toml`: shared dependency versions.
- `fastlane/metadata/android/`: store descriptions, changelogs, and screenshots.

## Build, Test, and Development Commands

Use JDK 17, matching CI, and install Android SDK platform 37. Configure the SDK path in untracked `local.properties` (`sdk.dir=...`). Run commands from the repository root; use `chmod +x gradlew` if needed.

- `./gradlew :app:assembleDebug`: build the debug APK under `app/build/outputs/apk/debug/`.
- `./gradlew :app:installDebug`: install on a connected device or emulator running Android 8.0/API 26 or newer; launch from the device.
- `./gradlew clean assemble`: reproduce the CI build.
- `./gradlew :app:lintDebug`: run Android Lint.
- `./gradlew :appintro:testDebugUnitTest`: run onboarding JVM tests after configuring test dependencies.
- `./gradlew :appintro:connectedDebugAndroidTest`: run instrumented tests on a connected device after configuring test dependencies.

## Coding Style & Naming Conventions

Follow the configured official Kotlin style with four-space indentation. Use `UpperCamelCase` for classes and composable functions, `lowerCamelCase` for ordinary functions and properties, and `snake_case` for Android resources. Keep package paths aligned with existing feature folders. Use Android Studio formatting; no standalone formatter is configured. Centralize shared dependency versions in the version catalog.

## Testing Guidelines

Existing `appintro` examples use JUnit 4 and AndroidJUnit4, but its build file currently lacks their test dependencies. Add those dependencies before extending or running tests. Place JVM tests in `src/test/` and device tests in `src/androidTest/`, using `*Test.kt` names. No coverage threshold is configured. Verify affected password, biometric, permission, and background-locking flows on a device.

## Commit & Pull Request Guidelines

History uses short, descriptive subjects, sometimes prefixed with `fix:`; no strict convention is enforced. Keep commits focused. PRs should explain the behavior change, link relevant issues, report build and test results, and include screenshots for UI changes. Keep SDK paths, signing keys, and credentials untracked.
