# Nikonect 0.4.0

基于 Vibe Coding 打造的

独立 Android USB Host / PTP 相机 App，新增实验性 Wi-Fi PTP/IP，目标 Nikon Z50（第一代）。工程在 `D:\Nikonect`，Kotlin + Jetpack Compose，支持 Android 10 及以上。

0.4.0 增加 Nikon 自动 ISO 控制值读取、统一底部导航与左右滑动、顶部可隐藏工具栏（+ 菜单）、相机目录浏览、JPG 对应 NEF 手动下载、手机照片导入、垃圾桶恢复和永久删除。保留 USB 16 KiB 兼容读取。完整步骤和限制见 [0.4.0 说明](docs/RELEASE_0.4.0.md)。

## 安装与升级

安装包：`outputs/Nikonect-0.4.0-debug.apk`（最新通用副本 `outputs/Nikonect-debug.apk`）。覆盖安装保留应用设置和已导入照片。Debug 包仅用于开发测试。

用户反馈上一版在实体手机可连接使用，但监看延迟较大。本版优化应用侧等待、解码排队和传图争用，新增四个独立页面。实际端到端延迟改善仍待用户使用同一手机和 Z50 对比复测。

## 页面

- **监看**：全屏画面上叠加曝光参数，点击调节；可用的模式、白平衡和电量显示在画面上。导航和信息层可隐藏，支持横屏、网格和亮度直方图。不是相机 LCD 镜像，不显示未经解析的对焦框和水平仪。
- **参数**：固定单屏操作 ISO、快门、光圈；相机实际值自动回读，描述范围定时更新、调节前重读，到边界禁用按钮。支持 USB 或实验性 Wi-Fi 连接与拍摄。
- **相册**：已接收、相机照片、手机导入、垃圾桶四个文件夹。按卡内目录浏览原片，查看缩略图/原图、保存 JPG、从 JPG 单独传输同目录 NEF。本地 JPG 支持多选、批量分享、移入垃圾桶、恢复和彻底删除。永久删除仅限垃圾桶且需确认。手机导入会复制文件，保留原片。
- **设置**：监看、传输、常规、诊断四类，包含帧率、解码尺寸、网格、直方图、监看优先、自动传图、NEF、扫描间隔、横屏、常亮、沉浸式、后台暂停和连接后自动取景，保存后下次启动保留。

默认首页是参数页；四页支持左右滑动，底部导航一致。监看顶部为网格/直方图等工具与 + 设置入口，工具栏和底部导航可独立隐藏恢复。返回按页面历史回退。界面采用 Material 3 和矢量图标。

自动 ISO：仅在 Nikon 公布 0xD054/0xD0B5 时读取开关和只读感光度控制值，显示 AUTO 及读数；读不到显示 AUTO —。手动设定值不再冒充自动 ISO 控制结果，实际 Z50 同步仍待真机确认。

垃圾桶：Android 11+ 使用系统垃圾桶，有到期清理机制；Android 10 使用应用 Trash 目录。恢复到原分类。只管理 App 拥有的 JPG，不修改相机卡或手机导入前的原片，也不删除独立 NEF。

无线：先在 Z50 建立连接至智能设备的 Wi-Fi 热点，手机连接热点后，在参数页点 Wi-Fi 图标，核对相机地址和端口 15740，再连接。已实现 PTP/IP 握手、参数读写、拍摄与新照片接收通道，但尚无 Z50 无线真机验证；不包含蓝牙配对或 Nikon 私有认证。完整步骤和限制见 [0.3.0 更新说明](docs/RELEASE_0.3.0.md)。

所有主页面采用固定布局，按钮或左右滑动切换，不使用纵向滚动总览。相册采用固定分页。

## 降低预览延迟

旧版每帧固定等待 200 ms 已删除。现在按目标帧率扣除 USB 请求耗时；取帧、解码分离，以单槽最新帧缓冲防止解码排队。USB 复用 16 KiB 兼容缓冲，预览按设置下采样并使用 RGB_565 解码。取景帧独立发布，不驱动设置、相册页面逐帧更新。

**默认开启监看优先**：监看时暂停存储扫描和整张照片下载，停止监看后再接收新照片，减少同一 PTP 通道被大文件占用。若关闭此设置，可边看边传，但下载期间画面仍可能停顿。

