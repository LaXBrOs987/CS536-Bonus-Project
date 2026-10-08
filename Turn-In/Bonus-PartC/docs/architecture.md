# CS536 Smart Glass Project

## Part A

Goal:

INMO smart glasses camera
-> Wi-Fi video stream
-> Google Pixel 5 Android application
-> Laptop display

The smart glasses will perform as little processing as possible.

The Google Pixel 5 will be the primary computing device.

## Part B

The Pixel 5 will perform real-time object detection on frames received
from the smart glasses.

Pipeline:

INMO Camera
-> Video Stream
-> Pixel 5
-> Object Detection
-> Bounding Boxes / Labels
-> Display

## Planned Technologies

Glasses:
- Android Camera / CameraX
- MJPEG
- HTTP

Phone:
- Kotlin
- Android
- MJPEG receiver
- MediaPipe Object Detector

Laptop:
- Android Studio
- ADB
- scrcpy