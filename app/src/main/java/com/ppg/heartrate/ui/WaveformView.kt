package com.ppg.heartrate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 采集过程中的实时亮度波形（需求 8）。
 *
 * 数据已由 ViewModel 归一化（滑动基线 + 放大倍数），这里只负责按「最旧在左、最新在右」
 * 滚动绘制。曲线高度超出画布时做纵向夹紧，不裁掉绘制路径。
 *
 * @param samples 归一化后的波形样本，最新值在末尾
 * @param modifier 布局修饰
 * @param lineColor 曲线颜色
 * @param height 画布高度
 */
@Composable
fun WaveformView(
    samples: FloatArray,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF4ADE80),
    height: Dp = 160.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(0xFF0E141C), RoundedCornerShape(12.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            // 中线：脉搏波形围绕它上下起伏
            val centerY = size.height / 2f
            drawLine(
                color = Color(0xFF1E293B),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1f
            )
            drawWaveform(samples, centerY, lineColor)
        }
    }
}

/**
 * 绘制波形路径。
 *
 * 纵向缩放：输入是「相对滑动基线的偏移量」，量级通常在 ±5 左右，
 * 统一放大到画布半高再夹紧，保证小波动可见、大波动不溢出。
 */
private fun DrawScope.drawWaveform(
    samples: FloatArray,
    centerY: Float,
    lineColor: Color
) {
    if (samples.size < 2) return

    val halfHeight = size.height / 2f
    val stepX = size.width / (samples.size - 1).toFloat()

    val path = Path()
    for (i in samples.indices) {
        // 用半高作为归一化基准，超出部分夹紧在画布内
        val y = centerY - (samples[i].coerceIn(-1f, 1f) * halfHeight * 0.92f)
        val x = i * stepX
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
}

/**
 * 采集期的实时亮度指示（需求 5）。
 *
 * 以一个低调的小圆点 + 数值展示当前帧亮度，方便确认手指是否已完全覆盖镜头。
 * 正式使用时可隐藏，此处按交接文档要求保留为开发期辅助。
 *
 * @param luma 当前平均亮度，0–255
 */
@Composable
fun LumaIndicator(luma: Double, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.height(12.dp)) {
        // 亮度映射到 0–1：全黑表示完全遮住，全白表示完全漏光
        val level = (luma / 255.0).coerceIn(0.0, 1.0)
        val radius = if (abs(level) < 0.02f) 3.dp.toPx() else 6.dp.toPx()
        val color = when {
            level > 0.85 -> Color(0xFFF87171)   // 过亮：手指没盖住或漏光
            level < 0.05 -> Color(0xFF4ADE80)   // 很暗：已完全遮住
            else -> Color(0xFFFBBF24)
        }
        drawCircle(color = color, radius = radius, center = Offset(size.width / 2f, size.height / 2f))
    }
}