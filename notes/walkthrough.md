# Walkthrough: Still Capture, 10-bit DNG, Viewport/Pink Cast Fixes, Progress Bar, and Dynamic Bitrate

We have successfully resolved all additional issues and implemented the new features! Here is the summary:

## 1. Resolved Telephoto 16:9 Pink Images (CFA Parity Fix)
- **Problem**: Capturing a 16:9 photo or rendering a 16:9 video on the Telephoto lens resulted in completely pink/magenta images.
- **Root Cause**: When cropping vertically to 16:9, if `cropStartRow` is an odd number, the CFA Bayer grid rows shift, swapping colors (e.g. Red/Blue with Green) and causing a heavy pink/magenta cast on sensors.
- **Solution**: Forced the crop start row index to be an even number in `DngExtractor.kt` (Android DNG extraction), `VideoRendererService.kt` (Android demosaicing), and `extract_dng.py` (PC Python script) by applying a bitwise `and -2` operation, which preserves the Bayer row alignment perfectly.

## 2. Resolved 16:9 Viewport Stretching
- **Problem**: When shooting 16:9, the live camera preview stretched to fill the viewport rather than cropping.
- **Solution**:
  - Added a `getScalerCropRegion()` helper in `MainActivity.kt` to calculate the 16:9 cropped rectangle dynamically from the physical camera sensor's active array.
  - Set `CaptureRequest.SCALER_CROP_REGION` on preview and single still capture requests.
  - The preview now matches the aspect ratio perfectly with zero stretching.

## 3. Rendering Progress Bar in Gallery
- **Problem**: Rendering videos takes a long time, and the user had no feedback on progress.
- **Solution**:
  - Declared reactive Compose state variables (`isRendering`, `renderingPath`, `currentPhase`, `progressFraction`) in `VideoRendererService.kt`.
  - Added progress fraction tracking in both `DngExtractor.kt` (0% to 20%) and `VideoRendererService.kt` demosaicing loop (20% to 90%).
  - Modified `GalleryActivity.kt` to dynamically replace the "RENDER HEVC" button with a smooth `LinearProgressIndicator` and status text for the actively rendering file.

## 4. Dynamic Bitrate Mapping
- **Problem**: Videos needed to preserve maximum detail under high noise conditions but save storage under clean conditions.
- **Solution**:
  - Added a `calculateDynamicBitrate` helper in `VideoRendererService.kt`.
  - Accumulates the exact ISO value of all frames during demosaicing to compute average ISO.
  - Dynamically calculates the target bitrate based on pixel count and average ISO (up to 2.2x multiplier for noisy/high-ISO footage to preserve film grain details) and clamps it between 10 Mbps and 60 Mbps.

## 5. UI Mode Labels
- **Solution**: The RESOLUTIONS menu subtitle at the bottom bar now dynamically shows `"PHOTO"` in Photo Mode and `"VIDEO"` in Video Mode, resolving the hardcoded `"VIDEO"` label.
