#!/usr/bin/env python3
"""
calculate_ccm_from_dcp.py - Calculate 3x3 CCM from DNG Camera Profile (.dcp)

Parses a standard Adobe/DNG Camera Profile (.dcp) file, extracts the factory-calibrated
ColorMatrix or ForwardMatrix, and computes the exact row-normalized 3x3 CCM
mapping the raw camera sensor to a target cinema color space (e.g., ARRI Wide Gamut 3).
"""

import sys
import os
import io
import argparse
import struct
import numpy as np
import tifffile
import colour

def patch_dcp_header(dcp_path):
    """
    Reads a .dcp file, checks if it has the DNG profile 'IIRC' header,
    and returns a patched bytes object with standard 'II*\0' header so tifffile can parse it.
    """
    with open(dcp_path, 'rb') as f:
        data = bytearray(f.read())
        
    if len(data) < 4:
        raise ValueError("Invalid DCP file size (too small).")
        
    header = bytes(data[:4])
    if header == b'IIRC':
        # Patch header to standard Intel TIFF
        data[0:4] = b'II\x2a\x00'
        print("Detected custom DCP header 'IIRC'. Patched to standard TIFF in memory.")
    elif header in (b'II\x2a\x00', b'MM\x00\x2a'):
        print("Detected standard TIFF header.")
    else:
        print(f"Warning: Unknown header signature {header}. Attempting to parse anyway...")
        
    return bytes(data)

def extract_dcp_matrices(dcp_bytes):
    """Parses the patched DCP bytes using tifffile and extracts color matrices."""
    tags = {}
    with tifffile.TiffFile(io.BytesIO(dcp_bytes)) as tif:
        # Check first page
        page = tif.pages[0]
        page_tags = page.tags
        
        # Mapping tag codes to names
        tag_specs = {
            50721: 'ColorMatrix1',
            50722: 'ColorMatrix2',
            50964: 'ForwardMatrix1',
            50965: 'ForwardMatrix2',
            50778: 'CalibrationIlluminant1',
            50779: 'CalibrationIlluminant2'
        }
        
        for code, name in tag_specs.items():
            if code in page_tags:
                tag = page_tags[code]
                tags[name] = np.array(tag.value)
                print(f"Extracted {name} (Tag {code})")
            else:
                tags[name] = None
                
    return tags

def parse_rational_matrix(flat_array):
    """Converts a flat list of DNG rationals (numerator, denominator) into a 3x3 float matrix."""
    if flat_array is None:
        return None
        
    # Standard DNG TIFF tags represent values as rational numbers (pairs of ints)
    # If the array is flat and contains 18 elements (9 pairs), reshape to (9, 2)
    if len(flat_array) == 18:
        pairs = flat_array.reshape((9, 2))
        floats = pairs[:, 0] / pairs[:, 1]
    elif len(flat_array) == 9:
        # Already parsed to floats
        floats = flat_array
    else:
        raise ValueError(f"Unexpected matrix array size: {len(flat_array)}")
        
    return floats.reshape((3, 3))

