package app.pi.bridge

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地 VPN：真实 TUN + DNS 拦截 + 明文 HTTP 可见性 + 本地代理通道。
 *
 * ### 为什么只有 VpnService 这条路
 *
 * 非 root 应用没有别的办法看到别人的出站流量：`iptables` 需要 root，抓包需要
 * root 或 `su`。系统唯一交给普通应用的网络入口就是 `VpnService` —— 用户点一次
 * 授权，系统把符合条件的包投进我们建立的 TUN。所以这是「本地 VPN」。
 *
 * ### 两层能力，边界必须写清楚
 *
 * **第一层（本文件，Kotlin 可及的最强）**
 *  1. TUN + 精确分流：[Builder] 建 IPv4 TUN，地址/MTU/自定义 DNS（如 `10.0.0.2`）
 *     由 [TunnelConfig] 传入；`addAllowedApplication` / `addDisallowedApplication`
 *     就是分流名单 —— 只有名单内应用的流量进 TUN。
 *  2. **DNS 拦截（真做）**：读取线程逐包解 IPv4/IPv6 的 UDP 53，自己解 DNS 报文头
 *     与 Question 段，不引第三方。每条查询记录（域名/类型/时间/客户端）；命中
 *     黑名单回一个**合法**的 DNS 响应（A 查询回 `0.0.0.0`，其余回 NXDOMAIN）；
 *     未命中且允许转发时，用真实 [DatagramSocket] 问到上游 DNS，再把响应重写成
 *     `DNS地址:53 → 客户端` 的 IPv4/IPv6 报文写回 TUN。
 *  3. **TCP 可见性**：解 IPv4/IPv6 + TCP 头，记录五元组与连接起止、SYN/FIN/RST；
 *     对目的 80 的单段载荷解析请求行与 `Host` 头。
 *  4. **本地代理通道**：在 127.0.0.1 起一个最小 HTTP / HTTPS-CONNECT 代理，端口
 *     由 [proxyPort] 导出。这是本轮**实际可用**的明文 HTTP / CONNECT 隧道入口。
 *
 * **第二层（本文件做不到，见 [PiVpnStack]）**
 *  VpnService 只给一个 TUN fd，**它本身没有 TCP/IP 栈** —— 内核不会替我们把 TUN
 *  里的 TCP 包变成 socket。因此只靠 DNS 拦截 + 包记录，拦不到 HTTP 正文；要真正
 *  转发并观察 TCP 流，必须有用户态 TCP/IP 栈（lwIP / tun2socks，走 JNI + NDK）。
 *  本仓库没有 NDK/CMake 构建步骤，本轮不引入原生代码（会炸 release 构建），所以
 *  [PiVpnStack] 只是一个返回 UNSUPPORTED 的对接点。
 *
 * **无 root 解不了他人 App 的 HTTPS。** TUN 拿到的是 IP 层报文，TLS 内容是密文；
 * 没有目标服务器密钥或用户信任的 MITM 证书就解不开。要解他人 App 的 HTTPS，
 * 需要「用户态 TCP 栈 + 在目标 App 信任域装用户 CA」两步，属下一阶段。
 *
 * ### 接线前提（波2 负责，不在本文件）
 *
 * `AndroidManifest.xml` 必须声明本服务并加
 * `android:permission="android.permission.BIND_VPN_SERVICE"` 与
 * `android.net.VpnService` 的 intent-filter。首次启用前，上层先用 [consentIntent]
 * 拿到的 Intent 走 `startActivityForResult`，用户同意后再调 [start] —— 本组件
 * 自己不起 Activity。
 */
class PiVpnService : VpnService() {

    /**
     * 建立 TUN 需要的一切。默认值是一套「安全的最小捕获」：
     *
     * - 只在 `10.0.0.0/24` 这条路由上收包（DNS 服务器 `10.0.0.2` 落在网段内），
     *   默认不会把整机流量拉进 TUN、也不会因为丢弃而让设备断网。
     * - 要抓整机流量时，调用方显式传 `routes = listOf("0.0.0.0/0")`，并用
     *   `allowedPackages` / `disallowedPackages` 把范围收窄到目标应用。
     *
     * 注意：一旦整机 TCP 被拉进 TUN 且没有用户态栈，TCP 会被丢弃，那些应用会
     * 失去网络 —— 这正是 [PiVpnStack] 存在的理由。
     */
    data class TunnelConfig(
        val sessionName: String = "PI 本地 VPN",
        /** TUN 接口自己的地址；DNS 通常放在同一网段的另一个地址。 */
        val address: String = "10.0.0.1",
        val prefixLength: Int = 24,
        /** 交给系统的 DNS 服务器。默认 `10.0.0.2`，查询会经 TUN 到达本服务。 */
        val dnsServers: List<String> = listOf("10.0.0.2"),
        /** 形如 `10.0.0.0/24`；为空时由 [address]/[prefixLength] 与 DNS 地址推导。 */
        val routes: List<String> = emptyList(),
        /** 只收这些应用的流量（放行名单）。与 [disallowedPackages] 互斥使用。 */
        val allowedPackages: List<String> = emptyList(),
        /** 排除这些应用的流量（排除名单）：其余应用都进 TUN。 */
        val disallowedPackages: List<String> = emptyList(),
        val mtu: Int = 1500,
        /** DNS 转发真正去问的上游服务器。 */
        val upstreamDns: String = "8.8.8.8",
        /** true：命中黑名单的 DNS 查询回合法响应（拦截）；false：只记录不拦截。 */
        val interceptDns: Boolean = true,
        /** 是否对 UDP 做真实的 socket 转发；false 时只记录后丢弃。 */
        val forwardUdp: Boolean = true,
        /** 启动时预置的 DNS 黑名单（与 [setBlocklist] 累加）。 */
        val blocklist: List<String> = emptyList(),
        /** 是否随隧道一起启动 127.0.0.1 的本地代理。 */
        val proxyEnabled: Boolean = true,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("sessionName", sessionName)
            put("address", address)
            put("prefixLength", prefixLength)
            put("dnsServers", JSONArray(dnsServers))
            put("routes", JSONArray(routes))
            put("allowedPackages", JSONArray(allowedPackages))
            put("disallowedPackages", JSONArray(disallowedPackages))
            put("mtu", mtu)
            put("upstreamDns", upstreamDns)
            put("interceptDns", interceptDns)
            put("forwardUdp", forwardUdp)
            put("blocklist", JSONArray(blocklist))
            put("proxyEnabled", proxyEnabled)
        }

