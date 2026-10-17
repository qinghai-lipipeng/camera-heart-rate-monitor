package com.ppg.heartrate.signal

import com.ppg.heartrate.model.MeasureOutcome
import com.ppg.heartrate.model.MeasureResult
import com.ppg.heartrate.model.SignalQuality
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 心率估计器：峰值检测 + 次谐波修正 → BPM + 质量分。
 *
 * 对应交接文档 5.6 与 5.7。本类不依赖 Android SDK，可脱离设备单独验证。
 */
object HeartRateEstimator {

    /** 合理区间之外的读数视为异常（交接文档 5.7）。 */
    const val MIN_VALID_BPM = 40.0
    const val MAX_VALID_BPM = 200.0

    /** 频段内次谐波判定阈值：该点幅度 > 主峰幅度的 30% 即认为显著。 */
    const val SUB_HARMONIC_RATIO = 0.30

    /** 次高峰 / 主峰 的分档阈值（交接文档 5.6 第二行）。 */
    private const val SECONDARY_GOOD = 0.5
    private const val SECONDARY_FAIR = 0.8

    /** 主峰能量占比的分档阈值（交接文档 5.6 第一行）。 */
    private const val PEAK_RATIO_GOOD = 0.4
    private const val PEAK_RATIO_FAIR = 0.2

    /**
     * 谐波位置的相对容差。±6% 基频足以覆盖 FFT 栅格误差（30 秒采集下约 0.037 Hz）
     * 与抛物线插值的残差，同时不会误伤基频附近的真实干扰。
     */
    private const val HARMONIC_TOLERANCE_RATIO = 0.06

    /**
     * Hann 窗主瓣的半宽（频点数）。
     *
     * Hann 窗的主瓣宽度为 4 个频点，故 ±3 内的 bin 都属于主峰本身。
     * 计算「干扰峰」时必须跳过这一范围，否则主瓣裙边（幅度可达峰值的 80%）
     * 会被当作次高峰，使判据恒定失效。
     */
    private const val MAIN_LOBE_HALF_WIDTH_BINS = 3

