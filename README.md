# CS536 Bonus Project

## Project Overview

This project explores real-time video streaming and AI-based object detection between INMO Air2 smart glasses and an Android smartphone. The goal is to develop a system that captures video from the smart glasses, transmits it over a wireless network, and performs object detection on the receiving smartphone.

The project also evaluates the system's performance under different configurations, focusing on network communication, processing latency, and real-time detection capabilities.

## Project Structure

The project is divided into four parts:

- **Part A – Video Streaming:** Establishes communication between the smart glasses and smartphone, enabling real-time video transmission over a wireless network.
- **Part B – AI Object Detection:** Integrates an object detection model into the smartphone receiver to identify objects in the streamed video.
- **Part C – Performance Evaluation:** Collects experimental data to measure frame rates, inference latency, network performance, and overall system responsiveness.
- **Part D – System Optimization and Comparison:** Evaluates an updated system configuration and compares its performance against Part C to identify improvements and trade-offs.

## Repository Organization

| Directory | Description |
| --- | --- |
| `Bonus-PartA/` | Initial video streaming implementation |
| `Bonus-PartB/` | AI object detection integration |
| `Bonus-PartC/` | Performance experiments, collected data, and analysis |
| `Bonus-PartD/` | Updated implementation and comparative performance analysis |

Each part contains the relevant Android applications and supporting files. Experimental sections also include collected CSV data, Python analysis scripts, and generated performance figures.

## Technologies Used

- **Android Studio / Kotlin** – Android application development
- **HTTP / TCP / Wi-Fi** – Video streaming and network communication
- **On-device Machine Learning** – Real-time object detection
- **Python** – Experimental data processing and performance analysis

## Project Objective

The overarching objective is to demonstrate the feasibility of combining wearable smart glasses with smartphone-based AI processing for real-time object recognition, while examining how system design and network conditions affect performance.

This project was developed as part of Purdue University's CS536 Computer Networks course.