package com.example.rawrecorder

import java.nio.ByteBuffer

/**
 * JNI wrapper for LZ4 real-time compression of raw Bayer sensor data.
 * 
 * Uses the LZ4 block API for maximum throughput (~400-500 MB/s on ARM64).
 * All operations use direct ByteBuffers for zero-copy interop with the
 * camera pipeline.
 */
object RawCompressor {

    init {
        System.loadLibrary("rawcompressor")
    }

    /**
     * Compress a raw frame payload using LZ4.
     *
     * @param src Direct ByteBuffer positioned at the start of data to compress
     * @param srcSize Number of bytes to compress
     * @param dst Direct ByteBuffer for compressed output (capacity >= compressBound(srcSize))
     * @return Compressed size in bytes, or 0 on failure
     */
    fun compress(src: ByteBuffer, srcSize: Int, dst: ByteBuffer): Int {
        return nativeCompressLZ4(src, srcSize, dst, dst.capacity())
    }

    /**
     * Decompress an LZ4-compressed frame payload.
     *
     * @param src Direct ByteBuffer containing compressed data
     * @param compressedSize Size of the compressed data
     * @param dst Direct ByteBuffer for decompressed output
     * @param originalSize Expected original (decompressed) size
     * @return Decompressed size in bytes, or negative value on failure
     */
    fun decompress(src: ByteBuffer, compressedSize: Int, dst: ByteBuffer, originalSize: Int): Int {
        return nativeDecompressLZ4(src, compressedSize, dst, originalSize)
    }

    /**
     * Get the worst-case compressed size for a given input size.
     * Use this to allocate the compression output buffer.
     */
    fun compressBound(inputSize: Int): Int {
        return nativeCompressBound(inputSize)
    }

    // --- Native methods ---

    @JvmStatic
    private external fun nativeCompressLZ4(
        src: ByteBuffer, srcSize: Int,
        dst: ByteBuffer, dstCapacity: Int
    ): Int

    @JvmStatic
    private external fun nativeDecompressLZ4(
        src: ByteBuffer, srcSize: Int,
        dst: ByteBuffer, originalSize: Int
    ): Int

    @JvmStatic
    private external fun nativeCompressBound(inputSize: Int): Int
}
