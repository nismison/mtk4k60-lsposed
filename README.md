# mtk4k60-lsposed

给「快速录像机 / Secret Video Recorder」（`com.kimcy929.secretvideorecorder`）加一档相机 API：
**MTK 4K60（厂商高速）**，与 App 自带的「原始相机 API」「Camera2 API」平级、可随时切换。

## 为什么 App 自己做不到 4K60

App 的 60fps 走的是 Camera2 的 *受限高速视频* 通道（`CONSTRAINED_HIGH_SPEED_VIDEO`）：

- 选 4K + 60fps 时，`r8.a`（`Camera2APISession`）会把画面尺寸换成 `getHighSpeedVideoSizes()` 里最大的一档，本机只有 1920×1080 与 1280×720，于是静默变成 1080p60；
- 公开 API 下本机 4K 的最小帧间隔是 33.3 ms（30fps），`aeAvailableTargetFpsRanges` 最大也只有 30；
- 系统相机能录 4K60，是因为它用 MediaTek 私有通道：`com.mediatek.streamingfeature.availableHfpsMaxResolutions` = 1920×1080@60 / **3840×2160@60**。

本模块就是替 App 走这条私有通道。

## 实现要点（踩过的坑）

1. **厂商键不在 `getKeys()` 里**，要从 `CameraCharacteristics.getAvailableSessionKeys()` / `getAvailableCaptureRequestKeys()` 取；本机共 733 个键，其中有 `com.mediatek.streamingfeature.hfpsMode`、`com.mediatek.smvrfeature.smvrMode`。
2. **这些键是数组类型**：传 `Integer` 会报 `IllegalArgumentException: Not an array`，必须传 `int[]{1}`（模块会依次试 `Integer/int[]/byte[]/long[]` 并记日志）。
3. **只写 `hfpsMode=1` 不够**：传感器仍然 30fps（实测 202 帧 / 6.745 秒）。必须同时把 AE 目标帧率范围设为 **60-60**，才会真正 60fps（实测 1962 帧 / 32.67 秒 = 60.06fps）。
4. **只往抽象类挂钩子抓不到会话创建**：App 调的是 `android.hardware.camera2.impl.CameraDeviceImpl#createCaptureSession(SessionConfiguration)`，要钩实现类（同时保留 `CameraDevice` 抽象方法上的钩子）。
5. **绕开 App 自己的高速通道但不能改帧率显示**：把 `r8.a.Y0(ch, 0)` 强制为 `false`（它决定"是否启用 App 高速通道"），这样设置里仍能选 60fps、但会话走普通模式由模块接管。
6. 摄像头前摄不动：只对 `LENS_FACING != FRONT` 的会话注入，且要求该相机存在 3840×2160 的 MediaRecorder 输出尺寸（超广角没有就自动跳过）。
7. 编码器：优先 HEVC（4K60 基本只有 HEVC 硬编支持），按 `MediaCodecList` 能力自动选择，可用 `hevc=off` 关掉。

## 三个 API 档位的对应关系

| 档位 | 存储 | 行为 |
| --- | --- | --- |
| 原始相机 API | `ENABLE_CAMERA_API2=false` | App 自带 Camera1 路径，最高 30fps |
| Camera2 API | `ENABLE_CAMERA_API2=true` | App 自带 Camera2 路径，60fps 只到 1080p |
| MTK 4K60（厂商高速） | `ENABLE_CAMERA_API2=true` + `MTK_HFPS=true` | 本模块接管，3840×2160@60 |

## 构建

工具链限制：`aapt` 是 Android-aarch64 二进制，只能在 Android shell 跑；`javac/d8/apksigner` 只在 Linux chroot 里有。因此分三段：

```sh
# 1) Linux chroot
sh scripts/build-dex.sh
# 2) Android shell (root)
sh /data/local/tmp/eta/mtk4k60-lsposed/scripts/package.sh
# 3) Linux chroot
sh scripts/sign.sh
# 4) Android shell
sh /data/local/tmp/eta/mtk4k60-lsposed/scripts/install.sh
am force-stop com.kimcy929.secretvideorecorder
```

更新模块**不需要重启手机**：装完强制停止目标 App 再打开即可。不要停止/重启 LSPosed 守护进程（会导致 system_server 与守护进程失联，管理器显示"未安装"，只能重启系统恢复）。

## 配置（热更新，无需重装）

路径：`/data/user/0/com.kimcy929.secretvideorecorder/files/mtk4k60.conf`，`key=value` 每行一个，1.5 秒生效。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `fps` | 60 | MediaRecorder 帧率 |
| `width` / `height` | 3840 / 2160 | 输出尺寸 |
| `bitrate` | 80000000 | 码率 |
| `hfps` | 1 | 写入 `com.mediatek.streamingfeature.hfpsMode`（int[]{1}） |
| `smvr` | 0 | 写入 `com.mediatek.smvrfeature.smvrMode`（本机 SMVR 只支持 1080p120/240，4K60 用不到） |
| `ae_range` | 60-60 | AE 目标帧率范围，**4K60 必须给**；填空则回落到 30fps |
| `force_quality` | 1 | 把后摄/超广角画质强制为 UHD 4K |
| `hevc` | auto | `auto` / `on` / `off` |
| `log` | 1 | 写 `files/mtk4k60.log` |

## 验证

```sh
python3 scripts/mp4_fps.py /storage/emulated/0/Download/.QuickRecorder/QVR_*.mp4
# vide 32.67s 1962 samples 60.06 fps  <- 正确
```

不要只信系统图库/文件管理显示的"帧率"：旧版本文件头会写 120，但 stts 只有 30fps 的帧间隔。以 `mp4_fps.py` 的实际样本数为准。

## 文件结构

```
AndroidManifest.xml          LSPosed 模块声明（xposedmodule/xposed_init/xposedscope）
assets/xposed_init           入口类
res/values/                  app_name、xposedscope
src/com/eta/mtk4k60/
  HookEntry.java             UI 档位、会话注入、MediaRecorder 覆盖
  Cfg.java                   热更新配置
  VendorKeys? -> 见 HookEntry.vendorKeys()
src/de/robv/android/xposed/  仅编译用桩，不进 dex
scripts/                     三段构建 + 安装 + fps 校验
```

## 已知限制

- 预览仍可能是 1080p/其它尺寸（录制输出是 4K60，不影响成片）。
- 前摄不改动；超广角没有 4K 输出尺寸时自动跳过。
- MTK HAL 行为未公开，换机型/换 ROM 需要重测 `hfpsMode` 与 `ae_range`。
