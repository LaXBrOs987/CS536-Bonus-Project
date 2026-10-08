import argparse
import csv
import math
from pathlib import Path
from statistics import mean

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


COLORS = {"C": "#355C9A", "D": "#008A83"}


def load_runs(folder, pattern, part):
    files = sorted(folder.glob(pattern))
    if not files:
        raise ValueError(f"No files match: {folder / pattern}")

    fields = ["elapsed_ms", "inference_ms"]
    fields += (
        ["fetch_ms", "total_ms"]
        if part == "C"
        else ["processing_ms"]
    )

    runs = []

    for path in files:
        with path.open(newline="", encoding="utf-8-sig") as file:
            reader = csv.DictReader(file)
            missing = set(fields + ["success"]) - set(reader.fieldnames or [])
            if missing:
                raise ValueError(
                    f"{path.name}: missing columns {sorted(missing)}"
                )
            rows = list(reader)

        if not rows:
            raise ValueError(f"{path.name}: empty file")

        for row in rows:
            for field in fields:
                row[field] = float(row[field])
                if not math.isfinite(row[field]) or row[field] < 0:
                    raise ValueError(f"{path.name}: invalid {field}")

            if row["success"].strip().lower() not in ["true", "false"]:
                raise ValueError(f"{path.name}: invalid success flag")

        elapsed = [row["elapsed_ms"] for row in rows]
        if elapsed[-1] <= 0 or any(
            b <= a for a, b in zip(elapsed, elapsed[1:])
        ):
            raise ValueError(f"{path.name}: elapsed_ms must increase")

        good = [
            row for row in rows
            if row["success"].strip().lower() == "true"
        ]
        if not good:
            raise ValueError(f"{path.name}: no successful frames")

        for row in good:
            processing = (
                row["total_ms"] - row["fetch_ms"]
                if part == "C"
                else row["processing_ms"]
            )
            row["overhead_ms"] = processing - row["inference_ms"]

            if row["overhead_ms"] < 0:
                raise ValueError(f"{path.name}: inconsistent timing values")

        duration = elapsed[-1] / 1000

        runs.append({
            "name": path.name,
            "part": part,
            "rows": good,
            "duration": duration,
            "fps": len(good) / duration,
        })

    return runs


def save(fig, output, filename, title, note):
    fig.suptitle(
        title, fontsize=20, fontweight="bold",
        x=0.08, ha="left", y=0.98,
    )
    fig.text(
        0.08, 0.025, note,
        fontsize=10, color="#526174", va="bottom",
    )
    fig.tight_layout(rect=[0, 0.1, 1, 0.91])
    fig.savefig(output / filename, dpi=250, facecolor="white")
    plt.close(fig)
    print(f"Saved: {output / filename}")


