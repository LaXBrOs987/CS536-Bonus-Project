# CS536 Bonus Project

Android applications and experiment data for the INMO Air2 smart glasses and an Android phone.

## Folder contents

| Folder | Contents |
| --- | --- |
| `Bonus-PartA/` | `glasses-sender/` and `phone-receiver/` Android projects. |
| `Bonus-PartB/` | `glasses-sender/` and `phone-receiver/` Android projects, including the object detection model. |
| `Bonus-PartC/` | `glasses-sender/` and `phone-receiver/` experiment applications, `raw/` CSV logs, `analysis/` Python scripts, `results/` summaries, and `figures/` plots. |
| `Bonus-PartD/` | `glasses-sender/` and `phone-receiver/` Android projects, `raw/` CSV logs, `compare_part_cd.py`, and comparison outputs in `analysis/part_c_vs_d/`. |

## Build and install the Android applications

1. Install Android Studio, Android SDK Platform 37, and SDK Platform Tools (`adb`).
   Open the selected part's `glasses-sender/` and `phone-receiver/` as separate Gradle projects.
   The checked-in Gradle daemon configuration requests JDK 25. Configure that JDK or allow
   Gradle to provision it, configure your SDK path in Android Studio, and sync dependencies.
   The sender requires Android API 28 or later; the receiver requires API 24 or later.
2. Enable USB debugging on the glasses and phone, connect them to the laptop, and authorize
   debugging. Use `adb devices -l` to find their serials.
3. Build and install with Android Studio, or use these PowerShell commands from the repository root.
   Set `$partFolder` to `Bonus-PartA`, `Bonus-PartB`, `Bonus-PartC`, or `Bonus-PartD`.

```powershell
$partFolder = "Bonus-PartC"
Push-Location "$partFolder/glasses-sender"
.\gradlew.bat assembleDebug
Pop-Location
Push-Location "$partFolder/phone-receiver"
.\gradlew.bat assembleDebug
Pop-Location
adb -s GLASSES_SERIAL install -r "$partFolder/glasses-sender/app/build/outputs/apk/debug/app-debug.apk"
adb -s PHONE_SERIAL install -r "$partFolder/phone-receiver/app/build/outputs/apk/debug/app-debug.apk"
```

Replace `GLASSES_SERIAL` and `PHONE_SERIAL` with the actual serials. Applications in different
parts use the same application IDs; installing a different part replaces the installed app.

## Run and record experiments

1. Connect the glasses and phone to the same reachable Wi-Fi or hotspot network.
2. Launch the sender on the glasses and grant camera permission. Find the glasses' current IP
   address. The sender serves video on port 8080.
3. Launch the receiver on the phone and enter the glasses IP address. Replace the prefilled
   address with the actual address for your network. In Part A, use the connect button;
   in Parts B, C, and D, use **Start AI Detection**.
4. For Part C or D, enter a unique run name before starting. Keep the receiver in the foreground
   during the recording, then tap **Stop AI Detection** before exporting. Reusing a run name
   overwrites its CSV file.

The Part C logger is included at
`Bonus-PartC/phone-receiver/app/src/main/java/com/example/cs536/ExperimentLogger.kt`.
Its receiver starts/stops the logger and records fetch time, inference time, total time,
frame bytes, cumulative receiver network bytes, interarrival times, detections, and errors.
The included Part C application files come from `BONUS-PartC:part-a/`.

## Export CSV logs

Part C and D receivers save logs to their app-specific external `files/experiments/` directory.
From the repository root, with the phone connected and USB debugging authorized:

```powershell
adb -s PHONE_SERIAL shell ls /sdcard/Android/data/com.example.cs536/files/experiments
adb -s PHONE_SERIAL pull /sdcard/Android/data/com.example.cs536/files/experiments/hotspot_stationary_11.csv Bonus-PartC/raw/hotspot_stationary_11.csv
adb -s PHONE_SERIAL pull /sdcard/Android/data/com.example.cs536/files/experiments/hotspot_stationary_new_11.csv Bonus-PartD/raw/hotspot_stationary_new_11.csv
```

Use the filename recorded for your run. The default comparison selects `hotspot_stationary_*.csv`
for C and `hotspot_stationary_new_*.csv` for D. Supplied CSVs omit `timestamp_ms`; fresh exports
include it, and the analysis scripts accept that additional column. Keep original exports and
record the device/OS, application commit, network condition, recording duration, and run settings
alongside each experiment.

## Run the Python analysis

From the repository root:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install matplotlib pandas numpy
.\.venv\Scripts\python.exe Bonus-PartC/analysis/summarize_part_c.py
.\.venv\Scripts\python.exe Bonus-PartD/compare_part_cd.py
```

The Part C summary reads `Bonus-PartC/raw/` and writes summaries to `Bonus-PartC/results/`
and plots to `Bonus-PartC/figures/`.

The Part D comparison resolves its default paths relative to the script, so it can run from
any working directory. It reads `Bonus-PartC/raw/` and `Bonus-PartD/raw/` and writes six PNG
figures plus `comparison_runs.csv` to `Bonus-PartD/analysis/part_c_vs_d/`.
The figures cover FPS, FPS over time, inference duration, local processing work, network and
continuity metrics, and frame handling/age. To specify inputs or output explicitly:

```powershell
.\.venv\Scripts\python.exe Bonus-PartD/compare_part_cd.py --c-input Bonus-PartC/raw --d-input Bonus-PartD/raw --output Bonus-PartD/analysis/part_c_vs_d --window 5
```

Explicit relative paths resolve against the current working directory. Use `--c-pattern` and
`--d-pattern` to select run filenames, or `--help` for the available arguments. Record the
Python version and `python -m pip freeze` output with your analysis environment.

## Required Git tags

| Tag | Milestone commit |
| --- | --- |
| `BONUS-PartA` | `4366c0f682fe1e60c8eed49088b75915103f06b8` |
| `BONUS-PartB` | `1ad6dce0597790bcd46f2199f45ad86fc2fdb36e` |
| `BONUS-PartC` | `eb937d0899e0c6d58690f8f1100754734b581dfc` |
| `BONUS-PartD` | `f59f09534b0c5514b2ea320958ece984643309bb` |

The tagged application projects are under the historical `part-a/` directory. Part C's logger
is available at `BONUS-PartC:part-a/phone-receiver/app/src/main/java/com/example/cs536/ExperimentLogger.kt`.
Inspect or export a milestone from a Git checkout:

```powershell
git show "BONUS-PartC^{commit}" --no-patch
git ls-tree -r BONUS-PartC -- part-a
git show BONUS-PartC:part-a/phone-receiver/app/src/main/java/com/example/cs536/ExperimentLogger.kt
git ls-remote --tags origin
git archive --format=zip --output=BONUS-PartC.zip BONUS-PartC
```

A folder ZIP includes the application files and data, but not `.git` history. Use the repository
or individual tag archives to inspect the milestone snapshots.

Repository: https://github.com/LaXBrOs987/CS536-Bonus-Project
