"""Computer-side YuNet/SFace stages. Run with Python and opencv-contrib-python."""
import argparse
import base64
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock

import cv2
import numpy as np

MODELS = Path(__file__).resolve().parents[1] / "app/src/main/assets/models"
detector = cv2.FaceDetectorYN.create(str(MODELS / "face_detection_yunet_2023mar.onnx"), "", (320, 320), 0.72, 0.3, 50)
recognizer = cv2.FaceRecognizerSF.create(str(MODELS / "face_recognition_sface_2021dec.onnx"), "")
lock = Lock()


def decode(data):
    return cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)


def elapsed(start):
    return (time.perf_counter() - start) * 1000


def process_frame(data):
    image = decode(data)
    start = time.perf_counter()
    detector.setInputSize((image.shape[1], image.shape[0]))
    _, faces = detector.detect(image)
    detect_ms = elapsed(start)
    align_ms = feature_ms = 0.0
    results = []
    for face in [] if faces is None else faces:
        start = time.perf_counter()
        aligned = recognizer.alignCrop(image, face)
        align_ms += elapsed(start)
        start = time.perf_counter()
        embedding = recognizer.feature(aligned).flatten().tolist()
        feature_ms += elapsed(start)
        x, y, w, h = [float(v) for v in face[:4]]
        results.append({"bounds": [x, y, x + w, y + h], "embedding": embedding})
    return {"faces": results, "detect_ms": detect_ms, "align_ms": align_ms, "feature_ms": feature_ms}


def process_aligned(data):
    results = []
    start = time.perf_counter()
    for face in json.loads(data)["faces"]:
        image = decode(base64.b64decode(face["image"]))
        results.append({"bounds": face["bounds"], "embedding": recognizer.feature(image).flatten().tolist()})
    return {"faces": results, "detect_ms": 0, "align_ms": 0, "feature_ms": elapsed(start)}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        return

    def do_GET(self):
        if self.path == "/health":
            self.reply({"status": "ok", "opencv": cv2.__version__})
        elif self.path == "/probe":
            self.send_response(200)
            self.send_header("Content-Length", "65536")
            self.end_headers()
            self.wfile.write(bytes(65536))
        else:
            self.send_error(404)

    def do_POST(self):
        data = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path == "/probe":
            self.reply({"received": len(data)})
            return
        with lock:
            if self.path == "/frame":
                result = process_frame(data)
            elif self.path == "/aligned":
                result = process_aligned(data)
            else:
                self.send_error(404)
                return
        self.reply(result)

    def reply(self, value):
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    print(f"MEC server listening on {args.host}:{args.port}", flush=True)
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
