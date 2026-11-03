# MobileAgent v0.1 Release Gate

**Release candidate — do not tag or publish until all mandatory unchecked boxes pass.** Maintainer reports WeChat positive and negative execution working; other items have not been independently verified by this release kit.

## Mandatory: correctness and build

- [ ] Copy latest **actual** Android Studio project to a clean release directory; keep all original Runtime/Skill modules intact
- [ ] Confirm `WechatUiExecutor.kt` and `WechatVisualObserver.kt` match the version proven on the phone
- [ ] `Build → Rebuild Project` successful with no Kotlin/Gradle errors
- [ ] Test A: stored contact + exact name succeeds
- [ ] Test B: stored contact + alias resolves and succeeds
- [ ] Test C: unregistered contact that exists in WeChat succeeds
- [ ] Test D: nonexistent/mistyped contact stops, **no chat click, no message input, no send, no `TASK_COMPLETED`**
- [ ] Test E: two different messages to one contact both arrive in order
- [ ] Test F: at least one alternative Skill (ChatGPT or Gemini) actually works in the same runtime
- [ ] Test G: cancellation prevents remaining actions
- [ ] Main v0.1 demo executed successfully **3 consecutive times**, recorded with pass/fail, device, app version
- [ ] Verify a search false positive cannot accidentally send to the wrong user in your tested scenarios

## Mandatory: privacy and Git

- [ ] Exclude all credentials, local.properties, unredacted profiles, chats, screenshots, signing materials, machine-specific paths
- [ ] Inspect staged files, Git history and candidate ZIP contents; run `scripts/preflight.ps1` and manual review
- [ ] If any actual key was committed, revoke/rotate it before publishing
- [ ] Don't upload an APK with personal API key embedded via BuildConfig
- [ ] Choose a license and commit actual `LICENSE` (template alone does not grant rights)
- [ ] Redact demo identities and notifications; no staged fake execution

## Mandatory: release assets

- [ ] README accurately lists implemented and unimplemented capabilities
- [ ] Architecture, limitations, roadmap and security docs present
- [ ] Record device **vivo V2505A**, Android 16, and the **exact WeChat version**
- [ ] Record ChatGPT/Gemini installed app version used for demo
- [ ] 60–90 second honest demo exported and linked from README
- [ ] GitHub repository public; check website rendering and broken links
- [ ] Create `v0.1.0` tag/release and describe DeepSeek **app** Skill as Experimental

## Not required for v0.1

DeepSeek Android App Skill send repair, fully verified message delivery, arbitrary device coverage, full Agent Loop, Vision LLM, MCP marketplace.

## Go / no-go

**GO** only if build passes, A–G pass, 3 demo runs pass, sensitive data scan and manual review pass, and the alternative Skill demo is real. **NO-GO** for any wrong-recipient send, false-positive completion on missing contact, credential leak or unreproducible demo.
