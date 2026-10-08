# Part C — Field Trial Results

## Experimental scope

Part C contains **40 official runs** across four scenarios, with 10 runs per scenario:

- Hotspot stationary
- Hotspot walking
- PAL3.0 walking
- 5G walking

Total experiment time was **114.83 minutes (1.91 hours)**, exceeding the one-hour requirement.

The smart-glass sender streamed 640×480 JPEG frames to the Android receiver. The receiver performed object detection and logged application/network performance for each frame.

## Metric definitions

- **Effective FPS:** successful frames divided by experiment duration.
- **Payload throughput:** JPEG payload bytes received per second.
- **Downlink/uplink throughput:** Android UID traffic counters (`TrafficStats`) over experiment duration. These are application-level byte counters, not link-layer throughput.
- **Network fetch latency:** application-level time to complete an HTTP frame request; this is not pure network RTT.
- **AI inference latency:** time spent running the object detector.
- **Total processing latency:** end-to-end receiver processing time for a frame.
- **Stall:** successful-frame interarrival time greater than 1000 ms.
- **Interarrival jitter:** variability of successful-frame interarrival times.
- **Failure rate:** failed frame requests divided by total frame attempts.
- **Detection rate:** fraction of successful frames containing at least one detected object.

## Scenario summary

| Scenario | Runs | Time (min) | Success | Fail | Failure % | FPS | Downlink Mbps | Uplink Mbps | Fetch ms | AI ms | Total ms | Stalls/min |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Hotspot stationary | 10 | 28.62 | 3179 | 0 | 0.00 | 1.85 | 0.537 | 0.012 | 181.2 | 140.6 | 336.9 | 5.03 |
| Hotspot walking | 10 | 24.91 | 2822 | 18 | 0.63 | 1.89 | 0.395 | 0.013 | 156.1 | 141.5 | 312.7 | 3.61 |
| PAL3.0 walking | 10 | 30.62 | 3955 | 6 | 0.15 | 2.15 | 0.442 | 0.015 | 102.9 | 142.1 | 260.1 | 1.44 |
| 5G walking | 10 | 30.68 | 3663 | 6 | 0.16 | 1.99 | 0.415 | 0.014 | 141.7 | 141.2 | 298.0 | 4.24 |

## Overall results

Across all 40 runs:

- **Total experiment time:** 114.83 minutes
- **Successful frames:** 13,619
- **Failed requests:** 30
- **Overall request failure rate:** 0.22%
- **Overall effective frame rate:** 1.98 FPS
- **Total stalls (>1 s):** 408

## Main observations

1. **AI inference was stable across network conditions.** Scenario-level mean inference latency stayed near 141–142 ms. This suggests that the object detector itself was not the primary source of performance variability.
2. **Network behavior changed materially by scenario.** PAL3.0 walking had the lowest mean fetch latency and highest effective FPS, while hotspot stationary had higher fetch latency and more stalls per minute.
3. **Failures were uncommon overall.** The overall failed-request rate was approximately 0.22%, but hotspot walking experienced more connection failures than the other scenarios.
4. **Stationary detection rate should not be interpreted as a network advantage.** The stationary runs used a repeatable visual scene and produced a 100% detection rate, while walking runs observed changing scenes. Detection-rate differences therefore reflect visual workload as well as system behavior.

## Part D motivation

Part C indicates that network transport is a better optimization target than AI inference. The current receiver requests each frame separately using `/frame.jpg`, creating repeated HTTP/TCP request cycles. Part D will evaluate a **persistent MJPEG stream (`/stream.mjpg`)** so that the receiver can reuse one long-lived connection.

The Part D comparison should focus on:

- effective FPS
- request/stream failures
- stalls
- interarrival jitter
- network latency / frame interarrival behavior
- downlink/uplink traffic
- total processing latency

The Part C implementation and raw data should remain unchanged after the `BONUS-PartC` tag so the baseline is preserved.
