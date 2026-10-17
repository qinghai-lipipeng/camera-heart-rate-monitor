package com.ppg.heartrate.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 时域预处理与频域截断。对应交接文档 5.5 的顺序：去趋势 → Hann 窗 → 零均值化 → 补零 → 频段保留。
 */
object Filter {

    /** 心率关注频段：0.7–4.0 Hz，对应 42–240 BPM。 */
    const val BAND_LOW_HZ = 0.7
    const val BAND_HIGH_HZ = 4.0

    /**
     * 去趋势：减去约 1 秒（fs 个点）的滑动平均，消除缓慢的基线漂移。
     *
     * 基线漂移主要来自手指按压力度的缓慢变化，若不消除，频谱能量会被 0.1–0.3 Hz
     * 的低频占据，主峰被淹没。
     *
     * 用滑动平均的**中心点**作为基线，端点用单边窗口补齐，避免首尾出现台阶。
     */
    fun detrend(signal: DoubleArray, fs: Double): DoubleArray {
        val n = signal.size
        if (n < 3 || fs <= 0.0) return signal.copyOf()

        val half = max(1, (fs.toInt() + 1) / 2)
        val out = DoubleArray(n)

        // 前缀和让滑动窗口求和降到 O(1)，整体 O(n)
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + signal[i]

        for (i in 0 until n) {
            val lo = max(0, i - half)
            val hi = min(n - 1, i + half)
            val mean = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
            out[i] = signal[i] - mean
        }
        return out
    }

    /** 乘 Hann 窗，抑制频谱泄漏。返回新数组。 */
    fun hannWindow(signal: DoubleArray): DoubleArray {
        val n = signal.size
        if (n < 2) return signal.copyOf()
        val out = DoubleArray(n)
        for (i in 0 until n) {
            // 周期型 Hann 窗，w[i] = 0.5·(1 - cos(2πi/(n-1)))
            val w = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            out[i] = signal[i] * w
        }
        return out
    }

    /** 零均值化：减去均值，去掉直流分量。返回新数组。 */
    fun removeMean(signal: DoubleArray): DoubleArray {
        val n = signal.size
        if (n == 0) return DoubleArray(0)
        var sum = 0.0
        for (v in signal) sum += v
        val mean = sum / n
        val out = DoubleArray(n)
        for (i in 0 until n) out[i] = signal[i] - mean
        return out
    }

    /**
     * 频段保留：把 [BAND_LOW_HZ, BAND_HIGH_HZ] 之外的幅度谱全部置零。
     *
     * 交接文档 5.5 第 6 步：用频域截断代替另写 IIR 带通滤波器，更简单可靠。
     * 注意置零发生在幅度谱上，仅用于后续的峰值查找与能量统计；我们不需要还原时域波形。
     */
    fun retainBand(magnitude: DoubleArray, fs: Double, fftSize: Int): DoubleArray {
        val out = magnitude.copyOf()
        if (fftSize <= 0 || fs <= 0.0) return out
        val binHz = fs / fftSize
        for (k in out.indices) {
            val hz = k * binHz
            if (hz < BAND_LOW_HZ || hz > BAND_HIGH_HZ) out[k] = 0.0
        }
        return out
    }
}