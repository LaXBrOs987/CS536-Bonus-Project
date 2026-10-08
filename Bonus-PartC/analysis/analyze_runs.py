import pandas as pd
from pathlib import Path


RAW_DIR = Path(__file__).resolve().parent.parent / "raw"


def analyze_file(csv_file):
    df = pd.read_csv(csv_file)

    print("\n" + "=" * 70)
    print(f"Run: {csv_file.name}")
    print("=" * 70)

    total_rows = len(df)

    if total_rows == 0:
        print("CSV is empty.")
        return

    # Convert success column safely in case pandas reads it as text.
    if df["success"].dtype == object:
        df["success"] = (
            df["success"]
            .astype(str)
            .str.lower()
            .map({
                "true": True,
                "false": False
            })
        )

    success_df = df[df["success"] == True]
    failed_df = df[df["success"] == False]

    successful_frames = len(success_df)
    failed_frames = len(failed_df)

    # ---------------------------------------------------------
    # RUN DURATION
    # ---------------------------------------------------------

    duration_seconds = (
        df["elapsed_ms"].max() / 1000.0
        if not df["elapsed_ms"].empty
        else 0
    )

    # ---------------------------------------------------------
    # FAILURE RATE
    # ---------------------------------------------------------

    if total_rows > 0:
        failure_rate = (
            failed_frames /
            total_rows *
            100
        )
    else:
        failure_rate = 0

    # ---------------------------------------------------------
    # EFFECTIVE FPS
    # ---------------------------------------------------------

    if duration_seconds > 0:
        effective_fps = (
            successful_frames /
            duration_seconds
        )
    else:
        effective_fps = 0

    # ---------------------------------------------------------
    # APPLICATION PAYLOAD THROUGHPUT
    #
    # This is based only on JPEG frame bytes received.
    # ---------------------------------------------------------

    total_frame_bytes = (
        success_df["frame_bytes"].sum()
        if successful_frames > 0
        else 0
    )

    if duration_seconds > 0:
        payload_throughput_mbps = (
            total_frame_bytes * 8
        ) / (
            duration_seconds *
            1_000_000
        )
    else:
        payload_throughput_mbps = 0

    # ---------------------------------------------------------
    # ACTUAL RX/TX NETWORK TRAFFIC
    #
    # Newer experiment files contain Android TrafficStats
    # cumulative RX and TX counters.
    #
    # Older CSVs such as test_run01.csv do not contain them.
    # ---------------------------------------------------------

    has_traffic_stats = (
        "cumulative_rx_bytes" in df.columns
        and
        "cumulative_tx_bytes" in df.columns
    )

    downlink_mbps = None
    uplink_mbps = None
    final_rx_bytes = None
    final_tx_bytes = None

    if has_traffic_stats:

        final_rx_bytes = (
            df["cumulative_rx_bytes"]
            .fillna(0)
            .max()
        )

        final_tx_bytes = (
            df["cumulative_tx_bytes"]
            .fillna(0)
            .max()
        )

        if duration_seconds > 0:

            downlink_mbps = (
                final_rx_bytes * 8
            ) / (
                duration_seconds *
                1_000_000
            )

            uplink_mbps = (
                final_tx_bytes * 8
            ) / (
                duration_seconds *
                1_000_000
            )

        else:

            downlink_mbps = 0
            uplink_mbps = 0

    # ---------------------------------------------------------
    # STALLS
    #
    # Definition:
    # more than 1000 ms between successful frames.
    # ---------------------------------------------------------

    if successful_frames > 0:

        stall_count = (
            success_df["interarrival_ms"] >
            1000
        ).sum()

    else:

        stall_count = 0

    # ---------------------------------------------------------
    # JITTER
    #
    # Defined here as the standard deviation of
    # successful frame interarrival times.
    #
    # Ignore the first frame because its interarrival value
    # is recorded as zero.
    # ---------------------------------------------------------

    if successful_frames > 0:

        valid_interarrival = success_df[
            success_df["interarrival_ms"] > 0
        ]["interarrival_ms"]

        if len(valid_interarrival) > 1:
            jitter_ms = valid_interarrival.std()
        else:
            jitter_ms = None

    else:

        jitter_ms = None

    # ---------------------------------------------------------
    # AI DETECTION METRICS
    # ---------------------------------------------------------

    if successful_frames > 0:

        detected_frames = (
            success_df["detections"] > 0
        ).sum()

        detection_rate = (
            detected_frames /
            successful_frames *
            100
        )

        mean_objects_per_frame = (
            success_df["detections"].mean()
        )

        detected_only = success_df[
            success_df["detections"] > 0
        ]

        if not detected_only.empty:

            mean_confidence = (
                detected_only[
                    "avg_confidence"
                ].mean()
            )

        else:

            mean_confidence = None

    else:

        detected_frames = 0
        detection_rate = 0
        mean_objects_per_frame = None
        mean_confidence = None

    # ---------------------------------------------------------
    # SUMMARY
    # ---------------------------------------------------------

    print(
        f"Duration:             "
        f"{duration_seconds:.2f} s"
    )

    print(
        f"Successful frames:    "
        f"{successful_frames}"
    )

    print(
        f"Failed frames:        "
        f"{failed_frames}"
    )

    print(
        f"Failure rate:         "
        f"{failure_rate:.2f}%"
    )

    print(
        f"Effective FPS:        "
        f"{effective_fps:.2f}"
    )

    print(
        f"Payload throughput:   "
        f"{payload_throughput_mbps:.3f} Mbps"
    )

    if downlink_mbps is not None:

        print(
            f"Downlink throughput:  "
            f"{downlink_mbps:.3f} Mbps"
        )

        print(
            f"Uplink throughput:    "
            f"{uplink_mbps:.3f} Mbps"
        )

        print(
            f"Total RX bytes:       "
            f"{int(final_rx_bytes)}"
        )

        print(
            f"Total TX bytes:       "
            f"{int(final_tx_bytes)}"
        )

    else:

        print(
            "Downlink throughput:  "
            "N/A (older log format)"
        )

        print(
            "Uplink throughput:    "
            "N/A (older log format)"
        )

    print(
        f"Stalls (>1000 ms):    "
        f"{stall_count}"
    )

    if jitter_ms is not None:

        print(
            f"Interarrival jitter:  "
            f"{jitter_ms:.2f} ms"
        )

    else:

        print(
            "Interarrival jitter:  "
            "N/A"
        )

    # ---------------------------------------------------------
    # LATENCY METRICS
    # ---------------------------------------------------------

    print("\nNETWORK FETCH LATENCY")

    if successful_frames > 0:

        print(
            f"Mean:                 "
            f"{success_df['fetch_ms'].mean():.2f} ms"
        )

        print(
            f"Median:               "
            f"{success_df['fetch_ms'].median():.2f} ms"
        )

        print(
            f"P95:                  "
            f"{success_df['fetch_ms'].quantile(0.95):.2f} ms"
        )

        print(
            f"Max:                  "
            f"{success_df['fetch_ms'].max():.2f} ms"
        )

    else:

        print("No successful frames.")

    print("\nAI INFERENCE LATENCY")

    if successful_frames > 0:

        print(
            f"Mean:                 "
            f"{success_df['inference_ms'].mean():.2f} ms"
        )

        print(
            f"Median:               "
            f"{success_df['inference_ms'].median():.2f} ms"
        )

        print(
            f"P95:                  "
            f"{success_df['inference_ms'].quantile(0.95):.2f} ms"
        )

        print(
            f"Max:                  "
            f"{success_df['inference_ms'].max():.2f} ms"
        )

    else:

        print("No successful frames.")

    print("\nTOTAL PROCESSING LATENCY")

    if successful_frames > 0:

        print(
            f"Mean:                 "
            f"{success_df['total_ms'].mean():.2f} ms"
        )

        print(
            f"Median:               "
            f"{success_df['total_ms'].median():.2f} ms"
        )

        print(
            f"P95:                  "
            f"{success_df['total_ms'].quantile(0.95):.2f} ms"
        )

        print(
            f"Max:                  "
            f"{success_df['total_ms'].max():.2f} ms"
        )

    else:

        print("No successful frames.")

    # ---------------------------------------------------------
    # AI METRICS
    # ---------------------------------------------------------

    print("\nAI DETECTION")

    print(
        f"Frames with object:   "
        f"{detected_frames}"
    )

    print(
        f"Detection rate:       "
        f"{detection_rate:.2f}%"
    )

    if mean_objects_per_frame is not None:

        print(
            f"Mean objects/frame:   "
            f"{mean_objects_per_frame:.2f}"
        )

    else:

        print(
            "Mean objects/frame:   "
            "N/A"
        )

    if mean_confidence is not None:

        print(
            f"Mean confidence:      "
            f"{mean_confidence:.3f}"
        )

    else:

        print(
            "Mean confidence:      "
            "N/A"
        )

    # ---------------------------------------------------------
    # FAILED REQUEST INFORMATION
    # ---------------------------------------------------------

    if failed_frames > 0:

        print("\nFAILURES")

        error_counts = (
            failed_df["error"]
            .fillna("Unknown error")
            .value_counts()
        )

        for error, count in error_counts.items():

            print(
                f"{count}x - {error}"
            )


def main():

    files = sorted(
        RAW_DIR.glob("*.csv")
    )

    if not files:

        print(
            f"No CSV files found in:"
            f"\n{RAW_DIR}"
        )

        return

    print(
        f"Found {len(files)} experiment file(s)."
    )

    for file in files:

        try:

            analyze_file(file)

        except Exception as e:

            print("\n" + "=" * 70)

            print(
                f"Could not analyze "
                f"{file.name}"
            )

            print(
                f"Error: {e}"
            )


if __name__ == "__main__":
    main()