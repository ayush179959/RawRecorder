import os
import struct
import sys
import numpy as np
import math

def process_file(file_path):
    print(f"Processing {file_path}...")
    output_dir = file_path + "_frames"
    os.makedirs(output_dir, exist_ok=True)
    
    with open(file_path, 'rb') as f:
        global_header = f.read(1024)
        if len(global_header) < 1024 or global_header[:8] != b'AYUSHRAW':
            print("Invalid global header")
            return
            
        (width, height, row_stride, cfa_pattern, bit_depth) = struct.unpack('<5I', global_header[8:28])
        ill1, ill2 = struct.unpack('<2I', global_header[28:36])
        packed_cm1 = global_header[36:108]
        packed_cm2 = global_header[108:180]
        packed_fm1 = global_header[180:252]
        packed_fm2 = global_header[252:324]
        
        packed_cal1 = global_header[324:396]
        packed_cal2 = global_header[396:468]
        
        bl_pattern = struct.unpack('<4I', global_header[468:484])
        white_level = struct.unpack('<I', global_header[484:488])[0]
        crop_left, crop_top, crop_width, crop_height = struct.unpack('<4I', global_header[488:504])
        
        source_height, dng_orientation, sensor_type = struct.unpack('<3I', global_header[504:516])
        if source_height <= 0 or source_height > 10000:
            source_height = height
        if dng_orientation not in (1, 3, 6, 8):
            dng_orientation = 1
            
        baseline_exp_num, baseline_exp_den = struct.unpack('<2i', global_header[516:524])
        noise_profile = struct.unpack('<8f', global_header[524:556])
        lens_intrinsic = struct.unpack('<5f', global_header[556:576])
        lens_distortion = struct.unpack('<5f', global_header[576:596])
        lens_aperture, lens_focal_length = struct.unpack('<2f', global_header[596:604])
        
        device_make_bytes = global_header[604:636]
        device_make = device_make_bytes.split(b'\x00')[0].decode('ascii', errors='ignore').strip()
        device_model_bytes = global_header[636:668]
        device_model = device_model_bytes.split(b'\x00')[0].decode('ascii', errors='ignore').strip()
        
        pre_width, pre_height = struct.unpack('<2I', global_header[668:676])
        
        print(f"Detected: {width}x{height}, Stride: {row_stride}, CFA: {cfa_pattern}, Depth: {bit_depth}-bit, PreCorrect: {pre_width}x{pre_height}, Make: {device_make}, Model: {device_model}")
        
        cfa_map = {
            0: (0, 1, 1, 2), # RGGB
            1: (1, 0, 2, 1), # GRBG
            2: (1, 2, 0, 1), # GBRG
            3: (2, 1, 1, 0)  # BGGR
        }
        cfa_tuple = cfa_map.get(cfa_pattern, (1, 2, 0, 1))

        payload_size = source_height * row_stride
        OUTPUT_BIT_DEPTH = 16
        
        active_black_level = bl_pattern
        active_white_level = white_level
        
        valid_width = width
        
        crop_origin_x = crop_left
        crop_origin_y = crop_top

        # Clean Make, Model, and UniqueCameraModel strings
        final_make = device_make if device_make else "Google"
        final_model = device_model if device_model else "Pixel"
        device_make_tag = final_make.encode('ascii') + b'\0'
        device_model_tag = final_model.encode('ascii') + b'\0'
        unique_model_tag = (final_make + " " + final_model).encode('ascii') + b'\0'

        base_tags = [
            (254, 'I', 1, 0),                                           # NewSubfileType = 0
            (274, 'H', 1, dng_orientation),                             # Orientation
            (277, 'H', 1, 1),                                           # SamplesPerPixel = 1
            (282, 'rational', 1, (300, 1)),                             # XResolution = 300
            (283, 'rational', 1, (300, 1)),                             # YResolution = 300
            (305, 's', 16, b"RawRecorder\0\0\0\0\0"),                   # Software
            (33421, 'H', 2, (2, 2)),                                    # CFARepeatPatternDim = 2x2
            (33422, 'B', 4, cfa_tuple),                                 # CFAPattern
            (50706, 'B', 4, (1, 4, 0, 0)),                              # DNGVersion = 1.4.0.0
            (50708, 's', len(unique_model_tag), unique_model_tag),      # UniqueCameraModel
            (50713, 'H', 2, (2, 2)),                                    # BlackLevelRepeatDim = 2x2
            (50714, 'H', 4, active_black_level),                        # BlackLevel
            (50717, 'I', 1, active_white_level),                        # WhiteLevel
            (50721, 'srational', 9, packed_cm1),                        # ColorMatrix1
            (50722, 'srational', 9, packed_cm2),                        # ColorMatrix2
            (50723, 'srational', 9, packed_cal1),                       # CameraCalibration1
            (50724, 'srational', 9, packed_cal2),                       # CameraCalibration2
            (50778, 'H', 1, ill1),                                      # CalibrationIlluminant1
            (50779, 'H', 1, ill2),                                      # CalibrationIlluminant2
            (50964, 'srational', 9, packed_fm1),                        # ForwardMatrix1
            (50965, 'srational', 9, packed_fm2),                        # ForwardMatrix2
            (50829, 'I', 4, (0, 0, height, width)),                     # ActiveArea (top, left, bottom, right)
            (51043, 'B', 8, (0, 0, 0, 0, 0, 0, 0, 0)),                  # TimeCodes
            (51044, 'srational', 1, (30000, 1000)),                     # FrameRate
            
            (50727, 'rational', 3, (1, 1, 1, 1, 1, 1)),                 # AnalogBalance (1.0, 1.0, 1.0)
            (50781, 'B', 16, b"\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10") # RawDataUniqueID
        ]

        if lens_focal_length > 0.0 and lens_aperture > 0.0:
            base_tags.append((50827, 'rational', 4, (int(lens_focal_length * 100), 100, int(lens_focal_length * 100), 100, int(lens_aperture * 100), 100, int(lens_aperture * 100), 100)))

        frame_idx = 0
        while True:
            frame_header = f.read(48)
            if len(frame_header) < 48:
                break
                
            (timestamp, shutter, iso, focus, idx, g_red, g_green_even, g_green_odd, g_blue) = struct.unpack('<qQififfff', frame_header[:44])
            
            g_red = g_red if g_red > 0.001 else 1.0
            g_green_even = g_green_even if g_green_even > 0.001 else 1.0
            g_green_odd = g_green_odd if g_green_odd > 0.001 else 1.0
            g_blue = g_blue if g_blue > 0.001 else 1.0
            
            raw_payload = f.read(payload_size)
            if len(raw_payload) < payload_size:
                print(f"Warning: Reached end of file at frame {frame_idx} with incomplete payload!")
                break
                
            raw_data = np.frombuffer(raw_payload, dtype=np.uint8).reshape((source_height, row_stride))
            
            valid_bytes = (valid_width * 5) // 4
            crop_start_row = ((source_height - height) // 2) & -2
            valid_data = raw_data[crop_start_row:crop_start_row+height, :valid_bytes]
            
            b0 = valid_data[:, 0::5].astype(np.uint16)
            b1 = valid_data[:, 1::5].astype(np.uint16)
            b2 = valid_data[:, 2::5].astype(np.uint16)
            b3 = valid_data[:, 3::5].astype(np.uint16)
            b4 = valid_data[:, 4::5].astype(np.uint16)
            
            if OUTPUT_BIT_DEPTH == 16:
                p0 = (b0 << 2) | ((b4 >> 0) & 0x03)
                p1 = (b1 << 2) | ((b4 >> 2) & 0x03)
                p2 = (b2 << 2) | ((b4 >> 4) & 0x03)
                p3 = (b3 << 2) | ((b4 >> 6) & 0x03)
                
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
                
                R_val = g_gain / r_gain
                B_val = g_gain / b_gain
                
                dynamic_neutral_tag = (50728, 'rational', 3, (int(R_val * 1000000), 1000000, 1, 1, int(B_val * 1000000), 1000000))
                all_tags = base_tags.copy()
                all_tags.append(dynamic_neutral_tag)
                
                all_tags.append((34855, 'H', 1, iso))
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
                            elif isinstance(val, bytes):
                                tag_data_block += val
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
