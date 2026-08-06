package com.eagleviewer.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * 可缩放图片：双指缩放、拖动平移（带边界约束与回弹动画）、
 * 双击以点击位置为中心平滑放大/还原、单击回调。
 *
 * 与 HorizontalPager 共存的关键：仅在「双指」或「已放大」时消费位移事件，
 * 单指且未放大时不消费，让 Pager 处理左右翻页。
 *
 * 手势期间用 snapTo 即时跟随手指；双击与松手回弹用 animateTo 平滑过渡。
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        if (s <= 1f || layoutSize == IntSize.Zero) return Offset.Zero
        val maxX = layoutSize.width * (s - 1f) / 2f
        val maxY = layoutSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun animateTo(targetScale: Float, targetOffset: Offset, animate: Boolean) {
        scope.launch {
            if (animate) {
                launch { scale.animateTo(targetScale, tween(250)) }
                launch { offsetX.animateTo(targetOffset.x, tween(250)) }
                launch { offsetY.animateTo(targetOffset.y, tween(250)) }
            } else {
                scale.snapTo(targetScale)
                offsetX.snapTo(targetOffset.x)
                offsetY.snapTo(targetOffset.y)
            }
        }
    }

    Box(
        modifier
            .onSizeChanged { layoutSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale.value > 1f) {
                            animateTo(1f, Offset.Zero, animate = true)
                        } else {
                            // 以双击点为中心放大：缩放围绕图形中心进行，
                            // 需要的平移量 = (中心 - 点击点) * (倍率 - 1)
                            val center = Offset(
                                layoutSize.width / 2f,
                                layoutSize.height / 2f,
                            )
                            val target = clamp((center - tap) * (2.5f - 1f), 2.5f)
                            animateTo(2.5f, target, animate = true)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val touches = event.changes
                        // 单指且未放大：不消费，交给 Pager 翻页
                        if (touches.size >= 2 || scale.value > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale.value * zoom).coerceIn(1f, 5f)
                            val newOffset = if (newScale > 1f) {
                                clamp(Offset(offsetX.value, offsetY.value) + pan, newScale)
                            } else Offset.Zero
                            animateTo(newScale, newOffset, animate = false)
                            touches.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (touches.any { it.pressed })
                    // 松手：未放大则复位；超出边界则弹簧回弹收敛
                    if (scale.value <= 1f) {
                        animateTo(1f, Offset.Zero, animate = false)
                    } else {
                        val current = Offset(offsetX.value, offsetY.value)
                        val clamped = clamp(current, scale.value)
                        if (clamped != current) {
                            scope.launch {
                                launch { offsetX.animateTo(clamped.x, spring()) }
                                launch { offsetY.animateTo(clamped.y, spring()) }
                            }
                        }
                    }
                }
            },
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offsetX.value
                    translationY = offsetY.value
                },
            contentScale = ContentScale.Fit,
        )
    }
}
