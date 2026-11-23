package com.adshield.app

import java.io.ByteArrayOutputStream

class UdpDnsQuery(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val dns: ByteArray
)

class DnsQuestion(val name: String, val type: Int, val end: Int)

object DnsPacket {

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    /** Reads an IPv4 + UDP packet going to port 53 and returns its parts, or null if it is anything else. */
    fun parseUdpDns(p: ByteArray, len: Int): UdpDnsQuery? {
        if (len < 28) return null
        val version = (p[0].toInt() shr 4) and 0x0F
        if (version != 4) return null
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((p[9].toInt() and 0xFF) != 17) return null // not UDP
        val srcPort = u16(p, ihl)
        val dstPort = u16(p, ihl + 2)
        if (dstPort != 53) return null
        val udpLen = u16(p, ihl + 4)
        val end = minOf(len, ihl + udpLen)
        if (end <= ihl + 8) return null
        return UdpDnsQuery(
            p.copyOfRange(12, 16),
            p.copyOfRange(16, 20),
            srcPort,
            dstPort,
            p.copyOfRange(ihl + 8, end)
        )
    }

    /** Reads the first question (domain name + type) of a DNS query. */
    fun parseQuestion(dns: ByteArray): DnsQuestion? {
        if (dns.size < 17) return null
        if ((dns[2].toInt() and 0x80) != 0) return null // it's a response, not a query
        if (u16(dns, 4) < 1) return null
        var pos = 12
        val sb = StringBuilder()
        while (true) {
            if (pos >= dns.size) return null
            val l = dns[pos].toInt() and 0xFF
            if (l == 0) {
                pos++
                break
            }
            if ((l and 0xC0) != 0) return null
            if (pos + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..l) sb.append((dns[pos + i].toInt() and 0xFF).toChar())
            pos += 1 + l
        }
        if (pos + 4 > dns.size) return null
        val type = u16(dns, pos)
        return DnsQuestion(sb.toString().lowercase(), type, pos + 4)
    }

    /** Builds a DNS reply that points the domain at 0.0.0.0 (A) or :: (AAAA). */
    fun blockedResponse(query: ByteArray, q: DnsQuestion): ByteArray {
        val out = ByteArrayOutputStream()
        val rd = query[2].toInt() and 0x01
        out.write(query[0].toInt())
        out.write(query[1].toInt())
        out.write(0x80 or rd) // QR=1, keep RD
        out.write(0x80)       // RA=1, RCODE=0
        out.write(0); out.write(1) // QDCOUNT
        val hasAnswer = q.type == 1 || q.type == 28
        out.write(0); out.write(if (hasAnswer) 1 else 0) // ANCOUNT
        out.write(0); out.write(0) // NSCOUNT
        out.write(0); out.write(0) // ARCOUNT
        out.write(query, 12, q.end - 12) // question section
        if (hasAnswer) {
            out.write(0xC0); out.write(0x0C) // pointer to the name
            out.write(0); out.write(q.type)
            out.write(0); out.write(1) // class IN
            out.write(0); out.write(0); out.write(0); out.write(60) // TTL
            val rdLen = if (q.type == 1) 4 else 16
            out.write(0); out.write(rdLen)
            out.write(ByteArray(rdLen))
        }
        return out.toByteArray()
    }

    /** Wraps a DNS payload in UDP + IPv4 headers so it can be written back into the tunnel. */
    fun buildUdpIpv4(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val b = ByteArray(total)
        b[0] = 0x45
        b[2] = (total shr 8).toByte()
        b[3] = total.toByte()
        b[6] = 0x40 // don't fragment
        b[8] = 64   // TTL
        b[9] = 17   // UDP
        System.arraycopy(srcIp, 0, b, 12, 4)
        System.arraycopy(dstIp, 0, b, 16, 4)
        val ck = checksum(b, 0, 20)
        b[10] = (ck shr 8).toByte()
        b[11] = ck.toByte()
        val udpLen = 8 + payload.size
        b[20] = (srcPort shr 8).toByte()
        b[21] = srcPort.toByte()
        b[22] = (dstPort shr 8).toByte()
        b[23] = dstPort.toByte()
        b[24] = (udpLen shr 8).toByte()
        b[25] = udpLen.toByte()
        // UDP checksum left at 0 (allowed over IPv4)
        System.arraycopy(payload, 0, b, 28, payload.size)
        return b
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len - 1) {
            sum += u16(b, i)
            i += 2
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
