# Nikon Z50 真机验证清单

目标为第一代 Z50，不能用 Z50 II 的固件或协议行为代替验证。记录机身型号、固件版本、手机型号、Android 版本及数据线/转接头配置。

## 操作与当前状态

| 操作/属性 | 编码 | 原型状态 | Z50 证据要求 |
|---|---|---|---|
| GetDeviceInfo/OpenSession/CloseSession | 0x1001/0x1002/0x1003 | 已实现标准容器与调用 | 成功返回型号、能力、session |
| GetStorageIDs/GetObjectHandles | 0x1004/0x1007 | 已实现基线与扫描 | 卡片可读、数量一致 |
| GetObjectInfo/GetObject | 0x1008/0x1009 | 已实现流式写入及大小检查 | JPG/NEF 与卡片原文件内容一致 |
| InitiateCapture | 0x100E | 已实现；无自动 vendor fallback | 不同模式触发拍摄及产生新句柄 |
| GetDevicePropDesc/SetDevicePropValue | 0x1014/0x1016 | 已实现整数枚举/范围和回读 | ISO/快门/光圈可写模式及实际曝光 |
| ISO/Shutter/FNumber 标准属性 | 0x500F/0x500D/0x5007 | 默认只访问 DeviceInfo 公布的属性 | Z50 的类型、枚举、写入与回读 |
| Nikon StartLiveView | 0x9201 | 已实现实验调用，无参数 | 是否公布、启动前提、返回码 |
| Nikon GetLiveViewImg | 0x9203 | 已实现请求及启发式 JPEG 解码 | 数据头格式、画幅、稳定帧率 |
| Nikon EndLiveView | 0x9202 | 已实现退出清理 | 停止后相机状态恢复 |
| Nikon GetEvent/DeviceReady | 0x90C7/0x90C8 | 未发送，后续适配候选 | 事件布局和 Busy 重试语义 |
| Nikon GetVendorPropCodes | 0x90CA | 未发送，后续适配候选 | Z50 扩展属性列表格式 |
| Nikon InitiateCaptureRecInMedia | 0x9207 | 未发送，后续适配候选 | 参数/对焦/目标介质语义需抓包确认 |

Nikon 编码参考：[libgphoto2 ptp.h](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)。这些定义证明存在对应扩展操作，不能证明 Z50 的特定固件接受相同调用。未嵌入 libgphoto2 的运行时代码。

## 第一轮检查

- [ ] USB 连接：允许/拒绝授权各一次，刷新设备，重连；确认显示 Nikon Z50 和正确固件。
- [ ] 使用 JPEG 单张、M 模式，先关闭取景；从 App 拍摄，检查相机新文件与手机相册文件。
- [ ] 在机身按快门，检查手机接收；若机身按钮被相机禁用则记录当前 session/模式。
- [ ] 调整 ISO、快门、光圈，检查相机显示、App 回读与实际拍摄 EXIF；验证只读状态能拒绝写入。
- [ ] 开启取景；若提示未公布操作，只在记录风险后打开实验选项；检查画面持续更新且停止后恢复。
- [ ] 取景时拍摄：确认取景结束、相机完成拍摄、新对象下载。
- [ ] RAW+JPEG：确认两个新对象都到达，核对大小/哈希及格式，不将 RAW 重命名成 JPEG。
- [ ] 传图时拔线/手机空间不足，确认未发布残缺文件，重新连接恢复。
- [ ] 相机休眠、Busy、连续拍摄、多个存储目录、禁止 USB 权限、旋转屏幕。

在 App 点击“导出诊断”，获取 Download/Nikonect 下文本日志。日志包含设备公布的操作/属性代码和最近状态，不包含照片内容或机身序列号。不要把无帧显示直接判断成不支持 USB；先区分不支持操作、相机忙碌、厂商帧头不匹配等情况。

## 0.2.0 追加验证

用户已反馈 0.1.0 可以在其真实手机使用，但监看延迟较大，具体手机型号和相机固件尚未记录。0.2.0 需要额外复测：

- [ ] 监看优先开启后，取景期间无存储扫描或整文件下载争用；停止取景后接收期间新增照片。
- [ ] 使用相同运动/时钟场景，对比 0.1.0 与 0.2.0。记录实际 FPS、USB 请求耗时、解码耗时；本机指标不能当作光学端到端延迟。
- [ ] JPEG+NEF 连拍，NEF 关闭时仅 JPG GetObject；NEF 开启时 JPG 优先、两种文件均正确保存，应用相册仍只显示 JPG。
- [ ] 调整帧率、预览尺寸、直方图并对比 CPU/发热与取景响应；低尺寸不改变 USB 图像数据量。
- [ ] 横竖屏切换不中断相机连接；进入后台按设置停止取景；覆盖更新保留原照片与设置。
