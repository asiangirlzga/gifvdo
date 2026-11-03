package com.example.img2vid

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.nio.ByteBuffer

/**
 * Turns any audio file the phone can decode (mp3, m4a, wav, ogg, flac...) into an AAC track
 * exactly as long as the video: longer songs are cut, shorter ones are looped.
 */
object AudioEncoder {

    fun encode(ctx: Context, uri: Uri, durationUs: Long, outPath: String, check: () -> Unit) {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        var sink: AacSink? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val trackIdx = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("That file has no audio track")
            ex.selectTrack(trackIdx)
            val inFmt = ex.getTrackFormat(trackIdx)
            val d = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(inFmt, null, null, 0)
            d.start()

            val info = MediaCodec.BufferInfo()
            var feedEos = false
            var finished = false
            var passStart = 0L

            while (!finished) {
                check()
                if (!feedEos) {
                    val i = d.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val buf = d.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            feedEos = true
                        } else {
                            d.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = d.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (sink == null) sink = newSink(outPath, d.outputFormat, durationUs)
                } else if (o >= 0) {
                    val out = d.getOutputBuffer(o)!!
                    if (sink == null) sink = newSink(outPath, d.outputFormat, durationUs)
                    if (info.size > 0 && sink.push(out, info.offset, info.size)) finished = true
                    d.releaseOutputBuffer(o, false)
                    if (!finished && (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // Reached the end of the song before the video ended: loop it.
                        if (sink.written == passStart) throw IllegalStateException("Audio could not be decoded")
                        passStart = sink.written
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        d.flush()
                        try { d.start() } catch (_: IllegalStateException) {}
                        feedEos = false
                    }
                }
            }
            sink!!.finish()
        } finally {
            sink?.release()
            try { dec?.stop() } catch (_: Exception) {}
            try { dec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    private fun newSink(outPath: String, fmt: MediaFormat, durationUs: Long): AacSink {
        val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (ch > 2) throw IllegalArgumentException("Only mono and stereo audio are supported")
        return AacSink(outPath, sr, ch, durationUs)
    }

    /** Copies the finished video and audio tracks into one MP4 without re-encoding. */
    fun mux(videoPath: String, audioPath: String, outPath: String) {
        val vEx = MediaExtractor().apply { setDataSource(videoPath) }
        val aEx = MediaExtractor().apply { setDataSource(audioPath) }
        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val vi = (0 until vEx.trackCount).first {
                vEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
            }
            val ai = (0 until aEx.trackCount).first {
                aEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
            }
            vEx.selectTrack(vi)
            aEx.selectTrack(ai)
            val vt = muxer.addTrack(vEx.getTrackFormat(vi))
            val at = muxer.addTrack(aEx.getTrackFormat(ai))
            muxer.start()
            started = true

            val buf = ByteBuffer.allocate(4 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val vt0 = vEx.sampleTime
                val at0 = aEx.sampleTime
                if (vt0 < 0 && at0 < 0) break
                val useVideo = at0 < 0 || (vt0 in 0..at0)
                val ex = if (useVideo) vEx else aEx
                val track = if (useVideo) vt else at
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                info.set(0, n, ex.sampleTime, flags)
                muxer.writeSampleData(track, buf, info)
                ex.advance()
            }
        } finally {
            try { if (started) muxer.stop() } catch (_: Exception) {}
            muxer.release()
            vEx.release()
            aEx.release()
        }
    }

    private class AacSink(outPath: String, val sr: Int, val ch: Int, durationUs: Long) {
        private val enc: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var started = false
        private val frame = 2 * ch
        private val target: Long = (durationUs * sr / 1_000_000L) * frame
        var written = 0L
            private set

        init {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
            }
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
        }

        /** Feeds 16-bit PCM to the encoder. Returns true once enough audio has been written. */
        fun push(src: ByteBuffer, offset: Int, size: Int): Boolean {
            var pos = offset
            var remaining = minOf(size.toLong(), target - written).toInt()
            remaining -= remaining % frame
            while (remaining > 0) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i < 0) { drain(false); continue }
                val eb = enc.getInputBuffer(i)!!
                eb.clear()
                var n = minOf(remaining, eb.capacity())
                n -= n % frame
                val d = src.duplicate()
                d.limit(pos + n)
                d.position(pos)
                eb.put(d)
                val pts = written * 1_000_000L / (frame.toLong() * sr)
                enc.queueInputBuffer(i, 0, n, pts, 0)
                written += n
                pos += n
                remaining -= n
                drain(false)
            }
            return written >= target
        }

        fun finish() {
            while (true) {
                val i = enc.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val pts = written * 1_000_000L / (frame.toLong() * sr)
                    enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
        }

        fun release() {
            try { enc.stop() } catch (_: Exception) {}
            try { enc.release() } catch (_: Exception) {}
            try { if (started) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            started = false
        }

        private fun drain(eos: Boolean) {
            while (true) {
                val i = enc.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!eos) return
                } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(enc.outputFormat)
                    muxer.start()
                    started = true
                } else if (i >= 0) {
                    val buf = enc.getOutputBuffer(i)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && started) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    enc.releaseOutputBuffer(i, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }
}
