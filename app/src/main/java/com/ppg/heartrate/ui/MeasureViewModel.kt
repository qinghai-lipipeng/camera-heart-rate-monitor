package com.ppg.heartrate.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppg.heartrate.camera.CameraController
import com.ppg.heartrate.model.MeasureOutcome
import com.ppg.heartrate.model.MeasureResult
import com.ppg.heartrate.signal.HeartRateEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 界面状态快照。
 *
 * @param phase 当前所处阶段
 * @param countdownMs 剩余采集时间（毫秒），非采集阶段为 0
 * @param waveform 最新一帧归一化后的亮度，供滚动波形绘制（已降采样到 ≤ [WAVEFORM_MAX_POINTS] 点）
 * @param luma 当前平均亮度，供开发期确认手指是否盖住
 * @param result 测量结果
 * @param errorMessage 非空时界面显示该提示（相机错误、采集失败等）
 */
data class MeasureUiState(
    val phase: Phase = Phase.Idle,
    val countdownMs: Long = 0L,
    val waveform: FloatArray = FloatArray(0),
    val luma: Double = 0.0,
    val result: MeasureResult? = null,
    val errorMessage: String? = null
) {
    /** 测量流程的阶段。 */
    enum class Phase {
        /** 空闲，等待用户开始。 */
        Idle,

        /** 预热：闪光灯已开，等待 3A 收敛后才锁参数并采样（硬性约束 9）。 */
        Settling,

        /** 采集中，30 秒倒计时。 */
        Measuring,

        /** 计算中：30 秒数据送入 FFT，耗时在百毫秒级，满足「1 秒内给出读数」。 */
        Computing,

        /** 已出结果。 */
        Done,

        /** 需要用户重新测量。 */
        Failed
    }

    /** 倒计时显示用的剩余秒数，向上取整保证 30 秒时不显示 29。 */
    val countdownSeconds: Int get() = ((countdownMs + 999) / 1000).toInt()

    /** data class 的数组字段需要显式声明相等性，否则每次重组都被判定为「变了」。 */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeasureUiState) return false
        return phase == other.phase &&
            countdownMs == other.countdownMs &&
            waveform.contentEquals(other.waveform) &&
            luma == other.luma &&
            result == other.result &&
            errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var code = phase.hashCode()
        code = 31 * code + countdownMs.hashCode()
        code = 31 * code + waveform.contentHashCode()
        code = 31 * code + luma.hashCode()
        code = 31 * code + (result?.hashCode() ?: 0)
        code = 31 * code + (errorMessage?.hashCode() ?: 0)
        return code
    }
}

/**
 * 测量流程的状态机与调度中心。
 *
 * 时序（对应交接文档 M0→M3）：
 * 1. 相机绑定、闪光灯开启（见 `CameraController.bind`）
 * 2. 等待 `AE_SETTLE_DELAY_MS` 让 3A 收敛
 * 3. 锁定 AE/AWB，开始 30 秒采样
 * 4. 停止采样，把缓冲送入 `HeartRateEstimator`，在后台线程计算
 * 5. 输出结果或失败原因，并关闭闪光灯
 */
class MeasureViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MeasureUiState())
    val uiState: StateFlow<MeasureUiState> = _uiState.asStateFlow()

    /**
     * 采集缓冲。容量按 30s × 30fps = 900 帧留一倍余量，运行期不再扩容，
     * 避免扩容与写入并发导致的数据竞争。
     */
    private val timestamps = DoubleArray(BUFFER_CAPACITY)
    private val lumaValues = DoubleArray(BUFFER_CAPACITY)
    private var sampleCount = 0

    /** 波形滚动缓存：定长环形缓冲，写满后覆盖最旧值，避免每次重组都做数组拷贝。 */
    private val waveformRing = FloatArray(WAVEFORM_MAX_POINTS)
    private var waveformWriteIndex = 0
    private var waveformFilled = 0

    /** 波形归一化用的滑动均值/极差，让脉搏起伏在图上可见。 */
    private var waveformCenter = 0.0

    private var measureJob: Job? = null

    /** 采集期间为 true，决定 [onFrame] 是否真的记录样本。 */
    @Volatile
    var isCollecting: Boolean = false
        private set

    /**
     * 相机分析线程的逐帧回调。
     *
     * 由 CameraX 的单线程分析执行器调用，每帧一次（30fps）。这里只做 O(1) 的写入，
     * 不做任何排序或分配，保证分析线程不会被拖慢导致丢帧。
     *
     * @param timestampNanos 帧真实曝光时刻，纳秒
     * @param meanLuma 画面中心区域平均亮度，0–255
     */
    fun onFrame(timestampNanos: Long, meanLuma: Double) {
        // 未采集中也要更新 luma，让界面能实时显示「手指是否已盖住镜头」（需求 5）
        if (!isCollecting) {
            _uiState.update { it.copy(luma = meanLuma) }
            return
        }

        if (sampleCount < BUFFER_CAPACITY) {
            // 纳秒转秒，与 Resampler 的时间轴约定一致
            timestamps[sampleCount] = timestampNanos / 1_000_000_000.0
            lumaValues[sampleCount] = meanLuma
            sampleCount++
        }

        // 波形：以滑动中心值为基准做归一化，让微小的脉搏起伏在图上放大可见
        waveformCenter += (meanLuma - waveformCenter) * WAVEFORM_SMOOTHING
        val normalized = ((meanLuma - waveformCenter) * WAVEFORM_GAIN).toFloat()
        waveformRing[waveformWriteIndex] = normalized
        waveformWriteIndex = (waveformWriteIndex + 1) % WAVEFORM_MAX_POINTS
        if (waveformFilled < WAVEFORM_MAX_POINTS) waveformFilled++

        _uiState.update { it.copy(luma = meanLuma, waveform = snapshotWaveform()) }
    }

    /**
     * 开始一次测量：预热 → 采样 30 秒 → 计算。
     *
     * @param controller 相机控制器，用于 3A 收敛后锁曝光、结束后关闪光灯
     */
    fun startMeasure(controller: CameraController) {
        measureJob?.cancel()
        resetBuffers()

        _uiState.update {
            it.copy(
                phase = MeasureUiState.Phase.Settling,
                errorMessage = null,
                result = null
            )
        }

        measureJob = viewModelScope.launch {
            // ---- 步骤 1：等 3A 收敛（硬性约束 9：一启动就锁会全黑/全白）----
            delay(CameraController.AE_SETTLE_DELAY_MS)

            // ---- 步骤 2：锁定曝光与白平衡（5.2）----
            // 若此刻画面已全黑/全白，说明固定曝光参数在该机型不适用，回退到 AE/AWB LOCK
            controller.lockExposure(darkOrBlown = CameraController.isDarkOrBlown(_uiState.value.luma))

            // ---- 步骤 3：正式开始采样 30 秒 ----
            isCollecting = true
            val startMark = System.currentTimeMillis()
            _uiState.update {
                it.copy(phase = MeasureUiState.Phase.Measuring, countdownMs = MEASURE_DURATION_MS)
            }

            while (isCollecting) {
                delay(COUNTDOWN_TICK_MS)
                val remaining = MEASURE_DURATION_MS - (System.currentTimeMillis() - startMark)
                _uiState.update { it.copy(countdownMs = max(0L, remaining)) }
                if (remaining <= 0L) break
            }
            isCollecting = false

            // ---- 步骤 4：后台计算（不阻塞主线程，硬性约束 15）----
            _uiState.update { it.copy(phase = MeasureUiState.Phase.Computing, countdownMs = 0L) }
            val outcome = withContext(Dispatchers.Default) {
                // 先在后台线程内拷贝快照，避免与分析线程并发写入产生竞态
                HeartRateEstimator.estimate(
                    timestamps.copyOf(sampleCount),
                    lumaValues.copyOf(sampleCount)
                )
            }

            // ---- 步骤 5：输出结果并关闭闪光灯（硬性约束 12：避免手机发热）----
            controller.disableTorch()
            when (outcome) {
                is MeasureOutcome.Success -> _uiState.update {
                    it.copy(phase = MeasureUiState.Phase.Done, result = outcome.result)
                }
                is MeasureOutcome.Failure -> _uiState.update {
                    it.copy(phase = MeasureUiState.Phase.Failed, errorMessage = outcome.reason)
                }
            }
        }
    }

    /** 取消采集（用户中途退出，或应用退到后台）。 */
    fun cancelMeasure() {
        measureJob?.cancel()
        measureJob = null
        isCollecting = false
        resetBuffers()
        _uiState.update {
            it.copy(phase = MeasureUiState.Phase.Idle, countdownMs = 0L, errorMessage = null)
        }
    }

    /** 清空错误提示，回到空闲态（需求 12 的「重新测量」）。 */
    fun resetToIdle() {
        resetBuffers()
        _uiState.update {
            MeasureUiState(phase = MeasureUiState.Phase.Idle)
        }
    }

    private fun resetBuffers() {
        sampleCount = 0
        waveformWriteIndex = 0
        waveformFilled = 0
        waveformCenter = 0.0
    }

    /** 把环形缓冲展开为「最旧 → 最新」顺序的快照，供 Compose 绘制。 */
    private fun snapshotWaveform(): FloatArray {
        if (waveformFilled == 0) return FloatArray(0)
        val out = FloatArray(waveformFilled)
        val start = (waveformWriteIndex - waveformFilled + WAVEFORM_MAX_POINTS) % WAVEFORM_MAX_POINTS
        for (i in 0 until waveformFilled) {
            out[i] = waveformRing[(start + i) % WAVEFORM_MAX_POINTS]
        }
        return out
    }

    override fun onCleared() {
        super.onCleared()
        measureJob?.cancel()
        isCollecting = false
    }

    companion object {
        /** 采集时长固定 30 秒（需求 6）。 */
        const val MEASURE_DURATION_MS = 30_000L

        /** 倒计时刷新间隔。100ms 足够顺滑，也不会过于频繁地重组。 */
        const val COUNTDOWN_TICK_MS = 100L

        /** 波形显示的最大点数。30fps × 10 秒 = 300 点，足够看清周期又不拖慢绘制。 */
        const val WAVEFORM_MAX_POINTS = 300

        /** 缓冲区容量：30s × 30fps = 900，留一倍余量。 */
        private const val BUFFER_CAPACITY = 1800

        /** 波形基线的平滑系数，越小越稳、越大越跟手。 */
        private const val WAVEFORM_SMOOTHING = 0.05

        /** 波形放大倍数。亮度波动通常只有个位数，放大后才看得出搏动。 */
        private const val WAVEFORM_GAIN = 4.0
    }
}