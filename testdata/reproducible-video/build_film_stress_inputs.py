"""Create compact, repeatable film-derived visual stress inputs for Android replay."""

from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np


WIDTH, HEIGHT, OUTPUT_FPS, DURATION_SECONDS = 640, 360, 10, 20
FRAMES = OUTPUT_FPS * DURATION_SECONDS


def fit_frame(frame: np.ndarray, width: int, height: int) -> np.ndarray:
    scale = min(width / frame.shape[1], height / frame.shape[0])
    resized = cv2.resize(frame, (round(frame.shape[1] * scale), round(frame.shape[0] * scale)))
    canvas = np.zeros((height, width, 3), dtype=np.uint8)
    x = (width - resized.shape[1]) // 2
    y = (height - resized.shape[0]) // 2
    canvas[y:y + resized.shape[0], x:x + resized.shape[1]] = resized
    return canvas


def frame_at(capture: cv2.VideoCapture, second: float) -> np.ndarray:
    capture.set(cv2.CAP_PROP_POS_MSEC, second * 1000)
    ok, frame = capture.read()
    if not ok:
        raise RuntimeError(f"Could not decode source frame at {second:.1f}s")
    return frame


def writer(path: Path) -> cv2.VideoWriter:
    result = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), OUTPUT_FPS, (WIDTH, HEIGHT))
    if not result.isOpened():
        raise RuntimeError(f"Could not create {path}")
    return result


def build_dialogue(capture: cv2.VideoCapture, start_second: float, output: Path) -> None:
    source_fps = capture.get(cv2.CAP_PROP_FPS)
    capture.set(cv2.CAP_PROP_POS_MSEC, start_second * 1000)
    stride = max(1, round(source_fps / OUTPUT_FPS))
    video = writer(output)
    try:
        for _ in range(FRAMES):
            ok, frame = capture.read()
            if not ok:
                raise RuntimeError(f"Dialogue clip ended before {DURATION_SECONDS}s")
            video.write(fit_frame(frame, WIDTH, HEIGHT))
            for _ in range(stride - 1):
                capture.grab()
    finally:
        video.release()


def crowd_loop(capture: cv2.VideoCapture, start_second: float) -> list[np.ndarray]:
    return [frame_at(capture, start_second + index / OUTPUT_FPS) for index in range(2 * OUTPUT_FPS)]


def build_crowd(frames: list[np.ndarray], mask_boxes: list[tuple[int, int, int, int]], output: Path, masked: bool) -> None:
    video = writer(output)
    try:
        for frame_index in range(FRAMES):
            frame = frames[frame_index % len(frames)].copy()
            second = frame_index / OUTPUT_FPS
            if masked and 5 <= second < 10:
                for x, y, width, height in mask_boxes:
                    cv2.rectangle(frame, (x - 4, y - 4), (x + width + 4, y + height + 4), (0, 0, 0), -1)
            video.write(frame)
    finally:
        video.release()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--dialogue-start", type=float, required=True)
    parser.add_argument("--crowd-start", type=float, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    capture = cv2.VideoCapture(str(args.source))
    if not capture.isOpened():
        raise RuntimeError(f"Could not open {args.source}")
    try:
        dialogue = args.output_dir / "his-girl-friday-dialogue-20s.mp4"
        crowd = args.output_dir / "his-girl-friday-crowd-20s.mp4"
        occlusion = args.output_dir / "his-girl-friday-occlusion-20s.mp4"
        build_dialogue(capture, args.dialogue_start, dialogue)
        frames = crowd_loop(capture, args.crowd_start)
        model = Path(__file__).resolve().parents[2] / "app/src/main/assets/models/face_detection_yunet_2023mar.onnx"
        detector = cv2.FaceDetectorYN.create(str(model), "", (WIDTH, HEIGHT), 0.72, 0.3, 50)
        detector.setInputSize((frames[10].shape[1], frames[10].shape[0]))
        _, faces = detector.detect(frames[10])
        ordered_faces = sorted(faces, key=lambda face: face[2] * face[3], reverse=True)
        mask_boxes = [tuple(int(value) for value in face[:4]) for face in ordered_faces[:2]]
        print(f"Face count at crowd source time {args.crowd_start + 1:.1f}s: {len(ordered_faces)}")
        build_crowd(frames, mask_boxes, crowd, masked=False)
        build_crowd(frames, mask_boxes, occlusion, masked=True)
    finally:
        capture.release()

    print("Created film stress assets:")
    for path in (dialogue, crowd, occlusion):
        print(f"{path.name}\t{path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
