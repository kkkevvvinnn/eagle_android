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
 * [imageAspect] 为图片宽高比（宽/高，0 表示未知）：ContentScale.Fit 下图片
 * 实际显示区域通常小于整屏（letterbox），平移边界必须按显示尺寸计算，
 * 否则图片可以被拖出屏幕；未知时退化为按整屏估算。
 *
 * 手势期间用 snapTo 即时跟随手指；双击与松手回弹用 animateTo 平滑过渡。
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    imageAspect: Float = 0f,
    onTap: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    /** Fit 模式下图片在缩放倍率 1 时的实际显示尺寸。 */
    fun fitBase(): Pair<Float, Float> {
        val lw = layoutSize.width.toFloat()
        val lh = layoutSize.height.toFloat()
        if (imageAspect <= 0f || lw <= 0f || lh <= 0f) return lw to lh
        return if (lw / lh > imageAspect) (lh * imageAspect) to lh
        else lw to (lw / imageAspect)
    }

    fun clamp(o: Offset, s: Float): Offset {
        if (s <= 1f || layoutSize == IntSize.Zero) return Offset.Zero
        val (bw, bh) = fitBase()
        // 某轴显示尺寸放大后仍小于屏幕时，该轴不允许平移（图片不会丢出屏幕）
        val maxX = maxOf(0f, (bw * s - layoutSize.width) / 2f)
        val maxY = maxOf(0f, (bh * s - layoutSize.height) / 2f)
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
                        val pressed = event.changes.filter { it.pressed }
                        // 单指且未放大：不消费，交给 Pager 翻页
                        if (pressed.size >= 2 || scale.value > 1f) {
                            val pan = event.calculatePan()
                            var base = Offset(offsetX.value, offsetY.value) + pan
                            var newScale = scale.value
                            if (pressed.size >= 2) {
                                val zoom = event.calculateZoom()
                                val oldScale = scale.value
                                newScale = (oldScale * zoom).coerceIn(1f, 5f)
                                // 焦点补偿：缩放围绕双指质心而非屏幕中心，
                                // 捏合时指尖下的内容不漂移
                                val centroid = pressed
                                    .map { it.position }
                                    .reduce { a, b -> a + b } / pressed.size.toFloat()
                                val center = Offset(
                                    layoutSize.width / 2f,
                                    layoutSize.height / 2f,
                                )
                                val zoomRatio = if (oldScale > 0f) newScale / oldScale else 1f
                                base += (centroid - center - base) * (1f - zoomRatio)
                            }
                            val newOffset = if (newScale > 1f) clamp(base, newScale) else Offset.Zero
                            animateTo(newScale, newOffset, animate = false)
                            pressed.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
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