    /**
     * 从一整段采集数据估算心率。
     *
     * @param timestamps 帧时间戳（秒），来自 `imageProxy.imageInfo.timestamp`；允许非均匀
     * @param luma 对应的画面中心区域平均亮度
     * @return [MeasureOutcome.Success] 或 [MeasureOutcome.Failure]
     */
    fun estimate(timestamps: DoubleArray, luma: DoubleArray): MeasureOutcome {
        // ---- 步骤 0：样本量与时间戳合法性 ----
        val clean = Resampler.sanitize(timestamps, luma)
            ?: return MeasureOutcome.Failure("没有采集到有效数据，请重测")
        val (t, v) = clean

        if (t.size < Resampler.MIN_FRAME_COUNT) {
            return MeasureOutcome.Failure(
                "帧率过低（仅 ${t.size} 帧，低于 ${Resampler.MIN_FRAME_COUNT} 帧），请关闭其他应用后重测"
            )
        }

        // ---- 步骤 1：重采样到均匀网格（5.4）----
        val resampled = Resampler.resampleUniform(t, v)
            ?: return MeasureOutcome.Failure("数据重采样失败，请重测")
        val signal = resampled.signal
        val fs = resampled.sampleRate
        if (signal.size < Resampler.MIN_FRAME_COUNT) {
            return MeasureOutcome.Failure("有效数据不足，请重测")
        }

        // ---- 步骤 2：预处理（5.5 的 1–4 步）----
        var work = Filter.detrend(signal, fs)
        work = Filter.hannWindow(work)
        work = Filter.removeMean(work)

        // ---- 步骤 3：FFT 与频段保留（5.5 的 5–6 步）----
        val rawMagnitude = FftProcessor.magnitudeSpectrum(work)
        val fftSize = FftProcessor.nextPowerOfTwo(work.size)
        val magnitude = Filter.retainBand(rawMagnitude, fs, fftSize)

        // ---- 步骤 4：找主峰 ----
        val binHz = fs / fftSize
        val kMin = ceil(Filter.BAND_LOW_HZ / binHz).toInt().coerceIn(1, magnitude.size - 1)
        val kMax = floor(Filter.BAND_HIGH_HZ / binHz).toInt().coerceIn(kMin, magnitude.size - 1)

        var peakIndex = kMin
        var peakAmp = 0.0
        for (k in kMin..kMax) {
            if (magnitude[k] > peakAmp) {
                peakAmp = magnitude[k]
                peakIndex = k
            }
        }
        if (peakAmp <= 0.0 || !peakAmp.isFinite()) {
            return MeasureOutcome.Failure("未检测到明显的脉搏信号，请用指腹按紧镜头后重测")
        }

        // ---- 步骤 5：次谐波修正（5.6 的谐波陷阱）----
        // PPG 波形非正弦，二次谐波常强于基频，直接取最大峰会得到两倍心率。
        val peakHz = parabolicRefineHz(magnitude, peakIndex, binHz)
        var fundamentalHz = peakHz
        var corrected = false

        val halfBinIndex = (peakHz / 2.0 / binHz).roundToInt()
        if (halfBinIndex in kMin..kMax && halfBinIndex != peakIndex) {
            // 在 f_peak/2 附近取一个小窗口内的最大幅度，避免因 FFT 栅格误差漏掉该点
            val (halfAmp, halfIdx) = localMaxWithIndex(magnitude, halfBinIndex, kMin, kMax)
            if (halfAmp > peakAmp * SUB_HARMONIC_RATIO) {
                val refinedHalf = parabolicRefineHz(magnitude, halfIdx, binHz)
                if (refinedHalf >= Filter.BAND_LOW_HZ) {
                    fundamentalHz = refinedHalf
                    corrected = true
                }
            }
        }

        // ---- 步骤 6：BPM 与数值合理性（5.7）----
        val bpm = fundamentalHz * 60.0
        if (!bpm.isFinite()) return MeasureOutcome.Failure("计算结果异常，请重测")
        if (bpm < MIN_VALID_BPM || bpm > MAX_VALID_BPM) {
            return MeasureOutcome.Failure("读数 $bpm BPM 超出合理范围（${MIN_VALID_BPM.toInt()}-${MAX_VALID_BPM.toInt()}），请重测")
        }

        // ---- 步骤 7：质量评估（5.6）----
        var bandEnergy = 0.0
        for (k in kMin..kMax) bandEnergy += magnitude[k] * magnitude[k]
        val peakRatio = if (bandEnergy > 0.0) {
            // 能量用平方幅度：单峰越集中，比值越高，符合「主峰能量占比」的物理含义
            (magnitude[peakIndex] * magnitude[peakIndex]) / bandEnergy
        } else 0.0

        // 干扰峰比值。interferencePeak 已排除主瓣裙边与自身谐波，
        // 因此该比值能真正反映「除脉搏外还有多强的杂散成分」。
        val secondaryRatio = if (peakAmp > 0.0) {
            val interference = interferencePeak(magnitude, fundamentalHz, peakIndex, binHz, kMin, kMax)
            interference / peakAmp
        } else 1.0

        val jumpCount = Resampler.countVarianceJumps(signal, fs)

        val (quality, reasons) = assessQuality(peakRatio, secondaryRatio, jumpCount, corrected)

        return MeasureOutcome.Success(
            MeasureResult(
                bpm = bpm,
                quality = quality,
                reasons = reasons,
                sampleRateHz = fs,
                frameCount = signal.size,
                peakRatio = peakRatio,
                secondaryRatio = secondaryRatio,
                jumpCount = jumpCount,
                corrected = corrected
            )
        )
    }

    /**
     * 三行判据取最差项作为最终质量等级，并给出对应的原因提示（交接文档 5.6 / 需求 10、11）。
     */
    private fun assessQuality(
        peakRatio: Double,
        secondaryRatio: Double,
        jumpCount: Int,
        corrected: Boolean
    ): Pair<SignalQuality, List<String>> {
        // 每项单独定档（1 = 良好，2 = 一般，3 = 差），最终取最大值
        val peakGrade = when {
            peakRatio > PEAK_RATIO_GOOD -> 1
            peakRatio >= PEAK_RATIO_FAIR -> 2
            else -> 3
        }
        val secondaryGrade = when {
            secondaryRatio < SECONDARY_GOOD -> 1
            secondaryRatio <= SECONDARY_FAIR -> 2
            else -> 3
        }
        val jumpGrade = when {
            jumpCount == 0 -> 1
            jumpCount <= 2 -> 2
            else -> 3
        }

        val worst = maxOf(peakGrade, secondaryGrade, jumpGrade)
        val reasons = ArrayList<String>()

        if (peakGrade >= 2) reasons.add("脉搏信号偏弱，请把指腹完全盖住镜头与闪光灯")
        if (secondaryGrade >= 2) reasons.add("频段内干扰峰明显，可能有光线干扰，请换个环境或按住更紧")
        if (jumpGrade >= 2) reasons.add("手指移动过多，请保持不动直到测量结束")
        if (reasons.isEmpty()) reasons.add("信号稳定")

        val quality = when (worst) {
            1 -> SignalQuality.GOOD
            2 -> SignalQuality.FAIR
            else -> SignalQuality.POOR
        }
        return quality to reasons
    }

