import struct, os, sys
import tifffile
import io

def parse_srational_tuple(val):
    """Parse 18-element tuple (9 num/den pairs) into 3x3 float matrix."""
    if val is None:
        return None
    if isinstance(val, tuple) and len(val) == 18:
        matrix = []
        for i in range(3):
            row = []
            for j in range(3):
                idx = i * 3 + j
                num = val[idx * 2]
                den = val[idx * 2 + 1]
                v = num / den if den != 0 else 0.0
                row.append(v)
            matrix.append(row)
        return matrix
    if isinstance(val, tuple) and len(val) == 9:
        return [[val[i*3+j] for j in range(3)] for i in range(3)]
    return None

def print_mat(m, label):
    print(f"\n{label}:")
    if m is None:
        print("  N/A")
        return
    for row in m:
        print(f"  [{row[0]:10.6f}, {row[1]:10.6f}, {row[2]:10.6f}]")

def extract_dng_tags(filepath):
    print(f"\n{'='*80}")
    print(f"DNG FILE: {os.path.basename(filepath)}")
    print(f"{'='*80}")
    
    with tifffile.TiffFile(filepath) as tif:
        page = tif.pages[0]
        tags = page.tags
        
        def v(tag_id):
            t = tags.get(tag_id)
            return t.value if t else None
        
        print(f"Camera: {v(271)} {v(50708)}")
        print(f"CalibrationIlluminant1: {v(50778)}")
        print(f"CalibrationIlluminant2: {v(50779)}")
        print(f"BaselineExposure: {v(50780)}")
        print(f"AsShotNeutral: {v(50728)}")
        
        cm1 = parse_srational_tuple(v(50721))
        cm2 = parse_srational_tuple(v(50722))
        fm1 = parse_srational_tuple(v(50964))
        fm2 = parse_srational_tuple(v(50965))
        cal1 = parse_srational_tuple(v(50723))
        cal2 = parse_srational_tuple(v(50724))
        
        print_mat(cm1, "ColorMatrix1")
        print_mat(cm2, "ColorMatrix2")
        print_mat(fm1, "ForwardMatrix1")
        print_mat(fm2, "ForwardMatrix2")
        print_mat(cal1, "CameraCalibration1")
        print_mat(cal2, "CameraCalibration2")
        
        return {
            'make': v(271), 'model': v(50708),
            'ill1': v(50778), 'ill2': v(50779),
            'cm1': cm1, 'cm2': cm2, 'fm1': fm1, 'fm2': fm2,
            'cal1': cal1, 'cal2': cal2,
            'asn': v(50728),
            'baseline': v(50780),
        }

def parse_dcp(filepath):
    print(f"\n{'='*80}")
    print(f"DCP FILE: {os.path.basename(filepath)}")
    print(f"{'='*80}")
    
    with open(filepath, 'rb') as f:
        raw_bytes = f.read()
    
    try:
        with tifffile.TiffFile(io.BytesIO(raw_bytes)) as tif:
            page = tif.pages[0]
            tags = page.tags
            
            # DCP tag mapping (different from DNG!)
            dcp_tags = {
                0xC614: ('ProfileName', None),
                0xC621: ('ColorMatrix1', 'srational9'),
                0xC622: ('ColorMatrix2', 'srational9'),
                0xC623: ('CameraCalibration1', 'srational9'),
                0xC624: ('CameraCalibration2', 'srational9'),
                0xC633: ('CalibrationIlluminant1', None),
                0xC634: ('CalibrationIlluminant2', None),
                0xC637: ('DNGColorMatrix1', None),  # These are DNG tags, not typically in DCP
                0xC65A: ('Illuminant1_DCP', None),
                0xC65B: ('Illuminant2_DCP', None),
                0xC645: ('ForwardMatrix1_DNG', None),
                0xC646: ('ForwardMatrix2_DNG', None),
                0xC714: ('ForwardMatrix1', 'srational9'),
                0xC715: ('ForwardMatrix2', 'srational9'),
            }
            
            profile_name = None
            if 0xC614 in tags:
                v = tags[0xC614].value
                if isinstance(v, bytes):
                    v = v.decode('ascii', errors='replace').strip('\x00').strip()
                profile_name = v
            
            ill1 = tags.get(0xC633, tags.get(0xC65A))
            ill2 = tags.get(0xC634, tags.get(0xC65B))
            ill1 = ill1.value if ill1 is not None else None
            ill2 = ill2.value if ill2 is not None else None
            
            print(f"ProfileName: {profile_name}")
            print(f"CalibrationIlluminant1: {ill1}")
            print(f"CalibrationIlluminant2: {ill2}")
            
            # Parse matrices
            cm1 = parse_srational_tuple(tags.get(0xC621, {}).get('value') if isinstance(tags.get(0xC621), dict) else tags.get(0xC621).value if tags.get(0xC621) else None)
            
            # Actually just use the value
            def get_srat(tid):
                t = tags.get(tid)
                if t is None:
                    return None
                return parse_srational_tuple(t.value)
            
            cm1 = get_srat(0xC621)
            cm2 = get_srat(0xC622)
            fm1 = get_srat(0xC714)
            fm2 = get_srat(0xC715)
            
            print_mat(cm1, "ColorMatrix1 (0xC621)")
            print_mat(cm2, "ColorMatrix2 (0xC622)")
            print_mat(fm1, "ForwardMatrix1 (0xC714)")
            print_mat(fm2, "ForwardMatrix2 (0xC715)")
            
            # Report all other DCP tags
            print(f"\nAll DCP tags found:")
            for code, t in sorted(tags.items()):
                if code in dcp_tags:
                    name, _ = dcp_tags[code]
                    v = t.value
                    if isinstance(v, bytes):
                        v = v.decode('ascii', errors='replace').strip('\x00').strip()[:60]
                    print(f"  0x{code:04X} ({name:25s}): {v}")
                elif code >= 0xC600:
                    v = t.value
                    if isinstance(v, bytes):
                        v = v.decode('ascii', errors='replace').strip('\x00').strip()[:40]
                    print(f"  0x{code:04X} (unknown          ): type={t.dtype} count={t.count} val={v}")
            
            return {'cm1': cm1, 'cm2': cm2, 'fm1': fm1, 'fm2': fm2,
                    'ill1': ill1, 'ill2': ill2,
                    'profile_name': profile_name}
    except Exception as e:
        print(f"  ERROR parsing DCP: {e}")
        import traceback
        traceback.print_exc()
        return {}

