package com.simplesshvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SshVpnService : VpnService() {

    companion object {
        private const val TAG = "SshVpnService"

        const val ACTION_CONNECT =
            "com.simplesshvpn.app.CONNECT"

        const val ACTION_DISCONNECT =
            "com.simplesshvpn.app.DISCONNECT"

        const val ACTION_STATUS =
            "com.simplesshvpn.app.STATUS"

        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ssh_vpn_channel"

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var sshClient: SSHClient? = null

    private val running = AtomicBoolean(false)

    private val executor =
        Executors.newCachedThreadPool()

    private var config: ConnectionConfig? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_CONNECT -> {

                val cfg =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                        intent.getSerializableExtra(
                            ConnectionConfig.EXTRA_CONFIG,
                            ConnectionConfig::class.java
                        )

                    } else {

                        @Suppress("DEPRECATION")

                        intent.getSerializableExtra(
                            ConnectionConfig.EXTRA_CONFIG
                        ) as? ConnectionConfig
                    }

                if (cfg != null) {

                    config = cfg

                    startForeground(
                        NOTIFICATION_ID,
                        createNotification("Connecting...")
                    )

                    executor.execute {
                        connect(cfg)
                    }

                } else {

                    broadcastStatus(
                        "ERROR",
                        "No configuration received"
                    )
                }
            }

            ACTION_DISCONNECT -> {

                disconnect()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun connect(
        cfg: ConnectionConfig
    ) {

        if (running.getAndSet(true)) {
            return
        }

        broadcastStatus(
            "CONNECTING",
            "Starting connection process..."
        )

        try {

            // 1. Create SSH client

            broadcastStatus(
                "CONNECTING",
                "Creating SSH client..."
            )

            val client = SSHClient()

            client.addHostKeyVerifier(
                PromiscuousVerifier()
            )

            client.timeout = 30000
            client.connectTimeout = 30000

            // 2. Setup proxy + payload

            val socketFactory =
                ProxySocketFactory(
                    cfg.proxyType,
                    cfg.proxyHost,
                    cfg.proxyPort,
                    cfg.proxyUser,
                    cfg.proxyPass,
                    cfg.payload
                )

            client.socketFactory =
                socketFactory

            val proxyInfo =
                when (cfg.proxyType) {

                    ProxyType.NONE ->
                        "Direct"

                    else ->
                        "${cfg.proxyType} " +
                                "${cfg.proxyHost}:${cfg.proxyPort}"
                }

            broadcastStatus(
                "CONNECTING",
                "Connecting via $proxyInfo → " +
                        "${cfg.sshHost}:${cfg.sshPort}"
            )

            Log.i(
                TAG,
                "Connecting to " +
                        "${cfg.sshHost}:${cfg.sshPort} " +
                        "via $proxyInfo"
            )

            // 3. Connect

            client.connect(
                cfg.sshHost,
                cfg.sshPort
            )

            broadcastStatus(
                "CONNECTING",
                "TCP connected, starting authentication..."
            )

            // 4. Authenticate

            if (!cfg.privateKey.isNullOrBlank()) {

                broadcastStatus(
                    "CONNECTING",
                    "Authenticating with Private Key..."
                )

                val keyProvider =
                    client.loadKeys(
                        cfg.privateKey,
                        null as String?,
                        null
                    )

                client.authPublickey(
                    cfg.username,
                    keyProvider
                )

            } else if (!cfg.password.isNullOrBlank()) {

                broadcastStatus(
                    "CONNECTING",
                    "Authenticating with Password..."
                )

                client.authPassword(
                    cfg.username,
                    cfg.password
                )

            } else {

                throw IOException(
                    "No password or private key provided"
                )
            }

            if (!client.isAuthenticated) {

                throw IOException(
                    "SSH authentication failed " +
                            "(wrong username/password or key)"
                )
            }

            sshClient = client

            broadcastStatus(
                "CONNECTING",
                "SSH authenticated successfully"
            )

            Log.i(
                TAG,
                "SSH authenticated successfully"
            )

            // 5. Establish TUN

            broadcastStatus(
                "CONNECTING",
                "Creating VPN interface..."
            )

            val builder =
                Builder()
                    .setSession("SimpleSSHVPN")
                    .addAddress(
                        "10.8.0.2",
                        24
                    )
                    .addRoute(
                        "0.0.0.0",
                        0
                    )
                    .addDnsServer(
                        "8.8.8.8"
                    )
                    .addDnsServer(
                        "1.1.1.1"
                    )
                    .setMtu(1500)
                    .setBlocking(true)

            try {

                builder.addDisallowedApplication(
                    packageName
                )

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Could not exclude self: ${e.message}"
                )
            }

            vpnInterface =
                builder.establish()

            if (vpnInterface == null) {

                throw IOException(
                    "Failed to establish VPN interface " +
                            "(permission issue?)"
                )
            }

            isRunning = true

            broadcastStatus(
                "CONNECTED",
                "VPN + SSH connected successfully"
            )

            updateNotification(
                "Connected to ${cfg.sshHost}"
            )

            // 6. Packet loop

            startPacketLoop(
                vpnInterface!!
            )

        } catch (e: Exception) {

            val fullError =
                buildString {

                    append(
                        e.javaClass.simpleName
                    )

                    append(": ")

                    append(
                        e.message ?: "Unknown error"
                    )

                    if (e.cause != null) {

                        append("\nCause: ")

                        append(
                            e.cause?.message
                        )
                    }
                }

            Log.e(
                TAG,
                "Connection failed",
                e
            )

            broadcastStatus(
                "ERROR",
                fullError
            )

            disconnect()
            stopSelf()
        }
    }

    private fun startPacketLoop(
        pfd: ParcelFileDescriptor
    ) {

        executor.execute {

            val input =
                FileInputStream(
                    pfd.fileDescriptor
                )

            val output =
                FileOutputStream(
                    pfd.fileDescriptor
                )

            val buffer =
                ByteArray(32767)

            Log.i(
                TAG,
                "Packet loop started (foundation mode)"
            )

            try {

                while (running.get()) {

                    val length =
                        input.read(buffer)

                    if (length > 0) {

                        // Full packet forwarding requires
                        // a TUN-to-SOCKS implementation.

                    } else if (length < 0) {

                        break
                    }
                }

            } catch (e: Exception) {

                if (running.get()) {

                    Log.e(
                        TAG,
                        "Packet loop error",
                        e
                    )

                    broadcastStatus(
                        "ERROR",
                        "Packet loop error: ${e.message}"
                    )
                }

            } finally {

                try {
                    input.close()
                } catch (_: Exception) {
                }

                try {
                    output.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun disconnect() {

        running.set(false)
        isRunning = false

        try {

            vpnInterface?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error closing TUN",
                e
            )
        }

        vpnInterface = null

        try {

            sshClient?.disconnect()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Error disconnecting SSH",
                e
            )
        }

        sshClient = null

        broadcastStatus(
            "DISCONNECTED",
            "Disconnected"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

        } else {

            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {

        disconnect()

        executor.shutdownNow()

        super.onDestroy()
    }

    override fun onRevoke() {

        disconnect()

        stopSelf()

        super.onRevoke()
    }

    private fun broadcastStatus(
        status: String,
        message: String
    ) {

        val intent =
            Intent(ACTION_STATUS).apply {

                putExtra(
                    EXTRA_STATUS,
                    status
                )

                putExtra(
                    EXTRA_MESSAGE,
                    message
                )

                setPackage(
                    packageName
                )
            }

        sendBroadcast(intent)

        Log.i(
            TAG,
            "[$status] $message"
        )
    }

    private fun createNotification(
        text: String
    ): Notification {

        createNotificationChannel()

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    this,
                    MainActivity::class.java
                ),
                PendingIntent.FLAG_IMMUTABLE or
                        PendingIntent.FLAG_UPDATE_CURRENT
            )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "SimpleSSHVPN"
            )
            .setContentText(
                text
            )
            .setSmallIcon(
                android.R.drawable.ic_lock_lock
            )
            .setContentIntent(
                pendingIntent
            )
            .setOngoing(true)
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    private fun updateNotification(
        text: String
    ) {

        val nm =
            getSystemService(
                NotificationManager::class.java
            )

        nm.notify(
            NOTIFICATION_ID,
            createNotification(text)
        )
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "SSH VPN Service",
                    NotificationManager.IMPORTANCE_LOW
                )

            val nm =
                getSystemService(
                    NotificationManager::class.java
                )

            nm.createNotificationChannel(
                channel
            )
        }
    }
}
