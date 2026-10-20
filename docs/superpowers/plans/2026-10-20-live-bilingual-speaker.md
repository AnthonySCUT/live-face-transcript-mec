# Live Bilingual Transcription and Visual Speaker Attribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show Chinese-English mixed speech and the most likely visible speaker during a live Android session, aiming for about two seconds of display latency.

**Architecture:** Keep YuNet/SFace and FaceTracker for identity. Add a separately scheduled mouth-landmark stream, timestamp its activity, and fuse it with speech activity. Abstract speech recognition so the current local Vosk path remains a baseline while a team-controlled FunASR streaming service becomes the primary bilingual candidate. Partial results update the UI; final results alone enter SQLite.

**Tech Stack:** Kotlin, CameraX, OpenCV, MediaPipe Face Landmarker, AudioRecord, Vosk, FunASR WebSocket runtime, SQLite, JUnit.

**Spec:** `docs/superpowers/specs/2026-10-20-live-bilingual-speaker-design.md`

## Global Constraints

- Android minSdk 26, compileSdk 35, JDK 17 or 21.
- Do not remove the participant-consent gate or existing face registration/history data.
- No prerequisite upload of recorded audio; send live PCM only when the remote mode is active.
- The first test platform is an Android emulator; repeat performance tests on a real phone.
- About two seconds from the end of a short spoken segment to displayed text and speaker is a measured target, not a guarantee.
- Voiceprint identification and simultaneous-speaker separation are outside scope.
- Planning itself does not install or launch the app. Full feature verification during execution requires emulator deployment and later phone testing.

## Review Focus

1. Remote disconnect during speech: show a clear fallback state and do not claim bilingual accuracy from local Vosk; Task 2 test.
2. Several faces with similar mouth activity: return `未确认说话人`; Task 3 test.
3. Face track disappears during speech: do not assign its old label to a new face; Task 3 test.
4. Partial results are revised by ASR: update the preview without duplicate SQLite rows; Task 4 test.
5. Session stops while frames or audio results are in flight: ignore late callbacks and release resources; Task 4 test.

---

### Task 1: Timestamped speech contract and local baseline

**Files:**
- Create: `app/src/main/java/edu/scut/livetranscript/SpeechEngine.kt`
- Create: `app/src/main/java/edu/scut/livetranscript/VoskSpeechEngine.kt`
- Modify: `app/src/main/java/edu/scut/livetranscript/MainActivity.kt`
- Test: `app/src/test/java/edu/scut/livetranscript/SpeechEventTest.kt`

**Interfaces:**
- Produce `data class SpeechEvent(val text: String, val startedAtMs: Long, val endedAtMs: Long, val isFinal: Boolean)`; time uses `SystemClock.elapsedRealtime()`.
- Produce `interface SpeechEngine { fun start(onEvent: (SpeechEvent) -> Unit); fun acceptPcm(samples: ShortArray, count: Int, capturedAtMs: Long); fun stop() }`.
- Vosk implementation owns its recognizer, never AudioRecord; MainActivity remains the single audio capture owner.

- [ ] Write a failing unit test asserting a partial event is not final and successive final events carry ordered timestamps.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --tests edu.scut.livetranscript.SpeechEventTest` and confirm the test fails for the missing contract.
- [ ] Implement the contract and move only the existing Vosk recognition calls behind it; preserve the existing speech gate and local database behavior.
- [ ] Run the same unit test and `./gradlew.bat :app:assembleDebug`; require both to pass.
- [ ] Commit the focused files once the working directory has been connected to the verified repository history.

### Task 2: Remote bilingual streaming candidate

**Files:**
- Create: `app/src/main/java/edu/scut/livetranscript/FunAsrSpeechEngine.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle`
- Modify: `app/src/main/java/edu/scut/livetranscript/MainActivity.kt`
- Test: `app/src/test/java/edu/scut/livetranscript/FunAsrProtocolTest.kt`

**Interfaces:**
- Implement `SpeechEngine` from Task 1. Send 16 kHz mono PCM in order over one WebSocket session; map FunASR online responses to partial events and final responses to final events, retaining the originating audio time window.
- Keep the endpoint as one development setting, not scattered constants. On connection failure, report state to the UI and switch to Vosk with an explicit `本地中文后备` label.

- [ ] Write failing protocol tests for connection setup, ordered PCM chunks, partial/final mapping, and disconnect fallback.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --tests edu.scut.livetranscript.FunAsrProtocolTest` and confirm relevant failures.
- [ ] Add the minimal WebSocket client and remote adapter against the official FunASR streaming protocol; verify the selected model's actual response fields before fixing the parser.
- [ ] Run the protocol tests and `:app:assembleDebug`; require both to pass.
- [ ] Install the APK on the emulator, then compare fixed Chinese, English, and sentence-level mixed-language samples against manual transcripts; retain the candidate only if it improves mixed-language results within the latency target.
- [ ] Commit the focused files.

