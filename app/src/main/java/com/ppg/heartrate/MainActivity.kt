package com.ppg.heartrate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ppg.heartrate.camera.CameraController
import com.ppg.heartrate.camera.LumaAnalyzer
import com.ppg.heartrate.ui.MeasureScreen
import com.ppg.heartrate.ui.MeasureUiState
import com.ppg.heartrate.ui.MeasureViewModel
import com.ppg.heartrate.ui.theme.PpgHeartRateTheme

/**
 * 应用入口：相机权限申请、相机绑定、Compose 挂载。
 *
 * 生命周期约定（对应交接文档 M3 / 硬性约束 13、14）：
 * - 采集期间 `FLAG_KEEP_SCREEN_ON`，防止息屏中断采集
 * - 退到后台（ON_STOP）立即取消采集并释放相机，避免后台持续占用闪光灯
 */
class MainActivity : ComponentActivity() {

    private lateinit var cameraController: CameraController

    /** 相机权限是否已授予（需求 1）。 */
    private var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraController = CameraController(applicationContext)
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            PpgHeartRateTheme {
                if (hasCameraPermission) {
                    // ViewModel 由 Compose 的 viewModel() 创建并正确跨越配置变更
                    MeasureFlow(controller = cameraController)
                } else {
                    PermissionRationale(
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController.release()
    }
}

/**
 * 已授权权限后的主流程。
 *
 * PreviewView 以 1×1 像素的固定尺寸挂在 Compose 树里：Preview 用例必须绑定
 * （否则部分机型闪光灯不亮，见硬性约束 8），但界面无需显示相机画面。
 */
@Composable
private fun MeasureFlow(controller: CameraController) {
    val viewModel: MeasureViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraError by remember { mutableStateOf<String?>(null) }

    // 分析器只负责逐帧取亮度并回调，不持有任何 UI 引用
    val analyzer = remember(viewModel) {
        LumaAnalyzer { timestampNanos, meanLuma ->
            viewModel.onFrame(timestampNanos, meanLuma)
        }
    }

    // 隐藏的 1×1 PreviewView。只提供 SurfaceProvider，不参与布局、也不显示画面。
    var previewReady by remember { mutableStateOf<PreviewBinding?>(null) }
    AndroidView(
        factory = { context ->
            androidx.camera.view.PreviewView(context).apply {
                implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
                layoutParams = ViewGroup.LayoutParams(1, 1)
            }
        },
        // width/height = 0 时 Compose 仍会为 AndroidView 分配一个最小测量空间，
        // 这里用固定 1dp 足够承载 Surface 且视觉上不可见
        modifier = Modifier.size(1.dp),
        update = { view ->
            // AndroidView.update 在每次重组后调用，用引用比较避免重复触发绑定
            if (previewReady?.view !== view) {
                previewReady = PreviewBinding(view, view.surfaceProvider)
            }
        }
    )

    // 相机绑定。key 变化（PreviewView 就绪 / 离开界面）时重新绑定或释放。
    DisposableEffect(previewReady, lifecycleOwner) {
        val binding = previewReady
        if (binding == null) {
            onDispose { }
        } else {
            controller.bind(
                lifecycleOwner = lifecycleOwner,
                surfaceProvider = binding.surfaceProvider,
                analyzer = analyzer,
                onError = { cameraError = it },
                onReady = { cameraError = null }
            )
            onDispose {
                // 离开组合时释放，避免相机与闪光灯在后台常驻
                controller.release()
            }
        }
    }

    // 硬性约束 14：应用退到后台时立即停止采集并释放相机。
    // 绑定用的 lifecycleOwner 不会因为 ON_STOP 而触发 DisposableEffect 的 onDispose，
    // 所以必须显式观察生命周期事件。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.cancelMeasure()
                controller.disableTorch()
                controller.release()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 硬性约束 13：采集与计算期间禁止息屏
    val busyPhase = state.phase == MeasureUiState.Phase.Settling ||
        state.phase == MeasureUiState.Phase.Measuring ||
        state.phase == MeasureUiState.Phase.Computing
    KeepScreenOn(busyPhase)

    MeasureScreen(
        state = state,
        errorBanner = cameraError,
        onStart = { viewModel.startMeasure(controller) },
        onRestart = { viewModel.resetToIdle() },
        onCancel = { viewModel.cancelMeasure() }
    )
}

/** 绑定所需的最小信息，避免把整个 PreviewView 存进状态。 */
private class PreviewBinding(
    val view: androidx.camera.view.PreviewView,
    val surfaceProvider: androidx.camera.core.SurfaceProvider
)

/**
 * 控制 `FLAG_KEEP_SCREEN_ON`。
 *
 * 在 Compose 里直接操作 Window 是绕过架构的做法；这里用 DisposableEffect 跟随
 * [enabled] 的变化加减 flag，并在 effect 销毁时清理，避免息屏残留。
 */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalContext.current as? MainActivity ?: return
    DisposableEffect(activity, enabled) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * 未授权相机权限时的说明与申请按钮（需求 1）。
 */
@Composable
private fun PermissionRationale(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "需要相机权限",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "本应用通过后置摄像头与闪光灯测量心率，全部计算在本机完成，不联网、不存储数据。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onRequestPermission) {
            Text("授予相机权限")
        }
    }
}