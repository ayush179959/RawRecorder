#include <jni.h>
#include <android/log.h>
#include "lz4.h"
#include <vector>
#include <cstring>

#define TAG "RawCompressorJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Thread-local scratch buffer to avoid repeated allocations in the compression thread
thread_local std::vector<uint8_t> tls_scratch_buf;

// Shuffles RAW10 packed bytes to group all upper 8-bits together, and all lower 2-bits together.
// This separates the image signal from the noise, massively increasing LZ4 compression ratio.
void shuffle_raw10(const uint8_t* src, uint8_t* dst, int size) {
    int groups = size / 5;
    uint8_t* upper = dst;
    uint8_t* lower = dst + (groups * 4);
    
    for (int i = 0; i < groups; i++) {
        // Fast and safe copy of 4 bytes using memcpy (compiler optimizes to a single 32-bit load/store)
        memcpy(upper, src, 4);
        lower[0] = src[4];
        
        src += 5;
        upper += 4;
        lower += 1;
    }
}

void unshuffle_raw10(const uint8_t* src, uint8_t* dst, int size) {
    int groups = size / 5;
    const uint8_t* upper = src;
    const uint8_t* lower = src + (groups * 4);
    
    for (int i = 0; i < groups; i++) {
        // Fast and safe copy of 4 bytes using memcpy
        memcpy(dst, upper, 4);
        dst[4] = lower[0];
        
        dst += 5;
        upper += 4;
        lower += 1;
    }
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_rawrecorder_RawCompressor_nativeCompressLZ4(
        JNIEnv *env, jclass clazz,
        jobject src, jint srcSize,
        jobject dst, jint dstCapacity) {
    
    auto *srcPtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(src));
    auto *dstPtr = static_cast<char *>(env->GetDirectBufferAddress(dst));
    
    if (srcPtr == nullptr || dstPtr == nullptr) {
        LOGI("Error: null buffer pointer");
        return 0;
    }
    
    int compressedSize = 0;
    
    // If it's perfectly divisible by 5 (RAW10 standard), apply byte shuffling
    if (srcSize % 5 == 0) {
        if (tls_scratch_buf.size() < srcSize) {
            tls_scratch_buf.resize(srcSize);
        }
        
        shuffle_raw10(srcPtr, tls_scratch_buf.data(), srcSize);
        
        // Acceleration 15 is much faster and prevents frame drops on mobile CPUs,
        // while the byte-shuffling still ensures a fantastic compression ratio.
        compressedSize = LZ4_compress_fast(reinterpret_cast<const char*>(tls_scratch_buf.data()), dstPtr, srcSize, dstCapacity, 15);
    } else {
        compressedSize = LZ4_compress_fast(reinterpret_cast<const char*>(srcPtr), dstPtr, srcSize, dstCapacity, 15);
    }
    
    if (compressedSize <= 0) {
        LOGI("LZ4 compression failed: srcSize=%d", srcSize);
        return 0;
    }
    
    return compressedSize;
}

JNIEXPORT jint JNICALL
Java_com_example_rawrecorder_RawCompressor_nativeDecompressLZ4(
        JNIEnv *env, jclass clazz,
        jobject src, jint srcSize,
        jobject dst, jint originalSize) {
    
    auto *srcPtr = static_cast<const char *>(env->GetDirectBufferAddress(src));
    auto *dstPtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(dst));
    
    if (srcPtr == nullptr || dstPtr == nullptr) {
        LOGI("Error: null buffer pointer");
        return -1;
    }
    
    // Decompress to scratch buffer first if we need to unshuffle
    if (originalSize % 5 == 0) {
        if (tls_scratch_buf.size() < originalSize) {
            tls_scratch_buf.resize(originalSize);
        }
        
        int decompressedSize = LZ4_decompress_safe(srcPtr, reinterpret_cast<char*>(tls_scratch_buf.data()), srcSize, originalSize);
        
        if (decompressedSize < 0) {
            LOGI("LZ4 decompression failed");
            return decompressedSize;
        }
        
        unshuffle_raw10(tls_scratch_buf.data(), dstPtr, originalSize);
        return decompressedSize;
    } else {
        int decompressedSize = LZ4_decompress_safe(srcPtr, reinterpret_cast<char*>(dstPtr), srcSize, originalSize);
        if (decompressedSize < 0) {
            LOGI("LZ4 decompression failed");
        }
        return decompressedSize;
    }
}

JNIEXPORT jint JNICALL
Java_com_example_rawrecorder_RawCompressor_nativeCompressBound(
        JNIEnv *env, jclass clazz,
        jint inputSize) {
    return LZ4_compressBound(inputSize);
}

} // extern "C"
