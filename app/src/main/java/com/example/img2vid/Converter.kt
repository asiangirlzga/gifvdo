package com.example.img2vid

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Img(val uri: Uri, val name: String)

data class Options(
    val secondsPerImage: Float,
    val canvasW: Int,
    val canvasH: Int,
    val makeVideo: Boolean,
    val makeGif: Boolean,
    val gifWidth: Int,
    val gifStep: Int,
    val audioUri: Uri? = null,
)

data class ConvertResult(
    val gifUri: Uri?,
    val videoUri: Uri?,
    val used: Int,
    val skipped: Int,
    val audioError: String? = null,
)

private val chunkRegex = Regex("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)")

/** "img2" sorts before "img10". */
val naturalOrder = Comparator<String> { a, b ->
    val pa = a.lowercase().split(chunkRegex)
    val pb = b.lowercase().split(chunkRegex)
    for (i in 0 until min(pa.size, pb.size)) {
        val x = pa[i]; val y = pb[i]
        val nx = x.toLongOrNull(); val ny = y.toLongOrNull()
        val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
        if (c != 0) return@Comparator c
    }
    pa.size.compareTo(pb.size)
}

class Converter(private val ctx: Context) {

    suspend fun run(
        images: List<Img>,
        o: Options,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ConvertResult = withContext(Dispatchers.Default) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cw = o.canvasW
        val ch = o.canvasH
        val canvasBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        val px = IntArray(cw * ch)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val dst = RectF()

        val gw = o.gifWidth
        val gh = max(1, (gw.toLong() * ch / cw).toInt())
        val gifBmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        val gifCanvas = Canvas(gifBmp)
        val gifPx = IntArray(gw * gh)
        val gifSrc = Rect(0, 0, cw, ch)
        val gifDst = Rect(0, 0, gw, gh)

        var gifUri: Uri? = null
        var gifOut: BufferedOutputStream? = null
        var gif: GifEncoder? = null
        var video: VideoEncoder? = null
        var tmp: File? = null
        var success = false

        try {
            if (o.makeGif) {
                gifUri = createPending(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "img2gif_$stamp.gif", "image/gif", "Pictures/ImageToGifVideo",
                )
                gifOut = BufferedOutputStream(ctx.contentResolver.openOutputStream(gifUri)!!, 1 shl 16)
                val delayCs = max(2, (o.secondsPerImage * o.gifStep * 100).roundToInt())
                gif = GifEncoder(gifOut, gw, gh, delayCs)
            }
            if (o.makeVideo) {
                tmp = File(ctx.cacheDir, "tmp_$stamp.mp4")
                val bitrate = (cw * ch * 5).coerceIn(1_000_000, 16_000_000)
                video = VideoEncoder(tmp.path, cw, ch, bitrate)
            }

            val frameUs = (o.secondsPerImage * 1_000_000).toLong()
            var used = 0
            var skipped = 0
            val v = video
            val g = gif

            images.forEachIndexed { i, img ->
                ensureActive()
                val bmp = decode(img.uri, cw, ch)
                if (bmp == null) {
                    skipped++
                } else {
                    canvas.drawColor(Color.BLACK)
                    val s = min(cw.toFloat() / bmp.width, ch.toFloat() / bmp.height)
                    val dw = bmp.width * s
                    val dh = bmp.height * s
                    dst.set((cw - dw) / 2f, (ch - dh) / 2f, (cw + dw) / 2f, (ch + dh) / 2f)
                    canvas.drawBitmap(bmp, null, dst, paint)
                    bmp.recycle()

                    if (v != null) {
                        canvasBmp.getPixels(px, 0, cw, 0, 0, cw, ch)
                        v.addFrame(px, used * frameUs)
                    }
                    if (g != null && used % o.gifStep == 0) {
                        gifCanvas.drawBitmap(canvasBmp, gifSrc, gifDst, paint)
                        gifBmp.getPixels(gifPx, 0, gw, 0, 0, gw, gh)
                        g.addFrame(gifPx)
                    }
                    used++
                }
                onProgress(i + 1, images.size)
            }
            if (used == 0) throw IllegalStateException("None of the selected files could be read as images.")

            var videoUri: Uri? = null
            var audioError: String? = null
            video?.let {
                // Repeat the last frame so it stays on screen for its full duration.
                it.addFrame(px, used * frameUs)
                val totalUs = used * frameUs + frameUs
                it.finish(totalUs)
                video = null

                var source: File = tmp!!
                val extra = mutableListOf<File>()
                try {
                    if (o.audioUri != null) {
                        try {
                            val audioFile = File(ctx.cacheDir, "tmp_$stamp.m4a").also { f -> extra.add(f) }
                            val mixed = File(ctx.cacheDir, "tmp_${stamp}_av.mp4").also { f -> extra.add(f) }
                            AudioEncoder.encode(ctx, o.audioUri, totalUs, audioFile.path) { ensureActive() }
                            AudioEncoder.mux(source.path, audioFile.path, mixed.path)
                            source = mixed
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            audioError = e.message ?: "unknown error"
                        }
                    }
                    videoUri = createPending(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        "img2video_$stamp.mp4", "video/mp4", "Movies/ImageToGifVideo",
                    )
                    ctx.contentResolver.openOutputStream(videoUri!!)!!.use { os ->
                        source.inputStream().use { s -> s.copyTo(os, 1 shl 16) }
                    }
                    publish(videoUri!!)
                } finally {
                    extra.forEach { f -> f.delete() }
                }
            }
            gif?.let {
                it.finish()
                gifOut?.close()
                gifOut = null
                publish(gifUri!!)
            }
            success = true
            ConvertResult(gifUri, videoUri, used, skipped, audioError)
        } finally {
            video?.release()
            try { gifOut?.close() } catch (_: Exception) {}
            tmp?.delete()
            if (!success && gifUri != null) {
                try { ctx.contentResolver.delete(gifUri, null, null) } catch (_: Exception) {}
            }
            canvasBmp.recycle()
            gifBmp.recycle()
        }
    }

    /** Decodes one image at no more than the canvas size, honouring EXIF rotation. */
    private fun decode(uri: Uri, maxW: Int, maxH: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = min(maxW.toFloat() / info.size.width, maxH.toFloat() / info.size.height)
            if (s < 1f) {
                decoder.setTargetSize(
                    max(1, (info.size.width * s).roundToInt()),
                    max(1, (info.size.height * s).roundToInt()),
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun createPending(collection: Uri, name: String, mime: String, relPath: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(collection, values) ?: error("Could not create output file")
    }

    private fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, values, null, null)
    }
}
