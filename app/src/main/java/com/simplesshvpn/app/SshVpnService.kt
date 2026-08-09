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
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VpnService that establishes a real SSH connection (via sshj)
 * and creates a TUN interface. Traffic handling is started;
 * full packet-to-SSH mapping requires additional userspace stack
 * (this implementation provides a solid foundation with real SSH + TUN).
 */
class SshVpnService : VpnService() {

    companion object {
        private const val TAG = "SshVpnService"
        const val ACTION_CONNECT = "com.simplesshvpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.simplesshvpn.app.DISCONNECT"
        const val ACTION_STATUS = "com.simplesshvpn.app.STATUS"
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
    private val executor = Executors.newCachedThreadPool()
    private var config: ConnectionConfig? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val cfg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(ConnectionConfig.EXTRA_CONFIG, ConnectionConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(ConnectionConfig.EXTRA_CONFIG) as? ConnectionConfig
                }
                if (cfg != null) {
                    config = cfg
                    startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                    executor.execute { connect(cfg) }
                }
            }
            ACTION_DISCONNECT -> {
                disconnect()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun connect(cfg: ConnectionConfig) {
        if (running.getAndSet(true)) return
        broadcastStatus("CONNECTING", "Starting SSH connection...")

        try {
            // 1. Create SSH client with optional proxy
            val client = SSHClient()
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.timeout = 20000
            client.connectTimeout = 20000

            val socketFactory = ProxySocketFactory(
                cfg.proxyType,
                cfg.proxyHost,
                cfg.proxyPort,
                cfg.proxyUser,
                cfg.proxyPass,
                cfg.payload
            )
            client.socketFactory = socketFactory

            Log.i(TAG, "Connecting to ${cfg.sshHost}:${cfg.sshPort} via ${cfg.proxyType}")
            client.connect(cfg.sshHost, cfg.sshPort)

            // Authenticate
            if (!cfg.privateKey.isNullOrBlank()) {
                val keyProvider = client.loadKeys(cfg.privateKey, null as String?, null)
                client.authPublickey(cfg.username, keyProvider)
            } else if (!cfg.password.isNullOrBlank()) {
                client.authPassword(cfg.username, cfg.password)
            } else {
                throw IOException("No password or private key provided")
            }

            if (!client.isAuthenticated) {
                throw IOException("SSH authentication failed")
            }

            sshClient = client
            Log.i(TAG, "SSH authenticated successfully")

            // 2. Establish TUN interface
            val builder = Builder()
                .setSession("SimpleSSHVPN")
                .addAddress("10.8.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(1500)
                .setBlocking(true)

            // Exclude this app to avoid routing loops
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not exclude self: ${e.message}")
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                throw IOException("Failed to establish VPN interface")
            }

            isRunning = true
            broadcastStatus("CONNECTED", "VPN + SSH connected")
            updateNotification("Connected to ${cfg.sshHost}")

            // 3. Start packet loop (foundation for full tunneling)
            // In a production app you would feed packets into a tun2socks-like
            // userspace stack that opens SSH direct-tcpip channels.
            startPacketLoop(vpnInterface!!)

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            broadcastStatus("ERROR", e.message ?: "Unknown error")
            disconnect()
            stopSelf()
        }
    }

    private fun startPacketLoop(pfd: ParcelFileDescriptor) {
        executor.execute {
            val input = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)

            Log.i(TAG, "Packet loop started")
            try {
                while (running.get()) {
                    val length = input.read(buffer)
                    if (length > 0) {
                        // Here a full implementation would parse IP packets
                        // and open SSH direct-tcpip channels for TCP streams.
                        // For now we keep the interface alive and the SSH session open.
                        // Real traffic requires additional userspace TCP/IP stack.
                    } else if (length < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Packet loop error", e)
                }
            } finally {
                try { input.close() } catch (_: Exception) {}
                try { output.close() } catch (_: Exception) {}
            }
        }
    }

    private fun disconnect() {
        running.set(false)
        isRunning = false

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TUN", e)
        }
        vpnInterface = null

        try {
            sshClient?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting SSH", e)
        }
        sshClient = null

        broadcastStatus("DISCONNECTED", "Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
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

    private fun broadcastStatus(status: String, message: String) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotification(text: String): Notification {
        createNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
