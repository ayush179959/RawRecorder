#!/usr/bin/env python3
"""
calibrate_camera_ccm.py - Interactive Camera CCM Calibration Tool

Loads a raw DNG image, displays an interactive window to select the Macbeth ColorChecker
corners, extracts the linear average RGB values from the 24 patches, and calculates the
Color Correction Matrix (CCM) to map to a target cinema color space.
"""

import os
import sys
import argparse
import numpy as np
import cv2
import rawpy
import colour

# Class to handle interactive mouse clicks
class ColorCheckerSelector:
    def __init__(self, image_display, window_name="Select ColorChecker Corners"):
        self.image_display = image_display.copy()
        self.orig_display = image_display.copy()
        self.window_name = window_name
        self.clicks = []
        
        # Color Checker patch names in order (1 to 24)
        self.patch_names = [
            "1: Dark Skin", "2: Light Skin", "3: Blue Sky", "4: Foliage", "5: Blue Flower", "6: Bluish Green",
            "7: Orange", "8: Purplish Blue", "9: Moderate Red", "10: Purple", "11: Yellow Green", "12: Orange Yellow",
            "13: Blue", "14: Green", "15: Red", "16: Yellow", "17: Magenta", "18: Cyan",
            "19: White", "20: Neutral 8", "21: Neutral 6.5", "22: Neutral 5", "23: Neutral 3.5", "24: Black"
        ]

    def mouse_callback(self, event, x, y, flags, param):
        if event == cv2.EVENT_LBUTTONDOWN:
            if len(self.clicks) < 4:
                self.clicks.append((x, y))
                self.draw_clicks()
                cv2.imshow(self.window_name, self.image_display)

    def draw_clicks(self):
        self.image_display = self.orig_display.copy()
        # Draw all registered clicks
        for i, pt in enumerate(self.clicks):
            cv2.circle(self.image_display, pt, 8, (0, 0, 255), -1)
            cv2.putText(
                self.image_display, f"Corner {i+1}", (pt[0] + 12, pt[1] + 5),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2
            )
        
        # Guide instructions based on click count
        instructions = ""
        if len(self.clicks) == 0:
            instructions = "Click 1/4: Center of TOP-LEFT patch (Dark Skin)"
        elif len(self.clicks) == 1:
            instructions = "Click 2/4: Center of TOP-RIGHT patch (Bluish Green)"
        elif len(self.clicks) == 2:
            instructions = "Click 3/4: Center of BOTTOM-LEFT patch (White)"
        elif len(self.clicks) == 3:
            instructions = "Click 4/4: Center of BOTTOM-RIGHT patch (Black)"
        else:
            instructions = "Grid calculated! Press ENTER to confirm, or 'c' to clear and retry."

        # Draw guidance banner
        h, w = self.image_display.shape[:2]
        cv2.rectangle(self.image_display, (0, h - 45), (w, h), (0, 0, 0), -1)
        cv2.putText(
            self.image_display, instructions, (20, h - 15),
            cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 1, cv2.LINE_AA
        )

    def clear(self):
        self.clicks = []
        self.image_display = self.orig_display.copy()
        self.draw_clicks()
        cv2.imshow(self.window_name, self.image_display)

def interpolate_macbeth_grid(corners):
    """Interpolates a 4x6 grid of coordinates given the 4 corner points."""
    # corners: [top_left, top_right, bottom_left, bottom_right]
    c_tl, c_tr, c_bl, c_br = corners
    
    grid = np.zeros((4, 6, 2))
    
    for r in range(4):
        v = r / 3.0
        for c in range(6):
            u = c / 5.0
            # Bilinear interpolation formula
            pt = (1 - u) * (1 - v) * np.array(c_tl) + \
                 u * (1 - v) * np.array(c_tr) + \
                 (1 - u) * v * np.array(c_bl) + \
                 u * v * np.array(c_br)
            grid[r, c] = pt
            
    # Flatten grid to 24 points ordered row by row (1 to 24)
    return grid.reshape((24, 2))

