package com.simplesshvpn.app

import android.util.Log
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local SOCKS5 server.
 *
 * 127.0.0.1:1080
 *
 * Every SOCKS5 CONNECT request is opened through:
 *
 * Android -> SOCKS5 -> SSH direct-tcpip -> destination
 */
class SshSocks5Server(
    private val sshClient: SSHClient,
    private val port: Int = 1080
) {

    companion object {
        private const val TAG = "SshSocks5Server"

        private const val SOCKS_VERSION = 0x05

        private const val METHOD_NO_AUTH = 0x00

        private const val CMD_CONNECT = 0x01

        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04

        private const val REP_SUCCESS = 0x00
        private const val REP_GENERAL_FAILURE = 0x01
        private const val REP_CONNECTION_NOT_ALLOWED = 0x02
        private const val REP_NETWORK_UNREACHABLE = 0x03
        private const val REP_HOST_UNREACHABLE = 0x04
        private const val REP_CONNECTION_REFUSED = 0x05
        private const val REP_TTL_EXPIRED = 0x06
        private const val REP_COMMAND_NOT_SUPPORTED = 0x07
        private const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
    }

    private val running =
        AtomicBoolean(false)

    private val executor: ExecutorService =
        Executors.newCachedThreadPool()

    private var serverSocket: ServerSocket? = null

    fun start() {

        if (running.getAndSet(true)) {
            return
        }

        serverSocket =
            ServerSocket()

        serverSocket!!.reuseAddress = true

        serverSocket!!.bind(
            InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                port
            )
        )

        Log.i(
            TAG,
            "SOCKS5 listening on 127.0.0.1:$port"
        )

        executor.execute {

            acceptLoop()
        }
    }

    private fun acceptLoop() {

        while (running.get()) {

            try {

                val socket =
                    serverSocket?.accept()
                        ?: break

                socket.tcpNoDelay = true

                Log.d(
                    TAG,
                    "SOCKS client connected: " +
                            socket.remoteSocketAddress
                )

                executor.execute {

                    handleClient(socket)
                }

            } catch (e: Exception) {

                if (running.get()) {

                    Log.e(
                        TAG,
                        "Accept error",
                        e
                    )
                }
            }
        }
    }

    private fun handleClient(
        socket: Socket
    ) {

        var directConnection: DirectConnection? = null

        try {

            socket.soTimeout = 30000

            val input =
                BufferedInputStream(
                    socket.getInputStream()
                )

            val output =
                BufferedOutputStream(
                    socket.getOutputStream()
                )

            /*
             * SOCKS5 greeting
             *
             * Client:
             * 05 NMETHODS METHODS...
             */

            val version =
                input.read()

            if (version != SOCKS_VERSION) {

                throw IOException(
                    "Unsupported SOCKS version: $version"
                )
            }

            val methodCount =
                input.read()

            if (
                methodCount < 1 ||
                methodCount > 255
            ) {

                throw IOException(
                    "Invalid SOCKS method count"
                )
            }

            val methods =
                ByteArray(methodCount)

            readFully(
                input,
                methods
            )

            var noAuthSupported = false

            for (method in methods) {

                if (
                    (method.toInt() and 0xff) ==
                    METHOD_NO_AUTH
                ) {

                    noAuthSupported = true
                    break
                }
            }

            if (!noAuthSupported) {

                output.write(
                    byteArrayOf(
                        0x05,
                        0xFF.toByte()
                    )
                )

                output.flush()

                throw IOException(
                    "SOCKS client does not support no-auth"
                )
            }

            output.write(
                byteArrayOf(
                    0x05,
                    0x00
                )
            )

            output.flush()

            /*
             * SOCKS request:
             *
             * VER CMD RSV ATYP
             */

            val requestVersion =
                input.read()

            if (
                requestVersion != SOCKS_VERSION
            ) {

                throw IOException(
                    "Invalid SOCKS request version"
                )
            }

            val command =
                input.read()

            input.read() // RSV

            val addressType =
                input.read()

            if (command != CMD_CONNECT) {

                sendReply(
                    output,
                    REP_COMMAND_NOT_SUPPORTED
                )

                throw IOException(
                    "Only CONNECT is supported"
                )
            }

            val destination =
                readDestination(
                    input,
                    addressType
                )

            Log.d(
                TAG,
                "SOCKS CONNECT " +
                        "${destination.host}:${destination.port}"
            )

            /*
             * Open TCP channel through SSH server.
             *
             * SSHJ's newDirectConnection()
             * creates a direct-tcpip channel.
             */

            directConnection =
                sshClient.newDirectConnection(
                    destination.host,
                    destination.port
                )

            directConnection!!.open()

            directConnection!!.setAutoExpand(true)

            /*
             * We now have:
             *
             * Local socket
             *      ↕
             * SSH DirectConnection
             *      ↕
             * Destination
             */

            sendReply(
                output,
                REP_SUCCESS
            )

            socket.soTimeout = 0

            val sshInput =
                BufferedInputStream(
                    directConnection!!.inputStream
                )

            val sshOutput =
                BufferedOutputStream(
                    directConnection!!.outputStream
                )

            val localToRemote =
                executor.submit {

                    copy(
                        input,
                        sshOutput
                    )
                }

            val remoteToLocal =
                executor.submit {

                    copy(
                        sshInput,
                        output
                    )
                }

            try {

                localToRemote.get()

            } catch (e: Exception) {

                Log.d(
                    TAG,
                    "Local->SSH ended: ${e.message}"
                )
            }

            try {

                remoteToLocal.cancel(
                    true
                )

            } catch (_: Exception) {
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "SOCKS client error: ${e.message}",
                e
            )

            try {

                val output =
                    BufferedOutputStream(
                        socket.getOutputStream()
                    )

                sendReply(
                    output,
                    mapError(e)
                )

            } catch (_: Exception) {
            }

        } finally {

            try {

                directConnection?.close()

            } catch (_: Exception) {
            }

            try {

                socket.close()

            } catch (_: Exception) {
            }
        }
    }

    private fun readDestination(
        input: BufferedInputStream,
        addressType: Int
    ): Destination {

        return when (addressType) {

            ATYP_IPV4 -> {

                val address =
                    ByteArray(4)

                readFully(
                    input,
                    address
                )

                val host =
                    InetAddress
                        .getByAddress(address)
                        .hostAddress
                        ?: throw IOException(
                            "Invalid IPv4 address"
                        )

                Destination(
                    host,
                    readPort(input)
                )
            }

            ATYP_DOMAIN -> {

                val length =
                    input.read()

                if (
                    length <= 0 ||
                    length > 255
                ) {

                    throw IOException(
                        "Invalid domain length"
                    )
                }

                val domainBytes =
                    ByteArray(length)

                readFully(
                    input,
                    domainBytes
                )

                val domain =
                    String(
                        domainBytes,
                        Charsets.UTF_8
                    )

                Destination(
                    domain,
                    readPort(input)
                )
            }

            ATYP_IPV6 -> {

                val address =
                    ByteArray(16)

                readFully(
                    input,
                    address
                )

                val host =
                    InetAddress
                        .getByAddress(address)
                        .hostAddress
                        ?: throw IOException(
                            "Invalid IPv6 address"
                        )

                Destination(
                    host,
                    readPort(input)
                )
            }

            else -> {

                throw SocksException(
                    REP_ADDRESS_TYPE_NOT_SUPPORTED,
                    "Address type not supported"
                )
            }
        }
    }

    private fun readPort(
        input: BufferedInputStream
    ): Int {

        val high =
            input.read()

        val low =
            input.read()

        if (
            high < 0 ||
            low < 0
        ) {

            throw IOException(
                "Unexpected end of SOCKS request"
            )
        }

        return (
            (high shl 8) or low
        )
    }

    private fun sendReply(
        output: BufferedOutputStream,
        reply: Int
    ) {

        /*
         * SOCKS5 reply:
         *
         * VER REP RSV ATYP BND.ADDR BND.PORT
         *
         * We return 0.0.0.0:0 because the
         * actual remote bind address is not
         * needed by Hev for normal CONNECT.
         */

        output.write(
            byteArrayOf(
                0x05,
                reply.toByte(),
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00
            )
        )

        output.flush()
    }

    private fun copy(
        input: java.io.InputStream,
        output: java.io.OutputStream
    ) {

        val buffer =
            ByteArray(32 * 1024)

        try {

            while (running.get()) {

                val count =
                    input.read(buffer)

                if (count < 0) {
                    break
                }

                if (count == 0) {
                    continue
                }

                output.write(
                    buffer,
                    0,
                    count
                )

                output.flush()
            }

        } catch (e: Exception) {

            Log.d(
                TAG,
                "Stream closed: ${e.message}"
            )
        }
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
                    "Unexpected end of SOCKS stream"
                )
            }

            offset += count
        }
    }

    private fun mapError(
        exception: Exception
    ): Int {

        if (
            exception is SocksException
        ) {
            return exception.replyCode
        }

        val message =
            exception.message
                ?.lowercase()
                ?: ""

        return when {

            "refused" in message ->
                REP_CONNECTION_REFUSED

            "unreachable" in message ->
                REP_HOST_UNREACHABLE

            else ->
                REP_GENERAL_FAILURE
        }
    }

    fun stop() {

        if (!running.getAndSet(false)) {
            return
        }

        Log.i(
            TAG,
            "Stopping SOCKS5"
        )

        try {

            serverSocket?.close()

        } catch (_: Exception) {
        }

        serverSocket = null

        executor.shutdownNow()
    }

    fun isRunning(): Boolean =
        running.get()

    private data class Destination(
        val host: String,
        val port: Int
    )

    private class SocksException(
        val replyCode: Int,
        message: String
    ) : IOException(message)
}
