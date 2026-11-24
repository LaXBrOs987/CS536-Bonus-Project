# CS536 Bonus Project

## Project Overview

Real-time video streaming and smartphone-based object detection using INMO Air2 smart glasses and an Android smartphone. The project evaluates network performance, inference latency, and responsiveness, and compares the original implementation with an optimized streaming pipeline.

## Repository Organization

| Directory | Description |
| --- | --- |
| `Bonus-PartA/` | Initial video streaming implementation |
| `Bonus-PartB/` | Smartphone object detection (original implementation) |
| `Bonus-PartC/` | Performance instrumentation, experiments, raw data and results |
| `Bonus-PartD/` | Optimized MJPEG streaming, latest-frame processing and comparison |

Each part has two independent Android projects: `glasses-sender/` runs on the INMO Air2 glasses; `phone-receiver/` runs on the Android smartphone. Use both applications from the **same part**. Part D is the final optimized version; use Part B separately to reproduce the original Part B implementation.

## 1. Prerequisites

- INMO Air2 smart glasses (Android 9 / API 28 or higher for Part D sender).
- Android smartphone (Part D receiver requires API 24 or higher).
- USB data cables, USB debugging enabled on both devices, and Android SDK Platform-Tools (ADB).
- A Wi-Fi network allowing direct device-to-device connections, or a smartphone hotspot. A hotspot is recommended if campus Wi-Fi blocks peer connections.
- Android Studio with a compatible Android Gradle Plugin and SDK, or the Android SDK command-line tools and a compatible JDK. Part D specifies compile SDK 37, AGP 9.4.1 and Gradle daemon JDK 25; install the requested SDK/toolchain and allow Gradle to download dependencies. Internet access is required for the initial build.

## 2. Download the Source Code

```bash
git clone https://github.com/LaXBrOs987/CS536-Bonus-Project.git
cd CS536-Bonus-Project
```

Alternatively, use **Code > Download ZIP** on GitHub and extract the repository.

## 3. Install Part D (Recommended Final Version)

### Option A — Android Studio

1. Open `Bonus-PartD/glasses-sender` as an Android Studio project (not the repository root).
2. Wait for Gradle sync. Install any requested SDK components.
3. Connect the glasses by USB, enable USB debugging, and accept the authorization prompt. Select the glasses in the device dropdown and click **Run**.
4. Open `Bonus-PartD/phone-receiver` as a separate Android Studio project.
5. Sync Gradle, connect the smartphone, select it in the device dropdown and click **Run**.
6. Grant requested permissions, including camera permission on the glasses.

### Option B — Gradle and ADB (No Android Studio IDE Required)

Install the Android SDK command-line tools, platform-tools, the SDK requested by the project, and the compatible JDK. Set `ANDROID_HOME` to your SDK location and add its `platform-tools` directory to PATH. If necessary, create a `local.properties` file in each project containing `sdk.dir=/path/to/android/sdk` (use forward slashes on Windows). Run the commands from the cloned repository root:

Linux/macOS:

```bash
cd Bonus-PartD/glasses-sender
chmod +x gradlew
./gradlew assembleDebug
cd ../phone-receiver
chmod +x gradlew
./gradlew assembleDebug
cd ../..
```

Windows PowerShell:

```powershell
cd Bonus-PartD/glasses-sender
.\gradlew.bat assembleDebug
cd ../phone-receiver
.\gradlew.bat assembleDebug
cd ../..
```

Connect both devices and identify their serial numbers:

```bash
adb devices -l
```

Replace `GLASSES_SERIAL` and `PHONE_SERIAL` with the actual device serial numbers:

```bash
adb -s GLASSES_SERIAL install -r Bonus-PartD/glasses-sender/app/build/outputs/apk/debug/app-debug.apk
adb -s PHONE_SERIAL install -r Bonus-PartD/phone-receiver/app/build/outputs/apk/debug/app-debug.apk
```

Launch both installed applications from their device launchers. The package IDs are `com.cs536.glassessender` and `com.example.cs536`, respectively.

### Option C — Prebuilt APKs

After building once using either method, copy the two generated `app-debug.apk` files to a convenient folder and rename them `partd-glasses.apk` and `partd-phone.apk`. Install them using `adb -s SERIAL install -r APK_PATH`. This avoids rebuilding during the classroom demo. APKs must first be built or obtained from a trusted build; this README does not assume they are included in the repository.

## 4. Run the Live Demo