def main():
    # Gather available color spaces and color checkers
    spaces = sorted(list(colour.RGB_COLOURSPACES.keys()))
    checkers = sorted(list(colour.CCS_COLOURCHECKERS.keys()))

    parser = argparse.ArgumentParser(
        description="Interactive Macbeth ColorChecker 3x3 CCM Calibration Tool."
    )
    parser.add_argument(
        "--image", "-i", type=str, required=True,
        help="Path to the RAW DNG file containing the ColorChecker target."
    )
    parser.add_argument(
        "--target", "-t", type=str, default="ARRI Wide Gamut 3",
        choices=spaces,
        help="Target cinema or display color space (default: 'ARRI Wide Gamut 3')."
    )
    parser.add_argument(
        "--checker", "-c", type=str, default="ColorChecker 2005",
        choices=checkers,
        help="Reference ColorChecker target version (default: 'ColorChecker 2005')."
    )
    parser.add_argument(
        "--box-size", "-b", type=int, default=50,
        help="Size of the crop sampling box in pixels inside the high-res image (default: 50)."
    )
    args = parser.parse_args()

    if not os.path.exists(args.image):
        print(f"Error: File '{args.image}' not found.")
        sys.exit(1)

    print(f"Loading RAW DNG: {args.image}")
    try:
        with rawpy.imread(args.image) as raw:
            # Process DNG as strictly linear (gamma=1.0, no auto brightness, no camera AWB)
            # Use raw color space to prevent any input matrix mapping
            print("Demosaicing raw frame into linear RGB space...")
            rgb_linear = raw.postprocess(
                gamma=(1, 1),
                no_auto_bright=True,
                use_camera_wb=False,
                output_color=rawpy.ColorSpace.raw,
                output_bps=16
            )
    except Exception as e:
        print(f"Failed to load or process DNG: {e}")
        sys.exit(1)

    h_orig, w_orig = rgb_linear.shape[:2]
    print(f"Loaded image resolution: {w_orig}x{h_orig}")

    # Create a display image for visual corner selection
    # Since linear raw data is extremely dark, scale and apply gamma for preview purposes
    preview_img = rgb_linear.astype(np.float32) / 65535.0
    
    # Calculate simple auto-gain to make details visible
    percentile_val = np.percentile(preview_img, 98)
    gain = 0.8 / max(0.001, percentile_val)
    print(f"Auto-gain scale factor for preview: {gain:.2f}x")
    preview_img = np.clip(preview_img * gain, 0.0, 1.0)
    
    # Apply standard display gamma (2.2) so the user can easily see the chart
    preview_img = np.power(preview_img, 1.0 / 2.2)
    preview_img = (preview_img * 255).astype(np.uint8)
    
    # Resize preview so it fits nicely on screen
    max_preview_h = 750
    scale_factor = max_preview_h / h_orig
    w_preview = int(w_orig * scale_factor)
    h_preview = int(h_orig * scale_factor)
    preview_resized = cv2.resize(preview_img, (w_preview, h_preview))
    
    # Convert preview to BGR for OpenCV
    preview_bgr = cv2.cvtColor(preview_resized, cv2.COLOR_RGB2BGR)

    # Launch GUI Window for click selection
    win_name = "Calibrate CCM - Select Macbeth Corners"
    cv2.namedWindow(win_name)
    selector = ColorCheckerSelector(preview_bgr, win_name)
    selector.draw_clicks()
    cv2.imshow(win_name, selector.image_display)
    cv2.setMouseCallback(win_name, selector.mouse_callback)

    print("\n" + "="*50)
    print("INTERACTIVE INSTRUCTIONS:")
    print("  1. Click the center of the TOP-LEFT patch (Dark Skin).")
    print("  2. Click the center of the TOP-RIGHT patch (Bluish Green).")
    print("  3. Click the center of the BOTTOM-LEFT patch (White).")
    print("  4. Click the center of the BOTTOM-RIGHT patch (Black).")
    print("  - Press 'c' to clear and restart clicks.")
    print("  - Press 'ENTER' once all 4 corners are selected to compute.")
    print("  - Press 'q' or 'ESC' to abort.")
    print("="*50)

    grid_points_orig = None

    while True:
        key = cv2.waitKey(20) & 0xFF
        if key == 27 or key == ord('q'):  # ESC or q
            print("Calibration aborted by user.")
            cv2.destroyAllWindows()
            sys.exit(0)
            
        elif key == ord('c'):  # Clear clicks
            selector.clear()
            grid_points_orig = None
            
        elif key in (13, 10):  # ENTER
            if len(selector.clicks) < 4:
                print(f"Waiting for 4 corners (currently selected: {len(selector.clicks)}/4).")
            else:
                # Interpolate grid on preview coordinates
                grid_points_preview = interpolate_macbeth_grid(selector.clicks)
                
                # Scale grid points back to original high-res coordinates
                grid_points_orig = grid_points_preview / scale_factor
                
                # Draw grid and preview boxes to verify alignment
                verify_display = selector.orig_display.copy()
                box_half_preview = int(args.box_size * scale_factor // 2)
                
                for i, pt in enumerate(grid_points_preview):
                    pt_int = (int(pt[0]), int(pt[1]))
                    # Draw a rectangle around the sampling zone
                    cv2.rectangle(
                        verify_display,
                        (pt_int[0] - box_half_preview, pt_int[1] - box_half_preview),
                        (pt_int[0] + box_half_preview, pt_int[1] + box_half_preview),
                        (0, 255, 0), 1
                    )
                    # Draw center point
                    cv2.circle(verify_display, pt_int, 2, (0, 255, 0), -1)
                    # Label indices
                    cv2.putText(
                        verify_display, str(i+1), (pt_int[0] - 5, pt_int[1] - 5),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.4, (255, 255, 0), 1
                    )
                    
                h_disp, w_disp = verify_display.shape[:2]
                cv2.rectangle(verify_display, (0, h_disp - 45), (w_disp, h_disp), (0, 128, 0), -1)
                cv2.putText(
                    verify_display, "Review sampling grid. Press ENTER to calculate CCM, or 'c' to retry.",
                    (15, h_disp - 15), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1, cv2.LINE_AA
                )
                
                cv2.imshow(win_name, verify_display)
                selector.image_display = verify_display
                
                # Wait for next ENTER to confirm
                while True:
                    key_confirm = cv2.waitKey(0) & 0xFF
                    if key_confirm in (13, 10):
                        break  # proceed to computation
                    elif key_confirm == ord('c'):
                        selector.clear()
                        grid_points_orig = None
                        break
                        
                if grid_points_orig is not None:
                    break  # Break outer loop and perform calculation

    cv2.destroyAllWindows()

    # Perform color sampling from the high-res linear image
    print("\nSampling linear values from DNG...")
    measured_sensor_rgb = []
    box_half = args.box_size // 2

    # Normalization factor for 16-bit TIFF image
    norm_factor = 65535.0

    for i, (x, y) in enumerate(grid_points_orig):
        xi, yi = int(x), int(y)
        
        # Calculate cropping boundaries and clip to image borders
        x0 = max(0, xi - box_half)
        y0 = max(0, yi - box_half)
        x1 = min(w_orig, xi + box_half)
        y1 = min(h_orig, yi + box_half)
        
        # Crop region from linear demosaiced array
        patch_crop = rgb_linear[y0:y1, x0:x1]
        
        # Average the RGB values inside the patch crop box and scale to [0.0, 1.0]
        mean_rgb = np.mean(patch_crop, axis=(0, 1)) / norm_factor
        measured_sensor_rgb.append(mean_rgb)
        
        print(f"  Patch {i+1:02d} ({selector.patch_names[i]:<20}): Linear RGB = [{mean_rgb[0]:.4f}, {mean_rgb[1]:.4f}, {mean_rgb[2]:.4f}]")

    measured_sensor_rgb = np.array(measured_sensor_rgb)

    # Load ColorChecker reference target
    cc_target = colour.CCS_COLOURCHECKERS[args.checker]
    xyz_targets = colour.xyY_to_XYZ(list(cc_target.data.values()))

    # Fetch target color space
    target_space = colour.RGB_COLOURSPACES[args.target]
    
    # Calculate reference RGB target values under adapted whitepoint
    target_rgb = colour.XYZ_to_RGB(
        xyz_targets,
        colourspace=target_space,
        illuminant=cc_target.illuminant
    )

    # Perform least-squares optimization
    matrix_m, residuals, rank, singular_values = np.linalg.lstsq(
        measured_sensor_rgb,
        target_rgb,
        rcond=None
    )

    # Transpose matrix to standard row-sum format
    matrix_3x3 = matrix_m.T

    # Save measurements to a text file for future reference
    txt_output_path = os.path.splitext(args.image)[0] + "_measured_patches.txt"
    np.savetxt(txt_output_path, measured_sensor_rgb, fmt="%.6f")
    print(f"\nSaved measured patch values to text file: {txt_output_path}")

    # Output details
    print("\n" + "="*50)
    print("--- COMPUTATION COMPLETE ---")
    print(f"Target Space    : {target_space.name}")
    print(f"Reference Target: {cc_target.name}")
    print(f"Input DNG Image : {args.image}")
    print("="*50)

    print("\nYour Custom 3x3 Color Correction Matrix is:")
    print("np.array([")
    for row in matrix_3x3:
        print(f"    [{row[0]:.6f}, {row[1]:.6f}, {row[2]:.6f}],")
    print("])")

    row_sums = np.sum(matrix_3x3, axis=1)
    print("\nRow Sum Checks (Should be close to 1.0 to preserve neutral balance):")
    for i, row_sum in enumerate(row_sums):
        channel = ["Red", "Green", "Blue"][i]
        print(f"  {channel} row sum: {row_sum:.4f}")

    print("\nCalibration successful!")

if __name__ == "__main__":
    main()
