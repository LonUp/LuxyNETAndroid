package com.example.xraycore

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

object VpnSocketProtector {
    private val serviceRef = AtomicReference<VpnService?>(null)

    fun attach(service: VpnService?) {
        serviceRef.set(service)
    }

    fun isAttached(): Boolean = serviceRef.get() != null

    fun protect(socket: Socket): Boolean {
        val svc = serviceRef.get() ?: return true
        return try {
            svc.protect(socket)
        } catch (_: Throwable) {
            false
        }
    }

    fun protect(socket: DatagramSocket): Boolean {
        val svc = serviceRef.get() ?: return true
        return try {
            svc.protect(socket)
        } catch (_: Throwable) {
            false
        }
    }

    fun protectFd(fd: Int): Boolean {
        val svc = serviceRef.get() ?: return true
        return try {
            svc.protect(fd)
        } catch (_: Throwable) {
            false
        }
    }

    fun protectDescriptor(pfd: ParcelFileDescriptor): Boolean = protectFd(pfd.fd)

    fun createProtectedSocketFactory(): SocketFactory = object : SocketFactory() {
        private val delegate = getDefault()

        @Throws(IOException::class)
        override fun createSocket(): Socket {
            val s = delegate.createSocket()
            protect(s)
            return s
        }

        @Throws(IOException::class)
        override fun createSocket(host: String, port: Int): Socket {
            val s = delegate.createSocket()
            protect(s)
            s.connect(java.net.InetSocketAddress(host, port), 10_000)
            return s
        }

        @Throws(IOException::class)
        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket {
            val s = delegate.createSocket()
            protect(s)
            s.bind(java.net.InetSocketAddress(localHost, localPort))
            s.connect(java.net.InetSocketAddress(host, port), 10_000)
            return s
        }

        @Throws(IOException::class)
        override fun createSocket(host: InetAddress, port: Int): Socket {
            val s = delegate.createSocket()
            protect(s)
            s.connect(java.net.InetSocketAddress(host, port), 10_000)
            return s
        }

        @Throws(IOException::class)
        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket {
            val s = delegate.createSocket()
            protect(s)
            s.bind(java.net.InetSocketAddress(localAddress, localPort))
            s.connect(java.net.InetSocketAddress(address, port), 10_000)
            return s
        }
    }
}
