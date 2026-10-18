package me.avinas.tempo.ui.home.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Interactive trend line chart with drag/tap scrubbing.
 *
 * Feature contributed by @FlazeIGuess (PR #1) with modifications.
 */

private const val DRAG_END_DELAY_MS = 300L
private const val TAP_DISPLAY_DURATION_MS = 1500L

@Composable
fun InteractiveTrendLine(
    dataPoints: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
    strokeWidth: androidx.compose.ui.unit.Dp = 3.dp,
    formatValue: (Float) -> String = { "${it.toInt()}m" },
    onValueSelected: ((Float, String) -> Unit)? = null,
    onSelectionCleared: (() -> Unit)? = null
) {
    if (dataPoints.isEmpty()) return
    
    require(labels.size == dataPoints.size) { 
        "Labels size (${labels.size}) must match dataPoints size (${dataPoints.size})" 
    }

    // Key on labels to reset state when dataset changes even if point count matches
    var selectedIndex by remember(labels) { mutableStateOf<Int?>(null) }
    var isDragging by remember(labels) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // Track pending clear job to cancel it on new interactions
    var pendingClearJob by remember(labels) { mutableStateOf<Job?>(null) }
    
    // Reset scrubbing state when data changes (e.g., switching time ranges)
    LaunchedEffect(labels) {
        pendingClearJob?.cancel()
        pendingClearJob = null
        onSelectionCleared?.invoke()
    }
    
    DisposableEffect(labels) {
        onDispose {
            pendingClearJob?.cancel()
        }
    }

    fun calculateNearestIndex(xPos: Float, width: Float): Int {
        if (dataPoints.size <= 1) return 0
        
        val fraction = (xPos / width).coerceIn(0f, 1f)
        
        val exactIndex = fraction * (dataPoints.size - 1)
        
        return exactIndex.toInt().coerceIn(0, dataPoints.size - 1)
    }
    
    fun clearSelectionWithDelay(delayMs: Long) {
        pendingClearJob?.cancel()
        pendingClearJob = scope.launch {
            delay(delayMs)
            isDragging = false
            selectedIndex = null
            onSelectionCleared?.invoke()
        }
    }
    
    fun cancelPendingClear() {
        pendingClearJob?.cancel()
        pendingClearJob = null
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(labels) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            cancelPendingClear()
                            
                            isDragging = true
                            
                            val width = size.width.toFloat()
                            val nearestIndex = calculateNearestIndex(offset.x, width)
                            
                            selectedIndex = nearestIndex
                            onValueSelected?.invoke(
                                dataPoints[nearestIndex],
                                labels[nearestIndex]
                            )
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val offset = change.position
                            
                            val width = size.width.toFloat()
                            val clampedX = offset.x.coerceIn(0f, width)
                            val nearestIndex = calculateNearestIndex(clampedX, width)
                            
                            if (nearestIndex != selectedIndex) {
                                selectedIndex = nearestIndex
                                onValueSelected?.invoke(
                                    dataPoints[nearestIndex],
                                    labels[nearestIndex]
                                )
                            }
                        },
                        onDragEnd = {
                            clearSelectionWithDelay(DRAG_END_DELAY_MS)
                        },
                        onDragCancel = {
                            isDragging = false
                            selectedIndex = null
                            pendingClearJob?.cancel()
                            pendingClearJob = null
                            onSelectionCleared?.invoke()
                        }
                    )
                }
                .pointerInput(labels) {
                    detectTapGestures { offset ->
                        // Cancel any pending clear from previous interaction
                        cancelPendingClear()
                        
                        isDragging = true
                        
                        val width = size.width.toFloat()
                        val nearestIndex = calculateNearestIndex(offset.x, width)
                        
                        selectedIndex = nearestIndex
                        onValueSelected?.invoke(
                            dataPoints[nearestIndex],
                            labels[nearestIndex]
                        )
                        
                        clearSelectionWithDelay(TAP_DISPLAY_DURATION_MS)
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val maxVal = dataPoints.maxOrNull() ?: 1f
            val minVal = 0f
            val range = (maxVal - minVal).coerceAtLeast(1f)

            val path = Path()
            val fillPath = Path()
            
            if (dataPoints.size == 1) {
                val x = width / 2f
                val y = height - ((dataPoints[0] - minVal) / range * height)
                
                path.moveTo(x, y)
                fillPath.moveTo(0f, height)
                fillPath.lineTo(0f, y)
                fillPath.lineTo(width, y)
                fillPath.lineTo(width, height)
                fillPath.close()
            } else {
                dataPoints.forEachIndexed { index, value ->
                    val x = (index.toFloat() / (dataPoints.size - 1)) * width
                    val y = height - ((value - minVal) / range * height)

                    if (index == 0) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, height)
                        fillPath.lineTo(x, y)
                    } else {
                        val prevIndex = index - 1
                        val prevX = (prevIndex.toFloat() / (dataPoints.size - 1)) * width
                        val prevY = height - ((dataPoints[prevIndex] - minVal) / range * height)
                        
                        val controlPoint1X = prevX + (x - prevX) / 2
                        val controlPoint1Y = prevY
                        val controlPoint2X = prevX + (x - prevX) / 2
                        val controlPoint2Y = y

                        path.cubicTo(controlPoint1X, controlPoint1Y, controlPoint2X, controlPoint2Y, x, y)
                        fillPath.cubicTo(controlPoint1X, controlPoint1Y, controlPoint2X, controlPoint2Y, x, y)
                    }
                }

                val lastX = ((dataPoints.size - 1).toFloat() / (dataPoints.size - 1)) * width
                fillPath.lineTo(lastX, height)
                fillPath.close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(fillColor, Color.Transparent),
                    startY = 0f,
                    endY = height
                )
            )

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            )

            if (isDragging && selectedIndex != null) {
                // Same x mapping as the selected point, so the line and dot stay in sync.
                val indicatorIndex = selectedIndex!!
                val indicatorX = if (dataPoints.size > 1) {
                    (indicatorIndex.toFloat() / (dataPoints.size - 1)) * width
                } else {
                    width / 2f
                }
                drawLine(
                    color = lineColor.copy(alpha = 0.3f),
                    start = Offset(indicatorX, 0f),
                    end = Offset(indicatorX, height),
                    strokeWidth = 2.dp.toPx()
                )
            }

            selectedIndex?.let { index ->
                val x = if (dataPoints.size > 1) {
                    (index.toFloat() / (dataPoints.size - 1)) * width
                } else {
                    width / 2f
                }
                val y = height - ((dataPoints[index] - minVal) / range * height)
                
                // Outer glow
                drawCircle(
                    color = lineColor.copy(alpha = 0.3f),
                    radius = 14.dp.toPx(),
                    center = Offset(x, y)
                )
                
                // Middle ring
                drawCircle(
                    color = lineColor.copy(alpha = 0.6f),
                    radius = 10.dp.toPx(),
                    center = Offset(x, y)
                )
                
                // Inner dot
                drawCircle(
                    color = lineColor,
                    radius = 6.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }
    }
}
