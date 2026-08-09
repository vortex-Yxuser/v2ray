package com.simplesshvpn.app

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/**
 * Custom SocketFactory with improved Proxy + Payload handling
 * and detailed error reporting.
 */
class ProxySocketFactory(
    private val proxyType: ProxyType,
    private val proxyHost: String?,
    private val proxyPort: Int,
    private val proxyUser: String?,
    private val proxyPass: String?,
    private val payload: String?
) : SocketFactory() {

    companion object {
        private const val TAG = "ProxySocketFactory"
        private const val CONNECT_TIMEOUT = 25000 // 25 seconds
    }

    override fun createSocket(): Socket = Socket()

    override fun createSocket(host: String, port: Int): Socket {
        return createSocket(InetAddress.getByName(host), port)
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val socket = createSocket()
        socket.bind(InetSocketAddress(localHost, localPort))
        connectViaProxy(socket, InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val socket = createSocket()
        connectViaProxy(socket, InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        val socket = createSocket()
        socket.bind(InetSocketAddress(localAddress, localPort))
        connectViaProxy(socket, InetSocketAddress(address, port))
        return socket
    }

    private fun connectViaProxy(socket: Socket, target: SocketAddress) {
        try {
            when (proxyType) {
                ProxyType.NONE -> {
                    Log.i(TAG, "Direct connection (no proxy)")
                    socket.connect(target, CONNECT_TIMEOUT)
                }
                ProxyType.HTTP, ProxyType.HTTPS -> {
                    Log.i(TAG, "Connecting via HTTP/HTTPS proxy $proxyHost:$proxyPort")
                    connectHttpConnect(socket, target as InetSocketAddress)
                }
                ProxyType.SOCKS5 -> {
                    Log.i(TAG, "Connecting via SOCKS5 proxy $proxyHost:$proxyPort")
                    connectSocks5(socket, target as InetSocketAddress)
                }
            }
        } catch (e: SocketTimeoutException) {
            throw IOException("Proxy/SSH connect timed out after ${CONNECT_TIMEOUT}ms → ${e.message}", e)
        } catch (e: Exception) {
            throw IOException("Proxy connection failed: ${e.message}", e)
        }
    }

    private fun connectHttpConnect(socket: Socket, target: InetSocketAddress) {
        if (proxyHost.isNullOrBlank()) {
            socket.connect(target, CONNECT_TIMEOUT)
            return
        }

        // 1. Connect to the proxy first
        socket.soTimeout = CONNECT_TIMEOUT
        socket.connect(InetSocketAddress(proxyHost, proxyPort), CONNECT_TIMEOUT)
        Log.i(TAG, "Connected to proxy $proxyHost:$proxyPort")

        val hostPort = "\( {target.hostString}: \){target.port}"
        val request = buildHttpConnectRequest(hostPort)

        Log.d(TAG, "Sending Payload:\n$request")

        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1)
        writer.write(request)
        writer.flush()

        // 2. Read response
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val statusLine = reader.readLine()
            ?: throw IOException("No response from HTTP proxy (empty reply)")

        Log.i(TAG, "Proxy response: $statusLine")

        if (!statusLine.contains("200")) {
            // Drain remaining headers for better error message
            val headers = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                headers.append(line).append("\n")
            }
            throw IOException("HTTP CONNECT failed → $statusLine\n$headers")
        }

        // Drain remaining headers
        var line: String?
        while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
            // skip
        }

        Log.i(TAG, "HTTP CONNECT successful (200)")
    }

    private fun buildHttpConnectRequest(hostPort: String): String {
        val template = payload?.takeIf { it.isNotBlank() }
            ?: "CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf][crlf]"

        var req = template
            .replace("[host_port]", hostPort)
            .replace("[host]", hostPort.substringBefore(":"))
            .replace("[protocol]", "HTTP/1.1")
            .replace("[crlf]", "\r\n")
            .replace("[lf]", "\n")
            .replace("\\r\\n", "\r\n")
            .replace("\\n", "\n")

        // Ensure ends with double CRLF
        if (!req.endsWith("\r\n\r\n") && !req.endsWith("\n\n")) {
            if (!req.endsWith("\r\n") && !req.endsWith("\n")) {
                req += "\r\n"
            }
            req += "\r\n"
        }

        // Add Proxy-Authorization if needed
        if (!proxyUser.isNullOrBlank()) {
            val credentials = "\( proxyUser: \){proxyPass ?: ""}"
            val encoded = Base64.encodeToString(
                credentials.toByteArray(Charsets.ISO_8859_1),
                Base64.NO_WRAP
            )
            val insertPoint = req.lastIndexOf("\r\n\r\n")
            if (insertPoint > 0) {
                req = req.substring(0, insertPoint) +
                        "\r\nProxy-Authorization: Basic $encoded" +
                        req.substring(insertPoint)
            } else {
                req = req.trimEnd() + "\r\nProxy-Authorization: Basic $encoded\r\n\r\n"
            }
        }

        return req
    }

    private fun connectSocks5(socket: Socket, target: InetSocketAddress) {
        if (proxyHost.isNullOrBlank()) {
            socket.connect(target, CONNECT_TIMEOUT)
            return
        }

        socket.soTimeout = CONNECT_TIMEOUT
        socket.connect(InetSocketAddress(proxyHost, proxyPort), CONNECT_TIMEOUT)

        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        // Greeting
        if (!proxyUser.isNullOrBlank()) {
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        out.flush()

        val greeting = ByteArray(2)
        readFully(inp, greeting)
        if (greeting[0] != 0x05.toByte()) {
            throw IOException("Invalid SOCKS5 version from proxy")
        }

        when (greeting[1].toInt() and 0xFF) {
            0x00 -> { /* no auth */ }
            0x02 -> {
                val userBytes = (proxyUser ?: "").toByteArray(Charsets.UTF_8)
                val passBytes = (proxyPass ?: "").toByteArray(Charsets.UTF_8)
                val auth = ByteArray(3 + userBytes.size + passBytes.size)
                auth[0] = 0x01
                auth[1] = userBytes.size.toByte()
                System.arraycopy(userBytes, 0, auth, 2, userBytes.size)
                auth[2 + userBytes.size] = passBytes.size.toByte()
                System.arraycopy(passBytes, 0, auth, 3 + userBytes.size, passBytes.size)
                out.write(auth)
                out.flush()

                val authResp = ByteArray(2)
                readFully(inp, authResp)
                if (authResp[1] != 0x00.toByte()) {
                    throw IOException("SOCKS5 authentication failed")
                }
            }
            else -> throw IOException("SOCKS5: no acceptable authentication method")
        }

        // CONNECT request
        val hostBytes = target.hostString.toByteArray(Charsets.UTF_8)
        val port = target.port
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((port shr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (port and 0xFF).toByte()
        out.write(req)
        out.flush()

        val resp = ByteArray(4)
        readFully(inp, resp)
        if (resp[1] != 0x00.toByte()) {
            throw IOException("SOCKS5 CONNECT failed with code ${resp[1].toInt() and 0xFF}")
        }

        // Skip bound address
        when (resp[3].toInt() and 0xFF) {
            0x01 -> readFully(inp, ByteArray(6))
            0x03 -> {
                val len = ByteArray(1)
                readFully(inp, len)
                readFully(inp, ByteArray((len[0].toInt() and 0xFF) + 2))
            }
            0x04 -> readFully(inp, ByteArray(18))
        }

        Log.i(TAG, "SOCKS5 CONNECT successful")
    }

    private fun readFully(inp: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("Unexpected end of stream while reading from proxy")
            off += n
        }
    }
}
