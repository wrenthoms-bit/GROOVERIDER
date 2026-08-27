package com.delrogue.grooverider.ui.cloud

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2

private const val LONG_PRESS_TIMEOUT_MS = 400L
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
private const val MOVE_SLOP_PX = 12f
private const val TWO_PI = (2.0 * Math.PI).toFloat()
private const val PI_F = Math.PI.toFloat()

/**
 * The whole XY-pad gesture map in one recogniser (spec 5.3): 1-finger drag,
 * long-press freeze, double-tap re-roll, 2-finger pinch/rotate, 3-finger
 * capture. Compose's higher-level detectors don't compose cleanly once you
 * need to arbitrate between them by live pointer count, so this reads pointer
 * events directly -- a best-effort implementation that will want on-device
 * gesture tuning (touch slop, timing) that this environment cannot do.
 */
suspend fun PointerInputScope.detectCloudPadGestures(
    onDrag: (x01: Float, y01: Float) -> Unit,
    onPinchDelta: (Float) -> Unit,
    onRotateDelta: (Float) -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    onThreeFingerTap: () -> Unit,
) {
    var lastTapUpTime = 0L

    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var handledAsLongPress = false
        var moved = false
        var sawMultiTouch = false
        var firedCapture = false

        // Race the long-press timeout against movement / release / a second
        // or third finger arriving, while exactly one pointer is down.
        val outcome = withTimeoutOrNull(LONG_PRESS_TIMEOUT_MS) {
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                when {
                    pressed.size >= 3 -> return@withTimeoutOrNull "capture"
                    pressed.size == 2 -> return@withTimeoutOrNull "multitouch"
                    pressed.isEmpty() -> return@withTimeoutOrNull "released"
                    else -> {
                        val c = pressed[0]
                        if ((c.position - c.previousPosition).getDistance() > MOVE_SLOP_PX) {
                            return@withTimeoutOrNull "moved"
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        when (outcome) {
            null -> { handledAsLongPress = true; onLongPress() }
            "capture" -> { firedCapture = true; onThreeFingerTap() }
            "moved" -> moved = true
            "multitouch" -> sawMultiTouch = true
            else -> Unit   // "released" -- straight to tap handling below
        }

        // Keep tracking the gesture (drag / pinch+rotate) until every finger lifts.
        if (outcome != "released" && !firedCapture) {
            var lastDistance = -1f
            var lastAngle = 0f
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                when {
                    pressed.size >= 2 -> {
                        sawMultiTouch = true
                        val p0 = pressed[0].position
                        val p1 = pressed[1].position
                        val dist = distance(p0, p1)
                        val angle = atan2(p1.y - p0.y, p1.x - p0.x)
                        if (lastDistance < 0f) {
                            lastDistance = dist; lastAngle = angle
                        } else {
                            onPinchDelta((dist - lastDistance) / 300f)
                            var dAngle = angle - lastAngle
                            if (dAngle > PI_F) dAngle -= TWO_PI
                            if (dAngle < -PI_F) dAngle += TWO_PI
                            onRotateDelta(dAngle)
                            lastDistance = dist; lastAngle = angle
                        }
                        pressed.forEach { it.consume() }
                    }
                    pressed.size == 1 && !handledAsLongPress -> {
                        lastDistance = -1f   // fresh deltas if a second finger returns
                        val c = pressed[0]
                        if ((c.position - c.previousPosition).getDistance() > 1f) moved = true
                        if (moved) {
                            onDrag(
                                (c.position.x / size.width.toFloat()).coerceIn(0f, 1f),
                                1f - (c.position.y / size.height.toFloat()).coerceIn(0f, 1f),
                            )
                            c.consume()
                        }
                    }
                    else -> Unit
                }
            } while (event.changes.any { it.pressed })
        }

        if (!handledAsLongPress && !moved && !sawMultiTouch && !firedCapture) {
            val now = System.currentTimeMillis()
            if (now - lastTapUpTime < DOUBLE_TAP_TIMEOUT_MS) {
                onDoubleTap()
                lastTapUpTime = 0L
            } else {
                lastTapUpTime = now
            }
        }
    }
}

private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
