# Nikonect

**面向 Nikon Z50（第一代）的独立 Android 相机控制与监看 App。** 通过 USB Host / PTP 连接相机，提供参数调整、实时取景、拍摄和照片传输，并探索 Wi-Fi / PTP-IP 连接。

**An independent Android camera control and live-view app for the original Nikon Z50.** Connect over USB Host / PTP to adjust camera settings, monitor live view, take photos, and transfer images, with experimental Wi-Fi / PTP-IP connectivity.

[简体中文](#简体中文) · [English](#english)

---

## 简体中文

### 项目状态

当前版本：**0.8.0 开发原型**。使用 Kotlin、Jetpack Compose 和 Material 3，支持 Android 10 及以上。

此前版本已有实体手机 USB 连接可用的用户反馈；0.8.0 已完成 Debug 构建及部分模拟器界面检查。最新相册手势、批量操作、自动 ISO、视频模式参数、触摸对焦以及无线配对仍需在 Z50 真机上完整验证。模拟器运行成功不代表相机兼容性验证通过。

本项目是独立开发项目，非 Nikon 官方产品。当前应用界面主要为中文；本 README 提供中英文说明。

### 功能

| 模块 | 当前实现 |
| --- | --- |
| 连接 | USB Host / PTP；实验性 Wi-Fi / PTP-IP；相机热点与手机热点下的自动发现；WTU 配对流程 |
| 参数 | ISO、快门、光圈、曝光补偿、白平衡等；读取相机属性描述、可选值和范围，修改后回读；自动 ISO 状态与读数；照片/视频模式属性映射 |
| 扩展参数 | 包含 251 项属性名称的目录，按相机实际公布的能力加载；支持的属性提供枚举、范围或类型化输入，只读属性仅显示 |
| 监看 | JPEG 实时取景、横屏、适应/填充画面、透明曝光参数栏、网格、亮度直方图、触摸对焦开关及已解析的对焦信息 |
| 拍摄与传输 | 远程拍照；检测连接后新增的照片并自动接收；默认传输 JPG，可选 NEF；从 JPG 单独下载匹配的 NEF |
| 相册 | 本地照片、相机照片、垃圾桶；直接展示机内 JPEG；缩略图分批加载；双指缩放切换 1–5 列；大图左右翻页；长按拖动多选 |
| 批量操作 | 相机照片批量下载；本地 JPG 分享、移入垃圾桶；垃圾桶恢复或永久删除 |
| 设置 | 目标帧率、预览解码尺寸、监看优先、自动传图、NEF、扫描间隔、横屏、常亮、沉浸式、后台暂停及诊断 |

默认首页为参数页，主页面通过导航按钮切换；仅设置页的顶部分类支持左右滑动。监看顶部工具栏可展开/收起，扳手图标打开设置，U 型返回按钮直接进入参数页。

### 连接与使用

#### USB

1. 准备支持 USB Host / OTG 的 Android 10+ 手机，以及支持数据传输的 USB 线；按接口需要使用 OTG 转接头。
2. 打开相机并连接手机，退出可能占用相机连接的其他应用。
3. 在参数页选择 USB 连接，允许 Android 弹出的 USB 设备访问权限。
4. 连接后读取参数，进入监看或拍摄；在相册查看已接收照片或机内 JPEG。

遇到「USB 读取超时或相机断开」时，检查数据线与 OTG、相机电源状态和其他应用占用情况，断开后重新连接。具体错误与诊断信息有助于定位问题。

#### Wi-Fi（实验性）

在参数页打开 Wi-Fi 连接，选择网络方式：

- **相机热点**：先让手机加入相机创建的 Wi-Fi，并保留无互联网连接。若机身处于「连接到计算机」并等待 Wireless Transmitter Utility（WTU），勾选对应选项；智能设备连接方式使用另一条实验性路径。
- **手机热点**：先在系统设置中开启手机热点，再通过相机的「连接到计算机」网络设置加入该热点。

返回 App 后选择「自动识别并连接」，无需手动输入 IP 地址或端口。需要配对时按提示输入相机显示的配对码；等机身显示完成并按 OK 后，再在 App 中确认并重新连接。

自动发现依赖手机网络接口与局域网可达性。以上流程已实现，但 **Z50 无线配对、参数控制、拍摄和传图尚未完成真机验证**；不包含蓝牙配对，也不能保证适用于所有手机热点或相机网络模式。

### 监看与延迟

取帧和解码分离，使用只保留最新帧的缓冲，减少排队造成的延迟。目标帧率的等待时间扣除取帧耗时，预览可按尺寸下采样。

默认开启「监看优先」：监看期间暂停自动存储扫描和整张照片下载，停止监看后再接收，减少 PTP 通道争用。手动进入相机相册或下载照片会暂停监看。

- 目标 30 FPS 不代表相机实际能输出 30 FPS。
- 界面显示的本机耗时是取帧请求到解码完成的时间，不是曝光到手机屏幕的完整端到端延迟。
- 解码尺寸设置不会改变相机传出的 JPEG 数据大小。
- 直方图来自预览 JPEG，不是 RAW 数据；画面叠加层不是相机 LCD 的完整镜像。

### 照片与存储

- JPG/JPEG 保存到 `Pictures/Nikonect`；NEF 保存到 `Download/Nikonect/NEF`。
- 相册只展示本应用管理的本地 JPG/JPEG 以及相机 JPEG，不提供从手机导入照片的入口。
- NEF 自动传输默认关闭；可在设置中开启，或从 JPG 页面手动下载匹配的 NEF。不提供 RAW 解码或编辑。
- 自动传图以连接后新增对象为目标；连接前的旧照片可通过相机相册下载。此前跳过的 NEF 不会因为开启开关而自动补传。
- Android 11+ 使用系统垃圾桶机制；Android 10 使用应用的 Trash 目录。本地 JPG 的删除/恢复不修改相机存储卡，也不连带删除独立保存的 NEF。
- 暂无后台传输服务或断点续传。

### 构建与安装

使用 Android Studio 打开项目根目录，在 SDK Manager 中安装与 `compileSdk` 对应的 **Android SDK Platform 37**，配置可供 Gradle 使用的 JDK（本项目已使用 Android Studio 自带 JBR 构建）。首次构建需要下载 Gradle 和 Maven 依赖。

| 项目 | 配置 |
| --- | --- |
| Android Gradle Plugin | 9.2.0 |
| Gradle Wrapper | 9.4.1 |
| Kotlin | AGP 内置 Kotlin；Compose 编译插件 2.2.10 |
| Android SDK | compileSdk 37 / targetSdk 36 / minSdk 29 |
| Java / Kotlin 字节码目标 | 17 |
| 应用 ID | `dev.nikonect` |

Android Studio 可生成本机的 `local.properties`；也可自行设置 `sdk.dir`。不要提交该文件。

Windows PowerShell：

```powershell
# 如未配置 JAVA_HOME，请改成你的 JDK/JBR 安装路径。
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

macOS / Linux（配置好 JDK 和 Android SDK 后）：

```bash
sh gradlew :app:assembleDebug
```

构建产物：`app/build/outputs/apk/debug/app-debug.apk`。通过 Android Studio 安装，或在设备开启 USB 调试后执行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APK 用于开发测试；覆盖安装需要兼容的应用签名。模拟器可用于界面检查，USB 相机交互需要实体手机与相机。

### 架构与目录

Compose 页面 → `CameraController` → `PtpSession` → USB / Wi-Fi 传输。PTP 事务串行执行；预览取帧与解码分离；`MediaLibrary` 负责 MediaStore 保存与本地相册。

```text
app/src/main/java/dev/nikonect/
├── MainActivity.kt
├── camera/
│   ├── CameraController.kt    # 会话、参数、取景、拍摄与传图
│   ├── CameraIso.kt           # 自动 ISO 与视频模式参数映射
│   ├── SdkCatalog.kt          # Nikon 属性目录
│   ├── Z50Focus.kt            # Z50 取景头与对焦坐标
│   ├── PreviewFrame.kt        # 解码、直方图与耗时
│   └── AppSettings.kt         # 持久设置与传输策略
├── ptp/
│   ├── Ptp.kt                # PTP 容器与数据解析
│   ├── PtpSession.kt         # 共用会话接口
│   ├── UsbPtpSession.kt      # Android USB Host
│   ├── PtpStream.kt          # USB 数据流
│   ├── WifiPtpSession.kt     # PTP/IP
│   ├── CameraDiscovery.kt    # 局域网相机发现
│   ├── WtuPairing.kt         # 实验性 WTU 配对
│   └── DeviceSetting.kt      # 类型化属性读写与校验
├── media/MediaLibrary.kt     # JPG、NEF、相册与垃圾桶
└── ui/                      # 参数、监看、相册、设置与手势
app/src/test/                # 已有协议与策略单元测试
app/src/androidTest/         # 已有媒体存储检查代码
docs/                       # 历史设计和版本记录
```

### Nikon SDK 与验证范围

实现参考 Nikon Remote Module SDK 包中的 Z50 USB/MTP 命令文档。**Android App 没有加载 Windows/macOS 的官方原生 SDK 库**，而是使用独立的 Kotlin PTP 实现；构建不需要下载官方 SDK。属性目录不等于实现了 SDK 的所有能力，特殊操作与二进制设置数据并未全部覆盖。

以下 Nikon 扩展已接入代码，实际行为仍需结合机身固件、模式和镜头验证：

| 用途 | 操作码 |
| --- | --- |
| 开始 / 结束实时取景 | `0x9201` / `0x9202` |
| 取景图像 / 扩展取景图像 | `0x9203` / `0x9428` |
| 移动 AF 区域 / 启动 AF / 就绪检查 | `0x9205` / `0x90C1` / `0x90C8` |
| Nikon 拍摄扩展 | `0x9207` |
| 扩展属性列表 | `0x90CA` |
| 实验性 WTU 配对 | `0x952B` / `0x935A` |

相机公布的能力、属性描述和当前模式决定可用功能。视频模式属性映射不代表完整的视频录制控制或视频文件管理。其他 Nikon 机型尚未验证。

### 反馈与仓库说明

反馈问题时请附上：手机型号、Android 版本、Z50 固件版本、镜头、连接方式、照片/视频模式、复现步骤、完整错误及可用的诊断信息。描述监看延迟时，请区分主观画面延迟和 App 显示的本机耗时。

`docs/` 中的文档保留历史设计与版本说明，可能包含旧版行为；当前功能以本 README 和源码为准。上传仓库时只包含项目源码与构建配置，官方 SDK 压缩包、个人 SDK 文档、`local.properties`、本机缓存、日志和构建产物不属于源码内容；APK 可单独放入 GitHub Releases。

当前项目未提供项目级 `LICENSE` 文件，本 README 不授予额外的使用或分发许可；第三方依赖遵循各自许可证。

---

## English

### Project status

Current version: **0.8.0 development prototype**. Built with Kotlin, Jetpack Compose, and Material 3 for Android 10 and later.

Users have reported working USB connections on physical phones with earlier versions. Version 0.8.0 has passed a Debug build and selected emulator UI checks. The latest gallery gestures, batch actions, auto ISO, movie-mode settings, touch focus, and wireless pairing still need comprehensive testing with a physical Z50. Running in an emulator does not establish camera compatibility.

This is an independent project, not an official Nikon product. The app interface is currently primarily in Chinese; this README is available in Chinese and English.

### Features

| Area | Current implementation |
| --- | --- |
| Connectivity | USB Host / PTP; experimental Wi-Fi / PTP-IP; automatic discovery on camera and phone hotspots; WTU pairing flow |
| Camera controls | ISO, shutter speed, aperture, exposure compensation, white balance, and more; camera-reported choices and ranges; readback after changes; auto ISO status/readings; still/movie property mapping |
| Extended settings | A catalog of 251 named properties, loaded according to advertised camera capabilities; enumeration, range, or typed input for supported writable properties; display of read-only properties |
| Live view | JPEG preview, landscape layout, fit/fill, transparent exposure controls, grids, luminance histogram, touch-focus toggle, and parsed focus information |
| Capture and transfer | Remote still capture; automatic reception of newly detected photos after connection; JPG by default with optional NEF; manual download of a matching NEF from a JPG |
| Gallery | Local photos, camera photos, and trash; a flat camera JPEG listing; incremental thumbnail loading; pinch to change between 1–5 columns; swipe between full images; long-press and drag selection |
| Batch actions | Download camera photos; share or trash local JPGs; restore or permanently delete trashed photos |
| Settings | Target frame rate, preview decoding size, live-view priority, automatic transfer, NEF, scan interval, landscape, keep screen on, immersive mode, background pause, and diagnostics |

The controls page is the default home page. Main pages use navigation buttons; only the settings categories support horizontal page swipes. The live-view toolbar expands/collapses, its wrench opens settings, and its U-turn button returns directly to the controls page.

### Connecting and using the app

#### USB

1. Use an Android 10+ phone with USB Host / OTG support and a data-capable USB cable. Add an OTG adapter if required.
2. Turn on the camera, connect it to the phone, and close other apps that may be using the camera connection.
3. Select USB connection on the controls page and grant Android's USB device access permission.
4. Read camera settings, start live view, or take a photo. Open the gallery to view received images or camera JPEGs.

If a USB read times out or the camera disconnects, check the cable/OTG connection, camera power state, and competing apps, then reconnect. Include the full error and available diagnostics when reporting a problem.

#### Wi-Fi (experimental)

Open the Wi-Fi connection dialog on the controls page and choose a network mode:

- **Camera hotspot:** connect the phone to the camera's Wi-Fi and keep the connection even without internet access. If the camera is in “Connect to PC” mode waiting for Wireless Transmitter Utility (WTU), enable the corresponding option. Smart-device mode uses a separate experimental path.
- **Phone hotspot:** enable the phone hotspot in system settings, then join it through the camera's “Connect to PC” network settings.

Return to the app and select automatic discovery and connection; no manual IP address or port is required. If pairing is requested, enter the code shown on the camera. Wait for the camera to report completion, press OK on the camera, then confirm in the app to reconnect.

Discovery depends on accessible phone network interfaces and local network reachability. These flows are implemented, but **Z50 wireless pairing, settings control, capture, and transfer have not completed physical-camera validation**. Bluetooth pairing is not implemented, and compatibility with every phone hotspot or camera network mode is not guaranteed.

### Live view and latency

Frame acquisition and decoding run separately, with a buffer that retains only the latest frame to reduce queueing delay. Frame pacing accounts for acquisition time, and previews can be downsampled during decoding.

Live-view priority is enabled by default. It defers automatic storage scans and full-image downloads until live view stops, reducing contention on the PTP connection. Manually browsing camera photos or downloading images pauses live view.

- A 30 FPS target does not guarantee 30 FPS from the camera.
- The displayed local timing covers the frame request through decoding, not complete exposure-to-screen latency.
- Changing the decoding size does not reduce the JPEG payload sent by the camera.
- The histogram is calculated from the preview JPEG, not RAW data. Overlays do not reproduce the entire camera LCD interface.

### Photos and storage

- JPG/JPEG files are saved to `Pictures/Nikonect`; NEF files to `Download/Nikonect/NEF`.
- The gallery shows app-managed local JPG/JPEG files and camera JPEGs. Importing existing phone photos is not offered.
- Automatic NEF transfer is off by default. Enable it in settings or manually download a matching NEF from a JPG view. RAW decoding and editing are not implemented.
- Automatic transfer targets new objects detected after connection. Download older photos through the camera gallery. Enabling NEF does not automatically backfill previously skipped files.
- Android 11+ uses the system trash mechanism; Android 10 uses an app Trash directory. Local JPG deletion/restoration does not alter the camera card or delete separately stored NEF files.
- Background transfer services and resumable downloads are not implemented.

### Build and install

Open the project root in Android Studio. Install **Android SDK Platform 37** to match `compileSdk` and configure a JDK for Gradle; the project has been built using Android Studio's bundled JBR. The first build needs access to Gradle and Maven dependencies.

| Component | Configuration |
| --- | --- |
| Android Gradle Plugin | 9.2.0 |
| Gradle Wrapper | 9.4.1 |
| Kotlin | AGP built-in Kotlin; Compose compiler plugin 2.2.10 |
| Android SDK | compileSdk 37 / targetSdk 36 / minSdk 29 |
| Java / Kotlin bytecode target | 17 |
| Application ID | `dev.nikonect` |

Let Android Studio generate `local.properties`, or set its `sdk.dir` to your local SDK location. Do not commit this file.

Windows PowerShell:

```powershell
# If JAVA_HOME is not configured, replace this with your JDK/JBR path.
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

macOS / Linux, after configuring the JDK and Android SDK:

```bash
sh gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Install through Android Studio, or enable USB debugging on your device and run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APKs are intended for development testing. Updating an existing installation requires a compatible signing key. Use an emulator for UI checks and a physical phone/camera for USB camera interaction.

### Architecture and source layout

Compose UI → `CameraController` → `PtpSession` → USB / Wi-Fi transport. PTP transactions are serialized; frame acquisition and decoding are separate; `MediaLibrary` handles MediaStore persistence and the local gallery.

| Path under `app/src/main/java/dev/nikonect/` | Responsibility |
| --- | --- |
| `MainActivity.kt` | Android application entry point |
| `camera/CameraController.kt` | Sessions, settings, preview, capture, and transfers |
| `camera/CameraIso.kt` | Auto ISO and movie-mode property mapping |
| `camera/SdkCatalog.kt` | Nikon property catalog |
| `camera/Z50Focus.kt` | Z50 live-view headers and focus coordinates |
| `camera/PreviewFrame.kt` | Decoding, histogram, and timing |
| `camera/AppSettings.kt` | Persistent settings and transfer policies |
| `ptp/Ptp.kt`, `ptp/DeviceSetting.kt` | PTP parsing, typed properties, and validation |
| `ptp/PtpSession.kt` | Shared session interface |
| `ptp/UsbPtpSession.kt`, `ptp/PtpStream.kt` | Android USB transport and data stream |
| `ptp/WifiPtpSession.kt` | PTP/IP transport |
| `ptp/CameraDiscovery.kt`, `ptp/WtuPairing.kt` | Network discovery and experimental WTU pairing |
| `media/MediaLibrary.kt` | JPG/NEF storage, gallery, and trash |
| `ui/` | Controls, live view, gallery, settings, and gestures |

Existing unit tests are under `app/src/test/`; media-storage checks are under `app/src/androidTest/`. Historical design and release notes are in `docs/`.

### Nikon SDK and validation scope

The implementation references the Z50 USB/MTP command documentation distributed with Nikon's Remote Module SDK. **The Android app does not load Nikon's Windows/macOS native SDK libraries.** It uses an independent Kotlin PTP implementation, and building it does not require the official SDK. The property catalog does not cover every SDK capability; special operations and binary settings datasets are not fully implemented.

These Nikon extensions are wired into the code and still require validation across camera firmware, modes, and lenses:

| Purpose | Operation codes |
| --- | --- |
| Start / stop live view | `0x9201` / `0x9202` |
| Live-view image / extended live-view image | `0x9203` / `0x9428` |
| Move AF area / drive AF / check readiness | `0x9205` / `0x90C1` / `0x90C8` |
| Nikon capture extension | `0x9207` |
| Extended property listing | `0x90CA` |
| Experimental WTU pairing | `0x952B` / `0x935A` |

Advertised camera capabilities, property descriptors, and the current mode determine availability. Movie-mode property mapping does not imply complete video recording control or video file management. Other Nikon models have not been validated.

### Feedback and repository notes

When reporting an issue, include the phone model, Android version, Z50 firmware, lens, connection method, still/movie mode, reproduction steps, full error, and available diagnostics. For latency reports, distinguish perceived preview delay from the app's local timing metric.

Documents in `docs/` preserve historical designs and releases and may describe older behavior. This README and the current source describe the present implementation. Publish project source and build configuration; keep official SDK archives, personal SDK documentation, `local.properties`, local caches, logs, and build artifacts outside the source repository. APKs can be distributed separately through GitHub Releases.

No project-level `LICENSE` file is currently provided. This README does not grant additional usage or redistribution rights; third-party dependencies retain their respective licenses.
