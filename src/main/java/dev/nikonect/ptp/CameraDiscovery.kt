package dev.nikonect.ptp

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Search only the selected local link. A listening port is only a candidate;
 * GetDeviceInfo must identify a Nikon before handing the session to the controller. */
internal object CameraDiscovery {
    private const val port = 15740
    private data class Link(val local: Inet4Address, val prefix: Int, val network: Network?, val gateway: InetAddress?)
    private fun links(cm: ConnectivityManager, hotspot: Boolean): List<Link> {
        val networks = cm.allNetworks
        if (!hotspot) return networks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { network ->
                val properties = cm.getLinkProperties(network) ?: return@flatMap emptyList()
                val gateway = properties.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
                properties.linkAddresses.mapNotNull { address ->
                    (address.address as? Inet4Address)?.let { Link(it, address.prefixLength, network, gateway) }
                }
            }
        val upstream = networks.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
        return NetworkInterface.getNetworkInterfaces().toList().filter { iface ->
            iface.isUp && !iface.isLoopback && iface.name !in upstream &&
                listOf("ap", "swlan", "wlan", "wifi", "softap", "br").any { iface.name.startsWith(it) }
        }.flatMap { iface -> iface.interfaceAddresses.mapNotNull { address ->
            (address.address as? Inet4Address)?.takeIf { it.isSiteLocalAddress }?.let { Link(it, address.networkPrefixLength.toInt(), null, null) }
        } }
    }
    suspend fun open(cm: ConnectivityManager, hotspot: Boolean, guid: ByteArray, wtu: Boolean,
        trusted: (String) -> Boolean, confirm: suspend (String?) -> Unit, status: (String) -> Unit): PtpSession {
        val links = links(cm, hotspot)
        check(links.isNotEmpty()) { if (hotspot) "未检测到手机热点，请开启热点并让相机加入" else "请在系统 Wi-Fi 中连接相机热点" }
        for (link in links) {
            val local = link.local.address.fold(0L) { n, b -> (n shl 8) or (b.toLong() and 255) }
            val mask = 0xffffffffL shl (32 - link.prefix.coerceIn(24, 30))
            val base = local and mask
            val end = base + (mask.inv() and 0xffffffffL)
            val hosts = (base + 1 until end).filter { it != local }.map { n -> (3 downTo 0).joinToString(".") { ((n ushr (8 * it)) and 255).toString() } }
            suspend fun identify(host: String): PtpSession? {
                fun connect() = WifiPtpSession(host, port, link.network, guid, link.local)
                val initial = try { connect() } catch (e: java.io.IOException) { return null }
                    catch (e: IllegalStateException) { return null }
                var owned: PtpSession? = initial
                try {
                    val info = try { DeviceInfo.decode(initial.execute(Ptp.DEVICE_INFO, dataIn = true)) }
                        catch (e: PtpError) { if (wtu) null else throw e }
                    if (info != null && !info.manufacturer.contains("Nikon", true)) return null
                    if (info == null && !(initial.peerName.contains("Nikon", true) || initial.peerName.replace(" ", "").startsWith("Z50", true))) return null
                    if (wtu || info?.operations?.contains(0x952B) == true) {
                        status("已发现相机，正在检查机身配对向导")
                        val paired = WtuPairing.negotiate(initial, wtu, trusted(initial.peerId), {
                            connect().also { fresh ->
                                if (fresh.peerId != initial.peerId) {
                                    fresh.close()
                                    error("重连时相机身份发生变化")
                                }
                            }
                        }, confirm, status) as WifiPtpSession
                        owned = paired
                        paired.pairingVerified = paired !== initial
                    }
                    return owned.also { owned = null }
                } finally { owned?.close() }
            }
            val gateway = link.gateway?.hostAddress
            if (gateway != null) identify(gateway)?.let { return it }
            for (batch in hosts.filter { it != gateway }.chunked(24)) {
                val candidates = coroutineScope { batch.map { host -> async(Dispatchers.IO) {
                    try {
                        (link.network?.socketFactory?.createSocket() ?: Socket()).use { probe ->
                            probe.bind(InetSocketAddress(link.local, 0))
                            probe.connect(InetSocketAddress(host, port), 450)
                        }
                        host
                    } catch (_: java.io.IOException) { null }
                } }.awaitAll().filterNotNull() }
                for (host in candidates) { currentCoroutineContext().ensureActive(); identify(host)?.let { return it } }
            }
        }
        error("未识别到可用的尼康 PTP/IP 相机。请保持相机无线连接开启；若机身等待 WTU，请保持在该页面并重新识别。")
    }
}
