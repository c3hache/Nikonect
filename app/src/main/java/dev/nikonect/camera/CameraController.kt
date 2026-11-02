package dev.nikonect.camera

import android.app.Application
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.MediaStore
import android.net.Uri
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nikonect.ptp.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import dev.nikonect.media.MediaLibrary
import dev.nikonect.media.ImportedPhoto
import dev.nikonect.media.PhotoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.util.UUID
import java.io.File

private const val PERMISSION = "dev.nikonect.USB_PERMISSION"
data class CameraState(
    val devices: List<UsbDevice> = emptyList(), val connected: Boolean = false,
    val info: DeviceInfo? = null, val status: String = "请在参数页连接相机", val working: Boolean = false,
    val live: Boolean = false, val settings: AppSettings = AppSettings(), val properties: List<CameraProperty> = emptyList(),
    val downloaded: Int = 0, val transferring: Boolean = false, val logs: List<String> = emptyList(),
    val transport: String = "USB", val lastParameterRead: Long = 0L, val parameterWarning: String? = null
)
class CameraController(private val app: Application) : AndroidViewModel(app) {
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val preferences = app.getSharedPreferences("nikonect_settings", Context.MODE_PRIVATE)
    private val library = MediaLibrary(app)
    private val mutableState = MutableStateFlow(CameraState(settings = AppSettings.load(preferences)))
    private val mutablePreview = MutableStateFlow<PreviewFrame?>(null)
    val preview = mutablePreview.asStateFlow()
    private val mutableGallery = MutableStateFlow<List<ImportedPhoto>>(emptyList())
    val gallery = mutableGallery.asStateFlow()
    private val mutableCameraListing = MutableStateFlow(CameraListing())
    val cameraListing = mutableCameraListing.asStateFlow()
    private var mediaToken = UUID.randomUUID().toString()
    private val mediaDownloads = app.getSharedPreferences("nikonect_downloads", Context.MODE_PRIVATE)
    val state = mutableState.asStateFlow()
    private val cameraLock = Mutex()
    @Volatile private var session: PtpSession? = null
    private var connectedDevice: UsbDevice? = null
    private var requestedDevice: UsbDevice? = null
    private var liveJob: Job? = null
    private var importJob: Job? = null
    private var propertyJob: Job? = null
    private var connecting = false
    private var connectJob: Job? = null
    private val knownHandles = mutableSetOf<Int>()
    private val failedHandles = mutableSetOf<Int>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PERMISSION -> {
                    val device = requestedDevice ?: return
                    requestedDevice = null
                    if (usb.hasPermission(device)) connect(device) else report("未获得 USB 访问权限")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (connectedDevice != null && connectedDevice?.deviceName !in usb.deviceList.keys) disconnect()
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevices()
            }
        }
    }

    init {
        val filter = IntentFilter(PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED) }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshDevices(); refreshGallery()
    }

    fun refreshDevices() {
        val devices = usb.deviceList.values.filter { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE } }
        mutableState.update { it.copy(devices = devices) }
    }
    private fun report(text: String) {
        mutableState.update { it.copy(status = text, logs = (listOf("${LocalTime.now().withNano(0)} $text") + it.logs).take(100)) }
    }
    private fun requireOperation(op: Int) { check(state.value.info?.operations?.contains(op) == true) { "相机未声明支持操作 0x%04X".format(op) } }

    private suspend fun openSession(s: PtpSession) {
        for (attempt in 0..3) {
            try { s.execute(Ptp.OPEN_SESSION, intArrayOf(1)); return }
            catch (e: PtpError) {
                when {
                    e.response == 0x201E && attempt == 0 -> {
                        report("相机报告旧会话未关闭，正在关闭旧会话后重新连接")
                        s.closeReportedOldSession()
                    }
                    e.response == Ptp.BUSY && attempt < 3 -> delay(250)
                    else -> throw e
                }
            }
        }
        error("无法打开相机会话")
    }

    fun connect(device: UsbDevice) {
        if (connecting || session != null) return
        if (!usb.hasPermission(device)) {
            requestedDevice = device
            val intent = Intent(PERMISSION).setPackage(app.packageName)
            usb.requestPermission(device, PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            report("发现 USB 相机，请允许访问；尚未建立相机会话")
            return
        }
        launchConnection(device, "USB") { UsbPtpSession(usb, device) }
    }

    // The challenge never enters persisted state, diagnostic logs or the pairing cache.
    val pairingPrompt = MutableStateFlow<Int?>(null)
    private var pairingInput: CompletableDeferred<String>? = null
    fun submitPairing(value: String) { pairingInput?.complete(value) }
    fun cancelWireless() { connectJob?.cancel() }

    private suspend fun confirmPairing(code: String?) {
        withContext(Dispatchers.Main.immediate) {
            val answer = CompletableDeferred<String>()
            pairingInput = answer
            pairingPrompt.value = code?.length ?: 0
            try {
                report(if (code != null) "等待核对机身配对码" else "等待在相机上按 OK 完成配对")
                val value = withTimeoutOrNull(120000) { answer.await() } ?: error("配对确认超时，请重试")
                check(code == null || value == code) { "配对码与机身不一致，请重新配对" }
            } finally {
                pairingInput = null
                pairingPrompt.value = null
            }
        }
    }

    fun discoverWifi(hotspot: Boolean, wtu: Boolean) {
        if (connecting || session != null) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val guid = preferences.getString("wifiGuid", null) ?: UUID.randomUUID().toString().replace("-", "").also { preferences.edit().putString("wifiGuid", it).apply() }
        val bytes = guid.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        launchConnection(null, if (hotspot) "手机热点" else "相机热点") {
            CameraDiscovery.open(cm, hotspot, bytes, wtu, { preferences.getBoolean("wtu:$it", false) }, ::confirmPairing, ::report)
        }
    }

    private fun launchConnection(device: UsbDevice?, transport: String, open: suspend () -> PtpSession) {
        connecting = true
        mutableState.update { it.copy(working = true, transport = transport) }
        connectJob = viewModelScope.launch {
            var opened: PtpSession? = null
            var stage = "打开 $transport 通道"
            try {
                report(stage)
                val s = withContext(Dispatchers.IO) { open() }; opened = s
                stage = "读取相机信息 GetDeviceInfo 0x1001"
                report("$stage · ${s.transportDescription}")
                val info = DeviceInfo.decode(s.execute(Ptp.DEVICE_INFO, dataIn = true))
                stage = "打开相机会话 OpenSession 0x1002"
                report(stage); openSession(s)
                ensureActive()
                if (s is WifiPtpSession && s.pairingVerified) {
                    check(info.manufacturer.contains("Nikon", true)) { "配对后未能确认尼康相机身份" }
                    preferences.edit().putBoolean("wtu:${s.peerId}", true).apply()
                }
                connectedDevice = device; session = s
                mediaToken = UUID.randomUUID().toString(); mutableCameraListing.value = CameraListing()
                mutableState.update { it.copy(connected = true, info = info, properties = emptyList()) }
                report("已连接 ${info.manufacturer} ${info.model}，固件 ${info.version}")
                mutablePreview.value = null; knownHandles.clear(); failedHandles.clear()
                // Optional feature initialization cannot turn a successful handshake into a generic failure.
                try {
                    knownHandles.addAll(cameraLock.withLock { handles(s) }); startImporter(s)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!s.healthy) throw e
                    report("相机已连接；传图初始化未完成：${e.message}")
                }
                stage = "读取曝光参数"
                if (info.manufacturer.contains("Nikon", true) && 0x90CA in info.operations) {
                    try {
                        val vendor = cameraLock.withLock { Reader(s.execute(0x90CA, dataIn = true)).array16() }
                        mutableState.update { it.copy(info = info.copy(properties = info.properties + vendor)) }
                    } catch (e: PtpError) { report("扩展参数列表未取得：${e.message}") }
                }
                try { publishProperties(cameraLock.withLock { loadProperties(s) }) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!s.healthy) throw e
                    mutableState.update { it.copy(parameterWarning = "参数尚未同步：${e.message}") }
                    report("相机已连接，参数稍后重试：${e.message}")
                }
                check(s.healthy) { "参数读取期间连接失效" }
                startPropertySync(s)
            } catch (e: CancellationException) {
                opened?.close(); if (session === opened) session = null
                throw e
            } catch (e: Exception) {
                importJob?.cancel(); propertyJob?.cancel()
                withContext(NonCancellable) { if (opened?.healthy == true) runCatching { opened.execute(Ptp.CLOSE_SESSION) } }
                opened?.close(); session = null; connectedDevice = null
                mutableState.update { it.copy(connected = false, info = null, properties = emptyList(), lastParameterRead = 0L) }
                report("连接失败 [$stage]：${e.message}")
            } finally {
                connecting = false; mutableState.update { it.copy(working = false) }
                if (state.value.connected && state.value.settings.autoStartLive) startLiveView()
            }
        }
    }
    fun disconnect() {
        connectJob?.cancel(); requestedDevice = null
        val s = session
        val attached = connectedDevice == null || connectedDevice?.deviceName in usb.deviceList.keys
        val live = liveJob; val importer = importJob; val sync = propertyJob
        live?.cancel(); importer?.cancel(); sync?.cancel()
        session = null; connectedDevice = null
        mutableState.update { it.copy(connected = false, live = false, info = null, properties = emptyList(), lastParameterRead = 0L) }
        mutablePreview.value = null
        mutableCameraListing.value = CameraListing(); mediaToken = UUID.randomUUID().toString()
        if (s == null || !attached) { s?.close(); report("连接已断开"); return }
        connecting = true
        mutableState.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                live?.join(); importer?.join(); sync?.join()
                runCatching { s.execute(Ptp.CLOSE_SESSION) }
            } finally {
                s.close(); connecting = false
                mutableState.update { it.copy(working = false) }
                report("相机会话已关闭")
            }
        }
    }

    private fun action(block: suspend (PtpSession) -> Unit) {
        val s = session ?: return
        if (state.value.working) return
        mutableState.update { it.copy(working = true) }
        viewModelScope.launch {
            try { cameraLock.withLock { block(s) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report("操作失败：${e.message}") }
            finally { mutableState.update { it.copy(working = false) } }
        }
    }

    private suspend fun handles(s: PtpSession): Set<Int> {
        requireOperation(Ptp.STORAGE_IDS); requireOperation(Ptp.OBJECT_HANDLES)
        val stores = Reader(s.execute(Ptp.STORAGE_IDS, dataIn = true)).array32()
        return stores.flatMap { Reader(s.execute(Ptp.OBJECT_HANDLES, intArrayOf(it, 0, 0), dataIn = true)).array32() }.toSet()
    }

    private fun startImporter(s: PtpSession) {
        importJob?.cancel()
        importJob = viewModelScope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive && session === s) {
                delay(state.value.settings.pollSeconds * 1000L)
                // Do not scan the entire card or download files during latency-priority monitoring.
                if (ImportPolicy.deferForLive(state.value.live, state.value.settings.prioritizeLive)) continue
                try {
                    cameraLock.withLock {
                        if (ImportPolicy.deferForLive(state.value.live, state.value.settings.prioritizeLive)) return@withLock
                        val found = handles(s)
                        val added = found - knownHandles - failedHandles
                        if (state.value.settings.autoImport) {
                            val pending = added.mapNotNull { handle ->
                                try {
                                    val info = ObjectInfo.decode(s.execute(Ptp.OBJECT_INFO, intArrayOf(handle), dataIn = true))
                                    if (!ImportPolicy.accept(info.filename, state.value.settings.importNef)) { knownHandles.add(handle); null }
                                    else handle to info
                                } catch (e: PtpError) {
                                    if (e.response == Ptp.BUSY) throw e
                                    failedHandles.add(handle); report("对象元数据未读取：${e.message}"); null
                                }
                            }.sortedBy { if (ImportPolicy.isJpeg(it.second.filename)) 0 else 1 }
                            for ((handle, info) in pending) {
                                try { importObject(s, handle, info); knownHandles.add(handle) }
                                catch (e: PtpError) {
                                    if (e.response == Ptp.BUSY) throw e
                                    failedHandles.add(handle); report("${info.filename} 未导入：${e.message}")
                                }
                            }
                        } else knownHandles.addAll(found)
                        failures = 0
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: PtpError) { if (e.response != Ptp.BUSY) { report("自动传图：${e.message}"); failures++ } }
                catch (e: Exception) { report("自动传图：${e.message}"); failures++ }
                if (failures >= 3) { report("自动传图连续失败，请重新连接相机"); break }
            }
        }
    }

    private fun cameraIdentity(): String {
        val info = state.value.info ?: error("相机未连接")
        val serial = info.serial.ifBlank { runCatching { connectedDevice?.serialNumber }.getOrNull() ?: mediaToken }
        return "${info.manufacturer}|${info.model}|$serial"
    }
    private fun source(info: ObjectInfo) = PhotoSource(cameraIdentity(), info.storage, info.parent, info.filename, info.size)

    private suspend fun importObject(s: PtpSession, handle: Int, info: ObjectInfo, manual: Boolean = false) {
        requireOperation(Ptp.GET_OBJECT)
        // Re-evaluate the preference immediately before starting a RAW download.
        if (!manual && !ImportPolicy.accept(info.filename, state.value.settings.importNef)) return
        val origin = source(info)
        if (manual && ImportPolicy.isJpeg(info.filename)) {
            val existing = library.loadJpegs().find { it.source == origin }
            if (existing != null) { report(if (existing.trashed) "此 JPG 已在垃圾桶，可恢复" else "此 JPG 已在手机相册"); return }
        }
        val downloadKey = origin.toString()
        if (manual && info.filename.endsWith(".nef", true)) {
            val existing = mediaDownloads.getString(downloadKey, null)
            if (existing != null && runCatching { app.contentResolver.openFileDescriptor(Uri.parse(existing), "r")?.use { true } == true }.getOrDefault(false)) {
                report("此 NEF 已保存在 Download/Nikonect/NEF"); return
            }
        }
        mutableState.update { it.copy(transferring = true) }
        try {
            val uri = library.save(info, origin = origin) { stream -> s.execute(Ptp.GET_OBJECT, intArrayOf(handle), dataIn = true, sink = stream) }
            mediaDownloads.edit().putString(downloadKey, uri.toString()).apply()
            mutableState.update { it.copy(downloaded = it.downloaded + 1) }
            if (ImportPolicy.isJpeg(info.filename)) refreshGallery()
            report("已接收 ${info.filename}")
        } finally { mutableState.update { it.copy(transferring = false) } }
    }

    fun refreshGallery() {
        viewModelScope.launch(Dispatchers.IO) {
            try { mutableGallery.value = library.loadJpegs() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report("相册读取失败：${e.message}") }
        }
    }

    fun deletePhotos(photos: List<ImportedPhoto>, onComplete: (Boolean) -> Unit = {}) {
        changePhotos(photos, "trash", onComplete)
    }
    fun changePhotos(photos: List<ImportedPhoto>, operation: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val result = library.change(photos, operation)
                mutableGallery.value = library.loadJpegs()
                val label = when (operation) { "trash" -> "移入垃圾桶"; "restore" -> "恢复"; else -> "永久删除" }
                report("$label ${result.completed} 张${if (result.failed > 0) "，${result.failed} 张未完成" else ""}")
                onComplete(result.failed == 0)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { refreshGallery(); report("相册操作失败：${e.message}"); onComplete(false) }
        }
    }
    private suspend fun pauseForMedia() {
        if (state.value.live) {
            liveJob?.cancelAndJoin()
            mutableState.update { it.copy(live = false) }
            report("已停止监看，正在读取相机照片")
        }
    }
    private suspend fun objectInfo(s: PtpSession, handle: Int) = ObjectInfo.decode(s.execute(Ptp.OBJECT_INFO, intArrayOf(handle), dataIn = true))
    private suspend fun folderObjects(s: PtpSession, storage: Int, parent: Int): List<Pair<Int, ObjectInfo>> {
        val filter = if (parent == 0) -1 else parent
        val ids = try { Reader(s.execute(Ptp.OBJECT_HANDLES, intArrayOf(storage, 0, filter), dataIn = true)).array32() }
                  catch (e: PtpError) { if (e.response !in setOf(0x2014, 0x201D)) throw e; Reader(s.execute(Ptp.OBJECT_HANDLES, intArrayOf(storage, 0, 0), dataIn = true)).array32() }
        val result = mutableListOf<Pair<Int, ObjectInfo>>()
        for ((index, id) in ids.withIndex()) {
            val info = objectInfo(s, id)
            if ((info.storage == storage || storage == -1) && (info.parent == parent || parent == -1 && info.parent in setOf(0, -1))) result += id to info
            if (index % 25 == 0) { report("读取相机目录 ${index + 1} / ${ids.size}"); yield() }
        }
        return result
    }
    fun refreshCameraFolder() = browseCamera(mutableCameraListing.value.path)
    fun openCameraFolder(entry: CameraEntry) {
        if (!entry.folder || entry.token != mediaToken) return
        browseCamera(mutableCameraListing.value.path + CameraFolder(entry.info.storage, entry.handle, entry.info.filename))
    }
    fun cameraFolderBack() = browseCamera(mutableCameraListing.value.path.dropLast(1))
    private fun browseCamera(path: List<CameraFolder>) = action { s ->
        pauseForMedia()
        val token = mediaToken
        mutableCameraListing.value = CameraListing(path = path, loading = true, status = "正在读取相机目录…")
        try {
            val entries = if (path.isEmpty()) {
                requireOperation(Ptp.STORAGE_IDS)
                Reader(s.execute(Ptp.STORAGE_IDS, dataIn = true)).array32().mapIndexed { index, storage ->
                    CameraEntry(token, -1, ObjectInfo(0x3001, 0, "存储卡 ${index + 1}", storage, 0), true)
                }
            } else {
                requireOperation(Ptp.OBJECT_HANDLES); requireOperation(Ptp.OBJECT_INFO)
                val folder = path.last()
                folderObjects(s, folder.storage, folder.handle).filter { it.second.format == 0x3001 || ImportPolicy.isJpeg(it.second.filename) }
                    .map { CameraEntry(token, it.first, it.second) }
                    .sortedWith(compareBy<CameraEntry> { !it.folder }.thenByDescending { it.info.filename })
            }
            if (session === s) mutableCameraListing.value = CameraListing(entries, path, status = "${entries.size} 项 · JPG 与文件夹；NEF 可在 JPG 中单独传输")
        } catch (e: Exception) {
            mutableCameraListing.value = CameraListing(path = path, status = "读取失败：${e.message}")
            throw e
        }
    }
    private suspend fun checkEntry(s: PtpSession, entry: CameraEntry) {
        check(entry.token == mediaToken && session === s && !entry.folder) { "相机连接已变化，请刷新相册" }
        val actual = objectInfo(s, entry.handle)
        check(actual == entry.info) { "相机照片已变化，请刷新目录" }
    }
    suspend fun cameraImage(entry: CameraEntry, full: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext null
        if (entry.token != mediaToken || state.value.live || state.value.working) return@withContext null
        try {
            val cache = File(app.cacheDir, "camera_${entry.token}_${entry.info.storage}_${entry.handle}_${if (full) "full" else "thumb"}.jpg")
            if (!cache.exists()) cameraLock.withLock {
                checkEntry(s, entry)
                if (full) {
                    requireOperation(Ptp.GET_OBJECT)
                    mutableState.update { it.copy(transferring = true) }
                    try { cache.outputStream().use { s.execute(Ptp.GET_OBJECT, intArrayOf(entry.handle), dataIn = true, sink = it) }; check(cache.length() == entry.info.size || entry.info.size == 0L) }
                    catch (e: Exception) { cache.delete(); throw e }
                    finally { mutableState.update { it.copy(transferring = false) } }
                } else {
                    if (state.value.info?.operations?.contains(Ptp.GET_THUMB) != true) return@withLock
                    cache.writeBytes(s.execute(Ptp.GET_THUMB, intArrayOf(entry.handle), dataIn = true))
                }
            }
            if (cache.exists()) MediaLibrary.fullImage(app, Uri.fromFile(cache)) else null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { report("相机图片读取失败：${e.message}"); null }
    }
    fun downloadCameraJpeg(entry: CameraEntry) = action { s ->
        pauseForMedia(); checkEntry(s, entry)
        check(ImportPolicy.isJpeg(entry.info.filename))
        importObject(s, entry.handle, entry.info, manual = true)
    }
    fun downloadCameraNef(entry: CameraEntry) = action { s ->
        pauseForMedia(); checkEntry(s, entry); downloadPairedNef(s, entry.info)
    }
    private suspend fun downloadPairedNef(s: PtpSession, jpeg: ObjectInfo) {
        val stem = jpeg.filename.substringBeforeLast('.')
        val matches = folderObjects(s, jpeg.storage, jpeg.parent).filter {
            it.second.filename.endsWith(".nef", true) && it.second.filename.substringBeforeLast('.').equals(stem, true)
        }
        check(matches.size == 1) { if (matches.isEmpty()) "该 JPG 同目录没有同名 NEF；相机需使用 RAW+JPG 拍摄" else "同目录存在多个同名 NEF，请检查存储卡" }
        importObject(s, matches.single().first, matches.single().second, manual = true)
    }
    fun downloadNefForPhoto(photo: ImportedPhoto) = action { s ->
        pauseForMedia()
        val origin = photo.source
        val jpeg = if (origin != null) {
            check(origin.camera == cameraIdentity()) { "照片来源与当前相机不一致；请在相机相册中选择对应 JPG" }
            val found = folderObjects(s, origin.storage, origin.parent).map { it.second }.filter { it.filename == origin.filename && it.size == origin.size }
            check(found.size == 1) { "相机卡内未找到原 JPG；请在相机相册重新选择" }; found.single()
        } else {
            // Old imports have no camera identity; require an unambiguous name AND byte-size match.
            val found = mutableListOf<ObjectInfo>()
            for (handle in handles(s)) {
                val info = objectInfo(s, handle)
                if (info.filename.equals(photo.name, true) && info.size == photo.bytes) found += info
            }
            check(found.size == 1) { "无法唯一匹配旧 JPG；请到相机相册选择原照片再传 NEF" }; found.single()
        }
        downloadPairedNef(s, jpeg)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val settings = transform(state.value.settings)
        mutableState.update { it.copy(settings = settings) }
        settings.save(preferences)
    }
    fun capture() = action { s ->
        val standardCapture = state.value.info?.operations?.contains(Ptp.CAPTURE) == true
        if (!standardCapture) requireOperation(Ptp.NIKON_CAPTURE_MEDIA)
        if (state.value.live) {
            liveJob?.cancelAndJoin()
            mutableState.update { it.copy(live = false) }
        }
        if (standardCapture) s.execute(Ptp.CAPTURE, intArrayOf(0, 0))
        else {
            check(state.value.info?.manufacturer?.contains("Nikon", true) == true)
            s.execute(Ptp.NIKON_CAPTURE_MEDIA, intArrayOf(-1, 0))
        }
        report("拍摄命令已接受；自动传图开启时将导入新照片")
    }

    private suspend fun readProperty(s: PtpSession, code: Int, label: String): CameraProperty {
        val desc = CameraProperty.decode(code, label, s.execute(Ptp.PROP_DESC, intArrayOf(code), dataIn = true))
        // Nikon descriptors may lag behind GetDevicePropValue after a dial change or a write.
        return if (state.value.info?.operations?.contains(Ptp.PROP_GET) == true) {
            val actual = Reader(s.execute(Ptp.PROP_GET, intArrayOf(code), dataIn = true)).scalar(desc.type)
            desc.copy(current = actual)
        } else desc
    }

    private suspend fun loadProperties(s: PtpSession): List<CameraProperty> {
        if (state.value.info?.operations?.contains(Ptp.PROP_DESC) != true) { report("相机未声明参数描述操作，参数面板不可用"); return emptyList() }
        val advertised = state.value.info?.properties.orEmpty()
        val selector = if (0xD1A6 in advertised) try { readProperty(s, 0xD1A6, "照片 / 视频") } catch (e: PtpError) { if (e.response != 0x200A) throw e else null } else null
        val movie = selector?.current?.value == 1L
        val base = listOf(0x500F to "ISO", 0x500D to "快门", 0x5007 to "光圈", 0x500E to "曝光模式", 0x5001 to "电池", 0x5010 to "曝光补偿", 0x5005 to "白平衡") +
            if (state.value.info?.manufacturer?.contains("Nikon", true) == true) CameraIso.properties else emptyList()
        val codes = if (!movie) base else base.filterNot { it.first in setOf(0xD054, 0xD0B5) }.map { (code, label) ->
            (movieCodes[code] ?: code) to label
        } + (0xD0AD to "视频自动 ISO")
        return listOfNotNull(selector) + codes.mapNotNull { (code, label) ->
            if (state.value.info?.properties?.contains(code) != true) null
            else try { readProperty(s, code, label) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!s.healthy || code in setOf(0x500F, 0x500D, 0x5007) || e is PtpError && e.response == Ptp.BUSY) throw e
                report("$label 读取失败：${e.message}"); null
            }
        }
    }
    private fun publishProperties(properties: List<CameraProperty>) {
        mutableState.update { it.copy(properties = properties, lastParameterRead = System.currentTimeMillis(), parameterWarning = null) }
    }
    private fun startPropertySync(s: PtpSession) {
        propertyJob?.cancel()
        propertyJob = viewModelScope.launch {
            var lastDescription = System.currentTimeMillis()
            while (isActive && session === s) {
                delay(if (state.value.live) 1800 else 900)
                if (state.value.working || state.value.transferring) continue
                try {
                    cameraLock.withLock {
                        if (session === s && !state.value.working) {
                            if (state.value.properties.isEmpty() || System.currentTimeMillis() - lastDescription >= 5000 || state.value.info?.operations?.contains(Ptp.PROP_GET) != true) {
                                publishProperties(loadProperties(s)); lastDescription = System.currentTimeMillis()
                            } else {
                                val previous = state.value.properties
                                val selector = previous.find { it.code == 0xD1A6 }
                                if (selector != null && Reader(s.execute(Ptp.PROP_GET, intArrayOf(selector.code), dataIn = true)).scalar(selector.type).value != selector.current.value) {
                                    publishProperties(loadProperties(s))
                                    lastDescription = System.currentTimeMillis()
                                    return@withLock
                                }
                                val properties = previous.map { p ->
                                    if (p === selector) return@map p
                                    val actual = Reader(s.execute(Ptp.PROP_GET, intArrayOf(p.code), dataIn = true)).scalar(p.type)
                                    p.copy(current = actual)
                                }
                                publishProperties(properties)
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: PtpError) { if (e.response != Ptp.BUSY) mutableState.update { it.copy(parameterWarning = "参数同步失败：${e.message}") } }
                catch (e: Exception) {
                    mutableState.update { it.copy(parameterWarning = "参数同步中断：${e.message}") }
                    if (!s.healthy) { report("连接中断，请重新连接：${e.message}"); disconnect(); break }
                }
            }
        }
    }
    suspend fun sdkSetting(code: Int): DeviceSetting {
        val s = session ?: error("相机已断开")
        return cameraLock.withLock {
            check(session === s && code in state.value.info!!.properties)
            DeviceSetting.decode(code, s.execute(Ptp.PROP_DESC, intArrayOf(code), dataIn = true))
        }
    }

    suspend fun applySdkSetting(expected: DeviceSetting, input: String): DeviceSetting {
        val s = session ?: error("相机已断开")
        return cameraLock.withLock {
            check(session === s && expected.code in state.value.info!!.properties)
            requireOperation(Ptp.PROP_SET)
            val args = intArrayOf(expected.code)
            suspend fun descriptor() = DeviceSetting.decode(expected.code, s.execute(Ptp.PROP_DESC, args, dataIn = true))
            val fresh = descriptor()
            check(fresh.type == expected.type && fresh.current == expected.current) { "机身参数已变化，请刷新后重试" }
            val payload = fresh.encode(input)
            s.execute(Ptp.PROP_SET, args, dataOut = payload)
            val updated = descriptor()
            if (Ptp.PROP_GET in state.value.info!!.operations)
                updated.copy(current = DeviceSetting.value(Reader(s.execute(Ptp.PROP_GET, args, dataIn = true)), updated.type))
            else updated
        }
    }

    fun refreshProperties() = action { s -> publishProperties(loadProperties(s)) }
    fun changeProperty(logicalCode: Int, direction: Int) = action { s ->
        val displayed = state.value.property(logicalCode) ?: error("当前模式无此参数")
        if (logicalCode in movieCodes && state.value.properties.any { it.code == 0xD1A6 }) {
            val movie = readProperty(s, 0xD1A6, "照片 / 视频").current.value == 1L
            if (movie != state.value.movieMode) {
                publishProperties(loadProperties(s))
                error("拍摄模式已切换，请重新调节")
            }
        }
        val code = displayed.code
        check(displayed.writable) { "当前模式下该参数只读" }
        requireOperation(Ptp.PROP_SET)
        val label = state.value.properties.first { it.code == code }.label
        val p = readProperty(s, code, label)
        if (code == 0x500F) {
            val fresh = loadProperties(s); publishProperties(fresh)
            check(CameraIso.automatic(fresh) != true) { "当前为自动 ISO，请在相机关闭自动 ISO 后再调节设定值" }
        }
        mutableState.update { it.copy(properties = it.properties.map { old -> if (old.code == code) p else old }) }
        check(p.writable) { "当前曝光模式下参数不可写" }
        val next = p.next(direction)
        if (next.value == p.current.value) { report("$label 已到相机报告的边界"); return@action }
        try { s.execute(Ptp.PROP_SET, intArrayOf(code), dataOut = next.encoded) }
        catch (e: PtpError) { publishProperties(loadProperties(s)); throw e }
        var refreshed = readProperty(s, code, label)
        for (attempt in 0..2) {
            if (refreshed.current.value == next.value) break
            delay(100); refreshed = readProperty(s, code, label)
        }
        publishProperties(loadProperties(s))
        report(if (refreshed.current.value == next.value) "$label 回读：${refreshed.display}" else "相机未采用该设置，$label 实际为 ${refreshed.display}；请检查镜头范围和曝光模式")
    }

    fun focusAt(x: Int, y: Int) = action { s ->
        check(state.value.live) { "请先打开监看" }
        requireOperation(0x9205)
        requireOperation(0x90C1)
        s.execute(0x9205, intArrayOf(x, y))
        s.execute(0x90C1)
        if (0x90C8 in state.value.info!!.operations) {
            repeat(16) {
                try {
                    s.execute(0x90C8)
                    report("机身已结束对焦操作")
                    return@action
                } catch (e: PtpError) {
                    if (e.response != Ptp.BUSY) throw e
                    delay(65)
                }
            }
        }
        report("已发送对焦请求")
    }

    fun startLiveView() = action { s ->
        if (state.value.live) return@action
        check(connectedDevice?.vendorId == 0x04B0 || state.value.info?.manufacturer?.contains("Nikon", true) == true) { "取景扩展仅适用于 Nikon" }
        val z50 = state.value.info?.model?.replace(" ", "")?.endsWith("Z50", true) == true
        var imageOp = if (z50 && 0x9428 in state.value.info!!.operations) 0x9428 else Ptp.LV_IMAGE
        if (!state.value.settings.experimentalLive) listOf(Ptp.LV_START, imageOp, Ptp.LV_END).forEach(::requireOperation)
        s.execute(Ptp.LV_START)
        mutableState.update { it.copy(live = true) }
        report("低延迟取景已启动")
        liveJob = viewModelScope.launch {
            val latest = Channel<EncodedFrame>(Channel.CONFLATED)
            try {
                coroutineScope {
                    val decodeJob = launch(Dispatchers.Default) {
                        val decoder = PreviewDecoder()
                        var histogram = IntArray(64)
                        var lastHistogram = 0L
                        var windowStart = System.nanoTime(); var count = 0; var fps = 0f
                        for (frame in latest) {
                            val decodeStart = System.nanoTime()
                            val bitmap = decoder.decode(frame.bytes, state.value.settings.previewSize)
                            val now = System.nanoTime()
                            if (state.value.settings.histogram && now - lastHistogram > 300_000_000L) {
                                histogram = PreviewDecoder.histogram(bitmap); lastHistogram = now
                            }
                            count++
                            if (now - windowStart >= 700_000_000L) {
                                fps = count * 1_000_000_000f / (now - windowStart); count = 0; windowStart = now
                            }
                            ensureActive()
                            if (session === s) mutablePreview.value = PreviewFrame(bitmap, histogram, fps,
                                ((now - frame.startedNanos) / 1_000_000).toInt(),
                                ((frame.receivedNanos - frame.startedNanos) / 1_000_000).toInt(),
                                ((now - decodeStart) / 1_000_000).toInt(), frame.receivedNanos, if (z50) Z50Focus.boxes(frame.bytes) else emptyList(), if (z50) Z50Focus.area(frame.bytes) else null)
                        }
                    }
                    try {
                        var busy = 0
                        while (isActive && session === s) {
                            val start = System.nanoTime()
                            try {
                                val data = cameraLock.withLock { s.execute(imageOp, dataIn = true) }
                                latest.trySend(EncodedFrame(data, start, System.nanoTime()))
                                busy = 0
                            } catch (e: PtpError) {
                                if (imageOp == 0x9428 && e.response == 0x2005 && Ptp.LV_IMAGE in state.value.info!!.operations) {
                                    imageOp = Ptp.LV_IMAGE
                                    continue
                                }
                                if (e.response != Ptp.BUSY || ++busy > 60) throw e
                                delay(35)
                            }
                            val remaining = FramePacing.remainingMillis(start, System.nanoTime(), state.value.settings.targetFps)
                            if (remaining > 0) delay(remaining) else yield()
                        }
                    } finally { latest.close(); decodeJob.cancelAndJoin() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report("取景停止：${e.message}") }
            finally {
                latest.cancel()
                withContext(NonCancellable) { runCatching { s.execute(Ptp.LV_END) } }
                if (session === s) {
                    mutableState.update { it.copy(live = false) }
                    report("监看已停止，可继续接收新照片")
                }
            }
        }
    }
    fun stopLiveView() { liveJob?.cancel() }
    fun exportDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = state.value
                val info = snapshot.info
                val content = buildString {
                    appendLine("Nikonect 0.7.1 / Android ${Build.VERSION.SDK_INT}")
                    appendLine("${info?.manufacturer} ${info?.model} ${info?.version}")
                    appendLine("Operations: " + info?.operations?.sorted()?.joinToString { "0x%04X".format(it) })
                    appendLine("Properties: " + info?.properties?.sorted()?.joinToString { "0x%04X".format(it) })
                    appendLine("USB read size: ${PtpStream.READ_BYTES} bytes (compatibility)")
                    appendLine("Transport: ${snapshot.transport}; ${session?.transportDescription}")
                    appendLine("Parameter read: ${snapshot.lastParameterRead}; ${snapshot.parameterWarning}")
                    snapshot.properties.forEach { p -> appendLine("0x%04X ${p.label} actual=${p.current.value} writable=${p.writable} range=${p.range} options=${p.options.joinToString { it.value.toString() }}".format(p.code)) }
                    appendLine("Settings: ${snapshot.settings}")
                    mutablePreview.value?.let { appendLine("Local pipeline ${it.pipelineMs} ms / USB request ${it.usbMs} ms / decode ${it.decodeMs} ms / FPS ${it.fps}; NOT optical end-to-end latency") }
                    snapshot.logs.forEach(::appendLine)
                }
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "Nikonect_${System.currentTimeMillis()}.txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Nikonect")
                }) ?: error("无法创建日志")
                app.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: error("无法保存日志")
                report("诊断日志已保存到 Download/Nikonect")
            } catch (e: Exception) { report("导出失败：${e.message}") }
        }
    }
    override fun onCleared() { liveJob?.cancel(); importJob?.cancel(); propertyJob?.cancel(); session?.close(); app.unregisterReceiver(receiver) }
}







