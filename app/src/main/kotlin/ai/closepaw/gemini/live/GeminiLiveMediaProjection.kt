package ai.closepaw.gemini.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

/**
 * Acquiring a [MediaProjection] requires a user-consented screen-capture
 * intent. This helper owns the platform calls; the host Activity owns the
 * `ActivityResultLauncher` wiring:
 *
 * ```
 * private val captureLauncher = registerForActivityResult(
 *     ActivityResultContracts.StartActivityForResult()
 * ) { result ->
 *     if (result.resultCode == Activity.RESULT_OK && result.data != null) {
 *         val projection = GeminiLiveMediaProjection.acquire(
 *             this, result.resultCode, result.data!!,
 *         )
 *         viewModel.onScreenCaptureGranted(projection)
 *     }
 * }
 *
 * // When the user starts the co-pilot:
 * captureLauncher.launch(GeminiLiveMediaProjection.createIntent(this))
 * ```
 */
object GeminiLiveMediaProjection {

    fun createIntent(context: Context): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.createScreenCaptureIntent()
    }

    fun acquire(context: Context, resultCode: Int, data: Intent): MediaProjection? {
        if (resultCode != Activity.RESULT_OK) return null
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return try {
            manager.getMediaProjection(resultCode, data)
        } catch (_: Exception) {
            null
        }
    }
}
