package com.ppg.heartrate.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceProvider
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 通路控制：绑定 Preview + ImageAnalysis，开闪光灯，锁定曝光与白平衡。
 *
 * 对应交接文档 M0 与 5.1 / 5.2。
 */
class CameraController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var boundCamera: androidx.camera.core.Camera? = null

    /** 相机是否已具备闪光灯能力。无闪光灯机型无法测量，需界面降级提示。 */
    var hasFlashUnit: Boolean = false
        private set

    /**
     * 绑定 Preview + ImageAnalysis 两个用例。
     *
     * 硬性约束 8：必须**同时绑定 Preview**，否则部分机型 `enableTorch` 不生效，
     * 即使界面不显示预览画面也必须绑定。
     *
     * @param surfaceProvider PreviewView 的 SurfaceProvider；不绑定 Preview 用例时部分机型闪光灯不亮
     * @param analyzer 逐帧亮度分析器
     * @param onError 绑定失败或无后置摄像头时回调
     * @param onReady 绑定成功后回调，此时闪光灯已开启
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: SurfaceProvider,
        analyzer: LumaAnalyzer,
        onError: (String) -> Unit,
        onReady: () -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                // ---- Preview 用例：仅为保证闪光灯可用，界面不显示其画面 ----
                val preview = Preview.Builder()
                    .setTargetResolution(Size(640, 480))
                    .build()
                preview.setSurfaceProvider(surfaceProvider)

                // ---- ImageAnalysis 用例：真正的数据来源 ----
                // 配置严格按交接文档 5.1：低分辨率换高帧率、KEEP_ONLY_LATEST 丢弃积压帧
                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(Size(640, 480))
                    .setTargetFrameRate(Range(30, 30))

                // ---- 5.2 锁曝光与白平衡：最容易翻车的一步 ----
                // AE / AWB 会不断调整画面亮度把脉搏波动压平。首选手动模式：
                //   固定曝光时间 20ms、ISO 400，并关闭 AE / AWB 自动调节。
                // 注意：这里只是把参数写进 capture request，**不是**启动即锁定。
                // 真正的锁定时机见 lockExposure()，必须在闪光灯点亮、3A 收敛之后。
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                        FIXED_EXPOSURE_TIME_NANOS
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.SENSOR_SENSITIVITY,
                        FIXED_ISO
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE,
                        CaptureRequest.CONTROL_AWB_MODE_OFF
                    )

                val analysis = analysisBuilder.build()

                val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also {
                    analysisExecutor = it
                }
                analysis.setAnalyzer(executor, analyzer)

                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    onError("未检测到后置摄像头，无法进行测量")
                    return@addListener
                }

                // 解绑前先停掉旧的 ImageAnalysis，避免旧分析器继续吃帧
                provider.unbindAll()
                val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                boundCamera = camera

                hasFlashUnit = camera.cameraInfo.hasFlashUnit()
                if (hasFlashUnit) {
                    // 闪光灯常亮，直到采集结束（30 秒）自动关闭，避免手机发热（硬性约束 12）
                    camera.cameraControl.enableTorch(true)
                }
                onReady()
            } catch (e: Exception) {
                onError("相机初始化失败：${e.message ?: e::class.java.simpleName}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 在 3A 收敛之后调用，锁定 AE / AWB。
     *
     * 交接文档 5.2：手动模式下若某些机型画面全黑或全白（说明该机型不支持
     * AE_MODE_OFF + 固定曝光参数），此时回退到备选方案——AE/AWB LOCK，
     * 锁定当前自动解算出的结果，同样能阻止 3A 继续压平脉搏波动。
     *
     * @param darkOrBlown true 表示画面明显过暗或过曝，触发 AE_LOCK 回退方案
     */
    fun lockExposure(darkOrBlown: Boolean) {
        val control = boundCamera?.cameraControl ?: return
        if (darkOrBlown) {
            // 回退路径：AE/AWB 保持 AUTO 并立即加锁，冻结当前自动解算值。
            // 注意不能只设 LOCK 而不设 MODE——若 MODE 仍是 OFF 且固定参数在该机型
            // 上导致全黑/全白，画面依旧无法恢复。
            control.enableTorch(true)
            applyCaptureOptions(control) {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
        }
        // 锁定：此后 3A 不再改变画面亮度，脉搏波动得以保留
        applyCaptureOptions(control) {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
    }

    /**
     * 写入 capture request 选项。
     *
     * 关键点：CameraX 的 `captureRequestOptions` 是**整体替换**而非合并，
     * 所以必须以「当前已有选项」为种子构造 Builder，否则后一次调用会把前一次
     * 写入的选项覆盖掉。
     */
    private fun applyCaptureOptions(
        control: androidx.camera.core.CameraControl,
        configure: CaptureRequestOptions.Builder.() -> Unit
    ) {
        val camera2Control = Camera2CameraControl.from(control)
        val builder = CaptureRequestOptions.Builder(camera2Control.captureRequestOptions)
        builder.configure()
        camera2Control.captureRequestOptions = builder.build()
    }

    /** 关闭闪光灯。采集结束或退出界面时必须调用。 */
    fun disableTorch() {
        boundCamera?.cameraControl?.enableTorch(false)
    }

    /** 释放相机与线程。退到后台（ON_STOP）或 Activity 销毁时调用（硬性约束 14）。 */
    fun release() {
        disableTorch()
        cameraProvider?.unbindAll()
        boundCamera = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        cameraProvider = null
    }

    companion object {
        /** 固定曝光时间 20ms，单位纳秒。20ms 曝光配合 ISO 400，在闪光灯直射下的信噪比足够。 */
        const val FIXED_EXPOSURE_TIME_NANOS = 20_000_000L

        /** 固定 ISO。400 在闪光灯 + 指腹遮挡下属于低噪声区间。 */
        const val FIXED_ISO = 400

        /** 3A 收敛等待时间：开闪光灯后等约 1 秒再锁（硬性约束 9）。 */
        const val AE_SETTLE_DELAY_MS = 1000L

        /**
         * 判断画面是否过暗或过曝，用于决定是否回退到 AE/AWB LOCK。
         * 全黑说明 20ms + ISO 400 在该机型上曝光不足；全白则说明过曝。
         */
        fun isDarkOrBlown(meanLuma: Double): Boolean = meanLuma < 5.0 || meanLuma > 250.0
    }
}