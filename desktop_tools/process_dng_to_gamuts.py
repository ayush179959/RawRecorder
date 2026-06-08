#!/usr/bin/env python3
"""
process_dng_to_gamuts.py - Convert DNG to Multiple Cinema Color Spaces

Loads a raw DNG, applies the calculated custom 3x3 CCMs to convert it to ARRI, RED, Sony,
and sRGB spaces, encodes them with their standard log/gamma curves, and saves the output images.
"""

import os
import sys
import numpy as np
import rawpy
import cv2
import colour

def main():
    dng_path = "desktop_tools/dng/raw.dng"
    output_dir = "desktop_tools/dng"
    
    if not os.path.exists(dng_path):
        print(f"Error: Raw DNG file not found at: {dng_path}")
        sys.exit(1)
        
    print(f"Loading raw DNG image: {dng_path}")
    try:
        with rawpy.imread(dng_path) as raw:
            # Process to strictly linear space, using camera white balance
            # and raw color space (no input profile conversion)
            print("Demosaicing raw frame into linear space...")
            rgb_linear_16 = raw.postprocess(
                gamma=(1, 1),
                no_auto_bright=True,
                use_camera_wb=True, # Apply camera AWB
                output_color=rawpy.ColorSpace.raw,
                output_bps=16
            )
    except Exception as e:
        print(f"Failed to load DNG: {e}")
        sys.exit(1)
        
    # Convert 16-bit integer [0, 65535] to float32 [0.0, 1.0] for math
    img_linear = rgb_linear_16.astype(np.float32) / 65535.0
    h, w = img_linear.shape[:2]
    print(f"Demosaiced image resolution: {w}x{h}")
    
    # ----------------------------------------------------
    # Define the custom calculated 3x3 CCMs
    # ----------------------------------------------------
    ccm_arri = np.array([
        [0.896141, 0.013035, 0.090824],
        [0.296479, 0.659254, 0.044267],
        [0.077347, 0.141655, 0.780998],
    ])
    
    ccm_red = np.array([
        [0.794056, 0.074082, 0.131862],
        [0.306848, 0.547322, 0.145830],
        [0.092788, 0.154454, 0.752758],
    ])
    
    ccm_sony = np.array([
        [0.816000, 0.076994, 0.107006],
        [0.301354, 0.667181, 0.031465],
        [0.076323, 0.127750, 0.795927],
    ])
    
    ccm_srgb = np.array([
        [0.985005, -0.047825, 0.062820],
        [0.140245, 0.975620, -0.115864],
        [-0.015147, -0.025007, 1.040153],
    ])
    
    # List of targets to process
    # (Color Space Name, CCM, Log/Gamma Method Name, File Name Suffix, is_log)
    targets = [
        ("ARRI Wide Gamut 3", ccm_arri, "ARRI LogC3", "arri_logc3", True),
        ("REDWideGamutRGB", ccm_red, "Log3G10", "red_log3g10", True),
        ("Sony S-Gamut3.Cine", ccm_sony, "S-Log3", "sony_slog3", True),
        ("Cineon Film Log (sRGB)", ccm_srgb, "Cineon", "cineon_log", True),
        ("sRGB (Linear to Display)", ccm_srgb, "sRGB", "srgb_display", False)
    ]
    
    print("\nProcessing conversions...")
    for space_name, ccm, method, suffix, is_log in targets:
        print(f"\nTransforming to: {space_name}...")
        
        # 1. Apply 3x3 CCM to linear RGB data
        # input shape is (H, W, 3), ccm shape is (3, 3)
        # We perform matrix multiplication: transformed = img_linear @ CCM.T
        img_transformed = img_linear @ ccm.T
        
        # 2. Apply Transfer Function (OETF)
        # Log curves can handle values above 1.0 (highlights), so we apply first and then clip/scale
        if is_log:
            print(f"Applying log encoding: {method}...")
            img_encoded = colour.log_encoding(img_transformed, method=method)
        else:
            # Display referred sRGB uses cctf_encoding
            print("Applying standard sRGB display gamma...")
            img_encoded = colour.cctf_encoding(img_transformed, method=method)
            
        # Clip to safe display range [0.0, 1.0] and scale to 16-bit integer [0, 65535]
        img_out = np.clip(img_encoded, 0.0, 1.0)
        img_out_16 = (img_out * 65535.0).astype(np.uint16)
        
        # Convert RGB to BGR for OpenCV saving
        img_bgr = cv2.cvtColor(img_out_16, cv2.COLOR_RGB2BGR)
        
        out_path = os.path.join(output_dir, f"raw_{suffix}.png")
        print(f"Saving 16-bit PNG: {out_path}")
        cv2.imwrite(out_path, img_bgr)
        
    print("\nAll conversions successfully processed and saved inside 'desktop_tools/dng/'.")

if __name__ == "__main__":
    main()