界面“本机 xx ms”是 USB 取帧请求到 JPEG 解码完成的耗时，不是相机曝光到手机屏幕的完整端到端延迟。目标 30 FPS 不代表相机一定达到 30 FPS。预览解码尺寸不会改变相机传出的 USB 图像大小。直方图来自预览 JPEG，并非 RAW 传感器数据。

建议先保留监看优先，NEF 关闭，目标 30 FPS，预览尺寸选择 960 或 1280。如果仍慢，可关闭直方图并降低到 640，用诊断导出区分 USB 请求与解码耗时。

## JPG 与 NEF

默认只下载 JPG/JPEG。关闭 NEF 时只读取对象元数据，不下载 NEF 原文件。开启后 JPG 优先，NEF 保存到 `Download/Nikonect/NEF`，JPG 保存到 `Pictures/Nikonect`。相册只查询本应用拥有的 JPG。

自动传图只监测连接后新增的文件。关闭自动传图期间发现的对象、已经跳过的 NEF 不会自动补传；可在相机相册保存旧 JPG 或手动下载对应 NEF。监看优先暂停期间，新增照片留到停止监看后处理。正在进行的文件事务不会因修改开关被中途破坏。

## 构建

AGP 9.2.0、Gradle 9.4.1、Compose 编译插件 2.2.10（Kotlin 由 AGP 内置）、compileSdk 37、targetSdk 36、minSdk 29。打开 Android Studio 后同步即可。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

本机已安装 SDK 37.0 / Build Tools 36.0.0，SDK 路径写在不提交的 `local.properties`。普通输出 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 代码

```text
app/src/main/java/dev/nikonect/
  MainActivity.kt
  ptp/Ptp.kt                         标准 PTP 数据解析及参数编码
  ptp/UsbPtpSession.kt                Android USB 适配与事务互斥
  ptp/PtpSession.kt                   USB / Wi-Fi 共用会话接口
  ptp/WifiPtpSession.kt               PTP/IP 命令、数据、事件与心跳
  ptp/PtpStream.kt                    16KiB 兼容缓冲、PTP 数据流与会话状态
  camera/CameraController.kt         连接、拍摄、预览流水线、自动传图
  camera/CameraIso.kt                自动 ISO 状态和控制值显示
  camera/CameraMedia.kt              相机目录与对象模型
  camera/AppSettings.kt              持久设置与传图/帧率策略
  camera/PreviewFrame.kt             预览解码、直方图与耗时指标
  camera/PreviewMath.kt              JPEG 头位置与亮度计算
  media/MediaLibrary.kt              原子媒体保存、JPG 相册与缩略图
  ui/                               四页固定布局
app/src/test/                        PTP、筛选、调度和预览数学测试
app/src/androidTest/                 模拟器媒体存储集成验证（不打进主 APK）
```

最新说明：[0.4.0 自动 ISO、滑动导航与相册管理](docs/RELEASE_0.4.0.md)。

历史记录：[0.2 更新与复测](docs/RELEASE_0.2.md)、[构建状态](docs/BUILD_STATUS.md)、[架构](docs/ARCHITECTURE.md)、[Z50 验证清单](docs/Z50_VALIDATION.md)、[接续记忆](docs/PROJECT_MEMORY.md)。

## 当前范围

USB 权限、PTP/IP 双通道、DeviceInfo、会话、标准拍摄、对象下载、曝光属性读写与回读已实现。Nikon 取景使用 `0x9201/0x9203/0x9202`，默认检查设备公布的能力；未公布的取景指令需手动打开实验选项。相机未公布标准拍摄但公布 Nikon `0x9207` 时使用该拍摄扩展（真机待测）。

没有后台服务或断点续传；相机相册目前逐张手动保存原 JPG/NEF，手机导入可多选。用户反馈可用不能替代各种机身固件/手机 USB 组合的测试；本地模拟器不能证明 Z50 自动 ISO、相机目录和无线硬件兼容性，也不能测量真实画面延迟。

## 参考

- [Android USB Host](https://developer.android.com/develop/connectivity/usb/host)
- [USB bulkTransfer 大小与 API 版本](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)
- [最新帧缓冲语义](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/-factory/-c-o-n-f-l-a-t-e-d.html)
- [应用自有 MediaStore 媒体访问](https://developer.android.com/training/data-storage/shared/media)
- [libgphoto2 Nikon 操作定义](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)

Kotlin 实现自行编写，没有嵌入 libgphoto2 源码实现。

