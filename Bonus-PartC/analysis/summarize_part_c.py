from pathlib import Path
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "raw"
RESULTS = ROOT / "results"
FIGURES = ROOT / "figures"
RESULTS.mkdir(exist_ok=True)
FIGURES.mkdir(exist_ok=True)

SCENARIOS = {
    "hotspot_stationary_": "Hotspot stationary",
    "hotspot_walking_": "Hotspot walking",
    "campus_wifi_walking_": "PAL3.0 walking",
    "cellular_5g_walking_": "5G walking",
}

def scenario_for(name):
    for prefix, label in SCENARIOS.items():
        if name.startswith(prefix):
            return label
    return None

def safe_numeric(series):
    return pd.to_numeric(series, errors="coerce")

run_rows = []
all_frames = []

for path in sorted(RAW.glob("*.csv")):
    scenario = scenario_for(path.name)
    if scenario is None:
        continue

    df = pd.read_csv(path)
    if df.empty:
        continue

    df["scenario"] = scenario
    df["run"] = path.name

    for c in [
        "elapsed_ms", "frame_bytes", "cumulative_rx_bytes", "cumulative_tx_bytes",
        "fetch_ms", "inference_ms", "total_ms", "interarrival_ms",
        "detections", "avg_confidence"
    ]:
        if c in df.columns:
            df[c] = safe_numeric(df[c])

    success = df["success"].astype(str).str.lower().eq("true")
    good = df[success].copy()

    duration_s = df["elapsed_ms"].max() / 1000.0
    successful = int(success.sum())
    failed = int((~success).sum())
    attempts = successful + failed

    rx_bytes = df["cumulative_rx_bytes"].max() if "cumulative_rx_bytes" in df else np.nan
    tx_bytes = df["cumulative_tx_bytes"].max() if "cumulative_tx_bytes" in df else np.nan

    stalls = int((good["interarrival_ms"] > 1000).sum()) if "interarrival_ms" in good else 0
    positive_interarrival = good.loc[good["interarrival_ms"] > 0, "interarrival_ms"]

    detected = good["detections"] > 0 if "detections" in good else pd.Series(False, index=good.index)
    confidence = good.loc[detected, "avg_confidence"] if "avg_confidence" in good else pd.Series(dtype=float)

    run_rows.append({
        "run": path.name,
        "scenario": scenario,
        "duration_s": duration_s,
        "successful_frames": successful,
        "failed_frames": failed,
        "failure_rate_pct": 100 * failed / attempts if attempts else 0,
        "effective_fps": successful / duration_s if duration_s else 0,
        "payload_mbps": (good["frame_bytes"].sum() * 8 / duration_s / 1e6) if duration_s else 0,
        "downlink_mbps": (rx_bytes * 8 / duration_s / 1e6) if duration_s and pd.notna(rx_bytes) else np.nan,
        "uplink_mbps": (tx_bytes * 8 / duration_s / 1e6) if duration_s and pd.notna(tx_bytes) else np.nan,
        "stalls": stalls,
        "jitter_ms": positive_interarrival.std(ddof=1) if len(positive_interarrival) > 1 else np.nan,
        "fetch_mean_ms": good["fetch_ms"].mean(),
        "fetch_median_ms": good["fetch_ms"].median(),
        "fetch_p95_ms": good["fetch_ms"].quantile(.95),
        "fetch_max_ms": good["fetch_ms"].max(),
        "ai_mean_ms": good["inference_ms"].mean(),
        "ai_median_ms": good["inference_ms"].median(),
        "ai_p95_ms": good["inference_ms"].quantile(.95),
        "ai_max_ms": good["inference_ms"].max(),
        "total_mean_ms": good["total_ms"].mean(),
        "total_median_ms": good["total_ms"].median(),
        "total_p95_ms": good["total_ms"].quantile(.95),
        "total_max_ms": good["total_ms"].max(),
        "frames_with_object": int(detected.sum()),
        "detection_rate_pct": 100 * detected.mean() if len(good) else 0,
        "mean_objects_per_frame": good["detections"].mean() if len(good) else np.nan,
        "mean_confidence": confidence.mean() if len(confidence) else np.nan,
    })

    all_frames.append(df)

runs = pd.DataFrame(run_rows)
runs.to_csv(RESULTS / "run_summary.csv", index=False, float_format="%.3f")

