# Implementation Plan: Pink Images Fix, Viewport Stretch Fix, UI Labels, Gallery Progress Bar, and Dynamic Bitrate

This plan details updates to fix:
1. **Telephoto Pink 16:9 Images (CFA Row Alignment)**:
   - **Root Cause**: When cropping vertically to 16:9, if `cropStartRow` is an odd number, the CFA Bayer grid rows shift, swapping colors (e.g. Red/Blue with Green) and causing a heavy pink/magenta cast on sensors.
   - **Fix**: Force `cropStartRow` to be an even number in `DngExtractor.kt`, `VideoRendererService.kt`, and `extract_dng.py` by applying a bitwise `and -2` (clearing the LSB).
2. **Viewport Stretching in 16:9**:
   - **Root Cause**: The camera sensor captures a 4:3 area, but in 16:9 the SurfaceView aspect ratio is 16:9. Without setting the digital zoom crop region, the camera outputs the uncropped 4:3 stream which is stretched to 16:9.
   - **Fix**: Dynamically compute the 16:9 vertical crop region Rect using the lens's physical `activeArraySize` and apply `CaptureRequest.SCALER_CROP_REGION` on both preview and still capture requests in `MainActivity.kt`.
3. **Resolution Subtitle Label**:
   - **Fix**: Change the subtitle from hardcoded `"VIDEO"` to dynamically show `"PHOTO"` when `isPhotoMode` is true.
4. **Rendering Progress Bar**:
   - **Fix**: Expose Compose state variables (`isRendering`, `renderingPath`, `currentPhase`, `progressFraction`) in `VideoRendererService`. During DNG extraction and demosaicing, calculate frame progress against total frames. In `GalleryActivity.kt`, display a reactive progress bar and phase text for the rendering item instead of the render button.
5. **Dynamic Bitrate**:
   - **Fix**: Calculate the average ISO across all frames and compute the target HEVC bitrate dynamically based on resolution and average ISO (to allocate higher bitrates to noisy/high-ISO footage and save storage on clean/low-ISO footage), constrained between 10 Mbps and 60 Mbps.

## User Review Required

> [!IMPORTANT]
> - By forcing `cropStartRow` to be even, Bayer pattern alignment is guaranteed across all crop ratios, solving the telephoto pink frames.
> - The live viewport will no longer stretch or squish when switching aspect ratios.
> - A beautiful, smooth progress bar will show up in the Gallery row during video rendering.

## Proposed Changes

### 1. Viewport Crop & UI Label
#### [MODIFY] [MainActivity.kt](file:///c:/Users/Ayush/AndroidStudioProjects/RawRecorder/app/src/main/java/com/example/rawrecorder/MainActivity.kt)
- Define `getScalerCropRegion(): Rect` returning the calculated 16:9 crop Rect or active array.
- Apply `builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)` in `updateRepeatingRequest` and `triggerSinglePhotoCapture`.
- Update the RESOLUTIONS bottom nav item subtitle to conditionally show `"PHOTO"` or `"VIDEO"`.

### 2. Single Photo Capture Completion & Progress
#### [MODIFY] [PhotoCaptureEngine.kt](file:///c:/Users/Ayush/AndroidStudioProjects/RawRecorder/app/src/main/java/com/example/rawrecorder/PhotoCaptureEngine.kt)
- Adapt the `DngExtractor.extractAyushrawToDngs` progress callback parameters to match the new signature.

### 3. Even Crop Offset & 10-bit consecutive DNG
#### [MODIFY] [DngExtractor.kt](file:///c:/Users/Ayush/AndroidStudioProjects/RawRecorder/app/src/main/java/com/example/rawrecorder/gallery/DngExtractor.kt)
- Apply `and -2` to the calculated `cropStartRow` to ensure it is always even.
- Update `extractAyushrawToDngs` callback to pass `(progress, total)` frames.

#### [MODIFY] [extract_dng.py](file:///c:/Users/Ayush/AndroidStudioProjects/RawRecorder/extract_dng.py)
- Update `crop_start_row` to be even by applying `& -2`.

---

### 4. Custom Demosaic State, Progress & Dynamic Bitrate
#### [MODIFY] [VideoRendererService.kt](file:///c:/Users/Ayush/AndroidStudioProjects/RawRecorder/app/src/main/java/com/example/rawrecorder/gallery/VideoRendererService.kt)
- Declare `@getValue @setValue` Compose state variables: `isRendering`, `renderingPath`, `currentPhase`, `progressFraction`.
- Apply `and -2` to `cropStartRow` in `processAyushrawToRgbStream`.
- Keep a running sum of frame ISOs, calculate average ISO, and compute dynamic bitrate based on pixel count and ISO.
- Pipe progress updates to state variables.

---

### 5. Gallery Progress Indicator UI
#### [MODIFY] [GalleryActivity.kt](file:///c:/Users/Ayush/AndroidStudioProjects/RawRecorder/app/src/main/java/com/example/rawrecorder/gallery/GalleryActivity.kt)
- Read progress states from `VideoRendererService` and show a `LinearProgressIndicator` with progress phase text in place of the button for the actively rendering file.

## Verification Plan

### Automated Tests
- None.

### Manual Verification
1. Open the app; switch to 16:9 mode on the Telephoto lens and capture a photo. Verify the saved DNG is not pink and the viewport is not stretched.
2. Select Video mode, capture a 16:9 raw video, open Gallery, and render it. Verify the progress bar updates smoothly, the rendering finishes successfully, and the video has correct colors and dynamic bitrate.
