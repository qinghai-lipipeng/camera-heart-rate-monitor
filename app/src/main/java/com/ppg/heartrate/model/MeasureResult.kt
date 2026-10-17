package com.ppg.heartrate.model

/**
 * 信号质量等级。对应交接文档 5.6 的三行判据，取三项中最差的一项作为最终等级。
 */
enum class SignalQuality {
    GOOD,    // 良好
    FAIR,    // 一般
    POOR     // 差：仍显示读数，但必须标注不可信
}

/**
 * 一次测量的最终产物。数据为纯 Kotlin，不依赖 Android SDK。
 *
 * @param bpm 心率读数，成功时有效
 * @param quality 信号质量等级
 * @param reasons 质量为「差 / 一般」时的具体原因提示（界面直接展示）
 * @param sampleRateHz 实际估算出的采样率，用于排查帧率不足
 * @param frameCount 参与计算的有效帧数
 * @param peakRatio 主峰能量占频段总能量的比例
 * @param secondaryRatio 频段内次高峰与主峰的比值
 * @param jumpCount 采集期间亮度方差突变次数
 * @param corrected 是否做过次谐波修正（f_peak / 2）
 */
data class MeasureResult(
    val bpm: Double,
    val quality: SignalQuality,
    val reasons: List<String>,
    val sampleRateHz: Double,
    val frameCount: Int,
    val peakRatio: Double,
    val secondaryRatio: Double,
    val jumpCount: Int,
    val corrected: Boolean
) {
    /** 质量不足两档时提示读数不可信（交接文档 5.6：不要假装准确）。 */
    val trustworthy: Boolean get() = quality != SignalQuality.POOR
}

/**
 * 测量的两种结局：拿到读数，或需要重测。
 * 采集失败、NaN/Inf、超出 40~200 BPM 合理区间都走 [Failure]，界面直接提示重测。
 */
sealed interface MeasureOutcome {
    data class Success(val result: MeasureResult) : MeasureOutcome
    data class Failure(val reason: String) : MeasureOutcome
}