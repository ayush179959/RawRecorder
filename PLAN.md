# Fix Plan: HEVC Video Exposure, Cineon Log, and Bitrate

## Root Cause Analysis

### Bug 1: HEVC Video Dark (vs DNG)
**Root cause**: The HEVC export uses a hardcoded `1.25x` exposure multiplier, while the DNG pipeline uses `2^(baselineExpNum/baselineExpDen)` derived from the actual camera sensor's baseline exposure. These don't match. For the main sensor (`baselineExp = 5/100`), the correct multiplier is `2^0.05 = 1.035x`, but the HEVC pipeline uses `1.25x` — which is actually *brighter* than the DNG for the main sensor. This means the darkness is NOT just an exposure gain issue. The likely culprit is that ACES filmic tone mapping produces a darker result than what the phone's default RAW viewer applies. The fix: use the actual baselineExp from the file (for sensor-correct exposure) AND apply a small ACES exposure offset (~+0.5 EV) to compensate for the cinematic look of ACES vs. typical phone RAW viewers.

### Bug 2: Cineon Log Not Applied
**Root cause**: The shader code, uniform location, and config flow all look correct structurally. The issue is likely a **GLSL `int` precision mismatch** — `glUniform1i` with a Kotlin `Int` may not correctly set the value if the uniform location is -1 (optimized out or not found). Need to add validation logging. Also possible: the shader compiler optimizes away the `uColorProfile` uniform because both branches produce a valid output and the compiler may treat `uColorProfile` as constant 0. Fix: add `LOG.d` validation of the uniform location value, and change `uColorProfile` to `uniform float` to avoid GLSL int precision issues.

### Bug 3: Bitrate Not Taking Effect
**Root cause**: `MediaFormat.KEY_BITRATE_MODE` is never set. The default is `BITRATE_MODE_VBR` which lets the encoder use significantly less bitrate than the target for "easy" content. Fix: set `KEY_BITRATE_MODE` to `BITRATE_MODE_CBR` (constant bitrate).

## Changes

### File: `VideoRendererService.kt`

#### Fix 1: Exposure gain (line ~615-616)
Replace:
```kotlin
val boostMultiplier = max(postRawBoost.toFloat(), 100f) / 100f
val exposureGain = boostMultiplier * 1.25f
```
With:
```kotlin
val boostMultiplier = postRawBoost.toFloat() / 100f
val baselineExp = if (baselineExpDen > 0) baselineExpNum.toFloat() / baselineExpDen.toFloat() else 0f
val baselineExpScale = Math.pow(2.0, baselineExp.toDouble()).toFloat()
val acesOffset = 1.5f  // +0.58 EV offset for ACES filmic vs phone RAW viewer
val exposureGain = boostMultiplier * baselineExpScale * acesOffset
```

Also apply the same fix to `generatePreview` (line ~900-901) for consistency.

#### Fix 2: Cineon Log — shader `uColorProfile` int→float
Change shader uniform declaration from `uniform int uColorProfile;` to `uniform float uColorProfile;`. Change the comparison from `if (uColorProfile == 1)` to `if (uColorProfile > 0.5)`. Update host-side from `glUniform1i` to `glUniform1f`. This avoids potential GLSL int precision issues.

#### Fix 3: Bitrate CBR (line ~452)
After `format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)`, add:
```kotlin
format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
```

#### Fix 4: Add debug logging
After `renderer.init()`, log the uniform locations:
```kotlin
Log.d(TAG, "uColorProfileLoc=$uColorProfileLoc, uExposureGainLoc=$uExposureGainLoc")
```
And in the per-frame loop, log the first frame's exposure values:
```kotlin
if (frameCount == 0) Log.d(TAG, "Frame0: boost=$postRawBoost, baselineExp=$baselineExp, gain=$exposureGain, gamut=${config.outputGamut}")
```

## Verification
1. Export a video with Rec.709 gamut and verify it matches previous brightness
2. Export with Cineon Log — video should appear flat/desaturated (log-encoded)
3. Export with a custom bitrate (e.g., 50 Mbps) and verify the file size matches expectations
4. Compare HEVC video brightness against the preview.jpg thumbnail for consistency
