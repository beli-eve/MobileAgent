# Known Limitations — v0.1

This release is an **experimental Android prototype**, not a production assistant.

1. **Incomplete Agent Loop.** One-shot planning followed by execution; not a fully autonomous observe–replan loop.
2. **Partial business-state verification.** A completed Android gesture is not delivery confirmation. Visual checks are implemented at WeChat search and chat-entry stages, but end-to-end send/delivery acknowledgement remains incomplete.
3. **UI-dependent automation.** Android Accessibility permission, gestures and observed screenshots can break after app, device or keyboard updates.
4. **WeChat Accessibility Tree lacks usable contact content on the tested environment.** WeChat uses calibrated gestures and screenshot + local OCR rather than relying on semantic search-result nodes.
5. **OCR limitations.** Screenshots may fail or misread text, suffer from delays, overlapping notifications, duplicates or display changes. Unknown/ambiguous states stop rather than guessing.
6. **DeepSeek Android app Skill is Experimental and currently cannot reliably submit messages**; it is not part of the required main v0.1 demo. This does not mean the DeepSeek HTTP API Planner is broken.
7. **Other AI app Skills are action-only.** ChatGPT and Gemini Skills open the app, type and request send; they do not yet read the response and return it into the Planner.
8. **Limited device compatibility validation.** Maintainer-reported working behavior is limited to tested devices; exact device/Android/WeChat versions should be recorded before compatibility claims.
9. **Sensitive capabilities.** Screenshot and accessibility access can expose on-device information; the app should be used with consent and not shared with personal credentials or contact data.
10. **Key distribution.** A key injected via `local.properties` → `BuildConfig` is hidden from Git source but can be extracted from the compiled APK. Do not distribute an APK containing a shared developer API key.
11. **Task cancellation and timeouts** reduce damage, but race conditions and force-stop scenarios are still possible.
12. **No guarantees for background unattended operation, payments, emergency actions or other high-risk workflows.**

**Transparency requirement:** show actual behavior. Do not edit a failed take to appear as a successful automated execution.
