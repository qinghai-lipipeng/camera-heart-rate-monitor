package com.ppg.heartrate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ppg.heartrate.model.MeasureResult
import com.ppg.heartrate.model.SignalQuality
import kotlin.math.roundToInt

/**
 * 主界面：引导、波形、倒计时、结果（对应交接文档 M0–M2）。
 *
 * 所有状态由 [MeasureUiState] 驱动，本文件不含任何相机或信号处理逻辑。
 *
 * @param state 当前界面状态
 * @param errorBanner 相机层错误（无后置摄像头、绑定失败等），非空时在顶部显示红条
 * @param onStart 点击「开始测量」
 * @param onRestart 点击「重新测量」
 * @param onCancel 点击「取消」
 */
@Composable
fun MeasureScreen(
    state: MeasureUiState,
    errorBanner: String? = null,
    onStart: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (errorBanner != null) {
            ErrorBanner(errorBanner)
            Spacer(Modifier.height(12.dp))
        }

        Text(
            text = "心率检测",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "用食指指腹完全覆盖后置镜头与闪光灯，保持不动 30 秒",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // 需求 5：实时亮度指示，确认手指是否已盖住镜头
        CoverageIndicator(state)

        Spacer(Modifier.height(16.dp))

        // 需求 8：实时波形
        WaveformView(samples = state.waveform)

        Spacer(Modifier.height(24.dp))

        when (state.phase) {
            MeasureUiState.Phase.Idle -> IdleContent(onStart = onStart, hasError = state.errorMessage != null)
            MeasureUiState.Phase.Settling -> SettlingContent()
            MeasureUiState.Phase.Measuring -> MeasuringContent(
                countdownSeconds = state.countdownSeconds,
                onCancel = onCancel
            )
            MeasureUiState.Phase.Computing -> ComputingContent()
            MeasureUiState.Phase.Done -> ResultContent(result = state.result, onRestart = onRestart)
            MeasureUiState.Phase.Failed -> FailedContent(
                message = state.errorMessage ?: "测量失败，请重试",
                onRestart = onRestart
            )
        }
    }
}

/** 相机层错误条：绑定失败、无后置摄像头、缺少闪光灯等。 */
@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** 亮度覆盖指示：绿=已盖住，黄=可能漏光，红=完全没盖。 */
@Composable
private fun CoverageIndicator(state: MeasureUiState) {
    val level = (state.luma / 255.0).coerceIn(0.0, 1.0)
    val (color, label) = when {
        level > 0.85 -> Color(0xFFF87171) to "镜头未被遮住，请用指腹盖住"
        level < 0.05 -> Color(0xFF4ADE80) to "已覆盖，保持不动"
        else -> Color(0xFFFBBF24) to "正在覆盖，请按紧一些"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LumaIndicator(state.luma, Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$label（亮度 ${state.luma.roundToInt()}）",
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

@Composable
private fun IdleContent(onStart: () -> Unit, hasError: Boolean) {
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Text("开始测量（30 秒）", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
    if (hasError) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "请确认已授权相机权限，且设备具备闪光灯",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SettlingContent() {
    Spacer(Modifier.height(8.dp))
    Text(
        text = "正在稳定曝光，请不要移动手指…",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary
    )
    Spacer(Modifier.height(16.dp))
    CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun MeasuringContent(countdownSeconds: Int, onCancel: () -> Unit) {
    Text(
        text = "$countdownSeconds",
        style = MaterialTheme.typography.displayLarge,
        fontSize = 72.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        text = "秒后出结果 · 请保持手指不动",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
    )
    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("取消测量")
    }
}

@Composable
private fun ComputingContent() {
    Text(
        text = "正在分析信号…",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary
    )
    Spacer(Modifier.height(16.dp))
    CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
}

/** 结果展示（需求 9–12）。 */
@Composable
private fun ResultContent(result: MeasureResult?, onRestart: () -> Unit) {
    if (result == null) {
        FailedContent("没有可显示的结果，请重测", onRestart)
        return
    }

    Text(
        text = result.bpm.roundToInt().toString(),
        style = MaterialTheme.typography.displayLarge,
        fontSize = 88.sp,
        fontWeight = FontWeight.Bold,
        color = if (result.trustworthy) MaterialTheme.colorScheme.primary else Color(0xFFFBBF24)
    )
    Text(
        text = "BPM",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    )

    Spacer(Modifier.height(16.dp))
    QualityBadge(result.quality)

    if (!result.trustworthy) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "此读数可信度低，仅供参考",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFFBBF24),
            fontWeight = FontWeight.Medium
        )
    }

    Spacer(Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            result.reasons.forEach { reason ->
                Text(
                    text = "· $reason",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onRestart,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text("重新测量", fontSize = 17.sp)
    }
}

/** 信号质量徽标：良好 / 一般 / 差（需求 10）。 */
@Composable
private fun QualityBadge(quality: SignalQuality) {
    val (color, label) = when (quality) {
        SignalQuality.GOOD -> Color(0xFF4ADE80) to "信号质量：良好"
        SignalQuality.FAIR -> Color(0xFFFBBF24) to "信号质量：一般"
        SignalQuality.POOR -> Color(0xFFF87171) to "信号质量：差"
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), MaterialTheme.shapes.small)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text = label, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FailedContent(message: String, onRestart: () -> Unit) {
    Text(
        text = "测量未完成",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onRestart,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text("重新测量")
    }
}