def make_plots(groups, output, window):
    plt.rcParams.update({
        "font.size": 12,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.edgecolor": "#CBD5E1",
        "text.color": "#263449",
        "axes.labelcolor": "#263449",
        "grid.color": "#E6EBF1",
    })

    parts = ["C", "D"]
    positions = [0, 1]
    labels = [
        f"Part C baseline\n{len(groups['C'])} runs",
        f"Part D\n{len(groups['D'])} runs",
    ]
    colors = [COLORS[part] for part in parts]

    # 1. Average FPS with individual run values.
    fig, ax = plt.subplots(figsize=(10, 6))
    averages = [
        mean(run["fps"] for run in groups[part])
        for part in parts
    ]
    ax.bar(
        positions, averages, width=0.5,
        color=colors, alpha=0.75,
    )

    for i, part in enumerate(parts):
        values = [run["fps"] for run in groups[part]]
        offsets = (
            [0] if len(values) == 1 else
            [-0.14 + 0.28 * j / (len(values) - 1)
             for j in range(len(values))]
        )
        ax.scatter(
            [i + offset for offset in offsets],
            values, color="#182B42", s=40, zorder=3,
        )
        ax.text(
            i, max(values) + 0.2,
            f"{averages[i]:.2f} FPS",
            ha="center", fontweight="bold",
        )

    ax.set(
        xticks=positions, xticklabels=labels,
        ylabel="Successful processed frames / second",
    )
    ax.set_ylim(
        0,
        max(run["fps"] for runs in groups.values() for run in runs) * 1.2,
    )
    ax.grid(axis="y")
    ax.set_axisbelow(True)

    save(
        fig, output, "01_fps_comparison.png",
        "Part C vs Part D | Processing rate",
        "Bars: unweighted mean across runs. Dots: individual runs. "
        "Duration includes startup.",
    )

    # 2. FPS over time using complete windows.
    fig, ax = plt.subplots(figsize=(12, 6))

    for part in parts:
        series = []

        for run in groups[part]:
            count = int(run["duration"] // window)
            counts = [0] * count

            for row in run["rows"]:
                index = max(
                    0,
                    math.ceil(row["elapsed_ms"] / 1000 / window) - 1,
                )
                if index < count:
                    counts[index] += 1

            series.append([count / window for count in counts])

        times, averages, low, high = [], [], [], []

        for i in range(max(map(len, series))):
            values = [s[i] for s in series if i < len(s)]
            times.append((i + 0.5) * window)
            averages.append(mean(values))
            low.append(min(values))
            high.append(max(values))

        ax.plot(
            times, averages, color=COLORS[part],
            linewidth=2.8,
            label=f"Part {part} ({len(groups[part])} runs)",
        )
        ax.fill_between(
            times, low, high, color=COLORS[part], alpha=0.12
        )

    ax.set(
        xlabel="Elapsed time (seconds)",
        ylabel="Successful processed frames / second",
        ylim=(0, None),
    )
    ax.grid(alpha=0.8)
    ax.legend(frameon=False)

    save(
        fig, output, "02_fps_timeline.png",
        f"Part C vs Part D | FPS in {window:g}-second windows",
        "Lines: mean of active runs. Shading: min–max across runs.\n"
        "Incomplete final windows omitted; fewer runs contribute "
        "after shorter recordings end.",
    )

    # 3. Inference-time distributions.
    fig, ax = plt.subplots(figsize=(10, 7))
    values = [
        [
            row["inference_ms"]
            for run in groups[part]
            for row in run["rows"]
        ]
        for part in parts
    ]

    boxes = ax.boxplot(
        values, positions=positions, widths=0.45,
        patch_artist=True, whis=(5, 95), showfliers=False,
        medianprops={"color": "#182B42", "linewidth": 2},
    )
    for box, color in zip(boxes["boxes"], colors):
        box.set(facecolor=color, alpha=0.7)

    ax.set(
        xticks=positions, xticklabels=labels,
        ylabel="Inference duration (milliseconds)",
    )
    ax.grid(axis="y")
    ax.set_axisbelow(True)

    save(
        fig, output, "03_inference_distribution.png",
        "Part C vs Part D | Inference duration",
        "Box: middle 50%. Line: median. Whiskers: 5th–95th percentile.\n"
        "Successful frames pooled per part; longer runs contribute "
        "more frames. Extremes are not drawn.",
    )

    # 4. Processing work, excluding fetch/receive.
    fig, ax = plt.subplots(figsize=(10, 6))
    inference = [
        mean(
            mean(row["inference_ms"] for row in run["rows"])
            for run in groups[part]
        )
        for part in parts
    ]
    overhead = [
        mean(
            mean(row["overhead_ms"] for row in run["rows"])
            for run in groups[part]
        )
        for part in parts
    ]

    ax.bar(
        positions, inference, width=0.5,
        color="#355C9A", label="Inference",
    )
    ax.bar(
        positions, overhead, bottom=inference, width=0.5,
        color="#E9AD55", label="Other processing overhead",
    )

    totals = [a + b for a, b in zip(inference, overhead)]
    for i, total in enumerate(totals):
        ax.text(
            i, total + 2, f"{total:.1f} ms",
            ha="center", fontweight="bold",
        )

    ax.set(
        xticks=positions, xticklabels=labels,
        ylabel="Mean duration (milliseconds)",
    )
    ax.set_ylim(0, max(totals) * 1.2)
    ax.grid(axis="y")
    ax.set_axisbelow(True)
    ax.legend(frameon=False)

    save(
        fig, output, "04_processing_breakdown.png",
        "Part C vs Part D | Processing work",
        "Unweighted mean across runs. Part C: total_ms − fetch_ms. "
        "Part D: processing_ms.\n"
        "Fetching/receiving excluded; this is not end-to-end latency.",
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--c-input", type=Path, default=Path("../part-c/raw")
    )
    parser.add_argument("--d-input", type=Path, default=Path("./raw"))
    parser.add_argument(
        "--c-pattern", default="hotspot_stationary_*.csv"
    )
    parser.add_argument(
        "--d-pattern", default="hotspot_stationary_new_*.csv"
    )
    parser.add_argument(
        "--output", type=Path,
        default=Path("./analysis/part_c_vs_d"),
    )
    parser.add_argument("--window", type=float, default=5)
    args = parser.parse_args()

    if not math.isfinite(args.window) or args.window <= 0:
        parser.error("--window must be positive")

    groups = {
        "C": load_runs(args.c_input, args.c_pattern, "C"),
        "D": load_runs(args.d_input, args.d_pattern, "D"),
    }

    args.output.mkdir(parents=True, exist_ok=True)

    for part, runs in groups.items():
        for run in runs:
            print(f"Part {part} | {run['name']} | {run['fps']:.2f} FPS")

    make_plots(groups, args.output, args.window)


if __name__ == "__main__":
    main()