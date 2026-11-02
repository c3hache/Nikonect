# Nikonect 0.4.0 构建与查看记录

日期：2026-10-17；包名 dev.nikonect，版本0.4.0/code5，Android10/API29起。

- assembleDebug 离线构建成功，最后一轮修正了相册横屏文件夹卡片的布局。
- 全量编译提示2处 ConnectivityManager.allNetworks 弃用；后续增量构建无编译错误。
- 本轮未新建或运行 JVM、仪器、lint 测试套件。旧版22项测试不能作为本版通过记录。
- 已在模拟器覆盖安装并启动本版，查看底部导航、相册分类和页面恢复；修正旋转时保留滑页小数偏移的问题：重建后按历史栈对齐完整页面，只记录停止滑动后的整页。
- 未对用户照片执行移入垃圾桶、恢复或永久删除实验；Android10兼容路径和Android11+系统垃圾桶仍需在真实手机复测。
- Z50自动ISO实际值更新、目录对象筛选、缩略图、原图预览、NEF配对及无线兼容性待真机验证。

构建：work/gradle-9.4.1/bin/gradle.bat :app:assembleDebug --offline --no-daemon --console=plain
JAVA_HOME：Android Studio/jbr；GRADLE_USER_HOME：项目.gradle-user-home。

交付：outputs/Nikonect-0.4.0-debug.apk、同版本source.zip及说明。通用APK/source副本更新为本版，旧版本保留。

最终 APK：25,908,447 字节；SHA-256：268DD0ED061875BF6082299EBAB927FE0FB8AF9EB55A15398714209E75D952E8。