scenario_rows = []
for scenario, group in runs.groupby("scenario"):
    duration_s = group["duration_s"].sum()
    successes = group["successful_frames"].sum()
    failures = group["failed_frames"].sum()
    attempts = successes + failures

    # Use raw frames for scenario-level latency and detection statistics.
    raw = pd.concat(all_frames, ignore_index=True)
    raw = raw[raw["scenario"] == scenario].copy()
    success_mask = raw["success"].astype(str).str.lower().eq("true")
    good = raw[success_mask]
    detected = good["detections"] > 0

    scenario_rows.append({
        "scenario": scenario,
        "runs": len(group),
        "duration_min": duration_s / 60,
        "successful_frames": int(successes),
        "failed_frames": int(failures),
        "failure_rate_pct": 100 * failures / attempts if attempts else 0,
        "effective_fps": successes / duration_s if duration_s else 0,
        "payload_mbps": good["frame_bytes"].sum() * 8 / duration_s / 1e6,
        "downlink_mbps": np.average(group["downlink_mbps"], weights=group["duration_s"]),
        "uplink_mbps": np.average(group["uplink_mbps"], weights=group["duration_s"]),
        "stalls": int(group["stalls"].sum()),
        "stalls_per_min": group["stalls"].sum() / (duration_s / 60),
        "jitter_ms": good.loc[good["interarrival_ms"] > 0, "interarrival_ms"].std(ddof=1),
        "fetch_mean_ms": good["fetch_ms"].mean(),
        "fetch_median_ms": good["fetch_ms"].median(),
        "fetch_p95_ms": good["fetch_ms"].quantile(.95),
        "ai_mean_ms": good["inference_ms"].mean(),
        "ai_median_ms": good["inference_ms"].median(),
        "ai_p95_ms": good["inference_ms"].quantile(.95),
        "total_mean_ms": good["total_ms"].mean(),
        "total_median_ms": good["total_ms"].median(),
        "total_p95_ms": good["total_ms"].quantile(.95),
        "detection_rate_pct": 100 * detected.mean(),
        "mean_objects_per_frame": good["detections"].mean(),
        "mean_confidence": good.loc[detected, "avg_confidence"].mean(),
    })

summary = pd.DataFrame(scenario_rows)
order = ["Hotspot stationary", "Hotspot walking", "PAL3.0 walking", "5G walking"]
summary["_order"] = summary["scenario"].map({v: i for i, v in enumerate(order)})
summary = summary.sort_values("_order").drop(columns="_order")
summary.to_csv(RESULTS / "scenario_summary.csv", index=False, float_format="%.3f")

labels = summary["scenario"].tolist()
x = np.arange(len(labels))

plt.figure(figsize=(8, 5))
w = .36
plt.bar(x - w/2, summary["downlink_mbps"], width=w, label="Downlink")
plt.bar(x + w/2, summary["uplink_mbps"], width=w, label="Uplink")
plt.xticks(x, labels, rotation=20, ha="right")
plt.ylabel("Throughput (Mbps)")
plt.title("Part C Throughput by Scenario")
plt.legend()
plt.tight_layout()
plt.savefig(FIGURES / "throughput_by_scenario.png", dpi=180)
plt.close()

plt.figure(figsize=(8, 5))
w = .24
plt.bar(x - w, summary["fetch_mean_ms"], width=w, label="Network fetch")
plt.bar(x, summary["ai_mean_ms"], width=w, label="AI inference")
plt.bar(x + w, summary["total_mean_ms"], width=w, label="Total")
plt.xticks(x, labels, rotation=20, ha="right")
plt.ylabel("Mean latency (ms)")
plt.title("Part C Mean Latency by Scenario")
plt.legend()
plt.tight_layout()
plt.savefig(FIGURES / "latency_by_scenario.png", dpi=180)
plt.close()

plt.figure(figsize=(8, 5))
plt.bar(labels, summary["effective_fps"])
plt.xticks(rotation=20, ha="right")
plt.ylabel("Effective FPS")
plt.title("Part C Effective Frame Rate by Scenario")
plt.tight_layout()
plt.savefig(FIGURES / "fps_by_scenario.png", dpi=180)
plt.close()

plt.figure(figsize=(8, 5))
plt.bar(labels, summary["failure_rate_pct"])
plt.xticks(rotation=20, ha="right")
plt.ylabel("Failure rate (%)")
plt.title("Part C Request Failure Rate by Scenario")
plt.tight_layout()
plt.savefig(FIGURES / "failure_rate_by_scenario.png", dpi=180)
plt.close()

plt.figure(figsize=(8, 5))
plt.bar(labels, summary["stalls_per_min"])
plt.xticks(rotation=20, ha="right")
plt.ylabel("Stalls per minute")
plt.title("Part C Stalls (>1 s) by Scenario")
plt.tight_layout()
plt.savefig(FIGURES / "stalls_by_scenario.png", dpi=180)
plt.close()

print(summary.to_string(index=False))
print(f"\nWrote results to {RESULTS}")
print(f"Wrote figures to {FIGURES}")