# Hardcoded from MainActivity.kt lines 608-673
hardcoded = {
    1: {  # Ultrawide
        'cm1': [[1.4178, -0.8720, 0.0688], [-0.2895, 1.1364, 0.1730], [0.0110, 0.0814, 0.5508]],
        'cm2': [[1.1835, -0.5488, -0.1032], [-0.3371, 1.1891, 0.1648], [-0.0194, 0.1235, 0.4748]],
        'fm1': [[0.3777, 0.4906, 0.0960], [0.1585, 0.8136, 0.0278], [0.0459, 0.0016, 0.7775]],
        'fm2': [[0.3806, 0.4501, 0.1336], [0.1773, 0.7842, 0.0385], [0.0652, 0.0006, 0.7593]],
        'ill1': 17, 'ill2': 21,
    },
    2: {  # Telephoto
        'cm1': [[1.2163, -0.5088, -0.0692], [-0.2296, 1.0998, 0.1473], [0.0211, 0.1016, 0.4655]],
        'cm2': [[0.8380, -0.1926, -0.0623], [-0.4094, 1.2822, 0.1364], [-0.1193, 0.2655, 0.3949]],
        'fm1': [[0.4222, 0.4040, 0.1381], [0.1880, 0.7702, 0.0418], [0.0571, 0.0005, 0.7675]],
        'fm2': [[0.5208, 0.3320, 0.1116], [0.2917, 0.6733, 0.0350], [0.1702, 0.0013, 0.6536]],
        'ill1': 17, 'ill2': 21,
    },
    3: {  # Front
        'cm1': [[2.0174, -0.5979, -1.1229], [-1.9432, 4.3771, -2.1631], [-0.2889, 0.5882, 0.8368]],
        'cm2': [[1.2128, -0.5577, -0.1067], [-0.3077, 1.1648, 0.1599], [-0.0121, 0.1501, 0.5141]],
        'fm1': [[0.5917, -0.0885, 0.4611], [0.2729, 0.2453, 0.4819], [0.0485, -0.4309, 1.2075]],
        'fm2': [[0.3638, 0.4646, 0.1359], [0.1621, 0.7989, 0.0390], [0.0543, 0.0032, 0.7676]],
        'ill1': 17, 'ill2': 21,
    },
    0: {  # Main
        'cm1': [[1.1234, -0.5774, 0.0083], [-0.3535, 1.2410, 0.1210], [-0.0303, 0.2176, 0.5922]],
        'cm2': [[1.0662, -0.4641, -0.0954], [-0.3284, 1.1970, 0.1451], [-0.0170, 0.1989, 0.5182]],
        'fm1': [[0.4078, 0.4619, 0.0946], [0.2358, 0.7358, 0.0284], [0.1183, 0.0004, 0.7063]],
        'fm2': [[0.3666, 0.4641, 0.1335], [0.1639, 0.7979, 0.0383], [0.0477, 0.0042, 0.7733]],
        'ill1': 17, 'ill2': 21,
    }
}

sensor_names = {0: "Main", 1: "Ultrawide", 2: "Telephoto", 3: "Front"}
mat_keys = [('cm1','ColorMatrix1'), ('cm2','ColorMatrix2'), ('fm1','ForwardMatrix1'), ('fm2','ForwardMatrix2')]

