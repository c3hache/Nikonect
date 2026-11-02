package dev.nikonect.camera

import dev.nikonect.ptp.ObjectInfo

data class CameraFolder(val storage: Int, val handle: Int, val name: String)
data class CameraEntry(val token: String, val handle: Int, val info: ObjectInfo, val card: Boolean = false) {
    val folder get() = card || info.format == 0x3001
    val key get() = "${info.storage}:$handle"
}
data class CameraListing(val entries: List<CameraEntry> = emptyList(), val path: List<CameraFolder> = emptyList(),
    val loading: Boolean = false, val status: String = "连接相机后点刷新，浏览存储卡目录")
