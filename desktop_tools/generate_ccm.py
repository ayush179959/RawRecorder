#!/usr/bin/env python3
"""
generate_ccm.py - Color Correction Matrix (CCM) Calculator

Calculates a 3x3 Color Correction Matrix (CCM) using linear least-squares regression
to map measured camera RGB sensor values of a 24-patch Macbeth ColorChecker to target
cinema color spaces (e.g., ARRI Wide Gamut 3, REDWideGamutRGB, S-Gamut3.Cine).
"""

import sys
import argparse
import numpy as np
import colour

# Default Macbeth ColorChecker 2005 measured sensor placeholder values (Linear RGB)
# Replace these with your actual measured patch averages.
DEFAULT_MEASURED_RGB = np.array([
    [0.115, 0.082, 0.068],  # Patch 1: Dark Skin
    [0.385, 0.298, 0.254],  # Patch 2: Light Skin
    [0.183, 0.204, 0.245],  # Patch 3: Blue Sky
    [0.108, 0.142, 0.093],  # Patch 4: Foliage
    [0.222, 0.203, 0.291],  # Patch 5: Blue Flower
    [0.198, 0.267, 0.253],  # Patch 6: Bluish Green
    [0.370, 0.218, 0.129],  # Patch 7: Orange
    [0.113, 0.128, 0.248],  # Patch 8: Purplish Blue
    [0.312, 0.134, 0.144],  # Patch 9: Moderate Red
    [0.086, 0.065, 0.118],  # Patch 10: Purple
    [0.291, 0.342, 0.158],  # Patch 11: Yellow Green
    [0.395, 0.296, 0.117],  # Patch 12: Orange Yellow
    [0.069, 0.081, 0.204],  # Patch 13: Blue
    [0.129, 0.231, 0.122],  # Patch 14: Green
    [0.264, 0.087, 0.085],  # Patch 15: Red
    [0.391, 0.354, 0.098],  # Patch 16: Yellow
    [0.274, 0.138, 0.211],  # Patch 17: Magenta
    [0.124, 0.213, 0.252],  # Patch 18: Cyan
    [0.683, 0.685, 0.669],  # Patch 19: White 9.5
    [0.528, 0.531, 0.522],  # Patch 20: Neutral 8
    [0.354, 0.356, 0.351],  # Patch 21: Neutral 6.5
    [0.203, 0.204, 0.202],  # Patch 22: Neutral 5
    [0.091, 0.091, 0.092],  # Patch 23: Neutral 3.5
    [0.031, 0.031, 0.032]   # Patch 24: Black 2
])

def load_measured_values(file_path):
    """Loads 24 measured linear RGB triplets from a space/comma-separated text file."""
    try:
        data = np.loadtxt(file_path, delimiter=None)  # handles both space and comma
        if data.shape != (24, 3):
            raise ValueError(f"Input file must contain exactly 24 rows and 3 columns (RGB), found shape {data.shape}")
        return data
    except Exception as e:
        print(f"Error reading input file {file_path}: {e}")
        sys.exit(1)

def main():
    # Gather available color spaces and color checkers
    spaces = sorted(list(colour.RGB_COLOURSPACES.keys()))
    checkers = sorted(list(colour.CCS_COLOURCHECKERS.keys()))

    parser = argparse.ArgumentParser(
        description="Calculate 3x3 Color Correction Matrix (CCM) using colour-science."
    )
    parser.add_argument(
        "--input", "-i", type=str, default=None,
        help="Path to a text file containing 24 rows of measured RGB sensor triplets (space/comma separated). If not provided, placeholder values will be used."
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
        "--output-format", "-f", type=str, default="python",
        choices=["python", "raw", "xml"],
        help="Output format for the 3x3 matrix (default: 'python')."
    )
    args = parser.parse_args()

    # Load measured RGB sensor values
    if args.input:
        print(f"Loading measured sensor data from: {args.input}")
        measured = load_measured_values(args.input)
    else:
        print("No input file provided. Using default placeholder measured data for demonstration.")
        measured = DEFAULT_MEASURED_RGB

    # Load ColorChecker reference chromaticity data (xyY values)
    cc_target = colour.CCS_COLOURCHECKERS[args.checker]
    print(f"Using reference target: {cc_target.name}")
    print(f"Reference illuminant: {cc_target.illuminant}")

    # Convert reference xyY values to CIE XYZ
    xyz_targets = colour.xyY_to_XYZ(list(cc_target.data.values()))

    # Fetch target color space
    target_space = colour.RGB_COLOURSPACES[args.target]
    print(f"Target color space: {target_space.name}")

    # Convert CIE XYZ targets to the target color space RGB values
    # Uses CAT02 chromatic adaptation internally to adapt between checker illuminant and target space whitepoint
    target_rgb = colour.XYZ_to_RGB(
        xyz_targets,
        colourspace=target_space,
        illuminant=cc_target.illuminant
    )

    # Compute optimization matrix using Moore-Penrose pseudoinverse linear regression
    # measured * M ≈ target_rgb => M = pinv(measured) * target_rgb
    matrix_m, residuals, rank, singular_values = np.linalg.lstsq(
        measured,
        target_rgb,
        rcond=None
    )

    # Transpose matrix to fit standard 3x3 row-vector multiplication configuration:
    # [R_out, G_out, B_out] = [R_in, G_in, B_in] * M  <=>  out = M_3x3 * in
    matrix_3x3 = matrix_m.T

    # Display results
    print("\n" + "="*50)
    print("--- COMPUTATION COMPLETE ---")
    print(f"Target Space: {target_space.name}")
    print(f"Reference Target: {cc_target.name}")
    print("="*50)
    
    print("\nCalculated 3x3 Color Correction Matrix:")
    if args.output_format == "python":
        print("np.array([")
        for row in matrix_3x3:
            print(f"    [{row[0]:.6f}, {row[1]:.6f}, {row[2]:.6f}],")
        print("])")
    elif args.output_format == "xml":
        # Format often used in DCP or config XML files
        print(f"<ColorMatrix rows=\"3\" cols=\"3\">")
        print(f"  {matrix_3x3[0,0]:.6f} {matrix_3x3[0,1]:.6f} {matrix_3x3[0,2]:.6f}")
        print(f"  {matrix_3x3[1,0]:.6f} {matrix_3x3[1,1]:.6f} {matrix_3x3[1,2]:.6f}")
        print(f"  {matrix_3x3[2,0]:.6f} {matrix_3x3[2,1]:.6f} {matrix_3x3[2,2]:.6f}")
        print(f"</ColorMatrix>")
    else:  # raw
        for row in matrix_3x3:
            print(f"{row[0]:.6f} {row[1]:.6f} {row[2]:.6f}")

    # Row sum checks to ensure white balance is preserved
    row_sums = np.sum(matrix_3x3, axis=1)
    print("\nRow Sum Checks (Should be close to 1.0 to preserve neutral balance):")
    for i, row_sum in enumerate(row_sums):
        channel = ["Red", "Green", "Blue"][i]
        print(f"  {channel} row sum: {row_sum:.4f}")

    print("\nUsage example:")
    print("  # In python:")
    print("  calibrated_rgb = np.dot(linear_sensor_rgb, matrix_3x3.T)")

if __name__ == "__main__":
    main()