1. Connect the glasses and smartphone to the same reachable Wi-Fi network. If using the smartphone hotspot, connect the glasses to it.
2. Launch the sender application on the glasses and grant camera permission. The HTTP server starts automatically on port 8080.
3. Find the glasses' current Wi-Fi IPv4 address using one of the methods below.
4. On the phone, open a browser and test `http://GLASSES_IP:8080/health` (Part D), or `http://GLASSES_IP:8080/stream.mjpg` to check the stream. Replace `GLASSES_IP` with the actual address.
5. Launch the receiver on the smartphone. Enter only the glasses' IPv4 address (no `http://`, port, or path).
6. Tap **Start AI Detection**. Point the glasses at recognizable objects and confirm the video and detection overlays appear on the phone.
7. Tap the stop control when finished. Part D displays processing statistics including received, processed and dropped frames, inference time and frame age.

### Finding the Smart Glasses' IP Address

#### Method 1 — ADB (Recommended)

Connect the glasses to your computer via USB and ensure USB debugging is enabled. Open a terminal (including the Android Studio terminal, Windows PowerShell, or a regular terminal with ADB installed).

List connected devices:

```bash
adb devices -l
```

Identify the serial number of the glasses, then run:

```bash
adb -s GLASSES_SERIAL shell ip -4 addr show wlan0
```

Replace `GLASSES_SERIAL` with the actual serial number. If the glasses are the only connected ADB device, you can instead run:

```bash
adb shell ip -4 addr show wlan0
```

Example output:

```text
inet 192.168.43.105/24 brd 192.168.43.255 scope global wlan0
```

In this example, the glasses' IP address is `192.168.43.105`. Ignore the `/24` subnet prefix and broadcast address. If no IPv4 address appears, verify that the glasses are connected to Wi-Fi. If the interface name differs, list all IPv4 interfaces with `adb -s GLASSES_SERIAL shell ip -4 addr show`.

#### Method 2 — Glasses' Wi-Fi Settings (No ADB Required)

1. Open the glasses' Android settings.
2. Navigate to Wi-Fi or Network & Internet.
3. Select the currently connected Wi-Fi network and view its network details.
4. Locate the IPv4 address. Menu names may differ depending on the firmware.

#### Verify the Connection

With the glasses sender running, open the following example URL in the smartphone's browser:

```text
http://192.168.43.105:8080/health
```

For Part D, the health endpoint can confirm that the sender is reachable. For Part B, test `/frame.jpg` instead. Then enter `192.168.43.105` (or your actual glasses IP) into the phone receiver and tap **Start AI Detection**.

Do not enter the smartphone's IP, `127.0.0.1`, the subnet prefix, or port 8080 into the receiver's IP field. Recheck the glasses' IP after changing networks or reconnecting to a hotspot. If the browser cannot reach the sender, confirm that both devices can communicate on the network and that the sender application is running. Try a smartphone hotspot if campus Wi-Fi blocks device-to-device connections.

Part D uses a persistent HTTP MJPEG stream (`/stream.mjpg`) and separate networking and processing workers. The receiver retains the newest frame and replaces stale unprocessed frames. Object detection uses MediaPipe EfficientDet-Lite0, preferring GPU with CPU fallback.

## 5. Reproduce Parts B and C

Follow the same installation procedure using `Bonus-PartB/` or `Bonus-PartC/` instead of `Bonus-PartD/`. Check each project's own Gradle configuration for its build requirements. Start the matching glasses sender, enter the glasses IP in the matching phone receiver, and start detection. Part B retrieves JPEG snapshots through `http://GLASSES_IP:8080/frame.jpg`. Do not mix sender/receiver versions. If switching versions causes a signature conflict, uninstall the conflicting application first (this deletes its local data; export logs beforehand).

Part C includes recorded experiments in `raw/`, aggregate results in `results/`, and plots in `figures/`. Part D includes recorded runs in `raw/` and summaries/comparison figures in `analysis/`.

## 6. Reproduce the Part C vs. Part D Analysis

From the repository root, create a Python environment and install analysis dependencies:

```bash
python -m pip install numpy pandas matplotlib
python Bonus-PartD/compare_part_cd.py
```

The script defaults to the stationary hotspot runs in Part C and Part D and writes comparison outputs to `Bonus-PartD/analysis/part_c_vs_d/`. Use `python Bonus-PartD/compare_part_cd.py --help` for alternate inputs and options.

## 7. Troubleshooting

| Problem | Recommended action |
| --- | --- |
| ADB device not detected | Check cable, USB debugging, device authorization and `adb devices -l`. |
| Connection timeout | Confirm glasses IP, sender is running, both devices can communicate, and try a hotspot. Do not enter localhost on the phone. |
| Blank camera feed | Grant camera permission and reopen the sender. |
| Gradle sync/build fails | Install the SDK/JDK specified by the project, verify SDK path, and check internet access. |
| Wrong version installed | Install both applications from the same project part. |
| GPU unsupported | Part D automatically attempts CPU fallback. |

## Technologies

Kotlin, Android SDK, NanoHTTPD, HTTP/TCP over Wi-Fi, MJPEG, MediaPipe object detection, Python.

Developed for Purdue University's CS536 Computer Networks course.
