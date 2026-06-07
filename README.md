# RawRecorder 📸

RawRecorder is a high-performance Android camera application built using the Camera2 API. It is designed to capture raw sensor data and export clean, professionally calibrated Digital Negative (DNG) files. 

Unlike generic raw capture apps that output flat, color-inaccurate, or uncalibrated images, RawRecorder embeds precise sensor-specific calibration profiles directly into the DNG metadata. This ensures that the images look excellent and true-to-life right out of the box in professional post-processing software like DaVinci Resolve, Adobe Lightroom, and Photoshop.

---

## ⚡ The RawRecorder Advantage (Why it is better)

Most third-party raw recording apps write raw sensor data directly into standard DNG files using generic Android Camera2 API tags. This default mapping often results in:
- **Severe oversaturation** and clipping of intense hues (especially greens, cyans, and reds) in color-managed software like DaVinci Resolve and standard image viewers.
- **Incorrect white balance coefficients** and color temperature shifts due to a lack of accurate illuminant calibration.
- **Flat, lifeless skin tones** and wrong color space conversions because the software is guessing the camera's actual spectral response.

### How RawRecorder Solves This:
1. **Calibrated DCP (Digital Camera Profiles)**: RawRecorder embeds authentic, manufacturer-grade Adobe Camera Profiles (DCP) containing high-fidelity 3D Look-Up Tables (LUTs) for hue, saturation, and value (HSV) corrections, custom tone curves, and sensor matrices.
2. **Direct Metadata Injection**: Instead of using placeholder tags, our pipeline dynamically injects exact calibration tags (`ColorMatrix1/2`, `ForwardMatrix1/2`, `AnalogBalance`, `NoiseProfile`, and exact `DefaultCrop` coordinates) directly from the device's manufacturer calibration tables.
3. **Sensor-Matched Processing**: Each physical sensor (Main, Ultrawide, Telephoto, and Front) is profiled independently, applying distinct matrices depending on the active lens. This results in pristine, professional-grade color accuracy directly from the RAW sensor.

---

## 📱 Pixel 6 Pro Calibration (Active Device)

Currently, the app is **calibrated specifically for the Google Pixel 6 Pro**. Color correction matrices (CCMs), forward matrices, and color noise profiles are tailored to the Pixel 6 Pro's camera array:

- **Main Camera (Wide)**: Configured using custom extracted Pixel 6 Pro main sensor DCP color profiles.
- **Ultrawide Camera**: Calibrated for wide field-of-view perspective and color matching.
- **Telephoto Camera**: Tailored telephoto sensor matrix parameters.
- **Front Camera**: Full front-facing selfie camera DNG and color profile support.

> [!NOTE]
> While the application will run on other devices supporting the Camera2 API, the color profile and DNG calibration are currently optimized for the Pixel 6 Pro. **We are actively working on expanding calibration support to other popular devices!** Future updates will include a calibration step to automatically fetch and apply device-specific vendor DNG metadata.

---

## 📖 Usage & Workflow Guide

### 1. Capturing Raw Video
- Open the app, grant Camera and Storage permissions.
- Switch between lenses (Main, Ultrawide, Telephoto, Front) using the camera toggle on the UI.
- Tap the **Record** button to begin capturing raw frames. The camera stores the raw sensor frames in our high-speed container format: `.ayushraw`.

### 2. File Location on Device
Depending on your settings (public vs. private storage), your captured `.ayushraw` files will be saved to:
- **Private storage**: `Android/data/com.example.rawrecorder/files/`
- **Public storage**: `Documents/RawRecorder/`

### 3. Converting to Calibrated DNG

You can convert the captured raw stream into standard `.dng` frames in two ways:

#### A. On-Phone Conversion (In-App Gallery)
1. Open the in-app **Gallery** inside RawRecorder.
2. Select your captured `.ayushraw` recording.
3. Tap **Export to DNG**.
4. The background rendering service uses the custom `DngExtractor` to unpack the raw stream and generate a folder of fully calibrated `.dng` files on your phone's storage.

#### B. On-PC Conversion (Python Utility)
For fast batch processing and editing directly on a computer:
1. Copy the `.ayushraw` file from your phone to your computer.
2. Open a terminal/command prompt in the project's `desktop_tools/` directory.
3. Run the extraction script:
   ```bash
   python desktop_tools/extract_dng.py /path/to/your/file.ayushraw
   ```
4. This script will read the embedded color matrices, forward matrices, and sensor profiles from the file header, and extract a sequence of fully-calibrated `.dng` frames into a directory named `<file_name>_frames/`. You can then import these DNGs directly into **DaVinci Resolve**, **Adobe Lightroom**, or any other professional editor.

---

## 🛠️ Build Instructions

### Prerequisites
- Android Studio Koala / Ladybug or newer
- Android SDK 33 (Target SDK 36)
- A physical Android device supporting the **Camera2 API** with RAW capture capabilities (Pixel 6 Pro recommended for accurate colors)

### Build Steps
1. Clone the repository:
   ```bash
   git clone https://github.com/ayush179959/RawRecorder.git
   cd RawRecorder
   ```
2. Open the project in **Android Studio**.
3. Let Gradle sync and download dependencies.
4. Run the project on your connected device.

---

## 📁 Project Structure

- `app/src/main/java/.../MainActivity.kt`: Handles the Camera2 capture session, UI, and lens switching.
- `app/src/main/java/.../gallery/DngExtractor.kt`: Extracts, parses, and injects the calibrated binary DCP profiles into the generated DNG files.
- `app/src/main/assets/`: Binary Adobe color calibration profiles extracted from native device profiles.
- `desktop_tools/`: Python utilities for analyzing DCP color profiles, calculating color correction matrices (CCMs), and processing sample DNG outputs.

---

## 🤝 Contributing

Contributions are welcome! If you'd like to help test or extract calibration profiles for other devices:
1. Extract your device's DCP files using Adobe DNG Converter.
2. Open an issue or submit a pull request with the profiles.
3. Check the `universal_app_architecture.md` notes in the project repository for details on our plans for device-agnostic runtime calibration.

---

## 📄 License

This project is licensed under the terms of the MIT License. See the [LICENSE](LICENSE) file for details.
