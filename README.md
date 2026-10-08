# CS536 Bonus Project – Smart Glass Application

This repository contains the work completed for the CS536 Bonus Project using the INMO Air2 smart glasses and an Android smartphone.

The project is organized by the four bonus project parts.

## Bonus-PartA

Contains the original live-streaming application.

- `glasses-sender/` – runs on the INMO Air2 and captures camera frames.
- `phone-receiver/` – runs on the Android phone and displays the live stream from the glasses.

This folder represents the system completed for **Part A**.

---

## Bonus-PartB

Contains the AI-enabled version of the Part A application.

- `glasses-sender/` – smart-glass camera streaming application.
- `phone-receiver/` – Android receiver with object detection added to the live video stream.

The phone performs object detection on frames received from the glasses and displays the detected objects on the video.

This folder represents the system completed for **Part B**.

---

## Bonus-PartC

Contains the performance evaluation of the smart-glass system in multiple network and mobility conditions.

- `raw/` – raw experiment CSV logs.
- `analysis/` – Python scripts used to process the experiment data.
- `results/` – summarized experiment results and CSV files.
- `figures/` – plots generated from the Part C experiments.

The experiments evaluate metrics including throughput, latency, frame rate, stalls, jitter, failures, and AI inference performance.

This folder represents the work completed for **Part C**.

---

## Bonus-PartD

Contains the enhanced version of the system and its preliminary evaluation.

The Part D enhancement replaces repeated HTTP frame polling with a **persistent MJPEG stream** and uses **latest-frame processing** to prevent stale frames from building up while AI inference is running.

- Application code – implementation of the persistent MJPEG/latest-frame design.
- `raw/` – Part D experiment logs.
- `analysis/` – scripts comparing the Part C baseline with Part D.
- `results/` – summarized Part D results.
- `figures/` – Part C vs. Part D performance comparisons.

This folder represents the work completed for **Part D**.

---

## Git Tags

The completed milestones are also preserved using the required Git tags:

- `BONUS-PartA`
- `BONUS-PartB`
- `BONUS-PartC`
- `BONUS-PartD`

Each tag represents the repository state when that portion of the project was completed.