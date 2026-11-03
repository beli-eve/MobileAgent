# v0.1 Real-Device Test Record

This is a **maintainer-reported status log**, not an independent certification. Update every row from fresh tests on the final source commit.

- Date: 2026-11-03
- Device: **TBD — verify exact model from Android settings**
- Android: **TBD — verify on test device**
- WeChat version: **TBD — record installed version**
- APK commit SHA / branch: **TBD**
- Installed app versions for ChatGPT/Gemini: **TBD**

| Case | Requirement | Current evidence | Final RC outcome |
| --- | --- | --- | --- |
| A | Stored contact exact name | Maintainer reports existing contact can send; WeChat tested 3 times (scenario breakdown unrecorded) | ☐ pending repeat |
| B | Stored contact alias | Historically supported; not re-proven in this release turn | ☐ pending |
| C | Unregistered existing contact | Intended behavior; requires explicit current run | ☐ pending |
| D | Nonexistent contact | Maintainer reports incorrect contact stops without false positive | ☐ pending repeat + verify zero follow-up actions |
| E | Consecutive two messages | Historically supported; release verification outstanding | ☐ pending |
| F | ChatGPT or Gemini Skill | Maintainer reports basic ChatGPT and Gemini prompt submission working | ☐ pending |
| G | Cancel task | Maintainer reports basic ChatGPT and Gemini prompt submission working | ☐ pending |

## Main demo three-run record

| Run | Commit SHA | Date/time | WeChat path | Second Skill | Unexpected clicks / completion? | Pass/Fail | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | TBD | TBD | TBD | TBD | TBD | Pending | |
| 2 | TBD | TBD | TBD | TBD | TBD | Pending | |
| 3 | TBD | TBD | TBD | TBD | TBD | Pending | |

## Release criterion

A–G passed, plus 3 successive main demo runs. Record *observable* evidence rather than conflating gesture callback with message delivery. Safe failure does not count as positive-path success.
