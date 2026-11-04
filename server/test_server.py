import base64
import json
import unittest

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


if __name__ == "__main__":
    unittest.main()
