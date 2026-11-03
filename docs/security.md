# Security and Privacy — Release Gate

## Never commit or publish

- `local.properties` or real API tokens; check for secret patterns in **tracked files and history**
- Private contact/profile JSON, aliases, chat logs, test accounts, screenshots, screen recordings or unredacted notifications
- Signing assets: `*.jks`, `*.keystore`, password files, signing configs containing secrets
- Developers' local `*.properties`, `*.env`, private paths or credential caches
- Release APKs compiled with a developer-owned DeepSeek API key

The project currently uses local `DEEPSEEK_API_KEY` → `BuildConfig.DEEPSEEK_API_KEY`. This is sensible for a **source-only** release when secrets are excluded from Git, but **not secure key storage in an APK**. For distributing binaries, consider user-provided keys or an authenticated backend.

## Local and remote processing

`WechatVisualObserver` captures the Android screen and runs **bundled on-device ML Kit OCR** for WeChat UI verification. That observer does not intentionally persist screenshots or send them to a visual cloud API. Note that **the separate DeepSeek LLM Planner communicates with an external API** and may transmit plan inputs. Review all app code for logs and other network behavior before claiming comprehensive offline/privacy properties.

## Pre-publication procedure

1. Make a clean local backup of the development project **outside** the publish directory.
2. Work from a fresh copy; do not copy private working directories or local files into the public repository.
3. Merge `.gitignore.additions` into the existing `.gitignore`; note `.gitignore` cannot untrack files already committed.
4. Inspect `git status`, `git ls-files`, `git diff --cached` and run `scripts/preflight.ps1`. The script is a preliminary check, not a security certification.
5. Search history manually for secrets if the repository has prior commits; a clean working directory does **not** prove Git history is clean.
6. If a real API key ever entered commit history, **rotate/revoke it**. Removing the file in the latest commit alone does not erase history.
7. Redact every identifiable name and chat preview from published video/GIF. Cover system notifications, including those that appear mid-recording.
8. Review dependency licenses and choose an authorized open-source license before going public.
9. Start with **source code + video**, not a downloadable APK.

Reference: https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository
