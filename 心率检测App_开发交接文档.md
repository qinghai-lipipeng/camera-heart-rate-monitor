# 开发交接文档：手机摄像头心率检测 Android App（单机版）

> 本文档面向代码实现方（AI 或开发者）。请按本文档实现，遇到本文档未覆盖的细节，选择最简单、最少依赖的方案，不要自行扩展需求。

---

## 0. 交接目的与交付边界

**项目性质**：个人自用的技术玩具，非商业产品，非医疗设备。

**交付内容**：

- 一套完整、可直接用 Android Studio 打开的 Android 工程源码（Kotlin）
- 语言：Kotlin（100%）
- UI：Jetpack Compose

**交付边界（重要）**：

- ✅ 要求：工程结构完整、Gradle 依赖声明齐全、代码自洽、无 `TODO` 占位、无伪代码
- ✅ 要求：所有关键实现给出必要注释，尤其是相机参数设置与信号处理部分
- ❌ **不要求**：编译、构建、运行、截图、测试报告
- ❌ **不要求**：上架、签名、混淆、多语言、埋点

**不要做的事**：不要引入后端、不要引入云服务、不要引入账号系统、不要引入广告或统计 SDK。

---

## 1. 项目目标

用手机后置摄像头 + 闪光灯，测量用户的心率。

**工作原理（必须按此实现）**：

手指指腹覆盖后置摄像头镜头，强制开启闪光灯常亮。血液随心脏搏动周期性充盈指尖毛细血管，导致透过/反射的光强发生周期性变化。摄像头逐帧采样，提取画面亮度时间序列，对该序列做频域分析，找出 0.7–4.0 Hz 区间的主频，乘以 60 即为 BPM。

该原理为光电容积脉搏波描记法（PPG）。

---

## 2. 非目标（明确不做）

| 不做 | 原因 |
|---|---|
| 心电波形、心律失常判断 | 原理上不可能，PPG 只能给频率 |
| 医疗级精度承诺 | 只是玩具 |
| 前置摄像头方案 | 只需后置 |
| 多人/多设备/联网对比 | 单机自用 |
| 数据长期存储 | 测完即弃 |
| 血氧、血压 | 不在范围内 |

---

## 3. 技术栈与版本约束

| 项 | 选择 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 相机 | **CameraX**（`camera-camera2` / `camera-lifecycle` / `camera-view`） |
| 最低 SDK | `minSdk 24` |
| 目标 SDK | `targetSdk` 取实现时的最新稳定版 |
| Gradle / AGP | 以实现时的最新稳定版为准，由实现方自行锁定 |
| FFT | `com.github.wendykierp:JTransforms`（Maven 一行）或自行实现 radix-2 FFT，二选一，不要两者都写 |
| 权限 | 仅 `android.permission.CAMERA`，另需 `<uses-feature android:name="android.hardware.camera.flash" />`（声明为 `required=false`） |

**不需要的权限**：存储、网络、位置、传感器（除非选择用加速度计做运动检测，见 5.6）。

---

## 4. 功能需求（按实现顺序）

### M0 · 相机通路（先跑通这个，其他都别做）

1. 启动即请求相机权限，未授权时显示说明与申请按钮
2. 绑定 **Preview + ImageAnalysis 两个 UseCase**（注意：`enableTorch` 在某些设备上要求 Preview 用例同时存在，所以 Preview 必须绑定，即使界面不显示预览画面）
3. 开启闪光灯常亮：`camera.cameraControl.enableTorch(true)`
4. 屏幕中央给出引导：提示用户用食指指腹**完全覆盖**镜头与闪光灯，力度适中
5. 实时显示当前帧的平均亮度，方便开发期确认手指是否盖住（正式界面可隐藏或做成低调的指示灯）

### M1 · 信号处理

6. 采集时长固定 **30 秒**，采集期间显示倒计时
7. 采集完成后 1 秒内给出 BPM 读数
8. 实时绘制采集过程中的亮度波形（滚动显示）

### M2 · 结果展示

9. 大号数字显示 BPM
10. 显示信号质量等级：**良好 / 一般 / 差**（判定规则见 5.6）
11. 质量差时给出具体原因提示，例如"手指移动过多""光线干扰""请按紧一些"
12. 提供"重新测量"按钮

### M3 · 稳定性

13. 采集期间禁止息屏（`FLAG_KEEP_SCREEN_ON`）
14. 应用退到后台时立即停止采集并释放相机
15. 全程不得阻塞主线程，不得出现 ANR
16. **每一帧 `ImageProxy` 处理完必须调用 `close()`**，否则背压会导致后续所有帧被丢弃、界面卡死

---

## 5. 核心算法规格（本项目的重点）

> 本节是最容易实现错误的部分。请严格按下述顺序与参数实现。

### 5.1 采集配置

```
ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .setTargetResolution(Size(640, 480))   // 低分辨率换高帧率，不要用全分辨率
    .setTargetFrameRate(Range(30, 30))     // 若设备支持，可尝试 60fps，用 60 更稳
```

**不要使用全分辨率**。800 万像素逐帧处理会直接拖垮帧率，而心率检测只需要亮度均值，640×480 绰绰有余。

### 5.2 锁定曝光（最容易翻车的一步）

相机的自动曝光（AE）与自动白平衡（AWB）会自动调节画面亮度，而**待测信号恰好就藏在亮度里**——3A 算法一补偿，脉搏波动就被压平，波形变成一条直线。

首选方案（更稳）：

```
Camera2Interop.Extender(builder)
    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, <固定值，如 20_000_000L>) // 单位纳秒
    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, <固定值，如 400>)
    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
```

备选方案（若手动模式下画面全黑或全白）：

```
.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
```

**必须在启动采集前让 AE 收敛**：先开启闪光灯，等约 1 秒让 3A 稳定，再锁定参数，然后才开始采样。不要一上来就锁。

### 5.3 提取亮度信号（含一个必踩的坑）

只取 YUV 数据的 **Y 平面**（`imageProxy.planes[0]`）。Y 通道本身即亮度，无需任何颜色空间转换，不要转 RGB。

**坑：`rowStride` 通常大于图像宽度**，buffer 中存在行末填充字节。禁止把整个 buffer 当作连续像素数组读取，必须按行跨步取值：

```kotlin
val plane = imageProxy.planes[0]
val buffer = plane.buffer
val rowStride = plane.rowStride
val pixelStride = plane.pixelStride
val width = imageProxy.width
val height = imageProxy.height

var sum = 0L
var count = 0

// 只采样画面中心区域，避免边缘噪声与镜头暗角造成干扰
val yStart = height / 3
val yEnd = height * 2 / 3
val xStart = width / 3
val xEnd = width * 2 / 3

for (y in yStart until yEnd) {
    val rowBase = y * rowStride
    for (x in xStart until xEnd) {
        val index = rowBase + x * pixelStride
        if (index < buffer.limit()) {
            sum += (buffer.get(index).toInt() and 0xFF)
            count++
        }
    }
}
val meanLuma = if (count > 0) sum.toDouble() / count else 0.0
```

### 5.4 时间戳与重采样（第二个必踩的坑）

**禁止用帧序号当作时间轴**。相机实际帧间隔并不均匀（掉帧、抖动很常见），用帧序号算频率会得到系统性偏差。

用 `imageProxy.imageInfo.timestamp`（纳秒）记录每帧的真实时间：

```kotlin
val tNanos = imageProxy.imageInfo.timestamp
```

采集结束后得到两个数组：`timestamps: DoubleArray`（秒）、`luma: DoubleArray`。

**必须先重采样到均匀网格**再做 FFT。步骤：

1. 估算实际采样率：`fs = (n - 1) / (t_last - t_first)`，通常落在 25–33 Hz
2. 以 `dt = 1.0 / fs` 为间隔，用线性插值把 `luma` 重采样到均匀时间序列
3. 检查有效帧数：若采集 30 秒但有效帧少于 300 帧（即实际帧率低于 10fps），直接判定为采集失败，提示重测

### 5.5 滤波与频域分析

对重采样后的均匀序列依次执行：

1. **去趋势**：减去窗口长度约 1 秒（`fs` 个点）的滑动平均值，消除缓慢的基线漂移
2. **加窗**：乘 Hann 窗，减少频谱泄漏
3. **零均值化**：减去均值
4. **补零**：补到 2 的整数次幂（如 2048 或 4096 点），提高频率分辨率
5. **FFT**：得到复数频谱，取幅度谱
6. **只保留 0.7–4.0 Hz 区间**（对应 42–240 BPM），其余全部置零——这一步同时完成了带通滤波，比另写 IIR 滤波器更简单可靠
7. **找峰值**：取该区间内幅度最大的频点 `f_peak`

### 5.6 基频判定与质量评估（第三个必踩的坑）

**谐波陷阱**：由于 PPG 波形不是纯正弦，信号的二次谐波有时比基频更强，直接取最大峰会得到**两倍**的心率。

修正规则：设最大峰位于 `f_peak`，检查 `f_peak / 2` 处是否存在显著峰（该点幅度 > 最大峰幅度的 30%）。若存在且 `f_peak / 2 >= 0.7 Hz`，则采用 `f_peak / 2`。

**BPM 计算**：

```
bpm = f_peak * 60
```

**输出平滑**：对最近若干次估算结果做中位数滤波，避免数字乱跳。

**信号质量评估**（用于界面提示）：

| 指标 | 良好 | 一般 | 差 |
|---|---|---|---|
| 主峰能量占区间总能量比例 | > 0.4 | 0.2–0.4 | < 0.2 |
| 区间内次高峰 / 主峰 | < 0.5 | 0.5–0.8 | > 0.8 |
| 采集期间亮度方差突变次数 | 0 | 1–2 | ≥ 3 |

质量判定为"差"时，必须显示读数但同时标注不可信，不要假装准确。

（可选，非必须）可用加速度计检测手指抖动，作为质量评估的补充输入。

### 5.7 数值合理性

- 结果 < 40 BPM 或 > 200 BPM 时，标记为异常，提示用户重测
- 结果非有限数值（NaN / Inf）时，直接走重测流程，不要显示

---

## 6. 已知坑与硬性约束清单

实现方请逐条对照自查：

| # | 坑 | 后果 | 正确做法 |
|---|---|---|---|
| 1 | 未锁定 AE/AWB | 波形被压平为直线，永远测不出心率 | 见 5.2 |
| 2 | 把整个 Y buffer 当连续数组读 | 亮度值完全错乱，画面出现横向伪影 | 按 `rowStride` 跨步读取，见 5.3 |
| 3 | 用帧序号当时间轴 | BPM 系统性偏差 | 用 `imageInfo.timestamp`，见 5.4 |
| 4 | 不做均匀重采样直接 FFT | 频谱出现虚假峰 | 先线性插值重采样，见 5.4 |
| 5 | 直接把最大峰当心率 | 可能得到 2 倍心率 | 检查次谐波，见 5.6 |
| 6 | 使用全分辨率图像 | 帧率崩掉，采不到足够数据点 | 用 640×480 |
| 7 | 忘记 `imageProxy.close()` | 背压导致只处理第一帧，界面卡死 | 每个分支都要 close，用 try/finally |
| 8 | 未同时绑定 Preview 用例就调 `enableTorch` | 部分机型闪光灯不亮 | 绑定 Preview + ImageAnalysis |
| 9 | 一启动就锁定曝光参数 | 3A 未收敛，画面全黑或全白 | 先开闪光灯等约 1 秒再锁 |
| 10 | 在模拟器上调试 | 无真实闪光灯与指尖，无法验证 | 必须针对真机实现 |
| 11 | 采集期间允许息屏 | 采集中断 | 加 `FLAG_KEEP_SCREEN_ON` |
| 12 | 闪光灯长时间常亮无提示 | 手机发热 | 界面上给出提示，30 秒自动结束 |

---

## 7. 工程结构建议

```
app/src/main/java/<package>/
├── MainActivity.kt              // 入口、权限申请、Compose 挂载
├── ui/
│   ├── MeasureScreen.kt         // 主界面：引导、波形、倒计时、结果
│   ├── WaveformView.kt          // Compose Canvas 实时波形
│   └── theme/                   // Material 3 主题
├── camera/
│   ├── CameraController.kt      // CameraX 绑定、闪光灯、参数锁定
│   └── LumaAnalyzer.kt          // ImageAnalysis.Analyzer 实现，逐帧提取亮度
├── signal/
│   ├── Resampler.kt             // 时间戳 → 均匀网格重采样
│   ├── Filter.kt                // 去趋势、Hann 窗、频域截断
│   ├── FftProcessor.kt          // FFT 封装
│   └── HeartRateEstimator.kt     // 峰值检测、基频修正 → BPM + 质量分
└── model/
    └── MeasureResult.kt         // 数据类
```

要求：每个文件职责单一，`signal/` 包内不出现任何 Android SDK 依赖，保证这部分逻辑可以脱离设备独立验证。

---

## 8. 验收标准

实现方交付时，以下条件应能从代码逻辑上判定成立（无需实际运行）：

- [ ] 工程结构完整，Gradle 依赖声明齐全，无 `TODO` / 占位函数
- [ ] 曝光与白平衡参数按要求锁定
- [ ] Y 平面读取正确处理了 `rowStride` 与 `pixelStride`
- [ ] 时间轴使用帧时间戳，且实现了非均匀重采样
- [ ] 实现了次谐波检查
- [ ] 每个 `ImageProxy` 均被 `close()`
- [ ] `signal/` 包不依赖 Android SDK
- [ ] 未引入任何网络、账号、统计类依赖

用户在真机上验证时的目标（供参考，非实现方职责）：

- 静息状态下，读数与手动数脉搏（60 秒）对比误差在 ±5 BPM 以内
- 波形能肉眼看出周期性搏动
- 手指移开或剧烈抖动时，界面明确提示信号质量差

---

## 9. 术语

| 术语 | 含义 |
|---|---|
| PPG | 光电容积脉搏波描记法，本项目的测量原理 |
| AE / AWB | 相机自动曝光 / 自动白平衡，必须锁定 |
| Y 平面 | YUV 格式中的亮度分量，本项目的原始信号来源 |
| BPM | 每分钟心跳次数 |
| 基频 | 脉搏的基波频率，即心率对应频率；其整数倍为谐波 |
*（内容由AI生成，仅供参考）*
