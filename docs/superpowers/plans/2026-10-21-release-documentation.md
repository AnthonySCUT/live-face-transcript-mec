# Release Documentation and GitHub Publication Implementation Plan

> **For agentic workers:** Required sub-skill: use the implementation workflow to execute each task in order.

**Goal:** Publish the current Android implementation and documentation so a teammate can build, deploy, validate, and continue development without relying on stale assumptions.

**Architecture:** Documentation is derived from the checked-in Android code and the verified Lenovo emulator workflow. The README is the entry point; the handoff document holds operational detail; contributing and roadmap describe team workflow and course milestones.

**Tech Stack:** Kotlin, Gradle, Android SDK, CameraX, MediaPipe, OpenCV, FunASR WebSocket, Vosk, SQLite.

## Task 1: Align operational documentation

**Files:** `README.md`, `AGENT_HANDOFF.md`, `CONTRIBUTING.md`, `ROADMAP.md`

1. Replace the obsolete manual speaker-selection and Vosk-only descriptions with the actual FunASR/Vosk and mouth-motion implementation.
2. Document the default endpoint, `adb reverse`, and Lenovo-safe `adb install --no-streaming -r` command.
3. State measurable limits without claiming unmeasured latency or voiceprint support.

**Verification:** Cross-check all commands and behaviour with source files and the last verified Android build.

## Task 2: Publish a reproducible repository state

**Files:** source, tests, docs, and this plan

1. Run unit tests and assemble the debug APK.
2. Run `git diff --check` and inspect the staged file list.
3. Commit the scoped implementation and documentation, then push the existing feature branch to `origin`.

**Verification:** Confirm the commit is present in the remote tracking branch after `git push`.