        companion object {
            /** 服务是另一个进程入口，配置只能靠 Intent 传字符串 —— 用 JSON 避免 Parcelable。 */
            fun fromJson(text: String?): TunnelConfig {
                if (text.isNullOrBlank()) return TunnelConfig()
                return runCatching {
                    val json = JSONObject(text)
                    val base = TunnelConfig()
                    TunnelConfig(
                        sessionName = json.optString("sessionName", base.sessionName),
                        address = json.optString("address", base.address),
                        prefixLength = json.optInt("prefixLength", base.prefixLength),
                        dnsServers = json.optJSONArray("dnsServers")?.toStringList() ?: base.dnsServers,
                        routes = json.optJSONArray("routes")?.toStringList() ?: base.routes,
                        allowedPackages = json.optJSONArray("allowedPackages")?.toStringList()
                            ?: base.allowedPackages,
                        disallowedPackages = json.optJSONArray("disallowedPackages")?.toStringList()
                            ?: base.disallowedPackages,
                        mtu = json.optInt("mtu", base.mtu),
                        upstreamDns = json.optString("upstreamDns", base.upstreamDns),
                        interceptDns = json.optBoolean("interceptDns", base.interceptDns),
                        forwardUdp = json.optBoolean("forwardUdp", base.forwardUdp),
                        blocklist = json.optJSONArray("blocklist")?.toStringList() ?: base.blocklist,
                        proxyEnabled = json.optBoolean("proxyEnabled", base.proxyEnabled),
                    )
                }.getOrDefault(TunnelConfig())
            }

            private fun JSONArray.toStringList(): List<String> =
                (0 until length()).mapNotNull { optString(it)?.takeIf { s -> s.isNotBlank() } }
        }
    }

    // ------------------------------------------------------------ 运行态 ----

    private val tunnelRunning = AtomicBoolean(false)
    private val packetsRead = AtomicLong(0)
    private val bytesRead = AtomicLong(0)
    private val dnsQueries = AtomicInteger(0)
    private val udpRelayed = AtomicInteger(0)
    private val droppedPackets = AtomicInteger(0)
    private val unsupportedPackets = AtomicInteger(0)
    private val tcpPackets = AtomicInteger(0)
    private val tcpOpened = AtomicInteger(0)
    private val tcpClosed = AtomicInteger(0)

    /** 已见域名 → 次数。用并发表：读取线程写入，`/app/health` 线程读取。 */
    private val seenDomains = ConcurrentHashMap<String, Int>()

    private val queryLog = ArrayDeque<JSONObject>()
    private val queryLock = Any()
    private val httpLog = ArrayDeque<JSONObject>()
    private val httpLock = Any()
    private val tcpLog = ArrayDeque<JSONObject>()
    private val tcpLock = Any()

    /** 活动 TCP 流：五元组键 → 起始毫秒。 */
    private val tcpFlows = ConcurrentHashMap<String, Long>()

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var tunOutput: FileOutputStream? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var activeConfig: TunnelConfig? = null

    @Volatile
    private var lastError: String? = null

    private var proxy: LoopbackProxy? = null

    @Volatile
    private var proxyPortValue: Int = -1

    /** 供伴生对象读取：真正的隧道是否在跑（服务被创建但未建立 TUN 时为 false）。 */
    val tunRunning: Boolean get() = tunnelRunning.get()

    // ------------------------------------------------------------ 生命周期 ----

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        establishTunnel(TunnelConfig.fromJson(intent?.getStringExtra(EXTRA_CONFIG)))
        // 不要 STICKY：被系统杀掉后不应拿「默认配置」悄悄复活一个 VPN 会话。
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // 用户在系统里切到别的 VPN，或授权被撤回：立即收摊。
        tearDown()
        super.onRevoke()
    }

    override fun onDestroy() {
        tearDown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 建立隧道 ----

    private fun establishTunnel(config: TunnelConfig) {
        tearDown()
        applyBlocklist(config.blocklist)
        val descriptor = try {
            buildTun(config)
        } catch (t: Throwable) {
            lastError = "建立 TUN 失败：${t.message ?: t.javaClass.simpleName}"
            return
        }
        if (descriptor == null) {
            lastError = "系统拒绝了 TUN（establish() 返回空：没有用户授权，或已有其它 VPN 在运行）。"
            return
        }
        tun = descriptor
        tunOutput = FileOutputStream(descriptor.fileDescriptor)
        activeConfig = config
        lastError = null
        tunnelRunning.set(true)
        // 代理要在 TUN 建立之后起：它靠 protect() 绕开自己的隧道。
        if (config.proxyEnabled) startProxy()
        readerThread = Thread({ readLoop(descriptor) }, "pi-vpn-tun").apply {
            isDaemon = true
            start()
        }
    }

    private fun buildTun(config: TunnelConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(config.sessionName)
            .setMtu(config.mtu.coerceIn(576, 9000))
            // 阻塞读：非阻塞时 read() 会在没有包时立刻返回 0，读取线程会空转。
            .setBlocking(true)
            .addAddress(config.address, config.prefixLength)

        for (dns in config.dnsServers) builder.addDnsServer(dns)

        // 没给路由时，只覆盖接口网段与 DNS 地址本身 —— 默认不抓整机流量。
        val routes = config.routes.ifEmpty {
            listOf("${config.address}/${config.prefixLength}") +
                config.dnsServers.map { dns -> "$dns/${if (dns.contains(':')) 128 else 32}" }
        }
        for (route in routes) {
            val parsed = splitCidr(route) ?: continue
            val network = networkHost(parsed.first, parsed.second) ?: continue
            builder.addRoute(network, parsed.second)
        }

        for (pkg in config.allowedPackages) runCatching { builder.addAllowedApplication(pkg) }
        for (pkg in config.disallowedPackages) runCatching { builder.addDisallowedApplication(pkg) }
        return builder.establish()
    }

    private fun tearDown() {
        tunnelRunning.set(false)
        runCatching { readerThread?.interrupt() }
        readerThread = null
        runCatching { proxy?.stop() }
        proxy = null
        proxyPortValue = -1
        runCatching { tun?.close() }
        tun = null
        // 注意：不要 close(tunOutput) —— 它包的是同一个 tun fd，close 会连带关闭 TUN。
        tunOutput = null
        activeConfig = null
    }

    // ------------------------------------------------------------ 读包循环 ----

    private fun readLoop(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(65_536)
        try {
            while (tunnelRunning.get()) {
                val read = input.read(buffer)
                if (read <= 0) continue
                packetsRead.incrementAndGet()
                bytesRead.addAndGet(read.toLong())
                // 单个畸形包不应终止整条隧道。
                runCatching { handlePacket(buffer, read) }
            }
        } catch (_: Throwable) {
            // tun 被关闭 / 服务停止：这是正常退出路径，不当作错误。
        } finally {
            tunnelRunning.set(false)
        }
    }

    private fun handlePacket(buffer: ByteArray, length: Int) {
        if (length < 1) return
        when ((buffer[0].toInt() ushr 4) and 0x0F) {
            4 -> handleIpv4(buffer, length)
            6 -> handleIpv6(buffer, length)
            else -> unsupportedPackets.incrementAndGet()
        }
    }

    // ---------------------------------------------------------------- IPv4 ----

    private fun handleIpv4(buffer: ByteArray, length: Int) {
        if (length < IPV4_HEADER_MIN) return
        val headerLength = (buffer[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_HEADER_MIN || length < headerLength) return
        val protocol = buffer[9].toInt() and 0xFF
        val srcIp = buffer.copyOfRange(12, 16)
        val dstIp = buffer.copyOfRange(16, 20)

        if (protocol == PROTO_TCP) {
            handleTcp(
                buffer = buffer,
                length = length,
                l4Offset = headerLength,
                srcAddress = ipv4ToString(srcIp) ?: return,
                dstAddress = ipv4ToString(dstIp) ?: return,
            )
            return
        }
        if (protocol != PROTO_UDP) {
            unsupportedPackets.incrementAndGet()
            return
        }
        if (length < headerLength + UDP_HEADER_LENGTH) return

        val srcPort = u16(buffer, headerLength)
        val dstPort = u16(buffer, headerLength + 2)
        val udpLength = u16(buffer, headerLength + 4)
        val payloadStart = headerLength + UDP_HEADER_LENGTH
        val payloadEnd = minOf(length, headerLength + udpLength.coerceAtLeast(UDP_HEADER_LENGTH))
        if (payloadEnd <= payloadStart) return

        if (dstPort == DNS_PORT) {
            handleDnsIpv4(buffer, payloadStart, payloadEnd, srcIp, dstIp, srcPort)
            return
        }

        // 非 DNS 的 UDP：目的地址就是真实地址，做一次真实的 socket 往返。
        val config = activeConfig ?: return
        if (!config.forwardUdp) {
            droppedPackets.incrementAndGet()
            return
        }
        val destination = ipv4ToString(dstIp) ?: run {
            droppedPackets.incrementAndGet()
            return
        }
        val payload = buffer.copyOfRange(payloadStart, payloadEnd)
        val reply = udpRoundTrip(destination, dstPort, payload) ?: return
        writeToTun(
            buildIpv4Udp(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                payload = reply,
            ),
        )
        udpRelayed.incrementAndGet()
    }

    private fun handleDnsIpv4(
        buffer: ByteArray,
        payloadStart: Int,
        payloadEnd: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
    ) {
        dnsQueries.incrementAndGet()
        if (payloadEnd - payloadStart < DNS_HEADER_LENGTH) return
        val query = buffer.copyOfRange(payloadStart, payloadEnd)
        val question = parseDnsQuestion(query, DNS_HEADER_LENGTH, query.size)
        val config = activeConfig ?: return
        val blocked = config.interceptDns && question != null && isBlocked(question.name)
        recordQuery(question?.name, question?.type ?: 0, ipv4ToString(srcIp) ?: "", blocked)

        if (blocked && question != null) {
            blockedTotal.incrementAndGet()
            writeToTun(
                buildIpv4Udp(
                    srcIp = dstIp,
                    srcPort = DNS_PORT,
                    dstIp = srcIp,
                    dstPort = srcPort,
                    payload = buildDnsBlockResponse(query, question),
                ),
            )
            return
        }
        if (!config.forwardUdp) {
            droppedPackets.incrementAndGet()
            return
        }
        val reply = udpRoundTrip(config.upstreamDns, DNS_PORT, query) ?: return
        // 回包源地址必须是客户端当初查询的 DNS 地址（`10.0.0.2`），而不是上游 ——
        // 否则客户端的 DNS 事务会因源地址不匹配被丢弃。
        writeToTun(
            buildIpv4Udp(
                srcIp = dstIp,
                srcPort = DNS_PORT,
                dstIp = srcIp,
                dstPort = srcPort,
                payload = reply,
            ),
        )
        udpRelayed.incrementAndGet()
    }

    // ---------------------------------------------------------------- IPv6 ----

    private fun handleIpv6(buffer: ByteArray, length: Int) {
        if (length < IPV6_HEADER_LENGTH) return
        // 这里不追 IPv6 扩展头：DNS/HTTP 的常见路径没有扩展头，遇到别的按不支持计。
        val nextHeader = buffer[6].toInt() and 0xFF
        val srcIp = buffer.copyOfRange(8, 24)
        val dstIp = buffer.copyOfRange(24, 40)

        if (nextHeader == PROTO_TCP) {
            handleTcp(
                buffer = buffer,
                length = length,
                l4Offset = IPV6_HEADER_LENGTH,
                srcAddress = ipv6ToString(srcIp) ?: return,
                dstAddress = ipv6ToString(dstIp) ?: return,
            )
            return
        }
        if (nextHeader != PROTO_UDP) {
            unsupportedPackets.incrementAndGet()
            return
        }
        if (length < IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH) return
        val srcPort = u16(buffer, IPV6_HEADER_LENGTH)
        val dstPort = u16(buffer, IPV6_HEADER_LENGTH + 2)
        val udpLength = u16(buffer, IPV6_HEADER_LENGTH + 4)
        val payloadStart = IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH
        val payloadEnd = minOf(length, IPV6_HEADER_LENGTH + udpLength.coerceAtLeast(UDP_HEADER_LENGTH))
        if (payloadEnd <= payloadStart) return

        if (dstPort != DNS_PORT) {
            unsupportedPackets.incrementAndGet()
            return
        }
        dnsQueries.incrementAndGet()
        val query = buffer.copyOfRange(payloadStart, payloadEnd)
        val question = parseDnsQuestion(query, DNS_HEADER_LENGTH, query.size)
        val config = activeConfig ?: return
        val blocked = config.interceptDns && question != null && isBlocked(question.name)
        recordQuery(question?.name, question?.type ?: 0, ipv6ToString(srcIp) ?: "", blocked)

        if (blocked && question != null) {
            blockedTotal.incrementAndGet()
            writeToTun(
                buildIpv6Udp(
                    srcIp = dstIp,
                    srcPort = DNS_PORT,
                    dstIp = srcIp,
                    dstPort = srcPort,
                    payload = buildDnsBlockResponse(query, question),
                ),
            )
            return
        }
        if (!config.forwardUdp) {
            droppedPackets.incrementAndGet()
            return
        }
        val reply = udpRoundTrip(config.upstreamDns, DNS_PORT, query) ?: return
        writeToTun(
            buildIpv6Udp(
                srcIp = dstIp,
                srcPort = DNS_PORT,
                dstIp = srcIp,
                dstPort = srcPort,
                payload = reply,
            ),
        )
        udpRelayed.incrementAndGet()
    }

    // ------------------------------------------------------------------ TCP ----

    /**
     * 被动可见性：解析 TCP 头并记录五元组、连接起止与明文 HTTP 请求。
     *
     * 说明白这里的能力边界：没有用户态栈时 TUN 里的 TCP 会被**丢弃**（见类 KDoc），
     * 所以除非 [PiVpnStack] 上马并接力转发 TCP 数据段，这里的 HTTP 解析收不到
     * 请求行 —— 它解析的是「任何到达 TUN 的 TCP 载荷」，而不是假装转发了连接。
     */
    private fun handleTcp(
        buffer: ByteArray,
        length: Int,
        l4Offset: Int,
        srcAddress: String,
        dstAddress: String,
    ) {
        if (length < l4Offset + TCP_HEADER_MIN) {
            unsupportedPackets.incrementAndGet()
            return
        }
        val srcPort = u16(buffer, l4Offset)
        val dstPort = u16(buffer, l4Offset + 2)
        val flags = buffer[l4Offset + 13].toInt() and 0xFF
        val dataOffset = ((buffer[l4Offset + 12].toInt() ushr 4) and 0x0F) * 4
        val key = "$srcAddress:$srcPort->$dstAddress:$dstPort"
        val syn = (flags and TCP_FLAG_SYN) != 0
        val ack = (flags and TCP_FLAG_ACK) != 0
        val fin = (flags and TCP_FLAG_FIN) != 0
        val rst = (flags and TCP_FLAG_RST) != 0

        tcpPackets.incrementAndGet()
        if (syn && !ack) {
            if (tcpFlows.putIfAbsent(key, System.currentTimeMillis()) == null) {
                tcpOpened.incrementAndGet()
                recordTcpEvent(key, "open", null)
            }
        }
        if (fin || rst) {
            val startedAt = tcpFlows.remove(key)
            if (startedAt != null) {
                tcpClosed.incrementAndGet()
                recordTcpEvent(key, "close", System.currentTimeMillis() - startedAt)
            }
        }

        // 明文 HTTP 请求只可能出现在「客户端 → 服务器」且目的 80 的单段载荷里。
        if (dstPort == HTTP_PORT && dataOffset >= TCP_HEADER_MIN && l4Offset + dataOffset < length) {
            recordHttpRequest(
                payload = buffer.copyOfRange(l4Offset + dataOffset, length),
                srcAddress = srcAddress,
                dstAddress = dstAddress,
            )
        }
        droppedPackets.incrementAndGet()
    }

    private fun recordHttpRequest(payload: ByteArray, srcAddress: String, dstAddress: String) {
        val head = String(payload, 0, minOf(payload.size, MAX_HTTP_HEAD), Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val method = requestLine.substringBefore(' ').uppercase()
        if (method !in HTTP_METHODS) return
        val uri = requestLine.split(' ').getOrNull(1)
        val host = lines.drop(1)
            .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
        val entry = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("method", method)
            .put("uri", uri ?: JSONObject.NULL)
            .put("host", host ?: JSONObject.NULL)
            .put("from", srcAddress)
            .put("to", dstAddress)
        synchronized(httpLock) {
            httpLog.addLast(entry)
            while (httpLog.size > MAX_HTTP_LOG) httpLog.removeFirst()
        }
    }

    private fun recordTcpEvent(flow: String, event: String, durationMs: Long?) {
        val entry = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("event", event)
            .put("flow", flow)
            .put("durationMs", durationMs ?: JSONObject.NULL)
        synchronized(tcpLock) {
            tcpLog.addLast(entry)
            while (tcpLog.size > MAX_TCP_LOG) tcpLog.removeFirst()
        }
    }

    // ------------------------------------------------------------ UDP 往返 ----

    /**
     * 一次真实的 UDP 往返。
     *
     * `protect(socket)` 是关键：不保护的话，我们发往上游的包会因为目的地址落在
     * TUN 的默认路由里而再次被自己捕获，形成回环。protect 让这个 socket 绕过
     * 本 VPN 直接走底层网络。
     */
    private fun udpRoundTrip(host: String, port: Int, payload: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { socket ->
            protect(socket)
            socket.soTimeout = DNS_TIMEOUT_MS
            val target = InetSocketAddress(InetAddress.getByName(host), port)
            socket.send(DatagramPacket(payload, payload.size, target))
            val buffer = ByteArray(4096)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            buffer.copyOf(response.length)
        }
    }.getOrNull()

    private fun writeToTun(packet: ByteArray) {
        val output = tunOutput ?: return
        runCatching { output.write(packet) }
    }

    // ------------------------------------------------------------ DNS 记录 ----

    private fun recordQuery(name: String?, type: Int, client: String, blocked: Boolean) {
        val entry = JSONObject()
            .put("name", name ?: JSONObject.NULL)
            .put("type", dnsTypeName(type))
            .put("client", client)
            .put("blocked", blocked)
            .put("at", System.currentTimeMillis())
        synchronized(queryLock) {
            queryLog.addLast(entry)
            while (queryLog.size > MAX_QUERY_LOG) queryLog.removeFirst()
        }
    }

    /**
     * 黑名单匹配：精确命中，或域名以 `.<entry>` 结尾（拦 `example.com` 连带 `a.example.com`）。
     * 用域名后缀而不是正则，是因为规则是「域名」而不是「任意模式」。
     */
    private fun isBlocked(name: String): Boolean {
        if (blocklist.isEmpty()) return false
        if (blocklist.contains(name)) return true
        var dot = name.indexOf('.')
        while (dot >= 0) {
            if (blocklist.contains(name.substring(dot + 1))) return true
            dot = name.indexOf('.', dot + 1)
        }
        return false
    }

    private fun readQueries(limit: Int): JSONArray {
        val snapshot = synchronized(queryLock) { queryLog.toList() }
        return JSONArray().apply {
            for (entry in snapshot.takeLast(limit.coerceAtLeast(0))) put(entry)
        }
    }

    private fun readConnections(limit: Int): JSONArray {
        val snapshot = synchronized(tcpLock) { tcpLog.toList() }
        return JSONArray().apply {
            for (entry in snapshot.takeLast(limit.coerceAtLeast(0))) put(entry)
        }
    }

    private fun readHttpRequests(limit: Int): JSONArray {
        val snapshot = synchronized(httpLock) { httpLog.toList() }
        return JSONArray().apply {
            for (entry in snapshot.takeLast(limit.coerceAtLeast(0))) put(entry)
        }
    }

    // -------------------------------------------------------------- 本地代理 ----

    private fun startProxy() {
        runCatching {
            val created = LoopbackProxy()
            proxyPortValue = created.start()
            proxy = created
        }.onFailure { lastError = "本地代理启动失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /**
     * 最小 HTTP / HTTPS-CONNECT 代理，只监听 127.0.0.1。
     *
     * 它解决的是「本轮怎么真正看到/转发明文 HTTP」：让调用方把 `http_proxy` /
     * `https_proxy` 指向这个端口即可。`CONNECT` 是纯字节隧道（HTTPS 仍然是端到端
     * TLS，只能看到目标域名，看不到正文）；普通的 `GET http://…` 会被转发到源站。
     *
     * 为什么只绑 loopback：它没有认证、没有 ACL，暴露到局域网等于开放代理。
     */
    private inner class LoopbackProxy {
        private val server = ServerSocket()
        private var acceptThread: Thread? = null

        @Volatile
        private var running = false

        @Volatile
        var port: Int = -1
            private set

        fun start(): Int {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            port = server.localPort
            running = true
            acceptThread = Thread({ acceptLoop() }, "pi-vpn-proxy").apply {
                isDaemon = true
                start()
            }
            return port
        }

        fun stop() {
            running = false
            runCatching { server.close() }
            acceptThread = null
            port = -1
        }

        private fun acceptLoop() {
            while (running) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ runCatching { handle(client) } }, "pi-vpn-proxy-conn").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun handle(client: Socket) {
            client.use { c ->
                c.soTimeout = PROXY_HEAD_TIMEOUT_MS
                val head = readHttpHead(c.getInputStream())
                if (head == null) {
                    writeAscii(c, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                    return
                }
                val lines = head.split("\r\n")
                val parts = lines.firstOrNull().orEmpty().split(' ')
                if (parts.size < 3) {
                    writeAscii(c, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                    return
                }
                val method = parts[0].uppercase()
                val target = parts[1]
                // 头读完就取消超时：隧道可能长时间静默。
                c.soTimeout = 0

                if (method == "CONNECT") {
                    val hostPort = splitHostPort(target, DEFAULT_HTTPS_PORT)
                    if (hostPort == null) {
                        writeAscii(c, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                        return
                    }
                    val upstream = connect(hostPort.first, hostPort.second)
                    if (upstream == null) {
                        writeAscii(c, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
                        return
                    }
                    upstream.use { u ->
                        writeAscii(c, "HTTP/1.1 200 Connection Established\r\n\r\n")
                        relay(c, u)
                    }
                    return
                }

                val origin = parseAbsoluteHttp(target)
                if (origin == null) {
                    writeAscii(c, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                    return
                }
                val upstream = connect(origin.host, origin.port)
                if (upstream == null) {
                    writeAscii(c, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
                    return
                }
                upstream.use { u ->
                    u.getOutputStream().write(buildForwardedHead(method, origin, lines))
                    u.getOutputStream().flush()
                    relay(c, u)
                }
            }
        }

        private fun buildForwardedHead(method: String, origin: HttpTarget, lines: List<String>): ByteArray {
            val builder = StringBuilder()
            builder.append(method).append(' ').append(origin.path).append(" HTTP/1.1\r\n")
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val lower = line.lowercase()
                // 跳线与连接语义的头不能透传：强制 close 才能靠 EOF 判断响应结束。
                if (lower.startsWith("proxy-connection:")) continue
                if (lower.startsWith("connection:")) continue
                builder.append(line).append("\r\n")
            }
            builder.append("Connection: close\r\n\r\n")
            return builder.toString().toByteArray(Charsets.ISO_8859_1)
        }

        private fun readHttpHead(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            var match = 0
            while (out.size() < PROXY_MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                out.write(b)
                val c = b.toChar()
                match = when (match) {
                    0 -> if (c == '\r') 1 else 0
                    1 -> if (c == '\n') 2 else if (c == '\r') 1 else 0
                    2 -> if (c == '\r') 3 else 0
                    3 -> if (c == '\n') return out.toString(Charsets.ISO_8859_1.name()) else if (c == '\r') 1 else 0
                    else -> 0
                }
            }
            return null
        }

        private fun connect(host: String, port: Int): Socket? = runCatching {
            val socket = Socket()
            runCatching { protect(socket) }
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), PROXY_CONNECT_TIMEOUT_MS)
            socket
        }.getOrNull()

        private fun relay(client: Socket, upstream: Socket) {
            val up = pipe(upstream, client)
            val down = pipe(client, upstream)
            up.start()
            down.start()
            up.join()
            down.join()
        }

        private fun pipe(from: Socket, to: Socket): Thread = Thread({
            runCatching {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buffer = ByteArray(PROXY_PIPE_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
            runCatching { to.shutdownOutput() }
        }, "pi-vpn-proxy-pipe").apply { isDaemon = true }

        private fun writeAscii(socket: Socket, text: String) {
            runCatching {
                socket.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().flush()
            }
        }
    }

    // ------------------------------------------------------------ 状态输出 ----

    private fun snapshot(): JSONObject = JSONObject().apply {
        val config = activeConfig
        put("component", "vpn")
        put("running", tunnelRunning.get())
        put("sessionName", config?.sessionName ?: JSONObject.NULL)
        put("tunFd", runCatching { tun?.fd }.getOrNull() ?: -1)
        put("address", config?.let { "${it.address}/${it.prefixLength}" } ?: JSONObject.NULL)
        put("routes", JSONArray(config?.routes ?: emptyList<String>()))
        put(
            "dns",
            JSONObject()
                .put("servers", JSONArray(config?.dnsServers ?: emptyList<String>()))
                .put("upstream", config?.upstreamDns ?: JSONObject.NULL)
                .put("intercept", config?.interceptDns ?: false)
                .put("blocklist", blocklist.size),
        )
        put("split", splitJson(config))
        put("seenDomains", seenDomains.size)
        put("blockedCount", blockedTotal.get())
        put("proxyPort", proxyPortValue)
        put(
            "domains",
            JSONArray().apply {
                for (entry in seenDomains.entries.sortedByDescending { it.value }.take(MAX_DOMAINS_IN_STATUS)) {
                    put(JSONObject().put("name", entry.key).put("count", entry.value))
                }
            },
        )
        put("tcp", JSONObject().put("activeFlows", tcpFlows.size).put("opened", tcpOpened.get()).put("closed", tcpClosed.get()))
        put("httpRequests", synchronized(httpLock) { httpLog.size })
        put("queryLog", synchronized(queryLock) { queryLog.size })
        put(
            "counters",
            JSONObject()
                .put("packets", packetsRead.get())
                .put("bytes", bytesRead.get())
                .put("dnsQueries", dnsQueries.get())
                .put("udpRelayed", udpRelayed.get())
                .put("tcpPackets", tcpPackets.get())
                .put("dropped", droppedPackets.get())
                .put("unsupported", unsupportedPackets.get()),
        )
        put("stack", PiVpnStack.status())
        put("lastError", lastError ?: JSONObject.NULL)
        put("note", BOUNDARY_NOTE)
    }

    private fun splitJson(config: TunnelConfig?): JSONObject {
        val allowed = config?.allowedPackages ?: emptyList()
        val disallowed = config?.disallowedPackages ?: emptyList()
        return JSONObject()
            .put("allowed", JSONArray(allowed))
            .put("disallowed", JSONArray(disallowed))
            .put(
                "mode",
                when {
                    allowed.isNotEmpty() -> "allowlist"
                    disallowed.isNotEmpty() -> "blocklist"
                    else -> "all"
                },
            )
    }

    private fun clearLogs() {
        synchronized(queryLock) { queryLog.clear() }
        synchronized(httpLock) { httpLog.clear() }
        synchronized(tcpLock) { tcpLog.clear() }
        seenDomains.clear()
        tcpFlows.clear()
    }

    // ---------------------------------------------------------- 伴生对接面 ----

    companion object {
        private const val EXTRA_CONFIG = "app.pi.bridge.vpn.CONFIG"

        private const val IPV4_HEADER_MIN = 20
        private const val IPV6_HEADER_LENGTH = 40
        private const val UDP_HEADER_LENGTH = 8
        private const val TCP_HEADER_MIN = 20
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17
        private const val DNS_PORT = 53
        private const val HTTP_PORT = 80
        private const val DEFAULT_HTTPS_PORT = 443
        private const val TCP_FLAG_FIN = 0x01
        private const val TCP_FLAG_SYN = 0x02
        private const val TCP_FLAG_RST = 0x04
        private const val TCP_FLAG_ACK = 0x10
        private const val DNS_TIMEOUT_MS = 4000
        private const val PROXY_HEAD_TIMEOUT_MS = 10_000
        private const val PROXY_CONNECT_TIMEOUT_MS = 10_000
        private const val PROXY_MAX_HEAD = 64 * 1024
        private const val PROXY_PIPE_BUFFER = 16 * 1024
        private const val MAX_QUERY_LOG = 2000
        private const val MAX_HTTP_LOG = 500
        private const val MAX_TCP_LOG = 500
        private const val MAX_DOMAINS_IN_STATUS = 50
        private const val MAX_HTTP_HEAD = 2048

        private val HTTP_METHODS = setOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH", "TRACE")

        private const val BOUNDARY_NOTE =
            "TUN 只看到明文 DNS 与 TCP/UDP 的目的地址；无 root 解不了他人 App 的 HTTPS。" +
                "TCP 载荷转发需用户态栈（见 stack 字段）；明文 HTTP 经本地代理端口可用。"

        /** DNS 黑名单：命中的查询回合法响应（A → 0.0.0.0，其余 → NXDOMAIN）。 */
        private val blocklist = ConcurrentHashMap.newKeySet<String>()

        /** 会话内被拦截的查询总数；[clear] 会复位。 */
        private val blockedTotal = AtomicInteger(0)

        @Volatile
        private var instance: PiVpnService? = null

        /** 真正的隧道是否在跑。 */
        fun running(): Boolean = instance?.tunRunning == true

        /** 本地代理端口；未启动时为 -1。 */
        fun proxyPort(): Int = instance?.proxyPortValue ?: -1

        /**
         * 授权 Intent；已经授权过时返回 `null`（`VpnService.prepare` 的语义）。
         * 上层拿到非空 Intent 时，自己走 `startActivityForResult`。
         */
        fun consentIntent(context: Context): Intent? =
            runCatching { VpnService.prepare(context) }.getOrNull()

        /**
         * 这个组件此刻能不能直接用：授权已给（`prepare` 返回 null）或隧道已在跑。
         *
         * 没授权不等于「设备不支持」—— 那只是一次一次性授权，[status] 的
         * `lastError` 会说明。这里回答的是「无需再次交互即可启动」。
         */
        fun available(context: Context): Boolean =
            running() || runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

        /** 启动隧道。授权缺失时返回 false，由上层先用 [consentIntent] 拿授权。 */
        fun start(context: Context, config: TunnelConfig = TunnelConfig()): Boolean {
            if (runCatching { VpnService.prepare(context) }.getOrNull() != null) return false
            applyBlocklist(config.blocklist)
            val intent = Intent(context, PiVpnService::class.java)
                .putExtra(EXTRA_CONFIG, config.toJson().toString())
            return runCatching {
                context.startService(intent)
                true
            }.getOrDefault(false)
        }

        /** 停止隧道（服务自停）。返回停止前是否在运行。 */
        fun stop(): Boolean {
            val service = instance ?: return false
            val wasRunning = service.tunRunning
            runCatching { service.stopSelf() }
            return wasRunning
        }

        /** 停止隧道（按 context）。返回停止前是否在运行。 */
        fun stop(context: Context): Boolean {
            val wasRunning = running()
            runCatching { context.stopService(Intent(context, PiVpnService::class.java)) }
            return wasRunning
        }

        /** 最近 [limit] 条 DNS 查询（域名/类型/客户端/是否被拦/时间）。不会抛异常。 */
        fun readQueries(limit: Int = 100): JSONArray = instance?.readQueries(limit) ?: JSONArray()

        /** 最近 [limit] 条 TCP 连接起止事件。 */
        fun readConnections(limit: Int = 50): JSONArray = instance?.readConnections(limit) ?: JSONArray()

        /** 最近 [limit] 条明文 HTTP 请求（方法/URI/Host/来源）。 */
        fun readHttpRequests(limit: Int = 50): JSONArray = instance?.readHttpRequests(limit) ?: JSONArray()

        /** 本会话累计拦截数。 */
        fun blockedCount(): Int = blockedTotal.get()

        /**
         * 设置 DNS 黑名单。做后缀匹配：`example.com` 同时拦 `a.example.com`。
         * @return 生效后的条目数。
         */
        fun setBlocklist(domains: List<String>): JSONObject {
            val normalized = domains.map { it.trim().lowercase().removeSuffix(".") }.filter { it.isNotEmpty() }
            blocklist.clear()
            blocklist.addAll(normalized)
            return JSONObject().put("count", blocklist.size).put("domains", JSONArray(normalized))
        }

        /** 清空查询/连接/HTTP 日志、已见域名与拦截计数（不动黑名单与包统计）。 */
        fun clear(): JSONObject {
            // 日志、已见域名与活动流都在实例上；实例不存在时本就只有空状态。
            instance?.clearLogs()
            blockedTotal.set(0)
            return JSONObject().put("cleared", true)
        }

        /** 给 `/app/health` 用的状态对象；带 context 时额外报 `available`。 */
        fun status(context: Context): JSONObject = status().put("available", available(context))

        /** 不带 context 的状态对象；绝不抛异常。 */
        fun status(): JSONObject = instance?.snapshot() ?: idleStatus()

        private fun idleStatus(): JSONObject = JSONObject()
            .put("component", "vpn")
            .put("running", false)
            .put("sessionName", JSONObject.NULL)
            .put("tunFd", -1)
            .put("address", JSONObject.NULL)
            .put("routes", JSONArray())
            .put(
                "dns",
                JSONObject().put("servers", JSONArray()).put("upstream", JSONObject.NULL)
                    .put("intercept", false).put("blocklist", blocklist.size),
            )
            .put(
                "split",
                JSONObject().put("allowed", JSONArray()).put("disallowed", JSONArray()).put("mode", "all"),
            )
            .put("seenDomains", 0)
            .put("blockedCount", blockedTotal.get())
            .put("proxyPort", -1)
            .put("domains", JSONArray())
            .put("tcp", JSONObject().put("activeFlows", 0).put("opened", 0).put("closed", 0))
            .put("httpRequests", 0)
            .put("queryLog", 0)
            .put(
                "counters",
                JSONObject().put("packets", 0).put("bytes", 0).put("dnsQueries", 0)
                    .put("udpRelayed", 0).put("tcpPackets", 0).put("dropped", 0).put("unsupported", 0),
            )
            .put("stack", PiVpnStack.status())
            .put("lastError", JSONObject.NULL)
            .put("note", BOUNDARY_NOTE)

        /** 把黑名单并入静态集合（启动配置的预置项与 [setBlocklist] 累加）。 */
        private fun applyBlocklist(domains: List<String>) {
            for (domain in domains) {
                val normalized = domain.trim().lowercase().removeSuffix(".")
                if (normalized.isNotEmpty()) blocklist.add(normalized)
            }
        }
    }
}

/**
 * 用户态 TCP/IP 栈的对接点（本轮为**占位**，未接入）。
 *
 * 为什么需要它：VpnService 只给一个 TUN fd，内核不会替我们把 TUN 里的 TCP 包
 * 变成 socket；要真正代理 TCP（进而看到明文 HTTP、或做 TLS MITM），必须有一份
 * 在用户态跑 TCP 的栈（lwIP / tun2socks / hev-socks5-tunnel），它把 TUN 的 TCP
 * 流以 socket 形式交给上层。
 *
 * 本仓库没有 NDK/CMake 构建步骤，本轮不引入原生代码（会炸 release 构建），所以
 * 这里只留接口，[available] 恒为 false，[start]/[stop] 返回 UNSUPPORTED。
 *
 * 下一阶段：把 native 栈编译成 `.so`，经 JNI 起一个 tun2socks，管道接到
 * [PiVpnService] 的 TUN fd；届时 [available] 返回 true，TCP 转发与 HTTP 解析
 * 才会真正看到数据。解他人 App 的 HTTPS 另需在目标 App 信任域装用户 CA。
 */
object PiVpnStack {
    const val ID = "userspace-tcpip"

    /** 本轮恒 false：没有编译进来的原生栈。 */
    fun available(): Boolean = false

    fun status(): JSONObject = JSONObject()
        .put("id", ID)
        .put("available", false)
        .put("code", "UNSUPPORTED")
        .put("reason", "本轮未编译原生用户态 TCP/IP 栈（仓库没有 NDK/CMake 步骤）。")
        .put("nextPhase", "编译 lwIP/tun2socks 为 .so，经 JNI 接 TUN fd；HTTPS 正文另需用户 CA。")

    /** 接线占位：即便被调用也如实返回 UNSUPPORTED，不会假装转发。 */
    fun start(tunFd: Int, config: JSONObject? = null): JSONObject = status()
        .put("tunFd", tunFd)
        .put("requestedConfig", config ?: JSONObject.NULL)

    fun stop(): JSONObject = status()
}

// ---------------------------------------------------------------- 报文工具 ----

private const val DNS_HEADER_LENGTH = 12

private data class DnsQuestion(val name: String, val type: Int, val questionEnd: Int)

/** 按 CIDR 文本拆出「地址 + 前缀长度」；没有 `/` 或前缀非法时返回 null。 */
private fun splitCidr(cidr: String): Pair<String, Int>? {
    val trimmed = cidr.trim()
    if (trimmed.isEmpty()) return null
    val slash = trimmed.indexOf('/')
    if (slash <= 0) return null
    val prefix = trimmed.substring(slash + 1).trim().toIntOrNull() ?: return null
    return trimmed.substring(0, slash).trim() to prefix
}

/**
 * 把主机地址掩成网络地址。
 *
 * `VpnService.Builder.addRoute` 要的是网络地址，传 `10.0.0.1/24` 这种主机地址
 * 在某些 ROM 上会抛 `IllegalArgumentException`；这里统一按位与后再交出去。
 */
private fun networkHost(host: String, prefix: Int): String? = runCatching {
    val bytes = InetAddress.getByName(host).address
    val totalBits = bytes.size * 8
    if (prefix < 0 || prefix > totalBits) return null
    var remaining = prefix
    val masked = ByteArray(bytes.size)
    for (i in bytes.indices) {
        val take = remaining.coerceIn(0, 8)
        val mask = if (take == 0) 0 else (0xFF shl (8 - take)) and 0xFF
        masked[i] = (bytes[i].toInt() and mask).toByte()
        remaining -= take
    }
    InetAddress.getByAddress(masked).hostAddress
}.getOrNull()

private fun ipv4ToString(bytes: ByteArray): String? {
    if (bytes.size != 4) return null
    return "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}." +
        "${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"
}

private fun ipv6ToString(bytes: ByteArray): String? {
    if (bytes.size != 16) return null
    return runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull()
}

/** 拆 `host:port`（带可选 `[]`），缺端口时用 [defaultPort]。 */
private fun splitHostPort(text: String, defaultPort: Int): Pair<String, Int>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val close = trimmed.lastIndexOf(']')
    val colon = trimmed.lastIndexOf(':')
    if (colon > close) {
        val host = trimmed.substring(0, colon).trim().removePrefix("[").removeSuffix("]")
        val port = trimmed.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty()) return null
        return host to port
    }
    val host = trimmed.removePrefix("[").removeSuffix("]")
    return if (host.isEmpty()) null else host to defaultPort
}

private data class HttpTarget(val host: String, val port: Int, val path: String)

private fun parseAbsoluteHttp(target: String): HttpTarget? {
    if (!target.startsWith("http://", ignoreCase = true)) return null
    val rest = target.substring("http://".length)
    val slash = rest.indexOf('/')
    val authority = if (slash < 0) rest else rest.substring(0, slash)
    val path = if (slash < 0) "/" else rest.substring(slash)
    val hostPort = splitHostPort(authority, 80) ?: return null
    return HttpTarget(hostPort.first, hostPort.second, path)
}

private fun u16(buffer: ByteArray, offset: Int): Int =
    ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

private fun putU16(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = ((value ushr 8) and 0xFF).toByte()
    buffer[offset + 1] = (value and 0xFF).toByte()
}

private fun dnsTypeName(type: Int): String = when (type) {
    1 -> "A"
    2 -> "NS"
    5 -> "CNAME"
    6 -> "SOA"
    12 -> "PTR"
    15 -> "MX"
    16 -> "TXT"
    28 -> "AAAA"
    33 -> "SRV"
    255 -> "ANY"
    0 -> "?"
    else -> "TYPE$type"
}

/**
 * 从 DNS 报文的 Question 段读出 `QNAME`、`QTYPE` 与 Question 结束偏移。
 *
 * 只处理普通标签：查询里不会出现压缩指针，遇到 `0xC0` 就放弃（返回 null）。
 */
private fun parseDnsQuestion(message: ByteArray, start: Int, end: Int): DnsQuestion? {
    var index = start
    val name = StringBuilder()
    var guard = 0
    while (index < end && guard++ < 128) {
        val labelLength = message[index].toInt() and 0xFF
        if (labelLength == 0) {
            index += 1
            break
        }
        if ((labelLength and 0xC0) == 0xC0) return null
        if (index + 1 + labelLength > end) return null
        if (name.isNotEmpty()) name.append('.')
        for (j in 0 until labelLength) name.append((message[index + 1 + j].toInt() and 0xFF).toChar())
        index += 1 + labelLength
    }
    if (index + 4 > end) return null
    val type = u16(message, index)
    val questionEnd = index + 4
    val normalized = name.toString().lowercase().removeSuffix(".")
    if (normalized.isEmpty()) return null
    return DnsQuestion(normalized, type, questionEnd)
}

/**
 * 组一个合法的 DNS 拦截响应：A 查询回 `0.0.0.0`，其余类型回 NXDOMAIN。
 *
 * 这里只是把查询报文头改写成响应（QR=1、RA=1、保留 RD），再原样带上 Question
 * 段；A 查询额外加一条 `name→0.0.0.0` 的 Answer。两条路径对客户端都是合法 DNS。
 */
private fun buildDnsBlockResponse(message: ByteArray, question: DnsQuestion): ByteArray {
    val questionBytes = message.copyOfRange(DNS_HEADER_LENGTH, question.questionEnd)
    val answer = if (question.type == 1) zeroAddressAnswer() else null
    val out = ByteArray(DNS_HEADER_LENGTH + questionBytes.size + (answer?.size ?: 0))
    out[0] = message[0]
    out[1] = message[1]
    val recursionDesired = message[2].toInt() and 0x01
    val flags = 0x8000 or (recursionDesired shl 8) or 0x0080 or (if (answer != null) 0 else 3)
    putU16(out, 2, flags)
    putU16(out, 4, 1) // QDCOUNT
    putU16(out, 6, if (answer != null) 1 else 0) // ANCOUNT
    putU16(out, 8, 0)
    putU16(out, 10, 0)
    questionBytes.copyInto(out, DNS_HEADER_LENGTH)
    if (answer != null) answer.copyInto(out, DNS_HEADER_LENGTH + questionBytes.size)
    return out
}

private fun zeroAddressAnswer(): ByteArray = byteArrayOf(
    0xC0.toByte(), 0x0C, // 名字指针指向 Question 的 QNAME（偏移 12）
    0x00, 0x01, // TYPE A
    0x00, 0x01, // CLASS IN
    0x00, 0x00, 0x00, 0x3C, // TTL 60
    0x00, 0x04, // RDLENGTH
    0x00, 0x00, 0x00, 0x00, // 0.0.0.0
)

private fun foldSum(sum: Int): Int {
    var value = sum
    while (value ushr 16 != 0) value = (value and 0xFFFF) + (value ushr 16)
    return value
}

private fun sumBytes(sum: Int, data: ByteArray, offset: Int, length: Int): Int {
    var value = sum
    var i = offset
    val end = offset + length
    while (i + 1 < end) {
        value += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        i += 2
    }
    if (i < end) value += (data[i].toInt() and 0xFF) shl 8
    return foldSum(value)
}

private fun complement(sum: Int): Int = foldSum(sum).inv() and 0xFFFF

/**
 * 组一个完整的 IPv4/UDP 报文（IP 头 + UDP 头 + 载荷），校验和都算好。
 * [srcIp]/[dstIp] 是 4 字节地址，方向由调用方换好。
 */
private fun buildIpv4Udp(
    srcIp: ByteArray,
    srcPort: Int,
    dstIp: ByteArray,
    dstPort: Int,
    payload: ByteArray,
): ByteArray {
    val udpLength = 8 + payload.size
    val totalLength = 20 + udpLength
    val packet = ByteArray(totalLength)
    packet[0] = 0x45
    putU16(packet, 2, totalLength)
    putU16(packet, 4, 0)
    putU16(packet, 6, 0x4000) // 不分片
    packet[8] = 64
    packet[9] = 17 // UDP
    srcIp.copyInto(packet, 12)
    dstIp.copyInto(packet, 16)
    putU16(packet, 10, complement(sumBytes(0, packet, 0, 20)))

    putU16(packet, 20, srcPort)
    putU16(packet, 22, dstPort)
    putU16(packet, 24, udpLength)
    payload.copyInto(packet, 28)

    val pseudo = ByteArray(12)
    srcIp.copyInto(pseudo, 0)
    dstIp.copyInto(pseudo, 4)
    pseudo[9] = 17
    putU16(pseudo, 10, udpLength)
    val udpSum = complement(sumBytes(sumBytes(0, pseudo, 0, 12), packet, 20, udpLength))
    // IPv4 里 UDP 校验和 0 表示「未计算」，所以算出 0 时要写成 0xFFFF。
    putU16(packet, 26, if (udpSum == 0) 0xFFFF else udpSum)
    return packet
}

/** 组一个完整的 IPv6/UDP 报文（40 字节头 + UDP 头 + 载荷），含伪首部校验和。 */
private fun buildIpv6Udp(
    srcIp: ByteArray,
    srcPort: Int,
    dstIp: ByteArray,
    dstPort: Int,
    payload: ByteArray,
): ByteArray {
    val udpLength = 8 + payload.size
    val packet = ByteArray(40 + udpLength)
    packet[0] = 0x60
    putU16(packet, 4, udpLength)
    packet[6] = 17 // UDP
    packet[7] = 64
    srcIp.copyInto(packet, 8)
    dstIp.copyInto(packet, 24)
    putU16(packet, 40, srcPort)
    putU16(packet, 42, dstPort)
    putU16(packet, 44, udpLength)
    payload.copyInto(packet, 48)

    val pseudo = ByteArray(40)
    srcIp.copyInto(pseudo, 0)
    dstIp.copyInto(pseudo, 16)
    pseudo[32] = ((udpLength ushr 24) and 0xFF).toByte()
    pseudo[33] = ((udpLength ushr 16) and 0xFF).toByte()
    pseudo[34] = ((udpLength ushr 8) and 0xFF).toByte()
    pseudo[35] = (udpLength and 0xFF).toByte()
    pseudo[39] = 17
    val udpSum = complement(sumBytes(sumBytes(0, pseudo, 0, 40), packet, 40, udpLength))
    putU16(packet, 46, if (udpSum == 0) 0xFFFF else udpSum)
    return packet
}