def main():
    spaces = sorted(list(colour.RGB_COLOURSPACES.keys()))
    
    parser = argparse.ArgumentParser(
        description="Calculate 3x3 CCM mathematically from a DNG Camera Profile (.dcp)."
    )
    parser.add_argument(
        "--dcp", "-d", type=str, required=True,
        help="Path to the .dcp profile file."
    )
    parser.add_argument(
        "--target", "-t", type=str, default="ARRI Wide Gamut 3",
        choices=spaces,
        help="Target cinema or display color space (default: 'ARRI Wide Gamut 3')."
    )
    parser.add_argument(
        "--output-format", "-f", type=str, default="python",
        choices=["python", "raw", "xml"],
        help="Output format for the 3x3 matrix (default: 'python')."
    )
    args = parser.parse_args()
    
    if not os.path.exists(args.dcp):
        print(f"Error: File '{args.dcp}' not found.")
        sys.exit(1)
        
    print(f"Reading DNG Camera Profile: {args.dcp}")
    try:
        patched_bytes = patch_dcp_header(args.dcp)
        tags = extract_dcp_matrices(patched_bytes)
    except Exception as e:
        print(f"Failed to read or parse DCP profile: {e}")
        sys.exit(1)
        
    # Parse matrices to 3x3 floats
    cm1 = parse_rational_matrix(tags['ColorMatrix1'])
    cm2 = parse_rational_matrix(tags['ColorMatrix2'])
    fm1 = parse_rational_matrix(tags['ForwardMatrix1'])
    fm2 = parse_rational_matrix(tags['ForwardMatrix2'])
    
    ill1 = tags['CalibrationIlluminant1']
    ill2 = tags['CalibrationIlluminant2']
    
    # Select target color space
    target_space = colour.RGB_COLOURSPACES[args.target]
    print(f"\nTarget color space: {target_space.name}")
    
    obs = colour.CCS_ILLUMINANTS['CIE 1931 2 Degree Standard Observer']
    
    ccm = None
    source_method = ""
    
    # Determine the best calibration path
    # Method A: Use ForwardMatrix2 (typically calibrated for D65/D50 standard daylight)
    if fm2 is not None and (ill2 == 21 or ill2 is None):
        print("Using ForwardMatrix2 (D65 Calibration) for calculation...")
        # ForwardMatrix in DNG maps white-balanced camera sensor to D50 XYZ PCS coordinates.
        # We adapt D50 XYZ coordinates to target space RGB.
        d50_xy = obs['D50']
        xyz_d50 = fm2.T  # columns of fm2 represent Red, Green, Blue XYZ
        
        # Convert D50 XYZ to target space RGB with chromatic adaptation
        ccm = colour.XYZ_to_RGB(xyz_d50, target_space, illuminant=d50_xy)
        source_method = "ForwardMatrix2 (D50 adapted to target)"
        
    elif fm1 is not None and ill1 == 17:
        # Standard Light A (2850K) forward matrix - less ideal for daylight, but available
        print("Using ForwardMatrix1 (Standard Light A Calibration) for calculation...")
        std_a_xy = obs['A']
        xyz_a = fm1.T
        ccm = colour.XYZ_to_RGB(xyz_a, target_space, illuminant=std_a_xy)
        source_method = "ForwardMatrix1 (Standard Light A adapted to target)"
        
    # Method B: Use ColorMatrix2 (typically calibrated for D65)
    elif cm2 is not None:
        print("Using ColorMatrix2 (D65 Calibration) for calculation...")
        # ColorMatrix2 maps D65 XYZ to camera sensor space.
        # We invert it to map camera sensor to D65 XYZ, then map to target space.
        inv_cm2 = np.linalg.inv(cm2)
        xyz_d65 = inv_cm2.T # columns of inverse CM represent Red, Green, Blue XYZ
        
        d65_xy = obs['D65']
        ccm = colour.XYZ_to_RGB(xyz_d65, target_space, illuminant=d65_xy)
        source_method = "ColorMatrix2 (Inverse mapped to D65)"
        
    elif cm1 is not None:
        print("Using ColorMatrix1 (Standard Light A Calibration) for calculation...")
        inv_cm1 = np.linalg.inv(cm1)
        xyz_a = inv_cm1.T
        
        std_a_xy = obs['A']
        ccm = colour.XYZ_to_RGB(xyz_a, target_space, illuminant=std_a_xy)
        source_method = "ColorMatrix1 (Inverse mapped to Standard Light A)"
        
    else:
        print("Error: No valid ColorMatrix or ForwardMatrix found in the DCP profile.")
        sys.exit(1)
        
    # Row-normalize the CCM so it preserves neutral balance (rows sum to exactly 1.0)
    row_sums = np.sum(ccm, axis=1, keepdims=True)
    normalized_ccm = ccm / row_sums
    
    print("\n" + "="*50)
    print("--- COMPUTATION COMPLETE ---")
    print(f"Calibration Source: {source_method}")
    print(f"Target Space      : {target_space.name}")
    print("="*50)
    
    print("\nYour Custom 3x3 Color Correction Matrix is:")
    if args.output_format == "python":
        print("np.array([")
        for row in normalized_ccm:
            print(f"    [{row[0]:.6f}, {row[1]:.6f}, {row[2]:.6f}],")
        print("])")
    elif args.output_format == "xml":
        print(f"<ColorMatrix rows=\"3\" cols=\"3\">")
        print(f"  {normalized_ccm[0,0]:.6f} {normalized_ccm[0,1]:.6f} {normalized_ccm[0,2]:.6f}")
        print(f"  {normalized_ccm[1,0]:.6f} {normalized_ccm[1,1]:.6f} {normalized_ccm[1,2]:.6f}")
        print(f"  {normalized_ccm[2,0]:.6f} {normalized_ccm[2,1]:.6f} {normalized_ccm[2,2]:.6f}")
        print(f"</ColorMatrix>")
    else: # raw
        for row in normalized_ccm:
            print(f"{row[0]:.6f} {row[1]:.6f} {row[2]:.6f}")
            
    print("\nRow Sum Checks (Should be exactly 1.0):")
    for i, r_sum in enumerate(np.sum(normalized_ccm, axis=1)):
        channel = ["Red", "Green", "Blue"][i]
        print(f"  {channel} row sum: {r_sum:.4f}")
        
    print("\nUsage:")
    print("  Multiply white-balanced linear sensor pixels by this matrix:")
    print("  calibrated_rgb = np.dot(white_balanced_linear_rgb, normalized_ccm.T)")

if __name__ == "__main__":
    main()
