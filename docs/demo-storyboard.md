# 60–90 s Demo Storyboard

**Video rule:** actual device recording, no faked tool output, no composited automation; trim waiting, blur private information, annotate stages. Use a **consenting test contact** and innocuous messages.

| Timestamp | Scene | Proof point |
| --- | --- | --- |
| 00–10s | MobileAgent UI, goal text | Natural-language input |
| 10–20s | Planner → AgentPlan → selected Skill | Structured plan + one Runtime |
| 20–48s | WeChat opens; searches verified test contact; enters chat; sends two distinct short test messages | Real Android gesture UI and two-message queue |
| 48–60s | Execution events + results | Visible trace; do not imply independent delivery acknowledgement |
| 60–74s | Separate goal targeting `ChatGPTSkill` **or** `GeminiSkill` | Different Skill routed by same Runtime; show real prompt submission |
| 74–85s | Architecture closing frame with limitation: **Android Agent Runtime Prototype** | Clear positioning and non-claims |

**Optional separate clip:** demonstrate a nonexistent contact safely stopping at search with `TASK_FAILED`. This strengthens the engineering story but is not a substitute for successful main-demo runs.

### Suggested title and caption

**Title:** MobileAgent v0.1 — Android-native Agent Runtime Prototype

**Description:** A real-device Android prototype that turns natural-language goals into structured plans and dispatches modular Skills. The WeChat Skill uses calibrated Android gestures and screenshot/OCR guards; a separate ChatGPT or Gemini Skill demonstrates the same Runtime controlling a different app. Experimental, not a full autonomous Agent Loop.

### Recording hygiene

- Hide contact names, profile photos, conversations, notifications and API keys.
- Avoid including private Logcat data in the footage.
- If a run fails, retain it in test records; do not edit it to appear to have succeeded.
- Show contact search and send on the device, not merely status text in the app.