def compare_two(m1, m2, n1="A", n2="B"):
    if m1 is None and m2 is None:
        return 0, ["  Both N/A"]
    if m1 is None:
        return 999, [f"  {n1} is NONE, {n2} has data"]
    if m2 is None:
        return 999, [f"  {n2} is NONE, {n1} has data"]
    mx = 0
    dd = []
    for i in range(3):
        for j in range(3):
            d = abs(m1[i][j] - m2[i][j])
            if d > 0.001:
                dd.append(f"    [{i}][{j}]: {n1}={m1[i][j]:.6f} vs {n2}={m2[i][j]:.6f} (diff={d:.6f})")
            if d > mx:
                mx = d
    return mx, dd

def do_comparison(label, mats_a, mats_b, name_a, name_b):
    print(f"\n--- {label} ---")
    all_diffs = []
    mx = 0
    for mk, mn in mat_keys:
        md, dd = compare_two(mats_a.get(mk), mats_b.get(mk), name_a, name_b)
        all_diffs.extend(dd)
        if md > mx:
            mx = md
    if mx < 0.001:
        print(f"  MATCH (max diff: {mx:.6f})")
    elif mx == 999:
        print(f"  MISSING DATA: matrices not found")
        for d in all_diffs[:4]:
            print(d)
    else:
        print(f"  MISMATCH (max diff: {mx:.6f})")
        for d in all_diffs:
            print(d)
    return mx

def main():
    # 1. Parse DNG
    dng_path = r"C:\Users\Ayush\AndroidStudioProjects\RawRecorder\dng\sample poto.dng"
    dng = extract_dng_tags(dng_path)
    
    # 2. Parse DCP files
    dcp_files = []
    for d in [r"C:\Users\Ayush\AndroidStudioProjects\RawRecorder\dcp",
              r"C:\Users\Ayush\AndroidStudioProjects\RawRecorder\desktop_tools\dcp"]:
        if os.path.isdir(d):
            dcp_files.extend([os.path.join(d, f) for f in sorted(os.listdir(d)) if f.lower().endswith('.dcp')])
    
    dcp_results = {}
    for f in dcp_files:
        r = parse_dcp(f)
        dcp_results[os.path.basename(f)] = r
    
    # 3. DNG vs Hardcoded (try all 4 sensors)
    print(f"\n{'='*80}")
    print(f"COMPARISON: DNG vs HARDCODED (MainActivity.kt)")
    print(f"{'='*80}")
    print(f"\nDNG Illuminants: {dng.get('ill1')}, {dng.get('ill2')}")
    print(f"HC Illuminants (all): 17, 21")
    print(f"DNG BaselineExposure: {dng.get('baseline')}")
    print(f"DNG AsShotNeutral: {dng.get('asn')}")
    
    best_match = (None, 999)
    for sid, sname in sensor_names.items():
        md = do_comparison(f"vs {sname}", dng, hardcoded[sid], "DNG", "HC")
        if md < best_match[1]:
            best_match = (sname, md)
    print(f"\n>>> DNG BEST MATCHES: {best_match[0]} (max diff: {best_match[1]:.6f}) <<<")
    
    # 4. DCP vs Hardcoded
    print(f"\n{'='*80}")
    print(f"COMPARISON: DCP vs HARDCODED")
    print(f"{'='*80}")
    
    for fname, result in dcp_results.items():
        lower = fname.lower()
        guess = None
        if 'ultra' in lower or 'wide' in lower:
            guess = 1
        elif 'tele' in lower:
            guess = 2
        elif 'front' in lower:
            guess = 3
        elif 'main' in lower:
            guess = 0
        
        for sid, sname in sensor_names.items():
            if guess is None or sid == guess:
                do_comparison(f"{fname} vs HC({sname})", result, hardcoded[sid], "DCP", "HC")
    
    # 5. DNG vs DCP
    print(f"\n{'='*80}")
    print(f"COMPARISON: DNG vs DCP")
    print(f"{'='*80}")
    for fname, result in dcp_results.items():
        do_comparison(f"{fname} vs DNG", result, dng, "DCP", "DNG")
    
    # 6. File listings
    print(f"\n{'='*80}")
    print(f"FILE LISTINGS")
    print(f"{'='*80}")
    for dirpath in [r"C:\Users\Ayush\AndroidStudioProjects\RawRecorder\app\src\main\assets",
                    r"C:\Users\Ayush\AndroidStudioProjects\RawRecorder\desktop_tools\profile_assets"]:
        print(f"\n{os.path.basename(dirpath)}:")
        if os.path.isdir(dirpath):
            for f in sorted(os.listdir(dirpath)):
                fp = os.path.join(dirpath, f)
                s = os.path.getsize(fp)
                print(f"  {f:40s} {s:>8,} bytes")

if __name__ == '__main__':
    main()