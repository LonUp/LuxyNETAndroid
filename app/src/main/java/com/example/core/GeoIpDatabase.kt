package com.example.core

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * Offline GeoIP lookup backed by the bundled `geoip.dat` (v2fly/Xray protobuf format).
 *
 * On Android, builds compact sorted binary index files (`geoip_v4_*.idx` and `geoip_v6_*.idx`)
 * once per asset size into `filesDir` and memory-maps them for O(log N) lookups with near-zero
 * Java heap overhead. Also supports in-memory construction from an [InputStream] or explicit
 * CIDR entries for deterministic JVM unit tests.
 */
class GeoIpDatabase private constructor(
    private val codes: Array<String>,
    private val v4Store: V4Store,
    private val v6Store: V6Store
) {
    private interface V4Store {
        val size: Int
        fun lookup(ipUnsigned: Int): Short
    }

    private interface V6Store {
        val size: Int
        fun lookup(hi: Long, lo: Long): Short
    }

    private class MemoryV4Store(
        private val starts: IntArray,
        private val ends: IntArray,
        private val countryIdx: ShortArray
    ) : V4Store {
        override val size: Int get() = starts.size
        override fun lookup(ipUnsigned: Int): Short {
            var lo = 0
            var hi = starts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val s = starts[mid]
                val cmp = Integer.compareUnsigned(s, ipUnsigned)
                if (cmp <= 0) {
                    val e = ends[mid]
                    if (Integer.compareUnsigned(ipUnsigned, e) <= 0) return countryIdx[mid]
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return -1
        }
    }

    private class MemoryV6Store(
        private val startHi: LongArray,
        private val startLo: LongArray,
        private val endHi: LongArray,
        private val endLo: LongArray,
        private val countryIdx: ShortArray
    ) : V6Store {
        override val size: Int get() = startHi.size
        override fun lookup(hi: Long, lo: Long): Short {
            var low = 0
            var high = startHi.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val cmpStart = compareUnsigned128(startHi[mid], startLo[mid], hi, lo)
                if (cmpStart <= 0) {
                    if (compareUnsigned128(hi, lo, endHi[mid], endLo[mid]) <= 0) return countryIdx[mid]
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return -1
        }
    }

    private class MappedV4Store(
        private val buf: ByteBuffer,
        private val dataOffset: Int,
        override val size: Int
    ) : V4Store {
        override fun lookup(ipUnsigned: Int): Short {
            var lo = 0
            var hi = size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val pos = dataOffset + mid * V4_RECORD_BYTES
                val s = buf.getInt(pos)
                if (Integer.compareUnsigned(s, ipUnsigned) <= 0) {
                    val e = buf.getInt(pos + 4)
                    if (Integer.compareUnsigned(ipUnsigned, e) <= 0) return buf.getShort(pos + 8)
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return -1
        }
    }

    private class MappedV6Store(
        private val buf: ByteBuffer,
        private val dataOffset: Int,
        override val size: Int
    ) : V6Store {
        override fun lookup(hi: Long, lo: Long): Short {
            var low = 0
            var high = size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val pos = dataOffset + mid * V6_RECORD_BYTES
                val sHi = buf.getLong(pos)
                val sLo = buf.getLong(pos + 8)
                if (compareUnsigned128(sHi, sLo, hi, lo) <= 0) {
                    val eHi = buf.getLong(pos + 16)
                    val eLo = buf.getLong(pos + 24)
                    if (compareUnsigned128(hi, lo, eHi, eLo) <= 0) return buf.getShort(pos + 32)
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return -1
        }
    }

    fun lookupCountry(ipLiteral: String): String? {
        val clean = ipLiteral.trim().removePrefix("[").removeSuffix("]").trim()
        if (!isNumericIpLiteral(clean)) return null
        val addr = runCatching { InetAddress.getByName(clean) }.getOrNull() ?: return null
        return lookupCountry(addr)
    }

    fun lookupCountry(addr: InetAddress): String? {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) {
            return null
        }
        val idx: Short = when (addr) {
            is Inet4Address -> {
                val b = addr.address
                val ipInt = ((b[0].toInt() and 0xFF) shl 24) or
                    ((b[1].toInt() and 0xFF) shl 16) or
                    ((b[2].toInt() and 0xFF) shl 8) or
                    (b[3].toInt() and 0xFF)
                v4Store.lookup(ipInt)
            }
            is Inet6Address -> {
                val b = addr.address
                val hi = bytesToLong(b, 0)
                val lo = bytesToLong(b, 8)
                v6Store.lookup(hi, lo)
            }
            else -> -1
        }
        if (idx < 0 || idx >= codes.size) return null
        val code = codes[idx.toInt()]
        if (code.equals("PRIVATE", ignoreCase = true)) return null
        return ServerCountryResolver.normalizeCode(code)
    }

    fun entryCounts(): Pair<Int, Int> = v4Store.size to v6Store.size

    companion object {
        private const val INDEX_MAGIC = 0x4C584749 // "LXGI"
        private const val INDEX_VERSION = 1
        private const val V4_RECORD_BYTES = 10
        private const val V6_RECORD_BYTES = 34

        @Volatile
        private var instance: GeoIpDatabase? = null
        private val initLock = Any()

        fun getInstance(context: Context): GeoIpDatabase? {
            instance?.let { return it }
            synchronized(initLock) {
                instance?.let { return it }
                return runCatching { loadOrBuildFromAssets(context.applicationContext) }
                    .getOrNull()
                    ?.also { instance = it }
            }
        }

        fun fromStream(input: InputStream): GeoIpDatabase {
            val parsed = parseProtobuf(input)
            val v4Order = sortV4Indices(parsed.v4Starts, parsed.v4Count)
            val v4Starts = IntArray(parsed.v4Count)
            val v4Ends = IntArray(parsed.v4Count)
            val v4Codes = ShortArray(parsed.v4Count)
            for (i in 0 until parsed.v4Count) {
                val src = v4Order[i]
                v4Starts[i] = parsed.v4Starts[src]
                v4Ends[i] = parsed.v4Ends[src]
                v4Codes[i] = parsed.v4Codes[src]
            }

            val v6Order = sortV6Indices(parsed.v6StartHi, parsed.v6StartLo, parsed.v6Count)
            val v6StartHi = LongArray(parsed.v6Count)
            val v6StartLo = LongArray(parsed.v6Count)
            val v6EndHi = LongArray(parsed.v6Count)
            val v6EndLo = LongArray(parsed.v6Count)
            val v6Codes = ShortArray(parsed.v6Count)
            for (i in 0 until parsed.v6Count) {
                val src = v6Order[i]
                v6StartHi[i] = parsed.v6StartHi[src]
                v6StartLo[i] = parsed.v6StartLo[src]
                v6EndHi[i] = parsed.v6EndHi[src]
                v6EndLo[i] = parsed.v6EndLo[src]
                v6Codes[i] = parsed.v6Codes[src]
            }

            return GeoIpDatabase(
                codes = parsed.codes.toTypedArray(),
                v4Store = MemoryV4Store(v4Starts, v4Ends, v4Codes),
                v6Store = MemoryV6Store(v6StartHi, v6StartLo, v6EndHi, v6EndLo, v6Codes)
            )
        }

        fun fromCidrs(entries: List<Pair<String, String>>): GeoIpDatabase {
            val codeMap = LinkedHashMap<String, Short>()
            val codes = ArrayList<String>()
            val v4S = ArrayList<Int>()
            val v4E = ArrayList<Int>()
            val v4C = ArrayList<Short>()
            val v6SH = ArrayList<Long>()
            val v6SL = ArrayList<Long>()
            val v6EH = ArrayList<Long>()
            val v6EL = ArrayList<Long>()
            val v6C = ArrayList<Short>()

            for ((cidr, rawCode) in entries) {
                val code = rawCode.trim().uppercase(Locale.ROOT)
                val idx = codeMap.getOrPut(code) {
                    codes.add(code)
                    (codes.size - 1).toShort()
                }
                val slash = cidr.indexOf('/')
                val ipStr = if (slash >= 0) cidr.substring(0, slash) else cidr
                val addr = InetAddress.getByName(ipStr)
                val prefix = if (slash >= 0) cidr.substring(slash + 1).toInt() else addr.address.size * 8
                when (addr) {
                    is Inet4Address -> {
                        val (s, e) = ipv4Range(addr.address, prefix)
                        v4S.add(s); v4E.add(e); v4C.add(idx)
                    }
                    is Inet6Address -> {
                        val (sHi, sLo, eHi, eLo) = ipv6Range(addr.address, prefix)
                        v6SH.add(sHi); v6SL.add(sLo); v6EH.add(eHi); v6EL.add(eLo); v6C.add(idx)
                    }
                }
            }

            val v4Order = sortV4Indices(v4S.toIntArray(), v4S.size)
            val v4Starts = IntArray(v4S.size) { v4S[v4Order[it]] }
            val v4Ends = IntArray(v4E.size) { v4E[v4Order[it]] }
            val v4Codes = ShortArray(v4C.size) { v4C[v4Order[it]] }

            val v6SHArr = v6SH.toLongArray()
            val v6SLArr = v6SL.toLongArray()
            val v6Order = sortV6Indices(v6SHArr, v6SLArr, v6SHArr.size)
            val v6StartHi = LongArray(v6SHArr.size) { v6SHArr[v6Order[it]] }
            val v6StartLo = LongArray(v6SLArr.size) { v6SLArr[v6Order[it]] }
            val v6EndHi = LongArray(v6EH.size) { v6EH[v6Order[it]] }
            val v6EndLo = LongArray(v6EL.size) { v6EL[v6Order[it]] }
            val v6Codes = ShortArray(v6C.size) { v6C[v6Order[it]] }

            return GeoIpDatabase(
                codes = codes.toTypedArray(),
                v4Store = MemoryV4Store(v4Starts, v4Ends, v4Codes),
                v6Store = MemoryV6Store(v6StartHi, v6StartLo, v6EndHi, v6EndLo, v6Codes)
            )
        }

        private fun loadOrBuildFromAssets(context: Context): GeoIpDatabase {
            val revision = runCatching {
                val props = java.util.Properties()
                context.assets.open("routing-assets.properties").use { props.load(it) }
                props.getProperty("geoip.sha256")?.take(16)
            }.getOrNull() ?: "default"

            val dir = File(context.filesDir, "geoip-index-$revision")
            val metaFile = File(dir, "codes.bin")
            val v4File = File(dir, "v4.idx")
            val v6File = File(dir, "v6.idx")

            if (!metaFile.isFile || !v4File.isFile || !v6File.isFile) {
                dir.mkdirs()
                val parsed = context.assets.open("geoip.dat").use { parseProtobuf(it) }
                writeIndexFiles(dir, metaFile, v4File, v6File, parsed)
                context.filesDir.listFiles()?.forEach { entry ->
                    if (entry.isDirectory && entry.name.startsWith("geoip-index-") && entry.name != dir.name) {
                        entry.deleteRecursively()
                    }
                }
            }

            val codes = DataInputStream(BufferedInputStream(metaFile.inputStream())).use { dis ->
                val magic = dis.readInt()
                val ver = dis.readInt()
                check(magic == INDEX_MAGIC && ver == INDEX_VERSION) { "Invalid GeoIP index header" }
                val count = dis.readInt()
                Array(count) { dis.readUTF() }
            }

            val v4Buf = mapReadOnly(v4File)
            val v4Count = (v4File.length() / V4_RECORD_BYTES).toInt()
            val v6Buf = mapReadOnly(v6File)
            val v6Count = (v6File.length() / V6_RECORD_BYTES).toInt()

            return GeoIpDatabase(
                codes = codes,
                v4Store = MappedV4Store(v4Buf, 0, v4Count),
                v6Store = MappedV6Store(v6Buf, 0, v6Count)
            )
        }

        private fun mapReadOnly(file: File): MappedByteBuffer {
            RandomAccessFile(file, "r").use { raf ->
                return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, file.length()).apply {
                    order(ByteOrder.BIG_ENDIAN)
                }
            }
        }

        private fun writeIndexFiles(
            dir: File,
            metaFile: File,
            v4File: File,
            v6File: File,
            parsed: ParsedGeoIp
        ) {
            val metaTmp = File(dir, "codes.tmp")
            val v4Tmp = File(dir, "v4.tmp")
            val v6Tmp = File(dir, "v6.tmp")
            try {
                DataOutputStream(BufferedOutputStream(metaTmp.outputStream())).use { dos ->
                    dos.writeInt(INDEX_MAGIC)
                    dos.writeInt(INDEX_VERSION)
                    dos.writeInt(parsed.codes.size)
                    for (code in parsed.codes) dos.writeUTF(code)
                }

                val v4Order = sortV4Indices(parsed.v4Starts, parsed.v4Count)
                DataOutputStream(BufferedOutputStream(v4Tmp.outputStream(), 64 * 1024)).use { dos ->
                    for (i in 0 until parsed.v4Count) {
                        val src = v4Order[i]
                        dos.writeInt(parsed.v4Starts[src])
                        dos.writeInt(parsed.v4Ends[src])
                        dos.writeShort(parsed.v4Codes[src].toInt())
                    }
                }

                val v6Order = sortV6Indices(parsed.v6StartHi, parsed.v6StartLo, parsed.v6Count)
                DataOutputStream(BufferedOutputStream(v6Tmp.outputStream(), 64 * 1024)).use { dos ->
                    for (i in 0 until parsed.v6Count) {
                        val src = v6Order[i]
                        dos.writeLong(parsed.v6StartHi[src])
                        dos.writeLong(parsed.v6StartLo[src])
                        dos.writeLong(parsed.v6EndHi[src])
                        dos.writeLong(parsed.v6EndLo[src])
                        dos.writeShort(parsed.v6Codes[src].toInt())
                    }
                }

                check(metaTmp.renameTo(metaFile)) { "Failed to commit GeoIP codes index" }
                check(v4Tmp.renameTo(v4File)) { "Failed to commit GeoIP v4 index" }
                check(v6Tmp.renameTo(v6File)) { "Failed to commit GeoIP v6 index" }
            } finally {
                metaTmp.delete()
                v4Tmp.delete()
                v6Tmp.delete()
            }
        }

        private class ParsedGeoIp(
            val codes: List<String>,
            val v4Starts: IntArray,
            val v4Ends: IntArray,
            val v4Codes: ShortArray,
            val v4Count: Int,
            val v6StartHi: LongArray,
            val v6StartLo: LongArray,
            val v6EndHi: LongArray,
            val v6EndLo: LongArray,
            val v6Codes: ShortArray,
            val v6Count: Int
        )

        private fun parseProtobuf(input: InputStream): ParsedGeoIp {
            val bis = BufferedInputStream(input, 64 * 1024)
            val codes = ArrayList<String>(256)
            var v4Cap = 1024
            var v4Starts = IntArray(v4Cap)
            var v4Ends = IntArray(v4Cap)
            var v4Codes = ShortArray(v4Cap)
            var v4Count = 0

            var v6Cap = 1024
            var v6StartHi = LongArray(v6Cap)
            var v6StartLo = LongArray(v6Cap)
            var v6EndHi = LongArray(v6Cap)
            var v6EndLo = LongArray(v6Cap)
            var v6Codes = ShortArray(v6Cap)
            var v6Count = 0

            var entryBuf = ByteArray(64 * 1024)
            val ipBuf = ByteArray(16)

            while (true) {
                val tag = readVarintOrEof(bis)
                if (tag < 0) break
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x7L).toInt()
                if (fieldNum == 1 && wireType == 2) {
                    val len = readVarint(bis).toInt()
                    if (len > entryBuf.size) entryBuf = ByteArray(maxOf(len, entryBuf.size * 2))
                    readFully(bis, entryBuf, len)

                    var pos = 0
                    var code = ""
                    var reverseMatch = false
                    val entryStartV4 = v4Count
                    val entryStartV6 = v6Count
                    var codeIdx: Short = -1

                    while (pos < len) {
                        val (ftag, p1) = readVarintBuf(entryBuf, pos)
                        pos = p1
                        val fnum = (ftag ushr 3).toInt()
                        val fwire = (ftag and 0x7L).toInt()
                        when {
                            fnum == 1 && fwire == 2 -> {
                                val (slen, p2) = readVarintBuf(entryBuf, pos)
                                code = String(entryBuf, p2, slen.toInt(), Charsets.UTF_8).uppercase(Locale.ROOT)
                                pos = p2 + slen.toInt()
                                codeIdx = codes.size.toShort()
                                codes.add(code)
                            }
                            fnum == 2 && fwire == 2 -> {
                                val (clen, p2) = readVarintBuf(entryBuf, pos)
                                val cend = p2 + clen.toInt()
                                var cpos = p2
                                var ipLen = 0
                                var prefix = 0
                                while (cpos < cend) {
                                    val (ctag, cp1) = readVarintBuf(entryBuf, cpos)
                                    cpos = cp1
                                    val cnum = (ctag ushr 3).toInt()
                                    val cwire = (ctag and 0x7L).toInt()
                                    when {
                                        cnum == 1 && cwire == 2 -> {
                                            val (ilen, cp2) = readVarintBuf(entryBuf, cpos)
                                            ipLen = ilen.toInt()
                                            if (ipLen == 4 || ipLen == 16) {
                                                System.arraycopy(entryBuf, cp2, ipBuf, 0, ipLen)
                                            }
                                            cpos = cp2 + ipLen
                                        }
                                        cnum == 2 && cwire == 0 -> {
                                            val (pfx, cp2) = readVarintBuf(entryBuf, cpos)
                                            prefix = pfx.toInt()
                                            cpos = cp2
                                        }
                                        else -> cpos = skipFieldBuf(entryBuf, cpos, cwire)
                                    }
                                }
                                pos = cend
                                if (codeIdx < 0) {
                                    codeIdx = codes.size.toShort()
                                    codes.add("")
                                }
                                if (ipLen == 4) {
                                    if (v4Count == v4Cap) {
                                        v4Cap *= 2
                                        v4Starts = v4Starts.copyOf(v4Cap)
                                        v4Ends = v4Ends.copyOf(v4Cap)
                                        v4Codes = v4Codes.copyOf(v4Cap)
                                    }
                                    val (s, e) = ipv4Range(ipBuf, prefix)
                                    v4Starts[v4Count] = s
                                    v4Ends[v4Count] = e
                                    v4Codes[v4Count] = codeIdx
                                    v4Count++
                                } else if (ipLen == 16) {
                                    if (v6Count == v6Cap) {
                                        v6Cap *= 2
                                        v6StartHi = v6StartHi.copyOf(v6Cap)
                                        v6StartLo = v6StartLo.copyOf(v6Cap)
                                        v6EndHi = v6EndHi.copyOf(v6Cap)
                                        v6EndLo = v6EndLo.copyOf(v6Cap)
                                        v6Codes = v6Codes.copyOf(v6Cap)
                                    }
                                    val (sHi, sLo, eHi, eLo) = ipv6Range(ipBuf, prefix)
                                    v6StartHi[v6Count] = sHi
                                    v6StartLo[v6Count] = sLo
                                    v6EndHi[v6Count] = eHi
                                    v6EndLo[v6Count] = eLo
                                    v6Codes[v6Count] = codeIdx
                                    v6Count++
                                }
                            }
                            fnum == 3 && fwire == 0 -> {
                                val (rv, p2) = readVarintBuf(entryBuf, pos)
                                reverseMatch = rv != 0L
                                pos = p2
                            }
                            else -> pos = skipFieldBuf(entryBuf, pos, fwire)
                        }
                    }
                    if (reverseMatch) {
                        v4Count = entryStartV4
                        v6Count = entryStartV6
                    } else if (codeIdx >= 0 && codes[codeIdx.toInt()].isEmpty() && code.isNotEmpty()) {
                        codes[codeIdx.toInt()] = code
                    }
                } else {
                    skipFieldStream(bis, wireType)
                }
            }

            return ParsedGeoIp(
                codes = codes,
                v4Starts = v4Starts,
                v4Ends = v4Ends,
                v4Codes = v4Codes,
                v4Count = v4Count,
                v6StartHi = v6StartHi,
                v6StartLo = v6StartLo,
                v6EndHi = v6EndHi,
                v6EndLo = v6EndLo,
                v6Codes = v6Codes,
                v6Count = v6Count
            )
        }

        private fun ipv4Range(ip: ByteArray, prefix: Int): Pair<Int, Int> {
            val raw = ((ip[0].toInt() and 0xFF) shl 24) or
                ((ip[1].toInt() and 0xFF) shl 16) or
                ((ip[2].toInt() and 0xFF) shl 8) or
                (ip[3].toInt() and 0xFF)
            val p = prefix.coerceIn(0, 32)
            val mask = if (p == 0) 0 else (-1 shl (32 - p))
            val start = raw and mask
            val end = start or mask.inv()
            return start to end
        }

        private data class V6Range(val sHi: Long, val sLo: Long, val eHi: Long, val eLo: Long)

        private fun ipv6Range(ip: ByteArray, prefix: Int): V6Range {
            val rawHi = bytesToLong(ip, 0)
            val rawLo = bytesToLong(ip, 8)
            val p = prefix.coerceIn(0, 128)
            val maskHi: Long
            val maskLo: Long
            when {
                p == 0 -> { maskHi = 0L; maskLo = 0L }
                p < 64 -> { maskHi = -1L shl (64 - p); maskLo = 0L }
                p == 64 -> { maskHi = -1L; maskLo = 0L }
                p < 128 -> { maskHi = -1L; maskLo = -1L shl (128 - p) }
                else -> { maskHi = -1L; maskLo = -1L }
            }
            val sHi = rawHi and maskHi
            val sLo = rawLo and maskLo
            val eHi = sHi or maskHi.inv()
            val eLo = sLo or maskLo.inv()
            return V6Range(sHi, sLo, eHi, eLo)
        }

        private fun bytesToLong(b: ByteArray, offset: Int): Long =
            ((b[offset].toLong() and 0xFF) shl 56) or
                ((b[offset + 1].toLong() and 0xFF) shl 48) or
                ((b[offset + 2].toLong() and 0xFF) shl 40) or
                ((b[offset + 3].toLong() and 0xFF) shl 32) or
                ((b[offset + 4].toLong() and 0xFF) shl 24) or
                ((b[offset + 5].toLong() and 0xFF) shl 16) or
                ((b[offset + 6].toLong() and 0xFF) shl 8) or
                (b[offset + 7].toLong() and 0xFF)

        private fun compareUnsigned128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Int {
            val c = java.lang.Long.compareUnsigned(aHi, bHi)
            return if (c != 0) c else java.lang.Long.compareUnsigned(aLo, bLo)
        }

        private fun sortV4Indices(starts: IntArray, count: Int): IntArray {
            val order = IntArray(count) { it }
            if (count > 1) quickSortV4(order, starts, 0, count - 1)
            return order
        }

        private fun quickSortV4(order: IntArray, starts: IntArray, low: Int, high: Int) {
            var l = low
            var h = high
            val pivot = starts[order[(low + high) ushr 1]]
            while (l <= h) {
                while (Integer.compareUnsigned(starts[order[l]], pivot) < 0) l++
                while (Integer.compareUnsigned(starts[order[h]], pivot) > 0) h--
                if (l <= h) {
                    val tmp = order[l]; order[l] = order[h]; order[h] = tmp
                    l++; h--
                }
            }
            if (low < h) quickSortV4(order, starts, low, h)
            if (l < high) quickSortV4(order, starts, l, high)
        }

        private fun sortV6Indices(startHi: LongArray, startLo: LongArray, count: Int): IntArray {
            val order = IntArray(count) { it }
            if (count > 1) quickSortV6(order, startHi, startLo, 0, count - 1)
            return order
        }

        private fun quickSortV6(order: IntArray, hi: LongArray, lo: LongArray, low: Int, high: Int) {
            var l = low
            var h = high
            val midIdx = order[(low + high) ushr 1]
            val pHi = hi[midIdx]
            val pLo = lo[midIdx]
            while (l <= h) {
                while (compareUnsigned128(hi[order[l]], lo[order[l]], pHi, pLo) < 0) l++
                while (compareUnsigned128(hi[order[h]], lo[order[h]], pHi, pLo) > 0) h--
                if (l <= h) {
                    val tmp = order[l]; order[l] = order[h]; order[h] = tmp
                    l++; h--
                }
            }
            if (low < h) quickSortV6(order, hi, lo, low, h)
            if (l < high) quickSortV6(order, hi, lo, l, high)
        }

        private fun isNumericIpLiteral(host: String): Boolean {
            if (host.isEmpty()) return false
            if (host.all { it in '0'..'9' || it == '.' }) {
                val parts = host.split('.')
                return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
            }
            return host.contains(':') && host.all {
                it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.'
            }
        }

        private fun readVarintOrEof(input: InputStream): Long {
            val first = input.read()
            if (first < 0) return -1L
            var result = (first and 0x7F).toLong()
            if ((first and 0x80) == 0) return result
            var shift = 7
            while (shift < 64) {
                val b = input.read()
                if (b < 0) throw IllegalStateException("Truncated protobuf varint")
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            throw IllegalStateException("Malformed protobuf varint")
        }

        private fun readVarint(input: InputStream): Long {
            val v = readVarintOrEof(input)
            if (v < 0) throw IllegalStateException("Unexpected EOF reading varint")
            return v
        }

        private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) throw IllegalStateException("Unexpected EOF reading protobuf bytes")
                off += n
            }
        }

        private fun readVarintBuf(buf: ByteArray, start: Int): Pair<Long, Int> {
            var pos = start
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result to pos
                shift += 7
            }
        }

        private fun skipFieldBuf(buf: ByteArray, pos: Int, wireType: Int): Int = when (wireType) {
            0 -> readVarintBuf(buf, pos).second
            1 -> pos + 8
            2 -> {
                val (len, next) = readVarintBuf(buf, pos)
                next + len.toInt()
            }
            5 -> pos + 4
            else -> throw IllegalStateException("Unsupported protobuf wire type $wireType")
        }

        private fun skipFieldStream(input: InputStream, wireType: Int) {
            when (wireType) {
                0 -> readVarint(input)
                1 -> skipBytes(input, 8)
                2 -> skipBytes(input, readVarint(input))
                5 -> skipBytes(input, 4)
                else -> throw IllegalStateException("Unsupported protobuf wire type $wireType")
            }
        }

        private fun skipBytes(input: InputStream, count: Long) {
            var rem = count
            while (rem > 0) {
                val skipped = input.skip(rem)
                if (skipped > 0) rem -= skipped
                else {
                    if (input.read() < 0) throw IllegalStateException("Unexpected EOF skipping bytes")
                    rem--
                }
            }
        }
    }
}
