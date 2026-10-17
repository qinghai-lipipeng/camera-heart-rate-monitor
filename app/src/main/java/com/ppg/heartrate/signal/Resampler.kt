package com.ppg.heartrate.signal

import kotlin.math.max
import kotlin.math.min

/**
 * 把「时间戳 + 亮度」序列重采样到均匀时间网格。
 *
 * 对应交接文档 5.4：相机帧间隔并不均匀（掉帧、抖动很常见），禁止用帧序号当时间轴，
 * 也不允许直接把非均匀序列送进 FFT（会产生虚假峰）。
 */
object Resampler {

    /** 重采样结果。[sampleRate] 即估算出的实际采样率 fs。 */
    class Result(val signal: DoubleArray, val sampleRate: Double)

    /** 采集 30 秒但有效帧少于 300 帧（即实际帧率低于 10fps）时判定为采集失败。 */
    const val MIN_FRAME_COUNT = 300

    /**
     * 估算实际采样率：fs = (n - 1) / (t_last - t_first)。
     *
     * @return 采样率（Hz）；样本不足或时间跨度非法时返回 -1.0
     */
    fun estimateSampleRate(timestamps: DoubleArray): Double {
        val n = timestamps.size
        if (n < 2) return -1.0
        val span = timestamps[n - 1] - timestamps[0]
        if (!(span > 0.0) || !span.isFinite()) return -1.0
        return (n - 1).toDouble() / span
    }

    /**
     * 线性插值重采样到 [fs] 给定的均匀网格。
     *
     * 输入时间戳单位为秒且**严格递增**（相机帧时间戳天然递增，但需先过滤重复/倒序帧）。
     *
     * @param timestamps 帧时间戳（秒），严格递增
     * @param values 对应的信号值
     * @param fs 目标采样率，传 0 或负数时用 [estimateSampleRate] 自动估算
     * @return 均匀序列；样本不足或时间戳非法时返回 null
     */
    fun resampleUniform(timestamps: DoubleArray, values: DoubleArray, fs: Double = 0.0): Result? {
        val n = min(timestamps.size, values.size)
        if (n < 2) return null

        val rate = if (fs > 0.0) fs else estimateSampleRate(timestamps)
        if (rate <= 0.0 || !rate.isFinite()) return null

        val dt = 1.0 / rate
        val duration = timestamps[n - 1] - timestamps[0]
        val outLength = (duration / dt).toInt() + 1
        if (outLength < 2) return null

        val out = DoubleArray(outLength)
        // 游标式线性插值：t 单调递增，指针只前进不回头，整体 O(n + outLength)
        var cursor = 0
        for (i in 0 until outLength) {
            val t = timestamps[0] + i * dt
            // 将游标推进到「t 落在 timestamps[cursor] 与 timestamps[cursor+1] 之间」
            while (cursor < n - 2 && timestamps[cursor + 1] < t) cursor++
            val t0 = timestamps[cursor]
            val t1 = timestamps[cursor + 1]
            val span = t1 - t0
            val w = if (span > 0.0) ((t - t0) / span).coerceIn(0.0, 1.0) else 0.0
            out[i] = values[cursor] * (1.0 - w) + values[cursor + 1] * w
        }
        return Result(out, rate)
    }

    /**
     * 清掉时间戳异常（重复、倒序、非有限）的帧。
     *
     * 掉帧时时间戳仍前进，但个别机型可能出现相同时间戳甚至倒序；这类帧若不剔除，
     * 会让插值权重变为负数并污染整段频谱。
     *
     * @return 过滤后的时间戳与取值，两数组长度一致且严格递增；无有效帧时返回 null
     */
    fun sanitize(
        timestamps: DoubleArray,
        values: DoubleArray
    ): Pair<DoubleArray, DoubleArray>? {
        val n = min(timestamps.size, values.size)
        val keptT = ArrayList<Double>(n)
        val keptV = ArrayList<Double>(n)
        var last = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val t = timestamps[i]
            val v = values[i]
            if (!t.isFinite() || !v.isFinite()) continue
            if (t <= last) continue
            last = t
            keptT.add(t)
            keptV.add(v)
        }
        if (keptT.size < 2) return null
        return keptT.toDoubleArray() to keptV.toDoubleArray()
    }

    /**
     * 统计「亮度方差突变次数」，作为质量评估的输入（交接文档 5.6 第三行判据）。
     *
     * 做法：按 ~1 秒窗口算滚动标准差，若相邻窗口的标准差之比超过阈值即记一次突变。
     * 手指移动或手指与镜头间漏光会造成亮度台阶，正是这里要抓的现象。
     */
    fun countVarianceJumps(values: DoubleArray, fs: Double, thresholdRatio: Double = 3.0): Int {
        val n = values.size
        if (n < 4 || fs <= 0.0) return 0
        val window = max(2, fs.toInt())           // 约 1 秒
        val hop = max(1, window / 2)
        val stds = ArrayList<Double>(n / hop)
        for (start in 0 until (n - window); start += hop) {
            var sum = 0.0
            for (i in start until start + window) sum += values[i]
            val mean = sum / window
            var acc = 0.0
            for (i in start until start + window) {
                val d = values[i] - mean
                acc += d * d
            }
            stds.add(kotlin.math.sqrt(acc / window))
        }
        if (stds.size < 3) return 0

        // 稳健基线：取所有窗口标准差中较小一半的中位数。
        // 只看下半段可天然排除「手指剧烈抖动」造成的大值，避免阈值被自己抬高。
        val sorted = stds.sorted()
        val lowerHalf = sorted.subList(0, max(1, sorted.size / 2))
        val baseline = lowerHalf[lowerHalf.size / 2]
        if (baseline <= 1e-9) return 0

        var jumps = 0
        var inJump = false
        for (s in stds) {
            if (s > baseline * thresholdRatio) {
                // 连续多个窗口超阈值只算一次突变
                if (!inJump) jumps++
                inJump = true
            } else {
                inJump = false
            }
        }
        return jumps
    }
}