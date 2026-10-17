package com.ppg.heartrate.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 自实现的 radix-2（Cooley–Tukey）FFT。
 *
 * 交接文档 3. 要求 FFT 实现二选一：JTransforms 或自行实现。本项目选自行实现，
 * 好处是零第三方依赖，`signal/` 包保持纯 Kotlin，可脱离设备独立验证。
 *
 * 注意：不做实数 FFT 优化，输入必须是 2 的整数次幂长度。
 */
object FftProcessor {

    /** 返回不小于 [n] 的最小的 2 的整数次幂。 */
    fun nextPowerOfTwo(n: Int): Int {
        require(n > 0) { "FFT 长度必须为正" }
        var size = 1
        while (size < n) size = size shl 1
        return size
    }

    /**
     * 原地正变换。输入 [real]、[imag] 长度相同且为 2 的整数次幂，调用后被改写为频域结果。
     *
     * @return 实际使用的点数（即 real.size）
     */
    fun forward(real: DoubleArray, imag: DoubleArray): Int {
        val n = real.size
        require(n == imag.size) { "实部与虚部长度必须一致" }
        require(n > 0 && (n and (n - 1)) == 0) { "FFT 长度必须是 2 的整数次幂，实际=$n" }
        if (n == 1) return 1

        // 1) 位反转置换：把位序颠倒的数据调整为迭代蝶形所需的顺序
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        // 2) 分级蝶形运算。len 为当前段的点数，len = 2, 4, 8, ... , n
        var len = 2
        while (len <= n) {
            val half = len / 2
            // 旋转因子 w_len^k = exp(-2πi·k/len)，每级直接算三角函数避免递推累积误差
            val step = -2.0 * PI / len
            val twiddleRe = DoubleArray(half) { cos(step * it) }
            val twiddleIm = DoubleArray(half) { sin(step * it) }

            var blockStart = 0
            while (blockStart < n) {
                for (k in 0 until half) {
                    val a = blockStart + k
                    val b = a + half
                    val tRe = real[b] * twiddleRe[k] - imag[b] * twiddleIm[k]
                    val tIm = real[b] * twiddleIm[k] + imag[b] * twiddleRe[k]
                    real[b] = real[a] - tRe
                    imag[b] = imag[a] - tIm
                    real[a] = real[a] + tRe
                    imag[a] = imag[a] + tIm
                }
                blockStart += len
            }
            len = len shl 1
        }
        return n
    }

    /**
     * 对实信号求幅度谱。内部复制一份输入，调用方的数组不会被修改。
     *
     * @param input 实信号，长度任意（内部补零到 2 的整数次幂）
     * @return 长度为 fftSize/2 + 1 的幅度谱，下标 k 对应频率 k·fs/fftSize
     */
    fun magnitudeSpectrum(input: DoubleArray): DoubleArray {
        val fftSize = nextPowerOfTwo(maxOf(input.size, 2))
        val real = DoubleArray(fftSize)
        val imag = DoubleArray(fftSize)
        // 只复制到 fftSize，避免在已完成零填充的输入上重复截断
        val copyLen = minOf(input.size, fftSize)
        System.arraycopy(input, 0, real, 0, copyLen)
        forward(real, imag)

        val half = fftSize / 2
        val magnitude = DoubleArray(half + 1)
        for (k in 0..half) {
            magnitude[k] = kotlin.math.sqrt(real[k] * real[k] + imag[k] * imag[k])
        }
        return magnitude
    }
}