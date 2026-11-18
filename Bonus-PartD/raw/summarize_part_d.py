#!/usr/bin/env python3
"""Create a CSV summary and Overleaf table from Part D raw CSV logs.

Python 3, standard library only. Example from the Bonus-PartD folder:
  python summarize_part_d.py --input raw --pattern "lite0_gpu_*.csv" --output analysis

Duration is max(elapsed_ms)/1000, including startup through the last logged row.
Counters are run-relative: do NOT subtract their first logged values.
Received FPS uses the largest logged stream sequence number, which can miss
frames received after the selected final JPEG. No stop-time record is available.
Frame age is receiver receipt to processing completion, not capture-to-display.
Combined rates pool counts/time; combined latencies pool successful frames.
The graph's unweighted mean of per-run FPS is printed separately.
Select one model/backend/scenario at a time. This CSV schema does not identify
model or backend; distinguish them using run names or separate folders.
"""

import argparse
import csv
import math
import statistics
import sys
from pathlib import Path


REQUIRED = {
    "elapsed_ms", "stream_frame_number", "cumulative_rx_bytes", "inference_ms",
    "frame_age_ms", "interarrival_ms", "success",
}


def number(row, key):
    value = float(row[key])
    if not math.isfinite(value) or value < 0:
        raise ValueError(f"{key} must be finite and nonnegative: {row[key]!r}")
    return value


def summarize(path, run):
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        missing = REQUIRED - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"not a Part D raw log; missing {', '.join(sorted(missing))}")
        rows = [row for row in reader if any(v for v in row.values())]
    if not rows:
        raise ValueError("empty log")
    flags = [row["success"].strip().lower() for row in rows]
    if any(flag not in {"true", "false", "1", "0"} for flag in flags):
        raise ValueError("unrecognized success flag")
    good = [row for row, flag in zip(rows, flags) if flag in {"true", "1"}]
    seconds = max(number(row, "elapsed_ms") for row in rows) / 1000
    if seconds <= 0 or not good:
        raise ValueError("positive duration and successful frames are required")
    count = len(good)
    received = max(number(row, "stream_frame_number") for row in rows)
    rx = max(number(row, "cumulative_rx_bytes") for row in rows)
    ai_sum = sum(number(row, "inference_ms") for row in good)
    age_sum = sum(number(row, "frame_age_ms") for row in good)
    stalls = sum(number(row, "interarrival_ms") > 1000 for row in good)
    if "processed_frame_number" in rows[0]:
        logged_count = max(number(row, "processed_frame_number") for row in rows)
        if logged_count != count:
            print(f"WARNING {path.name}: successful rows ({count}) differ from the "
                  f"processed counter ({logged_count:g}); inspect for missing/duplicate rows.",
                  file=sys.stderr)
    if rx == 0:
        print(f"WARNING {path.name}: RX counter is zero; traffic measurement may be "
              "unsupported. Do not interpret this as a zero-bandwidth stream.", file=sys.stderr)
    return {
        "run": f"Run {run}", "file": str(path), "duration_s": seconds,
        "processed_frames": count, "received_frames_logged": received,
        "processed_fps": count / seconds, "received_fps_logged": received / seconds,
        "mean_ai_ms": ai_sum / count, "mean_frame_age_ms": age_sum / count,
        "stalls": stalls, "stalls_per_min": stalls * 60 / seconds,
        "downlink_mbps": rx * 8 / seconds / 1_000_000,
        "failed_rows": len(rows) - count,
        "_rx": rx, "_ai": ai_sum, "_age": age_sum,
    }


def combined(runs):
    total = {key: sum(r[key] for r in runs) for key in (
        "duration_s", "processed_frames", "received_frames_logged", "stalls",
        "failed_rows", "_rx", "_ai", "_age",
    )}
    seconds, count = total["duration_s"], total["processed_frames"]
    total.update(
        run="Combined", file="All selected runs",
        processed_fps=count / seconds,
        received_fps_logged=total["received_frames_logged"] / seconds,
        mean_ai_ms=total["_ai"] / count,
        mean_frame_age_ms=total["_age"] / count,
        stalls_per_min=total["stalls"] * 60 / seconds,
        downlink_mbps=total["_rx"] * 8 / seconds / 1_000_000,
    )
    return total


def table(runs, total):
    lines = [
        r"% Requires \usepackage{booktabs} and \usepackage{graphicx}.",
        r"% Run-to-filename mapping is in part_d_summary.csv.",
        r"\begin{table}[t]",
        r"\caption{Part D results for the selected runs. Combined rates pool counts and duration; combined latencies pool successful frames.}",
        r"\label{tab:partd_validation}",
        r"\centering",
        r"\resizebox{\columnwidth}{!}{%",
        r"\begin{tabular}{lrrrrrr}",
        r"\toprule",
        r"Run & Proc. FPS & Recv. FPS & AI (ms) & Frame age (ms) & Stalls & DL (Mbps) \\",
        r"\midrule",
    ]
    for r in [*runs, total]:
        if r is total:
            lines.append(r"\midrule")
        lines.append(
            f"{r['run']} & {r['processed_fps']:.2f} & {r['received_fps_logged']:.2f} & "
            f"{r['mean_ai_ms']:.2f} & {r['mean_frame_age_ms']:.2f} & "
            f"{r['stalls']:.0f} & {r['downlink_mbps']:.3f} " + r"\\"
        )
    lines += [r"\bottomrule", r"\end{tabular}}", r"\end{table}"]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", type=Path, required=True, help="Raw CSV directory; searched recursively")
    parser.add_argument("--pattern", default="*.csv", help="Filename glob for one experiment group")
    parser.add_argument("--output", type=Path, default=Path("analysis"))
    args = parser.parse_args()
    if not args.input.is_dir():
        parser.error(f"input directory does not exist: {args.input}")
    paths = sorted(p for p in args.input.rglob(args.pattern) if p.is_file())
    if not paths:
        parser.error(f"no files match {args.pattern!r} in {args.input}")
    runs = []
    for i, path in enumerate(paths, 1):
        try:
            runs.append(summarize(path, i))
        except (ValueError, KeyError, OSError, csv.Error) as error:
            parser.error(f"{path}: {error}")
    total = combined(runs)
    args.output.mkdir(parents=True, exist_ok=True)
    fields = [key for key in runs[0] if not key.startswith("_")]
    summary = args.output / "part_d_summary.csv"
    with summary.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows([*runs, total])
    latex = args.output / "part_d_table.tex"
    latex.write_text(table(runs, total), encoding="utf-8")
    print(f"Selected {len(runs)} runs:")
    for r in runs:
        print(f"  {r['run']}: {r['file']} ({r['duration_s']:.3f} s)")
    print(f"Pooled processed FPS: {total['processed_fps']:.4f}")
    print(f"Unweighted mean per-run FPS (for matching the existing graph): "
          f"{statistics.mean(r['processed_fps'] for r in runs):.4f}")
    print(f"Saved {summary} and {latex}")
    print("Received FPS uses logged sequence snapshots; duration ends at the final logged row.")
    print("Update the paper's nearby run count, duration, FPS, and latency claims to match these files.")


if __name__ == "__main__":
    main()
