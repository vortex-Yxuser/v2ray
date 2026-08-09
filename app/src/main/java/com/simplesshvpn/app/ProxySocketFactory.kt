package com.simplesshvpn.app

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory

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

        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 30_000

        private const val SOCKS_VERSION = 0x05
    }

    override fun createSocket(): Socket {
        return Socket()
    }

    override fun createSocket(
        host: String,
        port: Int
    ): Socket {
        val socket = createSocket()

        connectViaProxy(
            socket,
            InetSocketAddress(host, port)
        )

        return socket
    }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket {
        val socket = createSocket()

        socket.bind(
            InetSocketAddress(
                localHost,
                localPort
            )
        )

        connectViaProxy(
            socket,
            InetSocketAddress(host, port)
        )

        return socket
    }

    override fun createSocket(
        address: InetAddress,
        port: Int
    ): Socket {
        val socket = createSocket()

        connectViaProxy(
            socket,
            InetSocketAddress(address, port)
        )

        return socket
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket {
        val socket = createSocket()

        socket.bind(
            InetSocketAddress(
                localAddress,
                localPort
            )
        )

        connectViaProxy(
            socket,
            InetSocketAddress(address, port)
        )

        return socket
    }

    private fun connectViaProxy(
        socket: Socket,
        target: SocketAddress
    ) {
        try {
            when (proxyType) {

                ProxyType.NONE -> {
                    Log.i(
                        TAG,
                        "Direct connection"
                    )

                    socket.connect(
                        target,
                        CONNECT_TIMEOUT
                    )
                }

                ProxyType.HTTP,
                ProxyType.HTTPS -> {
                    connectHttpProxy(
                        socket,
                        target as InetSocketAddress
                    )
                }

                ProxyType.SOCKS5 -> {
                    connectSocks5(
                        socket,
                        target as InetSocketAddress
                    )
                }
            }

        } catch (e: SocketTimeoutException) {

            closeQuietly(socket)

            throw IOException(
                "Connection timeout: $proxyHost:$proxyPort",
                e
            )

        } catch (e: Exception) {

            closeQuietly(socket)

            throw IOException(
                "Proxy connection failed: ${e.message}",
                e
            )
        }
    }

    // ---------------------------------------------------------
    // HTTP / HTTPS proxy
    // ---------------------------------------------------------

    private fun connectHttpProxy(
        socket: Socket,
        target: InetSocketAddress
    ) {
        if (proxyHost.isNullOrBlank()) {
            throw IOException(
                "Proxy host is empty"
            )
        }

        socket.soTimeout = READ_TIMEOUT

        Log.i(
            TAG,
            "Connecting proxy $proxyHost:$proxyPort"
        )

        socket.connect(
            InetSocketAddress(
                proxyHost,
                proxyPort
            ),
            CONNECT_TIMEOUT
        )

        Log.i(
            TAG,
            "Proxy TCP connected"
        )

        val request = buildPayload(
            target.hostString,
            target.port
        )

        Log.d(
            TAG,
            "Payload:\n$request"
        )

        val writer = OutputStreamWriter(
            socket.getOutputStream(),
            Charsets.ISO_8859_1
        )

        writer.write(request)
        writer.flush()

        val reader = BufferedReader(
            InputStreamReader(
                socket.getInputStream(),
                Charsets.ISO_8859_1
            )
        )

        val statusLine =
            reader.readLine()
                ?: throw IOException(
                    "Proxy returned empty response"
                )

        Log.i(
            TAG,
            "Proxy response: $statusLine"
        )

        val statusCode =
            Regex(
                "HTTP/\\d\\.\\d\\s+(\\d+)"
            )
                .find(statusLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        if (statusCode != 200) {

            val headers = StringBuilder()

            while (true) {
                val line = reader.readLine()
                    ?: break

                if (line.isEmpty()) {
                    break
                }

                headers
                    .append(line)
                    .append('\n')
            }

            throw IOException(
                "HTTP CONNECT failed: " +
                        "$statusLine\n$headers"
            )
        }

        while (true) {
            val line = reader.readLine()
                ?: break

            if (line.isEmpty()) {
                break
            }
        }

        Log.i(
            TAG,
            "HTTP CONNECT 200 OK"
        )
    }

    private fun buildPayload(
        host: String,
        port: Int
    ): String {

        val hostPort = "$host:$port"

        var request =
            payload
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "CONNECT [host_port] [protocol][crlf]" +
                        "Host: [host][crlf]" +
                        "[crlf]"

        request = request
            .replace(
                "[host_port]",
                hostPort
            )
            .replace(
                "[host]",
                host
            )
            .replace(
                "[port]",
                port.toString()
            )
            .replace(
                "[protocol]",
                "HTTP/1.1"
            )
            .replace(
                "[crlf]",
                "\r\n"
            )
            .replace(
                "[lf]",
                "\n"
            )
            .replace(
                "\\r\\n",
                "\r\n"
            )
            .replace(
                "\\n",
                "\n"
            )

        if (!request.contains("\r\n\r\n")) {
            request =
                request.trimEnd(
                    '\r',
                    '\n'
                ) + "\r\n\r\n"
        }

        if (!proxyUser.isNullOrBlank()) {

            val credentials =
                "$proxyUser:${proxyPass ?: ""}"

            val encoded =
                Base64.encodeToString(
                    credentials.toByteArray(
                        Charsets.ISO_8859_1
                    ),
                    Base64.NO_WRAP
                )

            val separator = "\r\n\r\n"

            request =
                request.substringBeforeLast(
                    separator
                ) +
                        "\r\nProxy-Authorization: Basic $encoded" +
                        separator
        }

        return request
    }

    // ---------------------------------------------------------
    // SOCKS5
    // ---------------------------------------------------------

    private fun connectSocks5(
        socket: Socket,
        target: InetSocketAddress
    ) {
        if (proxyHost.isNullOrBlank()) {
            throw IOException(
                "SOCKS5 proxy host is empty"
            )
        }

        socket.soTimeout = READ_TIMEOUT

        Log.i(
            TAG,
            "Connecting SOCKS5 proxy $proxyHost:$proxyPort"
        )

        socket.connect(
            InetSocketAddress(
                proxyHost,
                proxyPort
            ),
            CONNECT_TIMEOUT
        )

        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // -----------------------------------------------------
        // SOCKS5 greeting
        // -----------------------------------------------------

        if (!proxyUser.isNullOrBlank()) {

            output.write(
                byteArrayOf(
                    0x05.toByte(),
                    0x02.toByte(),
                    0x00.toByte(),
                    0x02.toByte()
                )
            )

        } else {

            output.write(
                byteArrayOf(
                    0x05.toByte(),
                    0x01.toByte(),
                    0x00.toByte()
                )
            )
        }

        output.flush()

        val greeting = ByteArray(2)

        readFully(
            input,
            greeting
        )

        if (
            (greeting[0].toInt() and 0xff) !=
            SOCKS_VERSION
        ) {
            throw IOException(
                "Invalid SOCKS5 response version"
            )
        }

        val method =
            greeting[1].toInt() and 0xff

        when (method) {

            // NO AUTHENTICATION
            0x00 -> {
                Log.d(
                    TAG,
                    "SOCKS5 no authentication"
                )
            }

            // USERNAME / PASSWORD
            0x02 -> {

                if (proxyUser.isNullOrBlank()) {
                    throw IOException(
                        "SOCKS5 requires username/password"
                    )
                }

                authenticateSocks5(
                    input,
                    output
                )
            }

            else -> {
                throw IOException(
                    "SOCKS5 authentication method rejected: $method"
                )
            }
        }

        // -----------------------------------------------------
        // SOCKS5 CONNECT request
        // -----------------------------------------------------

        val hostBytes =
            target.hostString.toByteArray(
                Charsets.UTF_8
            )

        if (hostBytes.isEmpty()) {
            throw IOException(
                "SOCKS5 target host is empty"
            )
        }

        if (hostBytes.size > 255) {
            throw IOException(
                "SOCKS5 target hostname is too long"
            )
        }

        // VER
        writeByte(
            output,
            0x05
        )

        // CMD = CONNECT
        writeByte(
            output,
            0x01
        )

        // RSV
        writeByte(
            output,
            0x00
        )

        // ATYP = DOMAIN
        writeByte(
            output,
            0x03
        )

        // DOMAIN LENGTH
        writeByte(
            output,
            hostBytes.size
        )

        // DOMAIN
        output.write(hostBytes)

        // PORT high byte
        writeByte(
            output,
            (target.port shr 8) and 0xff
        )

        // PORT low byte
        writeByte(
            output,
            target.port and 0xff
        )

        output.flush()

        // -----------------------------------------------------
        // SOCKS5 response
        // -----------------------------------------------------

        val response = ByteArray(4)

        readFully(
            input,
            response
        )

        if (
            (response[0].toInt() and 0xff) !=
            SOCKS_VERSION
        ) {
            throw IOException(
                "Invalid SOCKS5 CONNECT response version"
            )
        }

        val reply =
            response[1].toInt() and 0xff

        if (reply != 0x00) {

            throw IOException(
                "SOCKS5 CONNECT rejected: $reply"
            )
        }

        val addressType =
            response[3].toInt() and 0xff

        when (addressType) {

            // IPv4
            0x01 -> {
                readFully(
                    input,
                    ByteArray(4)
                )
            }

            // DOMAIN
            0x03 -> {

                val length =
                    input.read()

                if (length < 0) {
                    throw IOException(
                        "Invalid SOCKS5 domain response"
                    )
                }

                readFully(
                    input,
                    ByteArray(length)
                )
            }

            // IPv6
            0x04 -> {
                readFully(
                    input,
                    ByteArray(16)
                )
            }

            else -> {
                throw IOException(
                    "Invalid SOCKS5 address type: $addressType"
                )
            }
        }

        // Destination port
        readFully(
            input,
            ByteArray(2)
        )

        Log.i(
            TAG,
            "SOCKS5 CONNECT successful"
        )
    }

    // ---------------------------------------------------------
    // SOCKS5 username/password authentication
    // ---------------------------------------------------------

    private fun authenticateSocks5(
        input: InputStream,
        output: OutputStream
    ) {

        val user =
            proxyUser
                ?.toByteArray(
                    Charsets.UTF_8
                )
                ?: ByteArray(0)

        val pass =
            (proxyPass ?: "")
                .toByteArray(
                    Charsets.UTF_8
                )

        if (user.isEmpty()) {
            throw IOException(
                "SOCKS5 username is empty"
            )
        }

        if (user.size > 255) {
            throw IOException(
                "SOCKS5 username is too long"
            )
        }

        if (pass.size > 255) {
            throw IOException(
                "SOCKS5 password is too long"
            )
        }

        // Version
        writeByte(
            output,
            0x01
        )

        // Username length
        writeByte(
            output,
            user.size
        )

        // Username
        output.write(user)

        // Password length
        writeByte(
            output,
            pass.size
        )

        // Password
        output.write(pass)

        output.flush()

        val authResponse =
            ByteArray(2)

        readFully(
            input,
            authResponse
        )

        val version =
            authResponse[0].toInt() and 0xff

        val status =
            authResponse[1].toInt() and 0xff

        if (version != 0x01) {
            throw IOException(
                "Invalid SOCKS5 authentication response"
            )
        }

        if (status != 0x00) {
            throw IOException(
                "SOCKS5 authentication failed: $status"
            )
        }

        Log.d(
            TAG,
            "SOCKS5 authentication successful"
        )
    }

    // ---------------------------------------------------------
    // Safe byte writer
    // ---------------------------------------------------------

    private fun writeByte(
        output: OutputStream,
        value: Int
    ) {
        output.write(
            value and 0xff
        )
    }

    // ---------------------------------------------------------
    // Read exactly N bytes
    // ---------------------------------------------------------

    private fun readFully(
        input: InputStream,
        buffer: ByteArray
    ) {

        var offset = 0

        while (offset < buffer.size) {

            val count =
                input.read(
                    buffer,
                    offset,
                    buffer.size - offset
                )

            if (count < 0) {
                throw IOException(
                    "Unexpected end of stream"
                )
            }

            if (count == 0) {
                continue
            }

            offset += count
        }
    }

    private fun closeQuietly(
        socket: Socket
    ) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
