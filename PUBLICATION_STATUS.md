# Publication status — 2026-11-03

This ZIP is a **sanitized source-release candidate**, not a distributable APK and not a published GitHub repository.

## Sanitization performed
- Excluded `local.properties` (which contained a real `DEEPSEEK_API_KEY` and developer-local SDK path).
- Excluded `.git/` entirely, so old objects, commit history, reflogs and previous revisions are not shipped.
- Excluded `.gradle/`, all `build/` directories, `.idea/`, `app/release/` and compiled APKs.
- Excluded any local signing/key material by filename rules; no `.jks`/`.keystore` source entries were detected.
- Kept Android/Kotlin source, Gradle build scripts and wrapper, local ML Kit OCR code, images, resource XML, and the existing coordinate calibration implementation.
- Replaced `.gitignore` with an expanded private-file and build-output ignore list.
- Scanned included UTF-8 source/config/doc files for common embedded token patterns and contact data markers.

## Before public GitHub release
1. Choose a license. `LICENSE.MIT.template` is **not** a license grant; only the project owner can authorize one. To choose MIT, put the correct copyright holder in the template and rename it `LICENSE`.
2. In a fresh folder, extract this ZIP, run Android Studio Gradle Sync / Build, and add a **new local-only** `local.properties` to run the Planner. Never upload that file.
3. Publish the source folder, not the original ZIP and not an APK built with a personal API key. Add a real demo video and fill exact tested device / WeChat version in the README.

**Security boundary:** Source exclusion keeps the key out of this ZIP, but any APK you compile with that API key will embed it through `BuildConfig`. For binary distribution, require per-user keys or a server proxy. Consider rotating the key if the original ZIP or any previous Git history was ever published or shared externally.
