"""Summarize an exported Android CSV without third-party dependencies."""
import argparse
import csv
import statistics
from collections import defaultdict


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * fraction)]


def main(path):
    groups = defaultdict(list)
    with open(path, encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            groups[(row["requested"], row["partition"], row["width"], row["height"], row["bandwidth_limit_kbps"])].append(row)
    print("| 请求模式 | 实际切分 | 输入尺寸 | 上行限速 kbps | 样本数 | 采集到跟踪中位数 ms | P95 ms | 上行中位数 B | 下行中位数 B |")
    print("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for (requested, partition, width, height, limit), rows in sorted(groups.items()):
        latency = [float(r["frame_to_track_ms"]) for r in rows]
        up = [int(r["upload_bytes"]) for r in rows]
        down = [int(r["download_bytes"]) for r in rows]
        print(f"| {requested} | {partition} | {width}×{height} | {limit} | {len(rows)} | {statistics.median(latency):.1f} | {percentile(latency, .95):.1f} | {statistics.median(up):.0f} | {statistics.median(down):.0f} |")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_file")
    main(parser.parse_args().csv_file)
