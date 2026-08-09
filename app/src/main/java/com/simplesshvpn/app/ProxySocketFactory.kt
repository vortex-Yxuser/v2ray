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

/**
 * SocketFactory used by SSHJ.
 *
 * Supports:
 *  - Direct connection
 *  - HTTP CONNECT proxy
 *  - HTTPS CONNECT proxy (same CONNECT protocol)
 *  - SOCKS5 proxy
 *  - Custom HTTP payload
 *
 * Payload placeholders:
 *  [host]
 *  [port]
 *  [host_port]
 *  [protocol]
 *  [crlf]
 *  [lf]
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

        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 30_000

        private const val HTTP_OK = 200
        private const val SOCKS5_VERSION = 0x05
    }

    // -------------------------------------------------------------------------
    // SocketFactory
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Main connection
    // -------------------------------------------------------------------------

    private fun connectViaProxy(
        socket: Socket,
        target: SocketAddress
    ) {

        try {

            when (proxyType) {

                ProxyType.NONE -> {

                    Log.i(
                        TAG,
                        "Connecting DIRECT -> $target"
                    )

                    socket.soTimeout = READ_TIMEOUT

                    socket.connect(
                        target,
                        CONNECT_TIMEOUT
                    )

                    Log.i(
                        TAG,
                        "Direct connection successful"
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
                "Connection timeout: " +
                        "${proxyHost ?: "direct"}:$proxyPort " +
                        "-> $target",
                e
            )

        } catch (e: IOException) {

            closeQuietly(socket)

            throw e

        } catch (e: Exception) {

            closeQuietly(socket)

            throw IOException(
                "Proxy connection failed: ${e.message}",
                e
            )
        }
    }

    // -------------------------------------------------------------------------
    // HTTP CONNECT
    // -------------------------------------------------------------------------

    private fun connectHttpProxy(
        socket: Socket,
        target: InetSocketAddress
    ) {

        if (proxyHost.isNullOrBlank()) {

            throw IOException(
                "HTTP proxy host is empty"
            )
        }

        requireProxyPort()

        socket.soTimeout = READ_TIMEOUT

        Log.i(
            TAG,
            "Connecting via HTTP proxy " +
                    "$proxyHost:$proxyPort"
        )

        // Connect to HTTP proxy first.
        socket.connect(
            InetSocketAddress(
                proxyHost,
                proxyPort
            ),
            CONNECT_TIMEOUT
        )

        Log.i(
            TAG,
            "HTTP proxy TCP connection established"
        )

        // Build payload.
        val request = buildPayload(
            target.hostString,
            target.port
        )

        Log.d(
            TAG,
            "Sending HTTP payload:\n$request"
        )

        val output =
            OutputStreamWriter(
                socket.getOutputStream(),
                Charsets.ISO_8859_1
            )

        output.write(request)
        output.flush()

        Log.i(
            TAG,
            "Payload sent, waiting for proxy response..."
        )

        val input =
            BufferedReader(
                InputStreamReader(
                    socket.getInputStream(),
                    Charsets.ISO_8859_1
                )
            )

        val statusLine =
            input.readLine()
                ?: throw IOException(
                    "HTTP proxy returned an empty response"
                )

        Log.i(
            TAG,
            "HTTP proxy response: $statusLine"
        )

        val statusCode =
            parseHttpStatusCode(statusLine)

        // Read all headers.
        val headers =
            StringBuilder()

        while (true) {

            val line =
                input.readLine()
                    ?: break

            if (line.isEmpty()) {
                break
            }

            headers
                .append(line)
                .append('\n')
        }

        if (statusCode != HTTP_OK) {

            throw IOException(
                "HTTP CONNECT failed. " +
                        "Status=$statusCode\n" +
                        "Response=$statusLine\n" +
                        headers.toString()
            )
        }

        Log.i(
            TAG,
            "HTTP CONNECT 200 OK"
        )
    }

    // -------------------------------------------------------------------------
    // HTTP payload builder
    // -------------------------------------------------------------------------

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
                ?: buildDefaultPayload(
                    hostPort,
                    host
                )

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

        // Normalize line endings if the user entered only LF.
        request =
            normalizeHttpLineEndings(
                request
            )

        // HTTP request must end with an empty line.
        if (!request.endsWith("\r\n\r\n")) {

            request =
                request.trimEnd(
                    '\r',
                    '\n'
                ) +
                        "\r\n\r\n"
        }

        // Add HTTP proxy authentication if configured.
        if (!proxyUser.isNullOrBlank()) {

            request =
                addProxyAuthorization(
                    request
                )
        }

        return request
    }

    private fun buildDefaultPayload(
        hostPort: String,
        host: String
    ): String {

        return "CONNECT $hostPort HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n"
    }

    private fun normalizeHttpLineEndings(
        value: String
    ): String {

        return value
            .replace(
                "\r\n",
                "\n"
            )
            .replace(
                "\r",
                "\n"
            )
            .replace(
                "\n",
                "\r\n"
            )
    }

    private fun addProxyAuthorization(
        request: String
    ): String {

        val credentials =
            "$proxyUser:${proxyPass ?: ""}"

        val encoded =
            Base64.encodeToString(
                credentials.toByteArray(
                    Charsets.ISO_8859_1
                ),
                Base64.NO_WRAP
            )

        val header =
            "Proxy-Authorization: Basic $encoded"

        val separator =
            "\r\n\r\n"

        val index =
            request.indexOf(separator)

        if (index >= 0) {

            return request.substring(
                0,
                index
            ) +
                    "\r\n" +
                    header +
                    request.substring(
                        index
                    )
        }

        return request.trimEnd(
            '\r',
            '\n'
        ) +
                "\r\n" +
                header +
                "\r\n\r\n"
    }

    private fun parseHttpStatusCode(
        statusLine: String
    ): Int {

        val match =
            Regex(
                """HTTP/\d(?:\.\d)?\s+(\d{3})"""
            ).find(
                statusLine
            )

        return match
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: throw IOException(
                "Invalid HTTP proxy response: $statusLine"
            )
    }

    // -------------------------------------------------------------------------
    // SOCKS5
    // -------------------------------------------------------------------------

    private fun connectSocks5(
        socket: Socket,
        target: InetSocketAddress
    ) {

        if (proxyHost.isNullOrBlank()) {

            throw IOException(
                "SOCKS5 proxy host is empty"
            )
        }

        requireProxyPort()

        socket.soTimeout = READ_TIMEOUT

        Log.i(
            TAG,
            "Connecting via SOCKS5 " +
                    "$proxyHost:$proxyPort"
        )

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

        socks5Greeting(
            input,
            output
        )

        socks5Connect(
            input,
            output,
            target
        )

        Log.i(
            TAG,
            "SOCKS5 CONNECT successful"
        )
    }

    private fun socks5Greeting(
        input: InputStream,
        output: OutputStream
    ) {

        val username =
            proxyUser
                ?.takeIf {
                    it.isNotEmpty()
                }

        if (username != null) {

            // Version 5
            // 2 authentication methods:
            // 0x00 = no auth
            // 0x02 = username/password
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

        val response =
            ByteArray(2)

        readFully(
            input,
            response
        )

        val version =
            response[0].toInt() and 0xff

        if (version != SOCKS5_VERSION) {

            throw IOException(
                "Invalid SOCKS5 version: $version"
            )
        }

        when (
            response[1].toInt() and 0xff
        ) {

            0x00 -> {

                Log.d(
                    TAG,
                    "SOCKS5: no authentication"
                )
            }

            0x02 -> {

                if (username == null) {

                    throw IOException(
                        "SOCKS5 proxy requires username/password"
                    )
                }

                socks5Authenticate(
                    input,
                    output,
                    username,
                    proxyPass ?: ""
                )
            }

            0xff -> {

                throw IOException(
                    "SOCKS5: no acceptable authentication method"
                )
            }

            else -> {

                throw IOException(
                    "SOCKS5: unsupported authentication method " +
                            "${response[1].toInt() and 0xff}"
                )
            }
        }
    }

    private fun socks5Authenticate(
        input: InputStream,
        output: OutputStream,
        username: String,
        password: String
    ) {

        val userBytes =
            username.toByteArray(
                Charsets.UTF_8
            )

        val passBytes =
            password.toByteArray(
                Charsets.UTF_8
            )

        if (userBytes.size > 255) {

            throw IOException(
                "SOCKS5 username is longer than 255 bytes"
            )
        }

        if (passBytes.size > 255) {

            throw IOException(
                "SOCKS5 password is longer than 255 bytes"
            )
        }

        // IMPORTANT:
        // OutputStream.write(Int) is used for the
        // length bytes. This avoids the Kotlin Byte
        // overload compilation problem.
        output.write(0x01)
        output.write(userBytes.size)
        output.write(userBytes)
        output.write(passBytes.size)
        output.write(passBytes)
        output.flush()

        val response =
            ByteArray(2)

        readFully(
            input,
            response
        )

        val status =
            response[1].toInt() and 0xff

        if (status != 0x00) {

            throw IOException(
                "SOCKS5 username/password authentication failed: " +
                        "code=$status"
            )
        }
    }

    private fun socks5Connect(
        input: InputStream,
        output: OutputStream,
        target: InetSocketAddress
    ) {

        val host =
            target.hostString

        val hostBytes =
            host.toByteArray(
                Charsets.UTF_8
            )

        if (hostBytes.isEmpty()) {

            throw IOException(
                "SOCKS5 target host is empty"
            )
        }

        if (hostBytes.size > 255) {

            throw IOException(
                "SOCKS5 target hostname is longer than 255 bytes"
            )
        }

        /*
         * SOCKS5:
         *
         * VER  CMD  RSV  ATYP  LEN  HOST  PORT
         *
         * We use ATYP=0x03 (domain name), so the
         * SOCKS5 proxy performs DNS resolution.
         */

        output.write(
            0x05
        )

        output.write(
            0x01
        )

        output.write(
            0x00
        )

        output.write(
            0x03
        )

        output.write(
            hostBytes.size
        )

        output.write(
            hostBytes
        )

        // Network byte order = big endian.
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

        val version =
            response[0].toInt() and 0xff

        if (version != SOCKS5_VERSION) {

            throw IOException(
                "Invalid SOCKS5 CONNECT response version: $version"
            )
        }

        val reply =
            response[1].toInt() and 0xff

        if (reply != 0x00) {

            throw IOException(
                "SOCKS5 CONNECT rejected: " +
                        socks5Error(reply)
            )
        }

        val addressType =
            response[3].toInt() and 0xff

        when (addressType) {

            // IPv4 + port
            0x01 ->
