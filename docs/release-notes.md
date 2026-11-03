# Release Notes — v0.1.0 (DRAFT)

**MobileAgent v0.1 — Android Agent Runtime Prototype**

### Highlights

- Structured goal-to-plan execution through an existing `AgentRuntime` + `SkillRegistry` architecture.
- Modular Android Skills: WeChat, ChatGPT, Gemini and an experimental DeepSeek **app** Skill.
- Real Android device UI gestures with existing coordinate calibration.
- WeChat search-result and chat-entry visual safeguards based on Android screenshots and local ML Kit OCR; safety stop on missing or unverifiable contacts.
- Local contact alias/entity profiles, queued multi-message tasks, execution events and cancellation.

### Limitations

- Not a general autonomous Agent Loop, not universal Android compatibility, not guaranteed delivery verification.
- DeepSeek Android **App Skill** sending currently unreliable. This is separate from the DeepSeek API Planner.
- Other AI app Skills currently submit prompts but do not read responses into the runtime.
- Source release is recommended; avoid sharing APKs embedding developer API keys.

### Verification still required

Maintainer reports three WeChat test runs and basic ChatGPT/Gemini prompt submission; independent A–G records and a demo video are still pending. This source archive has been cleaned of local properties, binaries, build caches and Git history. License selection remains outstanding.
