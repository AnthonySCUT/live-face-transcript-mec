from __future__ import annotations

import argparse
import csv
import io
import os
import statistics
import subprocess
from pathlib import Path


def adb_path() -> Path:
    sdk = Path(os.environ.get("ANDROID_HOME", Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"))
    candidate = sdk / "platform-tools/adb.exe"
    if candidate.exists():
        return candidate
    workspace = Path(__file__).resolve().parents[3]
    return workspace / "work/android-sdk/platform-tools/adb.exe"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--rows", type=int, default=72)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    result = subprocess.run(
        [str(adb_path()), "-s", args.serial, "exec-out", "run-as", "edu.scut.livetranscript",
         "cat", "files/reproducible-experiments.csv"],
        check=True,
        capture_output=True,
    )
    reader = csv.DictReader(io.StringIO(result.stdout.decode("utf-8")))
    rows = list(reader)
    latest = rows[-args.rows:]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=reader.fieldnames)
        writer.writeheader()
        writer.writerows(latest)

    groups: dict[tuple[str, str, str], list[float]] = {}
    for row in latest:
        if row["status"] != "ok" or row["task"] != "face":
            continue
        key = (row["scenario"], row["requested_mode"], row["bandwidth_limit_kbps"])
        groups.setdefault(key, []).append(float(row["total_ms"]))
    print(f"Saved {len(latest)} newest replay rows to {args.output}")
    for key, values in sorted(groups.items()):
        print(f"{key[0]} | requested={key[1]} | limit={key[2]} | n={len(values)} | median_ms={statistics.median(values):.1f}")
    dynamic = [row for row in latest if row["scenario"].startswith("dynamic_") and row["status"] == "ok"]
    selected = ", ".join(f"{mode}:{sum(row['selected_mode'] == mode for row in dynamic)}" for mode in ("LOCAL", "ALIGNED", "REMOTE"))
    print(f"Dynamic selected modes: {selected}")
    print(f"Dynamic transfer totals: upload={sum(int(row['upload_bytes'] or 0) for row in dynamic)} B, download={sum(int(row['download_bytes'] or 0) for row in dynamic)} B")


if __name__ == "__main__":
    main()
