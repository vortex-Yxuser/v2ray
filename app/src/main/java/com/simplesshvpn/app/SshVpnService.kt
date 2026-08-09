package com.simplesshvpn.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class SshVpnService : VpnService() {

    companion object {
        private const val TAG = "SshVpnService"

        const val ACTION_CONNECT =
            "com.simplesshvpn.app.ACTION_CONNECT"

        const val ACTION_DISCONNECT =
            "com.simplesshvpn.app.ACTION_DISCONNECT"

        const val ACTION_STATUS =
            "com.simplesshvpn.app.ACTION_STATUS"

        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"

        const val STATUS_CONNECTING = "CONNECTING"
        const val STATUS_CONNECTED = "CONNECTED"
        const val STATUS_DISCONNECTED = "DISCONNECTED"
        const val STATUS_ERROR = "ERROR"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelSocket: Socket? = null

    private var serviceJob: Job? = null
    private val serviceScope =
        CoroutineScope(Dispatchers.IO + Job())

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_CONNECT -> {
                connect()
            }

            ACTION_DISCONNECT -> {
                disconnect()
            }
        }

        return START_STICKY
    }

    private fun connect() {

        if (serviceJob?.isActive == true) {
            Log.d(TAG, "Connection already running")
            return
        }

        serviceJob = serviceScope.launch {

            try {

                broadcastStatus(
                    STATUS_CONNECTING,
                    "Starting VPN service"
                )

                val config = loadConfiguration()

                val proxyInfo = when (config.proxyType) {
                    ProxyType.NONE ->
                        "Direct"

                    else ->
                        "${config.proxyType} " +
                                "${config.proxyHost}:${config.proxyPort}"
                }

                Log.i(
                    TAG,
                    "Connecting to " +
                            "${config.sshHost}:${config.sshPort} " +
                            "via $proxyInfo"
                )

                broadcastStatus(
                    STATUS_CONNECTING,
                    "Connecting via $proxyInfo → " +
                            "${config.sshHost}:${config.sshPort}"
                )

                establishVpnInterface()

                establishSshConnection(config)

                broadcastStatus(
                    STATUS_CONNECTED,
                    "Connected"
                )

                startPacketLoop()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "VPN connection failed",
                    e
                )

                broadcastStatus(
                    STATUS_ERROR,
                    e.message ?: "Connection failed"
                )

                disconnectInternal()
            }
        }
    }

    private fun establishVpnInterface() {

        val builder = Builder()

        builder.setSession("SimpleSSHVPN")

        builder.addAddress(
            "10.8.0.2",
            32
        )

        builder.addRoute(
            "0.0.0.0",
            0
        )

        builder.addDnsServer(
            "1.1.1.1"
        )

        builder.addDnsServer(
            "8.8.8.8"
        )

        vpnInterface = builder.establish()

            ?: throw IllegalStateException(
                "Unable to establish VPN interface"
            )

        Log.i(
            TAG,
            "VPN interface established"
        )
    }

    private fun establishSshConnection(
        config: VpnConfig
    ) {

        /*
         * This establishes the underlying TCP connection.
         *
         * The actual SSH authentication/tunneling should be
         * performed by SshManager/SshTunnel in the project.
         */

        if (config.sshHost.isBlank()) {
            throw IllegalArgumentException(
                "SSH host is empty"
            )
        }

        if (config.sshPort <= 0) {
            throw IllegalArgumentException(
                "Invalid SSH port"
            )
        }

        tunnelSocket = Socket()

        protect(tunnelSocket)

        tunnelSocket!!.connect(
            InetSocketAddress(
                config.sshHost,
                config.sshPort
            ),
            15_000
        )

        Log.i(
            TAG,
            "TCP connection established"
        )
    }

    private fun startPacketLoop() {

        val vpn = vpnInterface
            ?: throw IllegalStateException(
                "VPN interface is not available"
            )

        serviceScope.launch {

            try {

                FileInputStream(
                    vpn.fileDescriptor
                ).use { input ->

                    FileOutputStream(
                        vpn.fileDescriptor
                    ).use { output ->

                        val buffer =
                            ByteArray(32 * 1024)

                        while (
                            serviceJob?.isActive == true
                        ) {

                            val length =
                                input.read(buffer)

                            if (length <= 0) {
                                break
                            }

                            /*
                             * Packet forwarding must be connected
                             * to the SSH/TUN tunnel implementation.
                             *
                             * We intentionally do not pretend that
                             * reading the TUN packet alone provides
                             * Internet connectivity.
                             */

                            Log.d(
                                TAG,
                                "TUN packet received: $length bytes"
                            )
                        }
                    }
                }

            } catch (e: Exception) {

                if (
                    serviceJob?.isActive == true
                ) {

                    Log.e(
                        TAG,
                        "Packet loop failed",
                        e
                    )
                }
            }
        }
    }

    private fun loadConfiguration(): VpnConfig {

        /*
         * Replace this method with the project's existing
         * configuration provider if one already exists.
         *
         * These values are only defaults and should not be
         * considered real SSH credentials.
         */

        return VpnConfig(
            sshHost = "",
            sshPort = 22,
            sshUsername = "",
            sshPassword = "",
            proxyType = ProxyType.NONE,
            proxyHost = "",
            proxyPort = 0
        )
    }

    private fun broadcastStatus(
        status: String,
        message: String
    ) {

        val intent =
            Intent(ACTION_STATUS).apply {

                setPackage(packageName)

                putExtra(
                    EXTRA_STATUS,
                    status
                )

                putExtra(
                    EXTRA_MESSAGE,
                    message
                )
            }

        sendBroadcast(intent)

        Log.i(
            TAG,
            "$status: $message"
        )
    }

    private fun disconnect() {

        serviceScope.launch {
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {

        try {

            tunnelSocket?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error closing tunnel socket",
                e
            )
        }

        tunnelSocket = null

        try {

            vpnInterface?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error closing VPN interface",
                e
            )
        }

        vpnInterface = null

        broadcastStatus(
            STATUS_DISCONNECTED,
            "Disconnected"
        )
    }

    override fun onDestroy() {

        Log.i(
            TAG,
            "VPN service destroyed"
        )

        serviceJob?.cancel()
        serviceJob = null

        disconnectInternal()

        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onRevoke() {

        Log.i(
            TAG,
            "VPN permission revoked"
        )

        disconnectInternal()

        super.onRevoke()
    }
}

enum class ProxyType {
    NONE,
    HTTP,
    SOCKS5
}

data class VpnConfig(
    val sshHost: String,
    val sshPort: Int,
    val sshUsername: String,
    val sshPassword: String,
    val proxyType: ProxyType,
    val proxyHost: String,
    val proxyPort: Int
)
