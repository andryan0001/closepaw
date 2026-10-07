package ai.closepaw.gemini.live

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Screen frame provider for Gemini Live at ~1 FPS.
 *
 * Mirrors the device display through a [MediaProjection] virtual display
 * into an [ImageReader], downscales to half resolution, JPEG-encodes and
 * emits Base64 frames via [onFrameCaptured] for `image/jpeg` realtime input.
 * Capture runs on [Dispatchers.Default]; encoding one frame per second keeps
 * uplink usage bounded while staying fresh enough for a co-pilot.
 *
 * The caller owns [mediaProjection] acquisition (see
 * [GeminiLiveMediaProjection]); [stop] releases the virtual display and
 * reader and stops the projection.
 */
class GeminiScreenCaster(
    private val mediaProjection: MediaProjection,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDensity: Int,
    private val onFrameCaptured: (base64Jpeg: String) -> Unit,
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null
    private var projectionCallback: MediaProjection.Callback? = null

    fun startCasting(scope: CoroutineScope) {
        if (captureJob != null) return
        val scaledWidth = (screenWidth / 2).coerceAtLeast(MIN_WIDTH)
        val scaledHeight = (screenHeight / 2).coerceAtLeast(MIN_HEIGHT)

        // Android 14+ (API 34) throws IllegalStateException from
        // createVirtualDisplay unless a MediaProjection.Callback is
        // registered first. Registering on all API levels (21+) is harmless
        // and also surfaces user-initiated stops via onStop().
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }
        projectionCallback = callback
        val mainHandler = Handler(Looper.getMainLooper())
        mediaProjection.registerCallback(callback, mainHandler)

        val reader = ImageReader.newInstance(scaledWidth, scaledHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "GeminiLiveScreenCast",
            scaledWidth,
            scaledHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler,
        )

        captureJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    reader.acquireLatestImage()?.use { image ->
                        encodeFrame(image, scaledWidth, scaledHeight)?.let(onFrameCaptured)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Frame capture failed: ${e.message}")
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private fun encodeFrame(image: Image, width: Int, height: Int): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                if (it != bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }
        return try {
            val output = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            cropped.recycle()
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        } finally {
            virtualDisplay = null
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        } finally {
            imageReader = null
        }
        projectionCallback?.let { callback ->
            try {
                mediaProjection.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        projectionCallback = null
        try {
            mediaProjection.stop()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "GeminiScreenCaster"
        private const val FRAME_INTERVAL_MS = 1000L
        private const val JPEG_QUALITY = 70
        private const val MIN_WIDTH = 360
        private const val MIN_HEIGHT = 640
    }
}