### Task 3: Mouth activity and speaker fusion

**Files:**
- Create: `app/src/main/java/edu/scut/livetranscript/MouthActivityAnalyzer.kt`
- Create: `app/src/main/java/edu/scut/livetranscript/SpeakerAttributor.kt`
- Modify: `app/src/main/java/edu/scut/livetranscript/MainActivity.kt`
- Modify: `app/build.gradle`
- Test: `app/src/test/java/edu/scut/livetranscript/SpeakerAttributorTest.kt`

**Interfaces:**
- Produce `data class MouthSample(val trackId: Int, val atMs: Long, val activity: Float)` and `fun attribute(startedAtMs: Long, endedAtMs: Long, visibleTrackIds: Set<Int>): Int?` in `SpeakerAttributor`; null means unconfirmed.
- MouthActivityAnalyzer uses MediaPipe face landmarks on a higher-frequency frame path, associates face bounds with existing track IDs, and passes timestamped samples to SpeakerAttributor. Keep identity recognition's current schedule initially; tune only after profiling.

- [ ] Write failing fusion tests: one clear moving mouth selects its ID; similar activity, stale samples, or lost tracks return null.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --tests edu.scut.livetranscript.SpeakerAttributorTest` and confirm failures.
- [ ] Implement the minimal timestamp window and confidence comparison; add the landmark adapter without changing YuNet/SFace identity logic.
- [ ] Run the fusion tests and `:app:assembleDebug`; require both to pass.
- [ ] On the emulator, measure processed frames per second and attribution against manually labeled single-speaker clips; repeat on a phone when one is available.
- [ ] Commit the focused files.

### Task 4: Live UI, persistence, lifecycle, and measurements

**Files:**
- Modify: `app/src/main/java/edu/scut/livetranscript/MainActivity.kt`
- Modify: `app/src/main/java/edu/scut/livetranscript/LocalStore.kt` only if final timestamps need schema changes
- Create: `app/src/test/java/edu/scut/livetranscript/TranscriptSessionTest.kt`
- Create: `docs/experiments/live-transcription-protocol.md`

**Interfaces:**
- Consume SpeechEvent and SpeakerAttributor. A partial event changes the preview and may revise its speaker; a final event inserts exactly one history row with the best supported label or `未确认说话人`.
- Stop invalidates the active session token before closing audio, camera analysis, engines, and remote connection; late callbacks cannot update UI or SQLite.

- [ ] Write failing tests for partial revision without persistence, one final row, unknown speaker, and ignored late callbacks.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --tests edu.scut.livetranscript.TranscriptSessionTest` and confirm failures.
- [ ] Connect preview and persistence to the new events and attribution, retaining the existing consent and stop behavior.
- [ ] Run all `:app:testDebugUnitTest` tests and `:app:assembleDebug`; require both to pass.
- [ ] Document and run a repeatable emulator protocol with fixed utterances, known speaker, first-text and text-plus-speaker latency, character/word error, attribution accuracy, network condition, and device. Repeat on a phone when one is available.
- [ ] Commit the focused files and review the final diff for changes unrelated to this feature.

## Execution boundary

Compilation and unit tests verify only code-level behavior. End-to-end acceptance requires installing and running the APK on an emulator with working camera and microphone input, and measuring real transcription, attribution, and latency. The initial request deferred deployment while requirements were being clarified; execution should include deployment after implementation is ready. Before live camera/microphone testing, use consenting participants. The local directory currently has no `.git`; inspect the remote repository history and reconcile the unpacked files before any commit rather than running `git init` blindly.