    /**
     * 求「真实干扰峰」的幅度：与主峰的比值作为质量判据。
     *
     * 这里有两个容易踩的坑，都必须排除，否则该判据会完全失效：
     *
     * 1. **主瓣裙边**：Hann 窗的主瓣宽度约 4 个频点，主峰相邻的 bin（如峰值 bin 的 ±1）
     *    幅度可高达峰值的 80%，但它们属于同一个峰，不是干扰。若不排除，任何信号
     *    的「次高峰/主峰」都会恒等于 0.8 左右，判据失去区分力。
     * 2. **自身谐波**：PPG 波形天然含 2f、3f 谐波，它们是脉搏信号的一部分而非干扰。
     *
     * 做法：跳过主瓣范围后，只统计**真正的局部极大值**（左右邻居都比它低）。
     * 平缓噪声段的随机起伏不会形成局部极大值，因此不会被误判为干扰峰。
     *
     * @param fundamentalHz 本次采用的基频（可能已经过次谐波修正）
     * @return 干扰峰幅度；频段内没有有效干扰峰时返回 0.0
     */
    private fun interferencePeak(
        magnitude: DoubleArray,
        fundamentalHz: Double,
        peakIndex: Int,
        binHz: Double,
        lo: Int,
        hi: Int
    ): Double {
        val toleranceHz = fundamentalHz * HARMONIC_TOLERANCE_RATIO
        var best = 0.0
        // 从 lo+1 到 hi-1：需要左右邻居才能判断局部极大值
        for (k in (lo + 1) until hi) {
            // 跳过主瓣裙边
            if (kotlin.math.abs(k - peakIndex) <= MAIN_LOBE_HALF_WIDTH_BINS) continue

            val hz = k * binHz
            val nearestMultiple = kotlin.math.round(hz / fundamentalHz)
            // 跳过 2f、3f 等自身谐波（1f 即基频，已由主瓣排除覆盖）
            if (nearestMultiple >= 2.0 &&
                kotlin.math.abs(hz - nearestMultiple * fundamentalHz) < toleranceHz
            ) {
                continue
            }

            // 只接受真正的局部极大值，避免把噪声起伏当成干扰峰
            if (magnitude[k] > magnitude[k - 1] && magnitude[k] >= magnitude[k + 1]) {
                if (magnitude[k] > best) best = magnitude[k]
            }
        }
        return best
    }

    /**
     * 以 [center] 为中心、±1 个频点内（不越界）的最大幅度及其下标。
     *
     * 合并返回 Pair 而不是拆成两个函数：两个实现完全相同，调用方总是同时需要
     * 幅度与下标，拆开会导致重复遍历。
     */
    private fun localMaxWithIndex(
        magnitude: DoubleArray,
        center: Int,
        lo: Int,
        hi: Int,
        radius: Int = 1
    ): Pair<Double, Int> {
        var max = -1.0
        var idx = center
        val start = maxOf(lo, center - radius)
        val end = minOf(hi, center + radius)
        for (k in start..end) {
            if (magnitude[k] > max) {
                max = magnitude[k]
                idx = k
            }
        }
        return max to idx
    }

    /**
     * 抛物线插值细化峰值频率。
     *
     * FFT 的频率栅格在 30s 采集下约 0.037 Hz，直接取 bin 会带来最多 ±1 BPM 的量化误差；
     * 用峰值点及其左右两点的抛物线顶点做插值即可显著改善。
     */
    private fun parabolicRefineHz(magnitude: DoubleArray, index: Int, binHz: Double): Double {
        if (index <= 0 || index >= magnitude.size - 1) return index * binHz
        val y0 = magnitude[index - 1]
        val y1 = magnitude[index]
        val y2 = magnitude[index + 1]
        val denom = y0 - 2.0 * y1 + y2
        if (abs(denom) < 1e-12) return index * binHz
        val delta = 0.5 * (y0 - y2) / denom
        // delta 理论范围 ±0.5，夹紧以防数值异常
        val clamped = delta.coerceIn(-0.5, 0.5)
        return (index + clamped) * binHz
    }
}