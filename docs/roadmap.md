# Roadmap

## v0.1 — public source and real-device demo

- [x] Existing `AgentRuntime` / `AgentSkill` / `SkillRegistry` / multiple Skills in project architecture
- [x] Multi-message WeChat task queue and local contact alias resolution (historically validated)
- [x] Android calibrated coordinate/gesture execution
- [x] Search-result and chat-entry guard using Android screenshot + local ML Kit OCR, with positive/negative behavior reported working on device
- [ ] Release Build/Rebuild and current-source audit recorded
- [ ] A–G regression tests and 3 consecutive main-demo runs documented
- [ ] ChatGPT or Gemini Skill real-device release retest
- [ ] Privacy/key/keystore audit of **all tracked files and Git history**
- [ ] Redacted 60–90 s genuine demo video
- [ ] License chosen and committed
- [ ] GitHub public repository and `v0.1.0` tag

## After public v0.1 — runtime reliability

1. Formalize `ToolResult`: `Success`, `Failure`, `RetryableFailure`, `Cancelled`, with `message`, `rawObservation`, `metadata`, `nextSuggestedAction`.
2. Standardize `Observation`: current app/screen, screenshot-derived evidence, action evidence, timestamp and errors.
3. Add bounded minimal Agent Loop (`decide → tool → observe → decide`), explicit exit conditions and action budgets.
4. Split Memory into User, Entity, Device, Task and Skill memory; existing aliases = Entity memory and device calibrations = Device memory.
5. Add richer permission constraints, confirmations for consequential operations, and privacy-preserving observability.
6. Expand Skills only after evidence and result contracts are robust.

## Deliberately out of scope for v0.1

Full multimodal agent, general multi-agent orchestration, MCP marketplace, arbitrary Android compatibility, DeepSeek app send-perfecting, cloud infrastructure and monetization.
