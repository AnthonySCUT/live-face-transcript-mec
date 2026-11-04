import base64
import json
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer

import cv2
import numpy as np

import server


class PipelineTest(unittest.TestCase):
    def test_frame_and_aligned_produce_equivalent_embeddings(self):
        image = cv2.imread(str(server.MODELS.parent / "test" / "lena.jpg"))
        _, encoded = cv2.imencode(".jpg", image)
        decoded = server.decode(encoded.tobytes())
        server.detector.setInputSize((decoded.shape[1], decoded.shape[0]))
        _, faces = server.detector.detect(decoded)
        aligned = server.recognizer.alignCrop(decoded, faces[0])
        _, png = cv2.imencode(".png", aligned)
        payload = json.dumps({"faces": [{"bounds": [0, 0, 1, 1], "image": base64.b64encode(png).decode()}]}).encode()
        full = server.process_frame(encoded.tobytes())["faces"][0]["embedding"]
        split = server.process_aligned(payload)["faces"][0]["embedding"]
        np.testing.assert_allclose(full, split, rtol=1e-4, atol=1e-4)

    def test_empty_aligned_batch(self):
        self.assertEqual([], server.process_aligned(b'{"faces":[]}')["faces"])


class HttpIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.http.server_port}"
        cls.frame = (server.MODELS.parent / "test" / "lena.jpg").read_bytes()

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()
        cls.thread.join()

    def post(self, path, body):
        request = urllib.request.Request(self.base + path, data=body, method="POST")
        with urllib.request.urlopen(request) as response:
            return json.loads(response.read())

    def test_health_and_probe_routes(self):
        with urllib.request.urlopen(self.base + "/health") as response:
            self.assertEqual("ok", json.load(response)["status"])
        with urllib.request.urlopen(self.base + "/probe") as response:
            self.assertEqual(65536, len(response.read()))

    def test_frame_and_aligned_routes_return_same_embedding(self):
        full = self.post("/frame", self.frame)["faces"][0]
        image = server.decode(self.frame)
        server.detector.setInputSize((image.shape[1], image.shape[0]))
        _, faces = server.detector.detect(image)
        aligned = server.recognizer.alignCrop(image, faces[0])
        _, encoded = cv2.imencode(".png", aligned)
        payload = json.dumps({"faces": [{
            "bounds": full["bounds"],
            "image": base64.b64encode(encoded.tobytes()).decode(),
        }]}).encode()
        split = self.post("/aligned", payload)["faces"][0]
        self.assertEqual(128, len(full["embedding"]))
        np.testing.assert_allclose(full["embedding"], split["embedding"], rtol=1e-4, atol=1e-4)


if __name__ == "__main__":
    unittest.main()
