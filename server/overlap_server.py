"""Two-speaker overlap separation and parallel FunASR transcription service."""
import argparse
import base64
import json
import re
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

import numpy as np
import torch
import torchaudio

# torchaudio 2.11 removed this capability query; SpeechBrain 1.0.3 still calls it.
if not hasattr(torchaudio, "list_audio_backends"):
    torchaudio.list_audio_backends = lambda: ["soundfile"]

from speechbrain.inference.separation import SepformerSeparation
from speechbrain.utils.fetching import LocalStrategy
from websockets.sync.client import connect

SENSEVOICE_TAG_PATTERN = re.compile(r"<\|[^|]+\|>")


def clean_transcription(text: str) -> str:
    return SENSEVOICE_TAG_PATTERN.sub("", text).strip()


class OverlapPipeline:
    def __init__(self, model_dir: str, asr_url: str):
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.separator = SepformerSeparation.from_hparams(
            source="speechbrain/sepformer-whamr16k",
            savedir=model_dir,
            run_opts={"device": self.device},
            local_strategy=LocalStrategy.COPY,
        )
        self.asr_url = asr_url
        self.separation_lock = Lock()

    def separate_and_transcribe(self, pcm: bytes):
        audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        waveform = torch.from_numpy(audio).unsqueeze(0).to(self.device)
        started = time.perf_counter()
        with self.separation_lock, torch.inference_mode():
            separated = self.separator.separate_batch(waveform)[0].detach().float().cpu().numpy()
        separation_ms = (time.perf_counter() - started) * 1000

        pcm_sources = []
        for index in range(2):
            samples = np.clip(separated[:, index], -1.0, 1.0)
            pcm_sources.append((samples * 32767.0).astype("<i2").tobytes())
        with ThreadPoolExecutor(max_workers=2) as executor:
            transcribed = list(executor.map(
                lambda item: self.transcribe(item[1], f"overlap-{item[0] + 1}"),
                enumerate(pcm_sources),
            ))
        sources = []
        for index, source_pcm in enumerate(pcm_sources):
            text, asr_ms = transcribed[index]
            sources.append({
                "index": index,
                "audio_base64": base64.b64encode(source_pcm).decode("ascii"),
                "text": text,
                "asr_ms": asr_ms,
            })
        return {"sources": sources, "device": self.device, "separation_ms": separation_ms}

    def transcribe(self, pcm: bytes, stream_name: str):
        started = time.perf_counter()
        start_message = {
            "mode": "2pass",
            "wav_name": stream_name,
            "is_speaking": True,
            "audio_fs": 16000,
            "chunk_size": [5, 10, 5],
            "chunk_interval": 10,
        }
        final_text = ""
        with connect(self.asr_url, subprotocols=["binary"], open_timeout=10, max_queue=None) as socket:
            socket.send(json.dumps(start_message, ensure_ascii=False))
            for offset in range(0, len(pcm), 3200):
                socket.send(pcm[offset:offset + 3200])
            socket.send(json.dumps({"is_speaking": False, "is_end": True}))
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                response = json.loads(socket.recv(timeout=max(1, deadline - time.monotonic())))
                if response.get("text") and response.get("is_final"):
                    final_text = clean_transcription(response["text"])
                if response.get("is_end"):
                    break
        return final_text, (time.perf_counter() - started) * 1000


PIPELINE = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        return

    def do_GET(self):
        if self.path == "/health":
            self.reply({"status": "ok", "device": PIPELINE.device})
            return
        self.send_error(404)

    def do_POST(self):
        if self.path != "/separate-and-transcribe":
            self.send_error(404)
            return
        pcm = self.rfile.read(int(self.headers["Content-Length"]))
        result = PIPELINE.separate_and_transcribe(pcm)
        self.reply(result)

    def reply(self, value):
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    global PIPELINE
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8001)
    parser.add_argument("--asr-url", default="ws://127.0.0.1:10095")
    parser.add_argument("--model-dir", default="server/artifacts/sepformer-whamr16k")
    args = parser.parse_args()
    PIPELINE = OverlapPipeline(args.model_dir, args.asr_url)
    print(f"Overlap separation ready on http://{args.host}:{args.port} ({PIPELINE.device})", flush=True)
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
