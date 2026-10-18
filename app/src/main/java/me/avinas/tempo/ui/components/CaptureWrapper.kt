package me.avinas.tempo.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest

// CompositionLocal to indicate we're inside a CaptureWrapper
// Images should use software bitmaps (allowHardware = false) when true
val LocalInCaptureContext = compositionLocalOf { false }

/**
 * Longest-edge cap, in pixels, for a captured bitmap.
 *
 * The capture is drawn on the UI thread (see [CaptureWrapper] for why it cannot be moved
 * off it), so the rendered surface is bounded here. A full-screen share canvas is typically
 * 1080x1920 px, i.e. 8.3 MB as ARGB_8888 and ~2.1 M pixels of software rasterisation on the
 * main thread; capping the long edge roughly halves both the allocation and the draw cost.
 *
 * Compose replays its draw commands against the scaled [android.graphics.Canvas], so text,
 * shapes and gradients are re-rasterised crisply at the capped size. Only bitmap content
 * (album art) is resampled.
 */
private const val MAX_CAPTURE_LONG_EDGE_PX = 1440

class CaptureController {
    private val _captureRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val captureRequest = _captureRequest.asSharedFlow()
    
    private val _capturedBitmap = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1)
    val capturedBitmap = _capturedBitmap.asSharedFlow()

    fun capture() {
        _captureRequest.tryEmit(Unit)
    }
    
    fun onCaptured(bitmap: Bitmap) {
        _capturedBitmap.tryEmit(bitmap)
    }
}

@Composable
fun rememberCaptureController(): CaptureController {
    return remember { CaptureController() }
}

@Composable
fun CaptureWrapper(
    controller: CaptureController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var capturedView: android.view.View? by remember { mutableStateOf(null) }
    val currentContent by rememberUpdatedState(content)
    LaunchedEffect(controller) {
        controller.captureRequest.collectLatest {
            capturedView?.let { view ->
                try {
                    android.util.Log.d("CaptureWrapper", "Capture requested. View dims: ${view.width}x${view.height}")
                    if (view.width > 0 && view.height > 0) {
                        // Bound the captured surface (see MAX_CAPTURE_LONG_EDGE_PX).
                        val longestEdge = maxOf(view.width, view.height)
                        val scale = if (longestEdge > MAX_CAPTURE_LONG_EDGE_PX) {
                            MAX_CAPTURE_LONG_EDGE_PX.toFloat() / longestEdge
                        } else {
                            1f
                        }
                        val bitmapWidth = (view.width * scale).toInt().coerceAtLeast(1)
                        val bitmapHeight = (view.height * scale).toInt().coerceAtLeast(1)

                        // Create bitmap and canvas for capture
                        val bitmap = Bitmap.createBitmap(
                            bitmapWidth,
                            bitmapHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        if (scale != 1f) {
                            // Scales the drawn output without changing the view's own layout,
                            // so what is captured still matches the on-screen composition.
                            canvas.scale(scale, scale)
                        }

                        // Draw the view hierarchy to the canvas.
                        // Must stay on the main thread: this ComposeView is attached to a live,
                        // hardware-accelerated window, and AndroidComposeView.dispatchDraw() calls
                        // measureAndLayout() and records into RenderNodes. Drawing it from a
                        // background thread races the UI thread's own layout/draw pass.
                        // Safe: LocalInCaptureContext forces software bitmap allocation
                        view.draw(canvas)

                        android.util.Log.d(
                            "CaptureWrapper",
                            "Bitmap captured successfully. Size: ${bitmap.byteCount} bytes " +
                                "(${bitmapWidth}x${bitmapHeight}, scale=$scale)"
                        )
                        controller.onCaptured(bitmap)
                    } else {
                        android.util.Log.e("CaptureWrapper", "View has 0 dimensions: w=${view.width}, h=${view.height}")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    android.util.Log.e("CaptureWrapper", "Error capturing view", e)
                }
            } ?: run {
                 android.util.Log.e("CaptureWrapper", "capturedView is null")
            }
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.compose.ui.platform.ComposeView(ctx).apply {
                setContent {
                    // Provide capture context to all children
                    // This signals that images should use software bitmaps
                    CompositionLocalProvider(LocalInCaptureContext provides true) {
                        currentContent()
                    }
                }
            }
        },
        update = { view ->
            capturedView = view
        },
        modifier = modifier
    )
}
