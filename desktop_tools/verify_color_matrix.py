"""
Verify the combined color matrix computation for the Pixel 6 Pro main camera.
Compare app's matrix math with a reference numpy implementation.
"""
import numpy as np

# D50 white point in XYZ
D50_XYZ = np.array([0.9642, 1.0000, 0.8249])

# sRGB from XYZ (D50-adapted Bradford)
sRGBfromXYZ = np.array([
    [ 3.1338561, -1.6168667, -0.4906146],
    [-0.9787684,  1.9161415,  0.0334540],
    [ 0.0719453, -0.2289914,  1.4052427]
])

# === Main Camera (sensor 0) ===
# ForwardMatrix1 (StdA / illuminant 1)
FM1_main = np.array([
    [0.4078, 0.4619, 0.0946],
    [0.2358, 0.7358, 0.0284],
    [0.1183, 0.0004, 0.7063]
])

# ForwardMatrix2 (D65 / illuminant 2)
FM2_main = np.array([
    [0.3666, 0.4641, 0.1335],
    [0.1639, 0.7979, 0.0383],
    [0.0477, 0.0042, 0.7733]
])

def compute_camera_to_xyz(FM):
    """Compute camera-RGB -> XYZ-D50 matrix per DNG spec:
    camToXYZ = FM * diag(FM^-1 * D50_XYZ)
    """
    fm_inv = np.linalg.inv(FM)
    d = fm_inv @ D50_XYZ  # D50 white in camera RGB space
    camToXYZ = FM * d[np.newaxis, :]  # FM @ diag(d), equivalent in numpy
    return camToXYZ

def compute_combined(FM):
    """Compute combined camera-RGB -> sRGB-linear matrix."""
    camToXYZ = compute_camera_to_xyz(FM)
    combined = sRGBfromXYZ @ camToXYZ
    return combined

print("=" * 70)
print("PIXEL 6 PRO - MAIN CAMERA COLOR MATRIX VERIFICATION")
print("=" * 70)

# Compute combined matrices for each illuminant
combined_FM1 = compute_combined(FM1_main)
combined_FM2 = compute_combined(FM2_main)

print("\n--- Combined matrix for FM1 (StdA) ---")
print(f"[{combined_FM1[0,0]:.6f}, {combined_FM1[0,1]:.6f}, {combined_FM1[0,2]:.6f}]")
print(f"[{combined_FM1[1,0]:.6f}, {combined_FM1[1,1]:.6f}, {combined_FM1[1,2]:.6f}]")
print(f"[{combined_FM1[2,0]:.6f}, {combined_FM1[2,1]:.6f}, {combined_FM1[2,2]:.6f}]")

print("\n--- Combined matrix for FM2 (D65) ---")
print(f"[{combined_FM2[0,0]:.6f}, {combined_FM2[0,1]:.6f}, {combined_FM2[0,2]:.6f}]")
print(f"[{combined_FM2[1,0]:.6f}, {combined_FM2[1,1]:.6f}, {combined_FM2[1,2]:.6f}]")
print(f"[{combined_FM2[2,0]:.6f}, {combined_FM2[2,1]:.6f}, {combined_FM2[2,2]:.6f}]")

# Test: neutral should map to [1,1,1]
print("\n--- Neutral verification [1,1,1] -> sRGB ---")
neutral = np.array([1.0, 1.0, 1.0])
print(f"FM1 combined * [1,1,1] = {combined_FM1 @ neutral}")
print(f"FM2 combined * [1,1,1] = {combined_FM2 @ neutral}")

# Test: primary color responses
print("\n--- Primary color mapping ---")
# Pure camera red
for name, cam_rgb in [("R=[1,0,0]", [1,0,0]), ("G=[0,1,0]", [0,1,0]), ("B=[0,0,1]", [0,0,1])]:
    srgb1 = combined_FM1 @ cam_rgb
    srgb2 = combined_FM2 @ cam_rgb
    print(f"  FM1: {name} -> sRGB=({srgb1[0]:.4f}, {srgb1[1]:.4f}, {srgb1[2]:.4f})")
    print(f"  FM2: {name} -> sRGB=({srgb2[0]:.4f}, {srgb2[1]:.4f}, {srgb2[2]:.4f})")
    print()

