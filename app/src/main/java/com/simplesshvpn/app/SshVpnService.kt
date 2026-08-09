package com.simplesshvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SshVpnService : VpnService() {

    companion object {

        private const val TAG =
            "SshVpnService"

        const val ACTION_CONNECT =
            "com.simplesshvpn.app.CONNECT"

        const val ACTION_DISCONNECT =
            "com.simplesshvpn.app.DISCONNECT"

        const val ACTION_STATUS =
            "com.simplesshvpn.app.STATUS"

        const val EXTRA_STATUS =
            "status"

        const val EXTRA_MESSAGE =
            "message"

        private const val NOTIFICATION_ID = 1

        private const val CHANNEL_ID =
            "ssh_vpn_channel"

        private const val LOCAL_SOCKS_HOST =
            "127.0.0.1"

        private const val LOCAL_SOCKS_PORT =
            1080

        @Volatile
        var isRunning = false
            private set
    }

    private var vpnInterface:
            ParcelFileDescriptor? = null

    private var sshClient:
            SSHClient? = null

    private var socksServer:
            SshSocks5Server? = null

    private val running =
        AtomicBoolean(false)

    private val executor =
        Executors.newCachedThreadPool()

    private var config:
            ConnectionConfig? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_CONNECT -> {

                val cfg =
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.TIRAMISU
                    ) {

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

                if (cfg == null) {

                    broadcastStatus(
                        "ERROR",
                        "No configuration received"
                    )

                    return START_NOT_STICKY
                }

                config = cfg

                startForeground(
                    NOTIFICATION_ID,
                    createNotification(
                        "Connecting..."
                    )
                )

                executor.execute {

                    connect(cfg)
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

        if (
            running.getAndSet(true)
        ) {
            return
        }

        broadcastStatus(
            "CONNECTING",
            "Starting connection..."
        )

        var client:
                SSHClient? = null

        try {

            /*
             * -------------------------------------------------
             * 1. SSH client
             * -------------------------------------------------
             */

            broadcastStatus(
                "CONNECTING",
                "Creating SSH client..."
            )

            client =
                SSHClient()

            /*
             * NOTE:
             *
             * PromiscuousVerifier accepts any SSH host key.
             * It is convenient for testing, but production apps
             * should pin/verify the server host key.
             */

            client.addHostKeyVerifier(
                PromiscuousVerifier()
            )

            client.timeout =
                30000

            client.connectTimeout =
                30000

            /*
             * -------------------------------------------------
             * 2. Proxy + Payload
             * -------------------------------------------------
             */

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
                                "${cfg.proxyHost}:" +
                                "${cfg.proxyPort}"
                }

            broadcastStatus(
                "CONNECTING",
                "Connecting via $proxyInfo → " +
                        "${cfg.sshHost}:${cfg.sshPort}"
            )

            Log.i(
                TAG,
                "SSH ${cfg.sshHost}:${cfg.sshPort} " +
                        "via $proxyInfo"
            )

            /*
             * -------------------------------------------------
             * 3. SSH TCP connection
             * -------------------------------------------------
             */

            client.connect(
                cfg.sshHost,
                cfg.sshPort
            )

            broadcastStatus(
                "CONNECTING",
                "SSH TCP connected"
            )

            /*
             * -------------------------------------------------
             * 4. SSH authentication
             * -------------------------------------------------
             */

            if (
                !cfg.privateKey.isNullOrBlank()
            ) {

                broadcastStatus(
                    "CONNECTING",
                    "Authenticating with private key..."
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

            } else if (
                !cfg.password.isNullOrBlank()
            ) {

                broadcastStatus(
                    "CONNECTING",
                    "Authenticating with password..."
                )

                client.authPassword(
                    cfg.username,
                    cfg.password
                )

            } else {

                throw IOException(
                    "No SSH password or private key"
                )
            }

            if (
                !client.isAuthenticated
            ) {

                throw IOException(
                    "SSH authentication failed"
                )
            }

            sshClient =
                client

            broadcastStatus(
                "CONNECTING",
                "SSH authenticated successfully"
            )

            /*
             * -------------------------------------------------
             * 5. Start local SOCKS5
             * -------------------------------------------------
             *
             * Hev will connect to:
             *
             * 127.0.0.1:1080
             *
             * Each CONNECT is then sent through SSH
             * using direct-tcpip.
             */

            broadcastStatus(
                "CONNECTING",
                "Starting local SOCKS5..."
            )

            val localSocks =
                SshSocks5Server(
                    client,
                    LOCAL_SOCKS_PORT
                )

            localSocks.start()

            socksServer =
                localSocks

            if (
                !localSocks.isRunning()
            ) {

                throw IOException(
                    "Could not start local SOCKS5"
                )
            }

            broadcastStatus(
                "CONNECTING",
                "SOCKS5 listening on " +
                        "$LOCAL_SOCKS_HOST:$LOCAL_SOCKS_PORT"
            )

            /*
             * -------------------------------------------------
             * 6. Android TUN
             * -------------------------------------------------
             */

            broadcastStatus(
                "CONNECTING",
                "Creating VPN interface..."
            )

            val builder =
                Builder()
                    .setSession(
                        "SimpleSSHVPN"
                    )
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

            /*
             * Prevent the VPN from routing its own
             * SSH/Proxy traffic back into itself.
             */

            try {

                builder.addDisallowedApplication(
                    packageName
                )

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Could not exclude app: " +
                            e.message
                )
            }

            vpnInterface =
                builder.establish()

            if (
                vpnInterface == null
            ) {

                throw IOException(
                    "Failed to establish VPN interface"
                )
            }

            /*
             * -------------------------------------------------
             * 7. VPN is ready
             * -------------------------------------------------
             */

            isRunning =
                true

            broadcastStatus(
                "CONNECTED",
                "SSH + SOCKS5 + TUN ready"
            )

            updateNotification(
                "Connected to ${cfg.sshHost}"
            )

            /*
             * -------------------------------------------------
             * 8. Start TUN -> SOCKS5
             * -------------------------------------------------
             *
             * IMPORTANT:
             *
             * HevTunnel must be implemented and its native
             * library must be included in the APK.
             *
             * It receives:
             *
             * TUN FD
             *
             * and sends traffic to:
             *
             * 127.0.0.1:1080
             */

            startHevTunnel(
                vpnInterface!!
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Connection failed",
                e
            )

            val message =
                buildString {

                    append(
                        e.javaClass.simpleName
                    )

                    append(": ")

                    append(
                        e.message
                            ?: "Unknown error"
                    )

                    e.cause?.let {

                        append(
                            "\nCause: "
                        )

                        append(
                            it.message
                                ?: it.javaClass.simpleName
                        )
                    }
                }

            broadcastStatus(
                "ERROR",
                message
            )

            disconnect()

            stopSelf()
        }
    }

    /**
     * Starts TUN -> SOCKS5.
     *
     * This replaces the old startPacketLoop().
     *
     * The old implementation only read packets from TUN
     * and discarded them. It did not forward them.
     */
    private fun startHevTunnel(
        pfd: ParcelFileDescriptor
    ) {

        val configText =
            """
            tunnel:
              mtu: 1500
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'

            socks5:
              address: 127.0.0.1
              port: 1080
              udp: tcp

            misc:
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-level: info
            """.trimIndent()

        executor.execute {

            try {

                broadcastStatus(
                    "CONNECTED",
                    "Starting TUN → SOCKS5..."
                )

                val fd =
                    pfd.fd

                val result =
                    HevTunnel.start(
                        configText,
                        fd
                    )

                Log.i(
                    TAG,
                    "Hev exited: $result"
                )

                if (
                    running.get()
                ) {

                    broadcastStatus(
                        "ERROR",
                        "TUN tunnel stopped: $result"
                    )

                    disconnect()
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Hev tunnel error",
                    e
                )

                if (
                    running.get()
                ) {

                    broadcastStatus(
                        "ERROR",
                        "TUN tunnel error: " +
                                e.message
                    )

                    disconnect()
                }
            }
        }
    }

    private fun disconnect() {

        if (
            !running.getAndSet(false)
        ) {

            /*
             * Even if the service was not marked running,
             * still clean up resources.
             */

            stopHevSafely()
            stopSocksSafely()
            closeVpnSafely()
            closeSshSafely()

            isRunning = false

            return
        }

        isRunning = false

        Log.i(
            TAG,
            "Disconnecting..."
        )

        /*
         * Stop TUN->SOCKS first.
         */

        stopHevSafely()

        /*
         * Then stop local SOCKS.
         */

        stopSocksSafely()

        /*
         * Then close Android TUN.
         */

        closeVpnSafely()

        /*
         * Finally close SSH.
         */

        closeSshSafely()

        broadcastStatus(
            "DISCONNECTED",
            "Disconnected"
        )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.N
        ) {

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

        } else {

            @Suppress("DEPRECATION")

            stopForeground(true)
        }
    }

    private fun stopHevSafely() {

        try {

            HevTunnel.stop()

        } catch (e: Throwable) {

            Log.d(
                TAG,
                "Hev stop: ${e.message}"
            )
        }
    }

    private fun stopSocksSafely() {

        try {

            socksServer?.stop()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "SOCKS stop error",
                e
            )
        }

        socksServer = null
    }

    private fun closeVpnSafely() {

        try {

            vpnInterface?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "TUN close error",
                e
            )
        }

        vpnInterface = null
    }

    private fun closeSshSafely() {

        try {

            sshClient?.disconnect()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "SSH disconnect error",
                e
            )
        }

        sshClient = null
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

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return super.onBind(intent)
    }

    private fun broadcastStatus(
        status: String,
        message: String
    ) {

        val intent =
            Intent(
                ACTION_STATUS
            ).apply {

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

        return NotificationCompat
            .Builder(
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

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
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

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }
}
