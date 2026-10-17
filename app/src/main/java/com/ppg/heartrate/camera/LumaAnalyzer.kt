package com.ppg.heartrate.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * 逐帧提取画面中心区域平均亮度的 [ImageAnalysis.Analyzer]。
 *
 * 三个关键点（对应交接文档 5.3 与硬性约束 7）：
 * 1. 只读 YUV 的 **Y 平面**，Y 本身就是亮度，不做任何颜色空间转换
 * 2. 必须按 [ImageProxy.PlaneProxy.rowStride] 与 pixelStride 跨步读取，
 *    把整个 buffer 当连续数组读会得到完全错乱的亮度值
 * 3. 每个分支都必须 `close()`，否则背压会让相机只送第一帧，界面表现为卡死
 */
class LumaAnalyzer(
    /** 每帧取样后的回调，运行在 CameraX 的分析线程。 */
    private val onSample: (timestampNanos: Long, meanLuma: Double) -> Unit
) : ImageAnalysis.Analyzer {

    /** 最近一次的亮度，供界面显示当前采集亮度（开发期确认手指是否盖住镜头）。 */
    @Volatile
    var lastLuma: Double = 0.0
        private set

    override fun analyze(image: ImageProxy) {
        // try/finally 保证任何异常路径下 ImageProxy 都会被关闭
        try {
            val mean = computeMeanLuma(image)
            if (mean >= 0.0) {
                lastLuma = mean
                // imageInfo.timestamp 单位为纳秒，是这一帧的真实曝光时刻。
                // 禁止用帧序号当时间轴，见交接文档 5.4 与硬性约束 3。
                onSample(image.imageInfo.timestamp, mean)
            }
        } catch (_: Exception) {
            // 单帧异常（设备特有问题）不应中断整个采集，静默丢弃该帧即可
        } finally {
            image.close()
        }
    }

    /**
     * 计算 Y 平面中心区域的平均亮度。
     *
     * @return 0.0–255.0 的均值；无法解析时返回 -1.0
     */
    private fun computeMeanLuma(image: ImageProxy): Double {
        if (image.planes.isEmpty()) return -1.0
        val plane = image.planes[0]
        val buffer = plane.buffer

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return -1.0

        // 只采样画面中心区域，避开边缘噪声与镜头暗角
        val yStart = height / 3
        val yEnd = height * 2 / 3
        val xStart = width / 3
        val xEnd = width * 2 / 3
        if (yEnd <= yStart || xEnd <= xStart) return -1.0

        var sum = 0L
        var count = 0

        for (y in yStart until yEnd) {
            val rowBase = y * rowStride
            for (x in xStart until xEnd) {
                val index = rowBase + x * pixelStride
                // buffer.limit() 是 capacity 的上界，跨步读取时最后一行可能越界
                if (index >= 0 && index < buffer.limit()) {
                    sum += (buffer.get(index).toInt() and 0xFF)
                    count++
                }
            }
        }
        return if (count > 0) sum.toDouble() / count else -1.0
    }

    companion object {
        /**
         * 判断当前画面是否大概率被手指完全遮住。
         *
         * 手指盖住镜头后画面整体变暗且方差很小（不再有真实场景的纹理与光照梯度）。
         * 界面用它来提示用户「按好了，可以开始」。
         */
        fun isCovered(meanLuma: Double, stabilityStdDev: Double): Boolean {
            // 阈值 60：手指甲/指腹在闪光灯直射下仍会有些亮度，但明显低于正常场景
            val darkEnough = meanLuma < 60.0
            // 方差小说明画面稳定，没有手指边缘漏光带来的高频变化
            val stable = stabilityStdDev < 12.0
            return darkEnough && stable
        }

        /**
         * 计算最近若干帧亮度的标准差，作为「是否按稳」的判据。
         *
         * @param samples 按时间先后排列的亮度样本
         */
        fun lumaStdDeviation(samples: List<Double>): Double {
            if (samples.size < 2) return 0.0
            var sum = 0.0
            for (s in samples) sum += s
            val mean = sum / samples.size
            var acc = 0.0
            for (s in samples) {
                val d = s - mean
                acc += d * d
            }
            return Math.sqrt(acc / samples.size)
        }

        /** 滑动窗口内亮度的极差，用于快速识别突发漏光。 */
        fun lumaRange(samples: List<Double>): Double {
            if (samples.isEmpty()) return 0.0
            var lo = samples[0]
            var hi = samples[0]
            for (s in samples) {
                if (s < lo) lo = s
                if (s > hi) hi = s
            }
            return hi - lo
        }
    }
}