package com.localai.app.geniex

import android.util.Log
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads `tokenizer.chat_template` from a GGUF file's metadata header. */
object GgufReader {
    private const val TAG = "GgufReader"

    private const val T_UINT8 = 0;  private const val T_INT8 = 1
    private const val T_UINT16 = 2; private const val T_INT16 = 3
    private const val T_UINT32 = 4; private const val T_INT32 = 5
    private const val T_FLOAT32 = 6;private const val T_BOOL = 7
    private const val T_STRING = 8; private const val T_ARRAY = 9
    private const val T_UINT64 = 10;private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    fun readChatTemplate(path: String): String? {
        return try {
            RandomAccessFile(path, "r").use { raf ->
                if (!readMagic(raf)) return null
                raf.skipBytes(4)                 // version
                readU64(raf)                     // tensor count
                val kvCount = readU64(raf)
                var i = 0L
                while (i < kvCount) {
                    val key = readString(raf) ?: return null
                    val type = readU32(raf)
                    if (key == "tokenizer.chat_template" && type == T_STRING) {
                        val v = readString(raf)
                        Log.i(TAG, "found chat_template (${v?.length ?: 0} chars)")
                        return v
                    }
                    skipValue(raf, type)
                    i++
                }
                Log.i(TAG, "no chat_template in $path")
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "readChatTemplate failed", e); null
        }
    }

    private fun readMagic(raf: RandomAccessFile): Boolean {
        val b = ByteArray(4); raf.readFully(b)
        return String(b, Charsets.US_ASCII) == "GGUF"
    }
    private fun readU32(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }
    private fun readU64(raf: RandomAccessFile): Long {
        val b = ByteArray(8); raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }
    private fun readString(raf: RandomAccessFile): String? {
        val len = readU64(raf)
        if (len < 0 || len > 50_000_000) return null
        val b = ByteArray(len.toInt()); raf.readFully(b)
        return String(b, Charsets.UTF_8)
    }
    private fun skipValue(raf: RandomAccessFile, type: Int) {
        when (type) {
            T_UINT8, T_INT8, T_BOOL -> raf.skipBytes(1)
            T_UINT16, T_INT16 -> raf.skipBytes(2)
            T_UINT32, T_INT32, T_FLOAT32 -> raf.skipBytes(4)
            T_UINT64, T_INT64, T_FLOAT64 -> raf.skipBytes(8)
            T_STRING -> {
                val len = readU64(raf)
                if (len in 0..50_000_000) raf.skipBytes(len.toInt())
            }
            T_ARRAY -> {
                val elemType = readU32(raf)
                val count = readU64(raf)
                for (j in 0 until count) skipValue(raf, elemType)
            }
            else -> throw IllegalArgumentException("unknown gguf type $type")
        }
    }
}
