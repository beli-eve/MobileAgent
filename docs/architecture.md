# Architecture — MobileAgent v0.1

## Goal and actual implementation

MobileAgent is an Android-native Agent Runtime prototype, not a hardcoded WeChat automation script. The following components are already implemented in the project as described by the project maintainer: `AgentRuntime`, `AgentSkill`, `SkillRegistry`, `AppContainer`, `AgentPlan`, multiple Skills, task cancellation and execution events.

```text
Natural Language
  → DeepSeek HTTP API Planner
  → AgentPlan (one-shot structured plan)
  → AgentRuntime
  → SkillRegistry
  → WechatSkill / ChatGPTSkill / GeminiSkill / DeepSeekSkill (Experimental)
  → Android device UI operations
  → Execution events / terminal state
```

**Not implemented yet:** general Goal → Tool → ToolResult/Observation → LLM replan → repeat until complete.

## WeChat execution path (2026-11 v0.1 candidate)

```text
Contact supplied by command
  → Local alias lookup (optional; never an allowlist)
  → Search term, or original name if no alias matches
  → Existing calibrated coordinate/gesture executor opens WeChat and searches
  → Android AccessibilityService.takeScreenshot()
  → On-device bundled ML Kit OCR
  → Identify an exact contact row in '联系人' or '最常使用' with appropriate evidence
       ├─ FOUND: click OCR-located row with existing gesture executor
       ├─ NOT_FOUND: stop, report failure, do not click or input message
       └─ UNKNOWN: limited retry, then stop safely
  → Screenshot/OCR chat-page check
       ├─ confirmed: input message and tap send using existing calibrated controls
       └─ not confirmed: stop
  → Emit events
```

### Trust boundaries

- Android `GestureResultCallback.onCompleted()` confirms **only that a gesture finished**. It does not prove a chat was opened or a message was delivered.
- System screenshot + OCR is used for **visual UI state evidence** in WeChat and does **not** rely on WeChat supplying accessible contact nodes.
- OCR failure, ambiguity, timeout, window mismatch or cancellation should terminate safely.
- The current code does **not** provide full independent message-delivery confirmation. Wording in events and demo must reflect that.
- Device coordinate calibration remains useful for stable UI targets. OCR provides a dynamically observed row location to cope with different result groups.

## Components

| Component | Responsibility |
| --- | --- |
| Planner | Convert a user goal to structured `AgentPlan` |
| `AgentRuntime` | Route plan actions and track execution |
| `SkillRegistry` / `AgentSkill` | Abstract concrete Android app operations |
| `AppContainer` | Application composition root |
| `WechatSkill` + `WechatUiExecutor` | Coordinate/gesture-based WeChat task executor |
| `WechatVisualObserver` | Android screenshots and bundled ML Kit OCR verification |
| Contact Profile / Memory | Local aliases and entity metadata, not a safety whitelist |
| Execution Events | User-visible state reporting, logs, cancellation |
| `ChatGPTSkill`, `GeminiSkill` | App UI action Skills (prompt submission only) |
| `DeepSeekSkill` | Experimental Android app action Skill; send detection unreliable |

## Version boundary

**v0.1:** publish and demonstrate the true plan–runtime–skill–device path, with known limitations disclosed. **Later:** formal `ToolResult`, observation contracts, bounded Agent Loop, layered memory and permission architecture. Avoid overengineering the first release.
