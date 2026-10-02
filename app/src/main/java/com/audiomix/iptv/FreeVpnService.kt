package com.audiomix.iptv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * Real Android VpnService backed by sing-box libbox.
 *
 * The VPN profile is fetched from a public GitHub project that publishes
 * tested free sing-box configurations. The IPTV app traffic is allowed into
 * the TUN; libbox protects its own outbound sockets to avoid a VPN loop.
 */
class FreeVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        private const val TAG = "FreeVpnService"
        private const val CHANNEL_ID = "audiomix_free_vpn"
        private const val NOTIFICATION_ID = 9101
        private const val CONFIG_URL =
            "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/singbox.json"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: android.content.Context) {
            val intent = Intent(context, FreeVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, FreeVpnService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("جاري تشغيل VPN مجاني..."))
        scope.launch {
            runCatching { startTunnel() }
                .onFailure {
                    Log.e(TAG, "VPN start failed", it)
                    running = false
                    stopTunnel()
                    stopSelf(startId)
                }
        }
        return START_NOT_STICKY
    }

    private suspend fun startTunnel() {
        if (prepare(this) != null) error("VPN permission is not granted")

        val config = fetchConfig()
        ensureLibbox()

        commandServer?.closeService()
        commandServer?.close()
        commandServer = CommandServer(this, this)
        commandServer!!.start()
        commandServer!!.checkConfig(config)
        commandServer!!.startOrReloadService(config, OverrideOptions())

        running = true
        updateNotification("VPN مجاني متصل — sing-box")
        Log.i(TAG, "Free VPN tunnel started")
    }

    private fun fetchConfig(): String {
        val connection = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AudioMixIPTV-FreeVPN/1.0")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                error("Free VPN config HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .also { if (it.length < 500) error("Free VPN config is empty") }
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureLibbox() {
        if (libboxReady) return
        synchronized(FreeVpnService::class.java) {
            if (libboxReady) return
            val base = filesDir.absolutePath
            val work = getDir("singbox", MODE_PRIVATE).absolutePath
            val temp = cacheDir.absolutePath
            val options = SetupOptions().apply {
                setBasePath(base)
                setWorkingPath(work)
                setTempPath(temp)
                setFixAndroidStack(true)
            }
            Libbox.setup(options)
            libboxReady = true
            Log.i(TAG, "libbox ready: ${Libbox.version()}")
        }
    }

    private fun stopTunnel() {
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunFd?.close() }
        tunFd = null
        running = false
    }

    override fun onDestroy() {
        stopTunnel()
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("VPN permission revoked")

        val builder = Builder()
            .setSession("AudioMix IPTV Free VPN")
            .setMtu(if (options.mtu > 0) options.mtu else 9000)

        val a4 = options.inet4Address
        while (a4.hasNext()) {
            val p = a4.next()
            builder.addAddress(p.address(), p.prefix())
        }
        val a6 = options.inet6Address
        while (a6.hasNext()) {
            val p = a6.next()
            builder.addAddress(p.address(), p.prefix())
        }

        val r4 = options.inet4RouteAddress
        var has4 = false
        while (r4.hasNext()) {
            val p = r4.next()
            if (p.prefix() == 0) has4 = true
            builder.addRoute(p.address(), p.prefix())
        }
        val r6 = options.inet6RouteAddress
        var has6 = false
        while (r6.hasNext()) {
            val p = r6.next()
            if (p.prefix() == 0) has6 = true
            builder.addRoute(p.address(), p.prefix())
        }
        if (!has4) builder.addRoute("0.0.0.0", 0)
        if (!has6) builder.addRoute("::", 0)

        runCatching {
            val dns = options.getDNSServerAddress()
            if (dns != null) builder.addDnsServer(dns.value)
        }
        builder.addDnsServer("1.1.1.1")

        tunFd?.close()
        tunFd = builder.establish()
            ?: error("VpnService.Builder.establish() returned null")

        Log.i(TAG, "TUN established fd=${tunFd!!.fd}")
        return tunFd!!.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) error("protect($fd) failed")
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): io.nekohasekai.libbox.ConnectionOwner? = io.nekohasekai.libbox.ConnectionOwner()

    override fun readWIFIState(): WIFIState? = null

    override fun getInterfaces(): NetworkInterfaceIterator? {
        val list = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        runCatching {
            val all = NetworkInterface.getNetworkInterfaces()
            while (all.hasMoreElements()) {
                val ni = all.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual || ni.mtu <= 0) continue
                if (ni.name.startsWith("tun") || ni.name.startsWith("wg")) continue
                val out = io.nekohasekai.libbox.NetworkInterface().apply {
                    name = ni.name
                    index = ni.index
                    mtu = ni.mtu
                    type = when {
                        ni.name.startsWith("wlan") -> io.nekohasekai.libbox.InterfaceTypeWIFI
                        ni.name.startsWith("rmnet") || ni.name.startsWith("ccmni") -> io.nekohasekai.libbox.InterfaceTypeCellular
                        ni.name.startsWith("eth") -> io.nekohasekai.libbox.InterfaceTypeEthernet
                        else -> io.nekohasekai.libbox.InterfaceTypeOther
                    }
                    addresses = StringList(ni.interfaceAddresses.mapNotNull(::toPrefix))
                    dnsServer = StringList(emptyList())
                }
                list.add(out)
            }
        }.onFailure { Log.w(TAG, "interface enumeration failed", it) }

        if (list.isEmpty()) return null
        return object : NetworkInterfaceIterator {
            private val iterator = list.iterator()
            override fun hasNext() = iterator.hasNext()
            override fun next() = iterator.next()
        }
    }

    private fun toPrefix(a: InterfaceAddress): String? {
        val address = a.address ?: return null
        val bits = when (address) {
            is Inet4Address -> 32
            is Inet6Address -> 128
            else -> return null
        }
        val host = address.hostAddress.substringBefore('%')
        val prefix = a.networkPrefixLength.toInt()
        return if (prefix in 0..bits) "$host/$prefix" else null
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
    override fun clearDNSCache() {}
    override fun systemCertificates(): StringIterator? = null
    override fun sendNotification(notification: LibboxNotification?) {}
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport? = null

    override fun serviceReload() {}
    override fun serviceStop() {
        running = false
        stopTunnel()
    }
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, "[libbox] ${message.orEmpty()}")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AudioMix VPN", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioMix IPTV")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private class StringList(private val items: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext() = index < items.size
        override fun len() = items.size
        override fun next() = items[index++]
    }

    private var libboxReady = false
}
