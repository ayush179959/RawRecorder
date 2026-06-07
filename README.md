# RawRecorder 📸

RawRecorder is a high-performance Android camera application built using the Camera2 API. It is designed to capture raw sensor data and export clean, professionally calibrated Digital Negative (DNG) files. 

Unlike generic raw capture apps that output flat, color-inaccurate, or uncalibrated images, RawRecorder embeds precise sensor-specific calibration profiles directly into the DNG metadata. This ensures that the images look excellent and true-to-life right out of the box in professional post-processing software like DaVinci Resolve, Adobe Lightroom, and Photoshop.

---

## 🌟 Key Features

- **Direct RAW Capture**: Captures 10-bit or 12-bit RAW sensor output directly from the hardware pipeline.
- **Dynamic DNG Calibration**: Automatically embeds custom Adobe Camera Profile (DCP) color matrices and lookup tables (LUTs) based on the active sensor.
- **Multi-Lens Support**: Seamlessly switch between Main (Wide), Ultrawide, Telephoto, and Front cameras with automated camera parameter adjustment.
- **Zero Saturation issues**: Calibrated to match standard cinema gamuts and prevent the color oversaturation common in native mobile viewers and DaVinci Resolve.
- **Minimalist, Clean UI**: Streamlined interface focusing on clean, uncompressed image acquisition.

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

## 🛠️ Getting Started & Build Instructions

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