# Test saturation on a standard color
print("--- Standard color check (D65 neutral: R=0.5, G=0.5, B=0.5) ---")
gray = np.array([0.5, 0.5, 0.5])
srgb1 = combined_FM1 @ gray
srgb2 = combined_FM2 @ gray
print(f"  FM1: {srgb1}")
print(f"  FM2: {srgb2}")

print("\n--- Colorful test (saturated red-green-blue gradient) ---")
test_colors = [
    ("Red-ish", [0.8, 0.2, 0.2]),
    ("Green-ish", [0.2, 0.8, 0.2]),
    ("Blue-ish", [0.2, 0.2, 0.8]),
    ("Yellow-ish", [0.8, 0.8, 0.2]),
    ("Cyan-ish", [0.2, 0.8, 0.8]),
    ("Magenta-ish", [0.8, 0.2, 0.8]),
]
for name, cam_rgb in test_colors:
    srgb1 = combined_FM1 @ cam_rgb
    srgb2 = combined_FM2 @ cam_rgb
    print(f"  {name:15s} camera=({cam_rgb[0]:.1f},{cam_rgb[1]:.1f},{cam_rgb[2]:.1f})")
    print(f"    FM1(StdA) -> ({srgb1[0]:.4f}, {srgb1[1]:.4f}, {srgb1[2]:.4f})")
    print(f"    FM2(D65)  -> ({srgb2[0]:.4f}, {srgb2[1]:.4f}, {srgb2[2]:.4f})")

# Check what dcraw would produce (using ColorMatrix inverse approach)
print("\n\n=== DCRAW APPROACH (ColorMatrix inverse) ===")
# ColorMatrix1 maps XYZ -> camera_RGB under StdA
CM1_main = np.array([
    [1.1234, -0.5774, 0.0083],
    [-0.3535, 1.2410, 0.1210],
    [-0.0303, 0.2176, 0.5922]
])

CM2_main = np.array([
    [1.0662, -0.4641, -0.0954],
    [-0.3284, 1.1970, 0.1451],
    [-0.0170, 0.1989, 0.5182]
])

# dcraw: camera_RGB -> XYZ = ColorMatrix^(-1)
cam_to_xyz_dcraw_1 = np.linalg.inv(CM1_main)
cam_to_xyz_dcraw_2 = np.linalg.inv(CM2_main)

combined_dcraw_1 = sRGBfromXYZ @ cam_to_xyz_dcraw_1
combined_dcraw_2 = sRGBfromXYZ @ cam_to_xyz_dcraw_2

print("\n--- Combined matrix via CM1 inverse (StdA) ---")
print(f"[{combined_dcraw_1[0,0]:.6f}, {combined_dcraw_1[0,1]:.6f}, {combined_dcraw_1[0,2]:.6f}]")
print(f"[{combined_dcraw_1[1,0]:.6f}, {combined_dcraw_1[1,1]:.6f}, {combined_dcraw_1[1,2]:.6f}]")
print(f"[{combined_dcraw_1[2,0]:.6f}, {combined_dcraw_1[2,1]:.6f}, {combined_dcraw_1[2,2]:.6f}]")

print("\n--- Combined matrix via CM2 inverse (D65) ---")
print(f"[{combined_dcraw_2[0,0]:.6f}, {combined_dcraw_2[0,1]:.6f}, {combined_dcraw_2[0,2]:.6f}]")
print(f"[{combined_dcraw_2[1,0]:.6f}, {combined_dcraw_2[1,1]:.6f}, {combined_dcraw_2[1,2]:.6f}]")
print(f"[{combined_dcraw_2[2,0]:.6f}, {combined_dcraw_2[2,1]:.6f}, {combined_dcraw_2[2,2]:.6f}]")

print("\n--- Color test via CM1 inverse ---")
for name, cam_rgb in test_colors:
    srgb = combined_dcraw_1 @ cam_rgb
    print(f"  {name:15s} -> ({srgb[0]:.4f}, {srgb[1]:.4f}, {srgb[2]:.4f})")

print("\n--- Color test via CM2 inverse ---")
for name, cam_rgb in test_colors:
    srgb = combined_dcraw_2 @ cam_rgb
    print(f"  {name:15s} -> ({srgb[0]:.4f}, {srgb[1]:.4f}, {srgb[2]:.4f})")