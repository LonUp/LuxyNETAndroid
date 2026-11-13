package com.example.xraycore

import android.net.VpnService
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * Coordinates VpnService.protect(...) across all outbound UDP/TCP/TLS/DoH sockets
 * (DNS pool resolvers, DNSTT/MasterDNS tunnels, Clean IP / WARP probes, and REALITY checks)
 * so traffic never loops back into the local TUN interface.
 */
object VpnSocketProtector {

    interface SocketProtectHook {
        fun onProtectSocket(socket: Socket): Boolean
        fun onProtectDatagram(socket: DatagramSocket): Boolean
        fun onProtectFd(fd: Int): Boolean
    }

    private val activeVpnService = AtomicReference<VpnService?>(null)
    private val testHook = AtomicReference<SocketProtectHook?>(null)
    private val protectedCount = AtomicInteger(0)

    fun attachService(service: VpnService) {
        activeVpnService.set(service)
    }

    fun detachService(service: VpnService? = null) {
        if (service == null || activeVpnService.get() === service) {
            activeVpnService.set(null)
        }
    }

    fun setTestHook(hook: SocketProtectHook?) {
        testHook.set(hook)
    }

    fun resetCounter() {
        protectedCount.set(0)
    }

    fun getProtectedCount(): Int = protectedCount.get()

    fun protect(socket: Socket): Boolean {
        val hook = testHook.get()
        val vpn = activeVpnService.get()
        val ok = when {
            hook != null -> hook.onProtectSocket(socket)
            vpn != null -> vpn.protect(socket)
            else -> true
        }
        if (hook != null || vpn != null) {
            protectedCount.incrementAndGet()
        }
        return ok
    }

    fun protect(socket: DatagramSocket): Boolean {
        val hook = testHook.get()
        val vpn = activeVpnService.get()
        val ok = when {
            hook != null -> hook.onProtectDatagram(socket)
            vpn != null -> vpn.protect(socket)
            else -> true
        }
        if (hook != null || vpn != null) {
            protectedCount.incrementAndGet()
        }
        return ok
    }

    fun protectFd(fd: Int): Boolean {
        if (fd < 0) return false
        val hook = testHook.get()
        val vpn = activeVpnService.get()
        val ok = when {
            hook != null -> hook.onProtectFd(fd)
            vpn != null -> vpn.protect(fd)
            else -> true
        }
        if (hook != null || vpn != null) {
            protectedCount.incrementAndGet()
        }
        return ok
    }

    fun createProtectedSocket(): Socket = Socket().also { protect(it) }

    fun createProtectedDatagramSocket(): DatagramSocket = DatagramSocket().also { protect(it) }

    /**
     * OkHttp-compatible SocketFactory that automatically calls VpnService.protect(...)
     * on every created TCP socket before connection.
     */
    fun protectedOkHttpSocketFactory(): SocketFactory = object : SocketFactory() {
        private val delegate = getDefault()

        override fun createSocket(): Socket = delegate.createSocket().also { protect(it) }

        override fun createSocket(host: String?, port: Int): Socket =
            createProtectedSocket().apply {
                if (host != null) connect(java.net.InetSocketAddress(host, port))
            }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createProtectedSocket().apply {
                bind(java.net.InetSocketAddress(localHost, localPort))
                if (host != null) connect(java.net.InetSocketAddress(host, port))
            }

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            createProtectedSocket().apply {
                if (host != null) connect(java.net.InetSocketAddress(host, port))
            }

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            createProtectedSocket().apply {
                bind(java.net.InetSocketAddress(localAddress, localPort))
                if (address != null) connect(java.net.InetSocketAddress(address, port))
            }
    }
}
