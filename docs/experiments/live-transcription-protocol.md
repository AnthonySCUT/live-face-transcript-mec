# Live transcription and speaker attribution experiment

This protocol measures recognition quality, response delay, and mouth based speaker attribution on the Android emulator. Run it again on a phone before treating the results as representative of real use. No measurements have been recorded yet; the configured SDK currently reports no attached ADB device.

## Test setup

For each run, record the APK revision, device/emulator model, Android version, CPU/RAM allocation, selected speech backend, FunASR endpoint and model, network type/round trip time, and whether the Vosk fallback activated. Grant camera and microphone permission and acknowledge participant consent in the app. Use one speaker who remains in camera view, then repeat attribution cases with two visible people.

Say each phrase live three times at a natural pace:

| Language | Fixed phrase |
| --- | --- |
| Chinese | 明天下午三点开会，会议地点在软件实验室。 |
| English | Please open the project dashboard and check the latest build. |
| Mixed | 我们先 review the architecture，然后确认 deployment plan。 |
| Mixed | Good morning，今天我们讨论 project architecture。 |

Also run these attribution cases: one visible person speaking; two visible people with only one speaking; two visible people taking turns; speaker's mouth partly covered; and visible people with no speech. Do not use simultaneous speech as an acceptance case because source separation is outside this implementation.

## Measurements

Record one row per utterance. Time speech end and the first visible partial result from the recorded test video or a monotonic stopwatch. Do not estimate timings after the run.

| Field | How to record |
| --- | --- |
| Backend | FunASR remote or Vosk local fallback |
| Reference / recognized text | Preserve the exact phrase and app output |
| Chinese CER | Character edit distance divided by reference Chinese character count; normalize punctuation first |
| English WER | Word edit distance divided by reference English word count; lowercase and remove punctuation first |
| First text latency | Speech end to first non-empty partial shown |
| Text + speaker latency | Speech end to the first displayed text with its final or unconfirmed speaker label |
| Speaker result | Correct person label or “未确认说话人” |
| Attribution outcome | Correct, wrong, or unconfirmed |
| Mouth processing rate | Successfully analyzed mouth frames divided by elapsed capture seconds |
| Notes | Drops, reconnects, fallback, occlusion, or other visible errors |

For mixed phrases, calculate Chinese CER and English WER separately from the same spoken sample. Include unconfirmed outcomes in the denominator when reporting attribution coverage; report both correct attribution rate and unconfirmed rate. Never count an unconfirmed result as correct attribution.

## Run record

| Run | Date/time | Device and Android version | Backend / network | Utterance | CER / WER | First text ms | Text + speaker ms | Attribution | Mouth FPS | Notes |
| --- | --- | --- | --- | --- | --- | ---: | ---: | --- | ---: | --- |

Repeat every phrase and attribution case three times. Compare remote mixed-language recognition against the Vosk fallback explicitly; do not describe the fallback as bilingual. Treat the two-second goal as met only if the measured text-plus-speaker latency supports it across the recorded test set.
