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
        private const val READ_TIMEOUT = 10_000
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
                        "Connecting directly"
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
                "Connection timeout: " +
                        "${proxyHost ?: "unknown"}:$proxyPort",
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

    // =========================================================
    // HTTP / HTTPS PROXY
    // =========================================================

    private fun connectHttpProxy(
        socket: Socket,
        target: InetSocketAddress
    ) {
        if (proxyHost.isNullOrBlank()) {
            throw IOException(
                "Proxy host is empty"
            )
        }

        Log.i(
            TAG,
            "HTTP proxy: $proxyHost:$proxyPort"
        )

        /*
         * IMPORTANT:
         *
         * ProxyType.HTTP:
         *   Plain TCP -> HTTP request
         *
         * ProxyType.HTTPS:
         *   TLS -> proxy -> HTTP request
         *
         * We keep these two modes separate.
         */

        if (proxyType == ProxyType.HTTPS) {

            connectHttpsProxy(
                socket,
                target
            )

            return
        }

        // -----------------------------------------------------
        // Plain HTTP proxy
        // -----------------------------------------------------

        socket.soTimeout = READ_TIMEOUT

        socket.connect(
            InetSocketAddress(
                proxyHost,
                proxyPort
            ),
            CONNECT_TIMEOUT
        )

        Log.i(
            TAG,
            "HTTP proxy TCP connected"
        )

        val request = buildPayload(
            target.hostString,
            target.port
        )

        Log.i(
            TAG,
            "Sending HTTP payload:\n$request"
        )

        val output = socket.getOutputStream()

        output.write(
            request.toByteArray(
                Charsets.ISO_8859_1
            )
        )

        output.flush()

        readHttpProxyResponse(
            socket
        )
    }

    // =========================================================
    // HTTPS PROXY
    // =========================================================

    private fun connectHttpsProxy(
        socket: Socket,
        target: InetSocketAddress
    ) {
        if (proxyHost.isNullOrBlank()) {
            throw IOException(
                "HTTPS proxy host is empty"
            )
        }

        socket.soTimeout = READ_TIMEOUT

        Log.i(
            TAG,
            "Connecting HTTPS proxy $proxyHost:$proxyPort"
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
            "HTTPS proxy TCP connected"
        )

        val sslContext =
            javax.net.ssl.SSLContext
                .getInstance("TLS")

        sslContext.init(
            null,
            null,
            null
        )

        val sslSocket =
            sslContext.socketFactory.createSocket(
                socket,
                proxyHost,
                proxyPort,
                false
            ) as javax.net.ssl.SSLSocket

        sslSocket.soTimeout = READ_TIMEOUT

        sslSocket.startHandshake()

        Log.i(
            TAG,
            "HTTPS proxy TLS handshake successful"
        )

        val request = buildPayload(
            target.hostString,
            target.port
        )

        Log.i(
            TAG,
            "Sending HTTPS proxy payload:\n$request"
        )

        val output =
            sslSocket.outputStream

        output.write(
            request.toByteArray(
                Charsets.ISO_8859_1
            )
        )

        output.flush()

        readHttpProxyResponse(
            sslSocket
        )

        /*
         * The SocketFactory API returns the original Socket.
         *
         * The TLS socket must remain alive, so we do not close it
         * here. The underlying socket is now owned by sslSocket.
         */
    }

    // =========================================================
    // READ HTTP RESPONSE
    // =========================================================

    private fun readHttpProxyResponse(
        socket: Socket
    ) {
        val input =
            socket.getInputStream()

        val reader =
            BufferedReader(
                InputStreamReader(
                    input,
                    Charsets.ISO_8859_1
                )
            )

        val statusLine =
            try {
                reader.readLine()
            } catch (e: SocketTimeoutException) {
                throw IOException(
                    "Proxy connected but did not send an HTTP response " +
                            "within ${READ_TIMEOUT}ms",
                    e
                )
            }

        if (statusLine.isNullOrBlank()) {
            throw IOException(
                "Proxy returned an empty HTTP response"
            )
        }

        Log.i(
            TAG,
            "HTTP proxy response: $statusLine"
        )

        val statusCode =
            Regex(
                "^HTTP/\\d\\.\\d\\s+(\\d+)"
            )
                .find(statusLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        if (statusCode == null) {
            throw IOException(
                "Invalid HTTP proxy response: $statusLine"
            )
        }

        // -----------------------------------------------------
        // Read HTTP headers
        // -----------------------------------------------------

        val headers =
            StringBuilder()

        while (true) {

            val line =
                try {
                    reader.readLine()
                } catch (e: SocketTimeoutException) {
                    throw IOException(
                        "Timeout while reading HTTP proxy headers",
                        e
                    )
                }

            if (line == null || line.isEmpty()) {
                break
            }

            headers
                .append(line)
                .append('\n')
        }

        Log.d(
            TAG,
            "HTTP proxy headers:\n$headers"
        )

        if (statusCode != 200) {

            throw IOException(
                "HTTP CONNECT failed: " +
                        "$statusLine\n$headers"
            )
        }

        Log.i(
            TAG,
            "HTTP CONNECT 200 OK"
        )
    }

    // =========================================================
    // BUILD PAYLOAD
    // =========================================================

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
                ?: (
                    "CONNECT [host_port] [protocol][crlf]" +
                            "Host: [host][crlf]" +
                            "[crlf]"
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

        /*
         * Make sure the HTTP request has an empty line
         * terminating the headers.
         */

        if (!request.contains("\r\n\r\n")) {

            request =
                request.trimEnd(
                    '\r',
                    '\n'
                ) +
                        "\r\n\r\n"
        }

        // -----------------------------------------------------
        // Proxy authentication
        // -----------------------------------------------------

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
                "\r\n\r\n"

            val beforeEnd =
                request.substringBeforeLast(
                    separator
                )

            request =
                beforeEnd +
                        "\r\nProxy-Authorization: Basic $encoded" +
                        separator
        }

        return request
    }

    // =========================================================
    // SOCKS5
    // =========================================================

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
            "Connecting SOCKS5
