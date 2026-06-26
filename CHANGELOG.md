# Changelog

## [Unreleased] - 2026-06-26

### Added
- **LZ4 Compression Pipeline**: Implemented multi-threaded real-time LZ4 compression (C++ JNI) for the AYUSHRC1 format, enabling much lower storage bandwidth and higher frame rates at peak resolution.
- **Cinematic Aspect Ratios**: Added native recording support for 2.35:1 and 2.39:1 cinematic aspect ratios across 2K, 2.5K, and 3K resolutions.
- **Batch Export**: Added the ability to export multiple video files in the gallery with the same shared settings (bitrate, color space, etc.).

### Fixed
- **Settings Persistence**: Fixed an issue where restarting the app would overwrite stored preferences with null values.
- **DNG Extraction Artifacts**: Resolved an issue causing DNG photos to render incorrectly (as a pink, zoomed-in, corrupted image) by aligning horizontal and vertical cropping in `RawSpoolerEngine` and `DngExtractor`.
- **Photo Save Location**: Changed the DNG photo save directory from `DCIM/Camera` to `Documents/RawRecorder` to match where the video files are saved.
- **Capture Race Condition**: Fixed a bug where photo extraction would fail silently because the extractor tried reading the file before the background compression thread had finished writing it.
- **Black Screen Resolution Bug**: Fixed incorrect parameter ordering in `ResolutionOption` initialization that caused a black screen.
- **Removed OpenGate**: Removed standard 4:3 and 16:9 opengate resolution options per user preference.
