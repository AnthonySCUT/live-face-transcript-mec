# Live Face Transcript

Android prototype for a consent-based live session. It detects multiple faces, matches registered participants on device, gives unregistered faces temporary session labels, and displays live speech recognition results.

## Build

Requirements: JDK 17+, Android SDK platform 35, Android build tools 35.0.0.

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The app supports Android 8.0 or later. Camera and microphone access are requested only after the user starts a session and confirms participant consent.

## Session behavior

- Tap **Start** to begin live camera and microphone analysis; **Stop** ends the session.
- YuNet/SFace detect and embed faces on device. Registered names and face embeddings are stored in a private SQLite database on the device. Unregistered faces receive temporary labels for that session.
- Tap a face to select it for participant registration. Automatic transcript attribution uses timestamped mouth activity; if the evidence is weak or multiple faces move similarly, the transcript is labeled `未确认说话人`.
- Live 16 kHz mono microphone audio is streamed to the configured FunASR WebSocket for mixed Chinese-English recognition. The app does not require prerecorded audio. If the remote service is unavailable, Vosk runs locally as a Chinese fallback and the status identifies that mode.
- Partial recognition updates the live preview. Final text is saved locally with its speaker label and timestamp. Original audio is not stored by the app.
- The live panel shows microphone input level. If it remains below `-55 dBFS`, check Android microphone permission, system input, and emulator microphone forwarding. A speech gate at `-52 dBFS` helps suppress background noise.
- The bundled Vosk Chinese model is about 42 MB and is unpacked into private app storage on first use. The participant registry and transcript history can be removed by clearing app storage; transcript history can also be cleared in the app.

## FunASR endpoint

The default endpoint is `ws://10.0.2.2:10095`, which targets a service listening on the host machine from a standard Android emulator. Override it at build time with `-PfunasrWsUrl=...`.

Android cleartext traffic is permitted only for `10.0.2.2`. Use `wss://` for other hosts, or add a specific trusted host to `app/src/main/res/xml/network_security_config.xml`; do not enable cleartext globally. The remote server must accept the app's 16 kHz mono PCM WebSocket stream and return results in utterance order. Standard two-pass responses have no app-level segment ID, so ambiguous source times cause the speaker to remain unconfirmed. See the [FunASR WebSocket server](https://github.com/modelscope/FunASR/blob/main/runtime/python/websocket/funasr_wss_server.py) for the two-pass response flow.

## Pipeline

`CameraX -> YuNet/SFace identity matching and face tracking`

`CameraX mouth frames -> MediaPipe Face Landmarker -> timestamped mouth activity -> speaker attribution`

`microphone -> AudioRecord (16 kHz PCM) -> FunASR streaming -> partial preview / final local history`

`FunASR unavailable -> local Vosk Chinese fallback`

About two seconds from speech end to visible transcript and speaker is a target to measure on the emulator and a real phone, not a verified performance claim. Multi-person attribution is based on visible mouth activity; the app does not perform voiceprint identification or separate overlapping speech.

Before a live test, obtain consent from participants. For the MediaPipe model asset convention, see the [Face Landmarker Android guide](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android). The Vosk Android implementation and Chinese model are Apache-2.0 licensed; see the [Vosk Android demo](https://github.com/alphacep/vosk-android-demo) and [model list](https://alphacephei.com/vosk/models).
