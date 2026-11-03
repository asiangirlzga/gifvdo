package com.example.img2vid

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer

/**
 * H.264 / MP4 encoder fed with ARGB frames and explicit timestamps.
 * Frames are streamed to the muxer as they are encoded, so memory stays flat.
 */
class VideoEncoder(
    path: String,
    private val width: Int,
    private val height: Int,
    bitRate: Int,
    fps: Int = 30,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun addFrame(argb: IntArray, ptsUs: Long) {
        val idx = dequeueInput()
        val image = codec.getInputImage(idx) ?: error("No input image")
        fill(image, argb)
        codec.queueInputBuffer(idx, 0, width * height * 3 / 2, ptsUs, 0)
        drain(false)
    }

    fun finish(endPtsUs: Long) {
        val idx = dequeueInput()
        codec.queueInputBuffer(idx, 0, 0, endPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        release()
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        muxerStarted = false
    }

    private fun dequeueInput(): Int {
        while (true) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) return idx
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
            } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.outputFormat)
                muxer.start()
                muxerStarted = true
            } else if (i >= 0) {
                val buf = codec.getOutputBuffer(i)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && muxerStarted) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                codec.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    private fun fill(img: Image, argb: IntArray) {
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        for (y in 0 until height) {
            val row = y * width
            val evenRow = (y and 1) == 0
            for (x in 0 until width) {
                val p = argb[row + x]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yb.put(y * yP.rowStride + x * yP.pixelStride, yy.toByte())
                if (evenRow && (x and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    ub.put((y shr 1) * uP.rowStride + (x shr 1) * uP.pixelStride, u.coerceIn(0, 255).toByte())
                    vb.put((y shr 1) * vP.rowStride + (x shr 1) * vP.pixelStride, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
