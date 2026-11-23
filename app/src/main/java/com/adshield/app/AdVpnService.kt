package com.adshield.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AdVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var executor: ExecutorService? = null

    @Volatile
    private var active = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (active) return
        Blocklist.load(this)
        AdStats.reset()

        val pfd = try {
            Builder()
                .setSession("AdShield")
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(VPN_DNS)
                .addRoute(VPN_DNS, 32) // only DNS traffic goes through us
                .setMtu(1500)
                .establish()
        } catch (e: Exception) {
            null
        }

        if (pfd == null) {
            running = false
            stopSelf()
            return
        }

        tun = pfd
        active = true
        running = true
        executor = Executors.newFixedThreadPool(8)
        worker = Thread { loop(pfd) }.also { it.start() }
    }

    private fun stopVpn() {
        active = false
        running = false
        try {
            tun?.close()
        } catch (e: Exception) {
        }
        tun = null
        executor?.shutdownNow()
        executor = null
        worker = null
    }

    private fun loop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (active) {
                val len = input.read(buf)
                if (len < 0) break
                if (len == 0) {
                    Thread.sleep(10)
                    continue
                }
                handle(buf, len, output)
            }
        } catch (e: Exception) {
            // tunnel closed or interrupted
        } finally {
            if (active) {
                stopVpn()
                stopSelf()
            }
        }
    }

    private fun handle(buf: ByteArray, len: Int, output: FileOutputStream) {
        val q = DnsPacket.parseUdpDns(buf, len) ?: return
        val question = DnsPacket.parseQuestion(q.dns) ?: return
        AdStats.total.incrementAndGet()

        if (Blocklist.isBlocked(question.name)) {
            AdStats.addBlocked(question.name)
            reply(q, DnsPacket.blockedResponse(q.dns, question), output)
        } else {
            val ex = executor ?: return
            try {
                ex.execute { forward(q, output) }
            } catch (e: Exception) {
            }
        }
    }

    private fun reply(q: UdpDnsQuery, dns: ByteArray, output: FileOutputStream) {
        val pkt = DnsPacket.buildUdpIpv4(q.dstIp, q.srcIp, q.dstPort, q.srcPort, dns)
        synchronized(output) {
            try {
                output.write(pkt)
            } catch (e: IOException) {
            }
        }
    }

    private fun forward(q: UdpDnsQuery, output: FileOutputStream) {
        for (server in UPSTREAM) {
            try {
                val socket = DatagramSocket()
                try {
                    protect(socket) // keep this socket outside the tunnel
                    socket.soTimeout = 3000
                    val addr = InetAddress.getByAddress(server)
                    socket.send(DatagramPacket(q.dns, q.dns.size, addr, 53))
                    val respBuf = ByteArray(4096)
                    val resp = DatagramPacket(respBuf, respBuf.size)
                    socket.receive(resp)
                    reply(q, respBuf.copyOf(resp.length), output)
                    return
                } finally {
                    socket.close()
                }
            } catch (e: Exception) {
                // try the next upstream server
            }
        }
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.adshield.app.START"
        const val ACTION_STOP = "com.adshield.app.STOP"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VPN_DNS = "10.111.222.2"
        private val UPSTREAM = listOf(
            byteArrayOf(1, 1, 1, 1),
            byteArrayOf(8, 8, 8, 8)
        )

        @Volatile
        var running = false
    }
}
