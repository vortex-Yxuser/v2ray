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

        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 30000
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

            throw IOException(
                "Connection timeout: " +
                        "${proxyHost}:${proxyPort}",
                e
            )

        } catch (e: Exception) {

            try {
                socket.close()
            } catch (_: Exception) {
            }

            throw IOException(
                "Proxy connection failed: " +
                        e.message,
                e
            )
        }
    }

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

        val request =
            buildPayload(
                target.hostString,
                target.port
            )

        Log.d(
            TAG,
            "Payload:\n$request"
        )

        val writer =
            OutputStreamWriter(
                socket.getOutputStream(),
                Charsets.ISO_8859_1
            )

        writer.write(request)
        writer.flush()

        val reader =
            BufferedReader(
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

            val headers =
                StringBuilder()

            while (true) {

                val line =
                    reader.readLine()
                        ?: break

                if (line.isEmpty()) {
                    break
                }

                headers.append(line)
                    .append('\n')
            }

            throw IOException(
                "HTTP CONNECT failed: " +
                        "$statusLine\n$headers"
            )
        }

        while (true) {

            val line =
                reader.readLine()
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

        val hostPort =
            "$host:$port"

        var request =
            payload
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "CONNECT [host_port] [protocol][crlf]" +
                   "Host: [host][crlf]" +
                   "[crlf]"

        request =
            request
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
                ) +
                        "\r\n\r\n"
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

            val separator =
                if (
                    request.contains("\r\n\r\n")
                ) {
                    "\r\n\r\n"
                } else {
                    "\n\n"
                }

            request =
                request.substringBeforeLast(
                    separator
                ) +
                        "\r\nProxy-Authorization: Basic $encoded" +
                        separator
        }

        return request
    }

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

        socket.connect(
            InetSocketAddress(
                proxyHost,
                proxyPort
            ),
            CONNECT_TIMEOUT
        )

        val input =
            socket.getInputStream()

        val output =
            socket.getOutputStream()

        if (!proxyUser.isNullOrBlank()) {

            output.write(
                byteArrayOf(
                    0x05,
                    0x02,
                    0x00,
                    0x02
                )
            )

        } else {

            output.write(
                byteArrayOf(
                    0x05,
                    0x01,
                    0x00
                )
            )
        }

        output.flush()

        val greeting =
            ByteArray(2)

        readFully(
            input,
            greeting
        )

        if (
            greeting[0] !=
            0x05.toByte()
        ) {
            throw IOException(
                "Invalid SOCKS5 response"
            )
        }

        when (
            greeting[1].toInt() and 0xff
        ) {

            0x00 -> Unit

            0x02 -> {

                if (proxyUser.isNullOrBlank()) {
                    throw IOException(
                        "SOCKS5 requires authentication"
                    )
                }

                val user =
                    proxyUser.toByteArray(
                        Charsets.UTF_8
                    )

                val pass =
                    (proxyPass ?: "")
                        .toByteArray(
                            Charsets.UTF_8
                        )

                output.write(
                    byteArrayOf(
                        0x01,
                        user.size.toByte()
                    )
                )

                output.write(user)

                output.write(
                    pass.size.toByte()
                )

                output.write(pass)

                output.flush()

                val auth =
                    ByteArray(2)

                readFully(
                    input,
                    auth
                )

                if (
                    auth[1].toInt() != 0
                ) {
                    throw IOException(
                        "SOCKS5 authentication failed"
                    )
                }
            }

            else -> {
                throw IOException(
                    "SOCKS5 authentication method rejected"
                )
            }
        }

        val host =
            target.hostString.toByteArray(
                Charsets.UTF_8
            )

        output.write(
            byteArrayOf(
                0x05,
                0x01,
                0x00,
                0x03,
                host.size.toByte()
            )
        )

        output.write(host)

        output.write(
            (target.port shr 8) and 0xff
        )

        output.write(
            target.port and 0xff
        )

        output.flush()

        val response =
            ByteArray(4)

        readFully(
            input,
            response
        )

        if (
            response[0] !=
            0x05.toByte()
        ) {
            throw IOException(
                "Invalid SOCKS5 CONNECT response"
            )
        }

        if (
            response[1].toInt() != 0
        ) {
            throw IOException(
                "SOCKS5 CONNECT rejected: " +
                        "${response[1].toInt() and 0xff}"
            )
        }

        when (
            response[3].toInt() and 0xff
        ) {

            0x01 -> {
                readFully(
                    input,
                    ByteArray(4)
                )
            }

            0x03 -> {

                val len =
                    input.read()

                if (len < 0) {
                    throw IOException(
                        "Invalid SOCKS5 address"
                    )
                }

                readFully(
                    input,
                    ByteArray(len)
                )
            }

            0x04 -> {
                readFully(
                    input,
                    ByteArray(16)
                )
            }

            else -> {
                throw IOException(
                    "Invalid SOCKS5 address type"
                )
            }
        }

        readFully(
            input,
            ByteArray(2)
        )

        Log.i(
            TAG,
            "SOCKS5 CONNECT successful"
        )
    }

    private fun readFully(
        input: java.io.InputStream,
        buffer: ByteArray
    ) {

        var offset = 0

        while (
            offset < buffer.size
        ) {

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

            offset += count
        }
    }
}
