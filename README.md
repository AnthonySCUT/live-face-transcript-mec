# Live Face Transcript

Independent Android prototype for a consent-based live session. It detects multiple faces, labels faces from a local participant registry, assigns session-only labels such as `陌生人 A` to unregistered people, and continuously displays Chinese speech recognition results.

## Run

Requirements: JDK 17+, Android SDK platform 35, Android build tools 35.0.0.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.
gradlew.bat :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on Android 8.0 or later. The app requests camera and microphone access only after the user starts a session and confirms participant consent.

## Session behavior

- Tap **Start** to begin camera analysis and speech recognition, and **Stop** to release both.
- YuNet/SFace detect and embed every face on device. Registered participant names and 128-float face embeddings are kept in a private SQLite database on the device.
- Tap a face box to select that person as the active speaker, then enter the participant's name and register the selected face. The current app assigns unregistered faces temporary labels per session.
- The bundled Vosk Chinese model performs offline speech recognition. Partial results are shown live; final transcripts are stored locally with the resolved speaker label and timestamp. Original audio is not stored.
- The live panel shows the input level in dBFS. If it stays below `-55 dBFS`, check the host microphone, Windows microphone privacy settings, and the emulator's host microphone forwarding. Recognition results are only shown or saved after the input rises above the speech gate (`-52 dBFS`) to reduce noise-triggered false transcripts.
- With one visible face, speech is attributed to that face. With several visible faces, tap the speaker first; otherwise the transcript is saved as `未确认说话人` because Android's standard speech recognizer does not provide reliable speaker diarization.
- The Chinese speech model is about 42 MB. The first session unpacks it into private app storage; later sessions reuse that copy.
- The checkbox is a user confirmation for a controlled session; use this only with participants who have been informed and have agreed. The registry is local to the app and can be removed by clearing app storage.

## Camera emulator

An emulator's `emulated` camera can show its generated test scene rather than a normal camera image. This machine has an ASUS webcam available. To use it with the existing AVD:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd GestureMEC_API_30 -camera-front none -camera-back webcam0
```

For microphone input in an already running emulator, enable host audio forwarding:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" emu avd hostmicon
```

## Pipeline

`CameraX frames -> YUV conversion -> YuNet multi-face detection -> SFace alignment/embedding -> known-person lookup -> short-term face tracking -> overlay labels`

`microphone -> Android AudioRecord (16 kHz PCM) -> input-level meter -> Vosk offline recognition -> speaker association -> SQLite transcript history`

The transcript pipeline does not include acoustic speaker diarization. For multi-person conversations, manual face selection is required for reliable attribution. The Vosk Android implementation and small Chinese model are Apache-2.0 licensed; see the [Vosk Android demo](https://github.com/alphacep/vosk-android-demo) and [model list](https://alphacephei.com/vosk/models).
