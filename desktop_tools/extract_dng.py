import os
import struct
import sys
import numpy as np

def process_file(file_path):
    print(f"Processing {file_path}...")
    output_dir = file_path + "_frames"
    os.makedirs(output_dir, exist_ok=True)
    
    with open(file_path, 'rb') as f:
        global_header = f.read(512)
        if len(global_header) < 512 or global_header[:8] != b'AYUSHRAW':
            print("Invalid global header")
            return
            
        (width, height, row_stride, cfa_pattern, bit_depth) = struct.unpack('<5I', global_header[8:28])
        ill1, ill2 = struct.unpack('<2I', global_header[28:36])
        packed_cm1 = global_header[36:36+72]
        packed_cm2 = global_header[108:108+72]
        packed_fm1 = global_header[180:180+72]
        packed_fm2 = global_header[252:252+72]
        
        bl_pattern = struct.unpack('<4I', global_header[324:340])
        white_level = struct.unpack('<I', global_header[340:344])[0]
        crop_left, crop_top, crop_width, crop_height = struct.unpack('<4I', global_header[344:360])
        try:
            source_height, dng_orientation = struct.unpack('<2I', global_header[360:368])
            if source_height <= 0 or source_height > 10000:
                source_height = height
            if dng_orientation not in (1, 3, 6, 8):
                dng_orientation = 1
        except Exception:
            source_height = height
            dng_orientation = 1
        
        print(f"Detected: {width}x{height}, Stride: {row_stride}, CFA: {cfa_pattern}, Depth: {bit_depth}-bit, SourceHeight: {source_height}, Orientation: {dng_orientation}")
        
        cfa_map = {
            0: (0, 1, 1, 2), # RGGB
            1: (1, 0, 2, 1), # GRBG
            2: (1, 2, 0, 1), # GBRG
            3: (2, 1, 1, 0)  # BGGR
        }
        cfa_tuple = cfa_map.get(cfa_pattern, (1, 2, 0, 1))

        payload_size = source_height * row_stride
        OUTPUT_BIT_DEPTH = 10
        
        if OUTPUT_BIT_DEPTH == 16:
            active_black_level = (4096, 4096, 4096, 4096)
            active_white_level = 65472
        else:
            active_black_level = bl_pattern
            active_white_level = white_level
        
        valid_width = width
        # DNG uses 16-bit unpacked format
        out_row_stride = valid_width * 2
        
        # Use dynamic crop geometry from CameraCharacteristics
        crop_origin_x = crop_left
        crop_origin_y = crop_top

        base_tags = [
            (254, 'I', 1, 0),                 # NewSubfileType = 0
            (274, 'H', 1, dng_orientation),                 # Orientation
            (277, 'H', 1, 1),                 # SamplesPerPixel = 1
            (282, 'rational', 1, (300, 1)),   # XResolution = 300
            (283, 'rational', 1, (300, 1)),   # YResolution = 300
            (271, 's', 7, b"Google\0"),                      # Make
            (272, 's', 12, b"Pixel 6 Pro\0"),                # Model
            (305, 's', 16, b"MotionCam Tools\0"), # Software
            (33421, 'H', 2, (2, 2)),          # CFARepeatPatternDim = 2x2
            (33422, 'B', 4, cfa_tuple),       # CFAPattern
            (50706, 'B', 4, (1, 4, 0, 0)),    # DNGVersion = 1.4.0.0
            (50707, 'B', 4, (1, 1, 0, 0)),    # DNGBackwardVersion = 1.1.0.0
            (50708, 's', 19, b"Google Pixel 6 Pro\0"),       # UniqueCameraModel
            (50711, 'H', 1, 1),               # CFALayout = 1
            (50713, 'H', 2, (2, 2)),          # BlackLevelRepeatDim = 2x2
            (50714, 'H', 4, active_black_level), # BlackLevel
            (50717, 'I', 1, active_white_level),  # WhiteLevel
            (50721, 'srational', 9, packed_cm1), # ColorMatrix1
            (50722, 'srational', 9, packed_cm2), # ColorMatrix2
            (50778, 'H', 1, ill1),            # CalibrationIlluminant1
            (50779, 'H', 1, ill2),            # CalibrationIlluminant2
            (50964, 'srational', 9, packed_fm1), # ForwardMatrix1
            (50965, 'srational', 9, packed_fm2), # ForwardMatrix2
            (50829, 'I', 4, (0, 0, height, width)), # ActiveArea (top, left, bottom, right)
            (50719, 'rational', 2, (crop_origin_x, 1, crop_origin_y, 1)), # DefaultCropOrigin (h, v)
            (50720, 'rational', 2, (crop_width, 1, crop_height, 1)), # DefaultCropSize (w, h)
            (51043, 'B', 8, (0, 0, 0, 0, 0, 0, 0, 0)), # TimeCodes
            (51044, 'srational', 1, (30000, 1000)), # FrameRate
            (50730, 'srational', 1, (104, 100)), # BaselineExposure (+1.04 EV)
            
            # Core Metadata Tags (to align with Pixel Camera DNG behavior)
            (50727, 'rational', 3, (1, 1, 1, 1, 1, 1)),     # AnalogBalance (1.0, 1.0, 1.0)
            (50731, 'rational', 1, (1, 1)),                 # BaselineNoise (1.0)
            (50732, 'rational', 1, (1, 1)),                 # BaselineSharpness (1.0)
            (51041, 'f', 6, (1.34395988e-4, 5.8752613e-7, 7.548273e-5, 2.4646738e-7, 1.3451263e-4, 5.9602803e-7)), # NoiseProfile
            (51110, 'I', 1, 1),                      # DefaultBlackRender
            (50781, 'B', 16, b"\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10") # RawDataUniqueID
        ]

        # Load profile assets if directory exists
        assets_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "profile_assets")
        if os.path.isdir(assets_dir):
            try:
                def load_bin(filename):
                    with open(os.path.join(assets_dir, filename), 'rb') as bin_f:
                        return bin_f.read()
                
                sig_bytes = load_bin("profile_sig.bin")
                name_bytes = load_bin("profile_name.bin")
                hs_dims_bytes = load_bin("profile_hs_dims.bin")
                hs1_bytes = load_bin("profile_hs1.bin")
                hs2_bytes = load_bin("profile_hs2.bin")
                tone_bytes = load_bin("profile_tone.bin")
                policy_bytes = load_bin("profile_embed_policy.bin")
                look_dims_bytes = load_bin("profile_look_dims.bin")
                look_bytes = load_bin("profile_look.bin")
                
                base_tags.extend([
                    (50932, 'B', len(sig_bytes), sig_bytes),       # ProfileCalibrationSignature
                    (50936, 'B', len(name_bytes), name_bytes),     # ProfileName
                    (50937, 'I', len(hs_dims_bytes)//4, hs_dims_bytes), # ProfileHueSatMapDims
                    (50938, 'f', len(hs1_bytes)//4, hs1_bytes),    # ProfileHueSatMapData1
                    (50939, 'f', len(hs2_bytes)//4, hs2_bytes),    # ProfileHueSatMapData2
                    (50940, 'f', len(tone_bytes)//4, tone_bytes),  # ProfileToneCurve
                    (50941, 'I', len(policy_bytes)//4, policy_bytes), # ProfileEmbedPolicy
                    (50981, 'I', len(look_dims_bytes)//4, look_dims_bytes), # ProfileLookTableDims
                    (50982, 'f', len(look_bytes)//4, look_bytes),  # ProfileLookTableData
                ])
                print("Embedded Google Camera Profile tags from profile_assets folder.")
            except Exception as e:
                print(f"Warning: Could not load profile assets: {e}")

        frame_idx = 0
        while True:
            frame_header = f.read(48)
            if len(frame_header) < 48:
                break
                
            (timestamp, shutter, iso, focus, idx, g_red, g_green_even, g_green_odd, g_blue) = struct.unpack('<qQififfff', frame_header[:44])
            
            # Prevent DivisionByZero by adding a small epsilon or falling back to 1.0
            g_red = g_red if g_red > 0.001 else 1.0
            g_green_even = g_green_even if g_green_even > 0.001 else 1.0
            g_green_odd = g_green_odd if g_green_odd > 0.001 else 1.0
            g_blue = g_blue if g_blue > 0.001 else 1.0
            
            raw_payload = f.read(payload_size)
            if len(raw_payload) < payload_size:
                print(f"Warning: Reached end of file at frame {frame_idx} with incomplete payload!")
                break
                
            # Unpack RAW10
            # raw_payload is source_height * row_stride bytes.
            # Convert to numpy array of uint8
            raw_data = np.frombuffer(raw_payload, dtype=np.uint8).reshape((source_height, row_stride))
            
            # The valid data is in the first (width * 5 // 4) bytes of each row
            valid_bytes = (valid_width * 5) // 4
            crop_start_row = ((source_height - height) // 2) & -2
            valid_data = raw_data[crop_start_row:crop_start_row+height, :valid_bytes]
            
            # Unpack
            b0 = valid_data[:, 0::5].astype(np.uint16)
            b1 = valid_data[:, 1::5].astype(np.uint16)
            b2 = valid_data[:, 2::5].astype(np.uint16)
            b3 = valid_data[:, 3::5].astype(np.uint16)
            b4 = valid_data[:, 4::5].astype(np.uint16)
            
            if OUTPUT_BIT_DEPTH == 16:
                # Unpack and shift to 16-bit range (<< 6)
                p0 = ((b0 << 2) | ((b4 >> 0) & 0x03)) << 6
                p1 = ((b1 << 2) | ((b4 >> 2) & 0x03)) << 6
                p2 = ((b2 << 2) | ((b4 >> 4) & 0x03)) << 6
                p3 = ((b3 << 2) | ((b4 >> 6) & 0x03)) << 6
                
                unpacked = np.empty((height, valid_width), dtype=np.uint16)
                unpacked[:, 0::4] = p0
                unpacked[:, 1::4] = p1
                unpacked[:, 2::4] = p2
                unpacked[:, 3::4] = p3
                
                pixel_data = unpacked.tobytes()
            else:
                p0 = (b0 << 2) | ((b4 >> 0) & 0x03)
                p1 = (b1 << 2) | ((b4 >> 2) & 0x03)
                p2 = (b2 << 2) | ((b4 >> 4) & 0x03)
                p3 = (b3 << 2) | ((b4 >> 6) & 0x03)
                
                out_b0 = (p0 >> 2).astype(np.uint8)
                out_b1 = (((p0 & 0x03) << 6) | (p1 >> 4)).astype(np.uint8)
                out_b2 = (((p1 & 0x0F) << 4) | (p2 >> 6)).astype(np.uint8)
                out_b3 = (((p2 & 0x3F) << 2) | (p3 >> 8)).astype(np.uint8)
                out_b4 = (p3 & 0xFF).astype(np.uint8)
                
                packed = np.empty((height, valid_width * 5 // 4), dtype=np.uint8)
                packed[:, 0::5] = out_b0
                packed[:, 1::5] = out_b1
                packed[:, 2::5] = out_b2
                packed[:, 3::5] = out_b3
                packed[:, 4::5] = out_b4
                
                pixel_data = packed.tobytes()
                
            out_filename = os.path.join(output_dir, f"frame_{frame_idx:05d}.dng")
            
            with open(out_filename, 'wb') as out:
                out.write(b'II*\0')
                out.write(struct.pack('<I', 8)) 
                
                r_gain = max(0.0001, g_red)
                g_gain = max(0.0001, (g_green_even + g_green_odd) / 2.0)
                b_gain = max(0.0001, g_blue)
                
                # AsShotNeutral needs to be the raw values of a neutral target, normalized to G=1.0
                R_val = g_gain / r_gain
                B_val = g_gain / b_gain
                
                dynamic_neutral_tag = (50728, 'rational', 3, (int(R_val * 1000000), 1000000, 1, 1, int(B_val * 1000000), 1000000))
                all_tags = base_tags.copy()
                all_tags.append(dynamic_neutral_tag)
                
                # Dynamic exposure and ISO
                all_tags.append((34855, 'H', 1, iso))
                # Prevent 32-bit unsigned rational overflow
                exp_sec = shutter / 1000000000.0
                exp_den = 1000000
                exp_num = int(exp_sec * exp_den)
                all_tags.append((33434, 'rational', 1, (exp_num, exp_den)))
                
                all_tags.append((256, 'I', 1, valid_width))
                all_tags.append((257, 'I', 1, height))
                all_tags.append((258, 'H', 1, OUTPUT_BIT_DEPTH))
                all_tags.append((259, 'H', 1, 1))
                all_tags.append((262, 'H', 1, 32803))
                all_tags.append((284, 'H', 1, 1))
                all_tags.append((278, 'I', 1, height))
                all_tags.append((279, 'I', 1, len(pixel_data)))
                all_tags.append((273, 'I', 1, 0))
                
                all_tags.sort(key=lambda x: x[0])
                
                ifd_size = 8 + 2 + len(all_tags) * 12 + 4
                pixel_data_offset = ifd_size
                
                for i in range(len(all_tags)):
                    if all_tags[i][0] == 273:
                        all_tags[i] = (273, 'I', 1, pixel_data_offset)
                
                out.write(struct.pack('<H', len(all_tags)))
                
                tag_data_block = b''
                current_data_offset = pixel_data_offset + len(pixel_data)
                
                for t in all_tags:
                    tag_id, fmt, count, val = t
                    out.write(struct.pack('<H', tag_id))
                    
                    type_code = 10 if fmt == 'srational' else (5 if fmt == 'rational' else (3 if fmt == 'H' else (4 if fmt == 'I' else (11 if fmt == 'f' else (1 if fmt == 'B' else 2)))))
                    out.write(struct.pack('<H', type_code))
                    out.write(struct.pack('<I', count))
                    
                    byte_size = count * 8 if fmt in ('srational', 'rational') else count * struct.calcsize('<' + fmt)
                    if byte_size <= 4:
                        if isinstance(val, bytes):
                            packed = val.ljust(4, b'\x00')
                        elif isinstance(val, tuple):
                            packed = struct.pack('<' + str(count) + fmt, *val)
                        else:
                            packed = struct.pack('<' + str(count) + fmt, val)
                        packed = packed.ljust(4, b'\x00')
                        out.write(packed)
                    else:
                        out.write(struct.pack('<I', current_data_offset))
                        if fmt == 'srational':
                            if isinstance(val, tuple):
                                tag_data_block += struct.pack('<' + str(count*2) + 'i', *val)
                            else:
                                tag_data_block += val
                        elif fmt == 'rational':
                            tag_data_block += struct.pack('<' + str(count*2) + 'i', *val)
                        elif isinstance(val, tuple):
                            tag_data_block += struct.pack('<' + str(count) + fmt, *val)
                        elif isinstance(val, bytes):
                            tag_data_block += val.ljust(byte_size, b'\x00')
                        else:
                            tag_data_block += struct.pack('<' + str(count) + fmt, val)
                        current_data_offset += byte_size
                
                out.write(struct.pack('<I', 0))
                out.write(pixel_data)
                out.write(tag_data_block)
                
            frame_idx += 1
            print(f"Extracted frame {frame_idx}", end='\r')
        print("\nDone!")

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: extract_dng.py <file.ayushraw>")
        sys.exit(1)
    process_file(sys.argv[1])
