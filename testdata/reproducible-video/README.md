# Reproducible video and speech inputs

The app ships nine video replay assets and two matching WAV inputs under `app/src/main/assets/test/reproducible/`. The fixed replay clips, the film generation script, source links, and SHA-256 checksums are kept here. The app's **可复现实验素材** buttons replay these inputs without relying on the emulator camera or microphone.

## Sources

- **English baseline:** a 60-second, 640×360, 10 fps H.264 replay and its same-interval mono 16 kHz PCM WAV are included. They use President Obama's 2017 farewell remarks, published by VOA and available on Wikimedia Commons: [source video](https://commons.wikimedia.org/wiki/File:President_Barack_Obama_says_because_of_you,_America_is_a_stronger_and_better_place.webm).
- **Two-speaker source:** the replay uses a controlled mixture of two 20-second speaker clips. The source is the public-domain White House press conference with President Obama and President Toomas Hendrik Ilves on Wikimedia Commons: [source video](https://commons.wikimedia.org/wiki/File:President_Obama_Holds_a_Press_Conference_with_President_Ilves_of_Estonia.webm), with an [English timed transcript](https://commons.wikimedia.org/wiki/TimedText:President_Obama_Holds_a_Press_Conference_with_President_Ilves_of_Estonia.webm.en.srt).
- **Film stress source:** the 1940 comedy *His Girl Friday*, using its [97-second trailer on Wikimedia Commons](https://commons.wikimedia.org/wiki/File:His_Girl_Friday_(1940)_by_Howard_Hawks,_trailer.webm) and original [YouTube upload](https://www.youtube.com/watch?v=0GYX46Bs1_8). `sources/his-girl-friday-trailer-360p.mp4` is the 640×360, 30 fps video track. The film has rapid conversational cuts and newsroom group shots.

## Scenarios

| App input | Reproducible condition | Format |
| --- | --- | --- |
| `obama-farewell-60s.mp4` | Single English speaker; baseline for local English Vosk vs computer FunASR and for static/dynamic face partitioning | H.264, 640×360, 10 fps, 60 s |
| `obama-farewell-60s-16k-mono.wav` | Same speech interval as the video; streaming ASR comparison | PCM16, mono, 16 kHz, 60 s |
| `two-speaker-overlap-20s.mp4` | Two independently recorded speakers are time-aligned and mixed to create a controlled simultaneous-speech stress case; each side is cropped and enlarged to show a face close-up for mouth-to-source assignment | H.264, 640×360, 10 fps, 20 s |
| `two-speaker-overlap-20s-16k-mono.wav` | Exact audio used by the overlap speech benchmark | PCM16, mono, 16 kHz, 20 s |
| `face-occlusion-20s.mp4` | Black rectangular occluders cover both visible faces from t=5 s through t=10 s; frames before and after measure detection and track recovery | H.264, 640×360, 10 fps, 20 s |
| `faces-leave-frame-20s.mp4` | The camera viewport pans across a padded 1280×360 canvas; both faces leave the crop at t=4–6 s and re-enter by t=10 s | H.264, 640×360, 10 fps, 20 s |
| `his-girl-friday-dialogue-20s.mp4` | Continuous fast-cut film dialogue from source 53–73 s; 39 frames are sampled at 500 ms intervals | MPEG-4, 640×360, 10 fps, 20 s |
| `his-girl-friday-crowd-20s.mp4` | Repeats the newsroom group shot from source 47–49 s; the MEC detector finds four faces at the one-second replay sample | MPEG-4, 640×360, 10 fps, 20 s |
| `his-girl-friday-occlusion-20s.mp4` | Same group shot; two detected face boxes are occluded from t=5–10 s, then visible again | MPEG-4, 640×360, 10 fps, 20 s |

The overlap scenario is a controlled audio mixture of the two public-domain speaker clips rather than an unrepeatable room recording. The film clips extend visual tests with quick edits and a four-person newsroom frame; the second newsroom version applies a timed occlusion to two detected faces. The film clip is a face-pipeline stress input, while the WAV mixture provides the repeatable simultaneous-speech and separation input.

Regenerate the three film inputs from the downloaded source with Python 3.12 and the face-service OpenCV environment:

```powershell
server\.venv-face\Scripts\python.exe testdata\reproducible-video\build_film_stress_inputs.py `
  --source testdata\reproducible-video\sources\his-girl-friday-trailer-360p.mp4 `
  --dialogue-start 53 --crowd-start 47 `
  --output-dir testdata\reproducible-video
Copy-Item testdata\reproducible-video\his-girl-friday-*.mp4 app\src\main\assets\test\reproducible\ -Force
```

## Run the app replay experiments

1. Start the face MEC service on the computer (`server/server.py`, port 8000).
2. Start FunASR (`server/funasr_wss_server.py`, port 10095) and the SepFormer overlap service (`server/overlap_server.py`, port 8001).
3. Install the APK and open **可复现实验素材**. Run the MP4 static/dynamic benchmark, edge replay, same 20-second WAV comparison for local bilingual Vosk, local English Vosk and computer FunASR, and overlap separation replay.
4. Export `reproducible-experiments.csv`. The rows record scenario, source timestamp, detected face count, track labels, overlap IDs, selected partition, compute/transfer time, input and returned bytes, transcript text, and Android battery readings.

For extraction and source licensing, use the Wikimedia Commons pages linked above. Generated scenario videos and their derived WAV files are the fixed replay artifacts included with this project.

## SHA-256

| File | SHA-256 |
| --- | --- |
| `obama-farewell-60s.mp4` | `0B8C3F8492364FCDDB49D110D5302AFFFBD8C2256D0A6993328E3A9BFA77B26E` |
| `obama-farewell-60s-16k-mono.wav` | `1E16CF8E0BBDFDDFA04AF27416E1833E8C1E66ACBEC9AE9410D60AB2A1736031` |
| `ilves-speaking-20s.mp4` | `43A8FE54E3DEFA1685D470DD851C8DAC1AE98D4E135818F917D1A228C50824BD` |
| `obama-speaking-20s.mp4` | `D7595E19DBA7BD2B868DC3FFBA9E84A475C86CDAF5670E26F2FFD998A1694FCC` |
| `two-speaker-overlap-20s.mp4` | `75F9902F24A6AF7B8BC403135B38E34563AD2B68C5E07E466CC6DBF419A8B523` |
| `two-speaker-overlap-20s-16k-mono.wav` | `26B2D461A323172FA361658183D7C9F23F139DDACFD2D2819BCFE91686D878F6` |
| `face-occlusion-20s.mp4` | `CBEE71AEB22FED0D6DBCBF949373CD3A0636E2B8D8380F6D861DBB2B2B40DABF` |
| `faces-leave-frame-20s.mp4` | `291BBF4BF03E7476605F249ACCA340AFEC4A4B5ADFA7573C0A27333B9F127FCF` |
| `sources/his-girl-friday-trailer-360p.mp4` | `8FB6AE8C338203C10C22AB7E577BFA5ECB35E681D7338A88DD558E5F4BCDD8AD` |
| `his-girl-friday-dialogue-20s.mp4` | `87F11CD3DF641EE73F33EDF916B56D5B26FF65B9F0EECA24789B3C52A9CA9F93` |
| `his-girl-friday-crowd-20s.mp4` | `AAEA67EB60AED3D0331506F7731C90DB7EAD7C94CC12480A318C2B73FDF79166` |
| `his-girl-friday-occlusion-20s.mp4` | `A8883E6E5278412E92139235DD01533E46E712C465679568BED6C93CE080F357` |
