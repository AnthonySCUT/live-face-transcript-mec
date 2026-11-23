import base64
import threading
import unittest

import numpy as np
import torch

from overlap_server import OverlapPipeline, clean_transcription


class FakeSeparator:
    def separate_batch(self, waveform):
        first = waveform[0]
        return torch.stack([torch.stack([first, first * 0.5], dim=-1)])


def make_pipeline(transcribe):
    pipeline = OverlapPipeline.__new__(OverlapPipeline)
    pipeline.device = "cpu"
    pipeline.separator = FakeSeparator()
    pipeline.asr_url = "ws://unused"
    pipeline.separation_lock = threading.Lock()
    pipeline.transcribe = transcribe
    return pipeline


class OverlapPipelineTest(unittest.TestCase):
    def test_returns_two_separated_audio_sources_and_transcripts(self):
        pipeline = make_pipeline(lambda pcm, name: (name, 12.0))
        pcm = np.array([0, 16_384, -16_384, 8_192], dtype="<i2").tobytes()

        result = pipeline.separate_and_transcribe(pcm)

        self.assertEqual([source["text"] for source in result["sources"]], ["overlap-1", "overlap-2"])
        self.assertEqual([source["index"] for source in result["sources"]], [0, 1])
        source_audio = [base64.b64decode(source["audio_base64"]) for source in result["sources"]]
        self.assertEqual(np.frombuffer(source_audio[0], dtype="<i2").tolist(), [0, 16_383, -16_383, 8_191])
        self.assertEqual(np.frombuffer(source_audio[1], dtype="<i2").tolist(), [0, 8_191, -8_191, 4_095])

    def test_transcribes_the_two_sources_in_parallel(self):
        both_entered = threading.Barrier(2)

        def transcribe(_, name):
            both_entered.wait(timeout=3)
            return name, 10.0

        pipeline = make_pipeline(transcribe)
        pcm = np.array([1_000, -1_000] * 80, dtype="<i2").tobytes()

        result = pipeline.separate_and_transcribe(pcm)

        self.assertEqual(len(result["sources"]), 2)
        self.assertEqual({source["text"] for source in result["sources"]}, {"overlap-1", "overlap-2"})

    def test_removes_sensevoice_language_and_speaker_tags(self):
        self.assertEqual(clean_transcription("<|en|><|NEUTRAL|>Hello there.<|woitn|>"), "Hello there.")

    def test_preserves_plain_chinese_and_punctuation(self):
        self.assertEqual(clean_transcription("你好，欢迎来到这里。"), "你好，欢迎来到这里。")


if __name__ == "__main__":
    unittest.main()
