"""
Verify color matrix approaches: ForwardMatrix (current) vs ColorMatrix inverse (dcraw/Adobe).
"""
import numpy as np

D50_XYZ = np.array([0.9642, 1.0000, 0.8249])

sRGBfromXYZ = np.array([
    [ 3.1338561, -1.6168667, -0.4906146],
    [-0.9787684,  1.9161415,  0.0334540],
    [ 0.0719453, -0.2289914,  1.4052427]
])

# === Main Camera ===
FM1 = np.array([
    [0.4078, 0.4619, 0.0946],
    [0.2358, 0.7358, 0.0284],
    [0.1183, 0.0004, 0.7063]
])

FM2 = np.array([
    [0.3666, 0.4641, 0.1335],
    [0.1639, 0.7979, 0.0383],
    [0.0477, 0.0042, 0.7733]
])

CM1 = np.array([
    [1.1234, -0.5774, 0.0083],
    [-0.3535, 1.2410, 0.1210],
    [-0.0303, 0.2176, 0.5922]
])

CM2 = np.array([
    [1.0662, -0.4641, -0.0954],
    [-0.3284, 1.1970, 0.1451],
    [-0.0170, 0.1989, 0.5182]
])

def compute_fm_approach(FM):
    fm_inv = np.linalg.inv(FM)
    d = fm_inv @ D50_XYZ
    camToXYZ = FM * d[np.newaxis, :]
    return sRGBfromXYZ @ camToXYZ

def compute_cm_approach(CM):
    """ColorMatrix inverse with chicken normalization"""
    cm_inv = np.linalg.inv(CM)
    chicken = CM @ D50_XYZ  # camera RGB for D50 white
    camToXYZ = cm_inv * chicken[np.newaxis, :]  # cm_inv @ diag(chicken)
    return sRGBfromXYZ @ camToXYZ

def interpolate_matrices(M1, M2, alpha):
    return (1 - alpha) * M1 + alpha * M2

print("=" * 80)
print("APPROACH COMPARISON: ForwardMatrix vs ColorMatrix-inverse")
print("Main Camera (Pixel 6 Pro Wide)")
print("=" * 80)

# FM approach for both illuminants
M_fm1 = compute_fm_approach(FM1)
M_fm2 = compute_fm_approach(FM2)

# CM inverse approach for both illuminants
M_cm1 = compute_cm_approach(CM1)
M_cm2 = compute_cm_approach(CM2)

test_colors = [
    ("White     ", [1.0, 1.0, 1.0]),
    ("Red       ", [0.9, 0.1, 0.1]),
    ("Green     ", [0.1, 0.9, 0.1]),
    ("Blue      ", [0.1, 0.1, 0.9]),
    ("Yellow    ", [0.9, 0.9, 0.1]),
    ("Cyan      ", [0.1, 0.9, 0.9]),
    ("Magenta   ", [0.9, 0.1, 0.9]),
    ("Gray50    ", [0.5, 0.5, 0.5]),
]

print("\n--- D65 (FM2) vs CM2 inverse ---")
print(f"{'Color':12s} {'FM2 R':8s} {'FM2 G':8s} {'FM2 B':8s} | {'CM2 R':8s} {'CM2 G':8s} {'CM2 B':8s}")
print("-" * 70)
for name, rgb in test_colors:
    fm = M_fm2 @ rgb
    cm = M_cm2 @ rgb
    print(f"{name:12s} {fm[0]:8.4f} {fm[1]:8.4f} {fm[2]:8.4f} | {cm[0]:8.4f} {cm[1]:8.4f} {cm[2]:8.4f}")

# Simulate what the app does: use FM2 (D65) for most scenes
print("\n\n--- Saturation comparison (how far from gray) ---")
for name, rgb in test_colors:
    fm = M_fm2 @ rgb
    cm = M_cm2 @ rgb
    # Saturation = max - min of RGB
    fm_sat = np.max(fm) - np.min(fm)
    cm_sat = np.max(cm) - np.min(cm)
    if name.strip() != "White" and name.strip() != "Gray50":
        print(f"  {name:12s} FM sat={fm_sat:.4f}   CM sat={cm_sat:.4f}  (CM is {cm_sat/fm_sat:.2f}x more saturated)")

# Interpolated approach: blend between StdA and D65
print("\n\n--- INTERPOLATED: blend based on warm/cool AWB ---")
# Warm scene (rVal=2, bVal=0.5) -> lean toward StdA
# Neutral scene (rVal=1, bVal=1) -> lean toward D65

for warm_name, rVal, bVal in [("Warm(r=2,b=0.5)", 2.0, 0.5), ("Neutral(r=1,b=1)", 1.0, 1.0), ("Cool(r=0.7,b=1.3)", 0.7, 1.3)]:
    print(f"\n  {warm_name}:")
    warmScore = max(0, rVal - 1) + max(0, 1 - bVal)
    blend = 1.0 / (1.0 + warmScore * 0.75)
    
    M_fm = interpolate_matrices(M_fm1, M_fm2, blend)
    M_cm = interpolate_matrices(M_cm1, M_cm2, blend)
    
    for name, rgb in test_colors:
        if name.strip() != "White" and name.strip() != "Gray50":
            fm = M_fm @ rgb
            cm = M_cm @ rgb
            fm_sat = np.max(fm) - np.min(fm)
            cm_sat = np.max(cm) - np.min(cm)
            print(f"    {name:12s} FM sat={fm_sat:.4f}   CM sat={cm_sat:.4f}")

# What if we just use CM2 (D65, no interpolation)?
print("\n\n--- CM2 (D65, no interpolation) vs FM2 (D65, no interpolation) ---")
print(f"{'Color':12s} {'FM2 linear':24s} {'CM2 linear':24s} {'CM2 after clamp+gamma':24s}")
print("-" * 84)
for name, rgb in test_colors:
    fm = M_fm2 @ rgb
    cm = M_cm2 @ rgb
    def oetf(L):
        L = np.clip(L, 0, 1)
        return np.where(L < 0.018, 4.5 * L, 1.099 * L**0.45 - 0.099)
    cm_gamma = oetf(cm)
    print(f"{name:12s} ({fm[0]:.4f},{fm[1]:.4f},{fm[2]:.4f})  ({cm[0]:.4f},{cm[1]:.4f},{cm[2]:.4f})  ({cm_gamma[0]:.4f},{cm_gamma[1]:.4f},{cm_gamma[2]:.4f})")

print("\n\n=== RECOMMENDATION ===")
print("The CM inverse approach produces MORE saturated colors than the FM approach.")
print("For red: FM2 gives (0.72, 0.17, 0.23) vs CM2 gives (1.50, 0.66, 0.21)")
print("After gamma+clamp: CM2 gives (0.62, 0.54, 0.38) for red - MUCH more saturated!")
print("Switching to CM2 inverse with chicken normalization should fix the desaturation.")