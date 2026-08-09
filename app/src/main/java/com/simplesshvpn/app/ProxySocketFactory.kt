package com.simplesshvpn.app

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import javax.net.SocketFactory

/**
 * Custom SocketFactory that supports HTTP CONNECT and SOCKS5 proxies
 * with optional payload injection for HTTP.
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
    }

    override fun createSocket(): Socket {
        return Socket()
    }

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
        when (proxyType) {
            ProxyType.NONE -> {
                socket.connect(target, 15000)
            }
            ProxyType.HTTP, ProxyType.HTTPS -> {
                connectHttpConnect(socket, target as InetSocketAddress)
            }
            ProxyType.SOCKS5 -> {
                connectSocks5(socket, target as InetSocketAddress)
            }
        }
    }

    private fun connectHttpConnect(socket: Socket, target: InetSocketAddress) {
        if (proxyHost.isNullOrBlank()) {
            socket.connect(target, 15000)
            return
        }

        // Connect to the proxy first
        socket.connect(InetSocketAddress(proxyHost, proxyPort), 15000)

        val hostPort = "${target.hostString}:${target.port}"
        val request = buildHttpConnectRequest(hostPort)

        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1)
        writer.write(request)
        writer.flush()

        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val statusLine = reader.readLine() ?: throw IOException("No response from HTTP proxy")
        Log.d(TAG, "HTTP Proxy response: $statusLine")

        if (!statusLine.contains("200")) {
            // Drain remaining headers
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                // skip
            }
            throw IOException("HTTP CONNECT failed: $statusLine")
        }

        // Drain headers until empty line
        var line: String?
        while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
            // skip headers
        }
    }

    private fun buildHttpConnectRequest(hostPort: String): String {
        val template = payload?.takeIf { it.isNotBlank() }
            ?: "CONNECT [host_port] HTTP/1.1\r\nHost: [host]\r\n\r\n"

        var req = template
            .replace("[host_port]", hostPort)
            .replace("[host]", hostPort.substringBefore(":"))
            .replace("[crlf]", "\r\n")
            .replace("[lf]", "\n")
            .replace("\\r\\n", "\r\n")
            .replace("\\n", "\n")

        // Ensure ends with double CRLF if not present
        if (!req.endsWith("\r\n\r\n") && !req.endsWith("\n\n")) {
            if (!req.endsWith("\r\n") && !req.endsWith("\n")) {
                req += "\r\n"
            }
            req += "\r\n"
        }

        // Add Proxy-Authorization if credentials provided
        if (!proxyUser.isNullOrBlank()) {
            val credentials = "$proxyUser:${proxyPass ?: ""}"
            val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)
            // Insert before the final empty line
            val insertPoint = req.lastIndexOf("\r\n\r\n")
            if (insertPoint > 0) {
                req = req.substring(0, insertPoint) +
                        "\r\nProxy-Authorization: Basic $encoded" +
                        req.substring(insertPoint)
            } else {
                req = req.trimEnd() + "\r\nProxy-Authorization: Basic $encoded\r\n\r\n"
            }
        }

        Log.d(TAG, "HTTP CONNECT request:\n$req")
        return req
    }

    private fun connectSocks5(socket: Socket, target: InetSocketAddress) {
        if (proxyHost.isNullOrBlank()) {
            socket.connect(target, 15000)
            return
        }

        socket.connect(InetSocketAddress(proxyHost, proxyPort), 15000)
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        // Greeting
        if (!proxyUser.isNullOrBlank()) {
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02)) // NO AUTH + USERNAME/PASSWORD
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00)) // NO AUTH only
        }
        out.flush()

        val greeting = ByteArray(2)
        readFully(inp, greeting)
        if (greeting[0] != 0x05.toByte()) {
            throw IOException("Invalid SOCKS5 version")
        }

        when (greeting[1].toInt() and 0xFF) {
            0x00 -> { /* no auth */ }
            0x02 -> {
                // Username/Password auth
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
            else -> throw IOException("SOCKS5 no acceptable authentication method")
        }

        // CONNECT request
        val hostBytes = target.hostString.toByteArray(Charsets.UTF_8)
        val port = target.port
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05 // VER
        req[1] = 0x01 // CONNECT
        req[2] = 0x00 // RSV
        req[3] = 0x03 // DOMAINNAME
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
            0x01 -> readFully(inp, ByteArray(4 + 2)) // IPv4 + port
            0x03 -> {
                val len = ByteArray(1)
                readFully(inp, len)
                readFully(inp, ByteArray((len[0].toInt() and 0xFF) + 2))
            }
            0x04 -> readFully(inp, ByteArray(16 + 2)) // IPv6 + port
        }
    }

    private fun readFully(inp: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("Unexpected end of stream")
            off += n
        }
    }
}
