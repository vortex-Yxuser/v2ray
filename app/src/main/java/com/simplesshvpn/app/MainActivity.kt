package com.simplesshvpn.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.simplesshvpn.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentStatus = "DISCONNECTED"

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            updateStatus("ERROR", "VPN permission denied by user")
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore */ }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SshVpnService.ACTION_STATUS) {
                val status = intent.getStringExtra(SshVpnService.EXTRA_STATUS) ?: return
                val message = intent.getStringExtra(SshVpnService.EXTRA_MESSAGE) ?: ""
                runOnUiThread {
                    updateStatus(status, message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupProxySpinner()
        setupButtons()
        requestNotificationPermissionIfNeeded()

        if (SshVpnService.isRunning) {
            updateStatus("CONNECTED", "Already connected")
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SshVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }

    private fun setupProxySpinner() {
        val types = listOf("None", "HTTP", "HTTPS", "SOCKS5")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProxyType.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener {
            if (currentStatus == "CONNECTED" || currentStatus == "CONNECTING") return@setOnClickListener
            prepareAndConnect()
        }
        binding.btnDisconnect.setOnClickListener {
            disconnect()
        }
    }

    private fun prepareAndConnect() {
        val config = collectConfig() ?: return

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnService(config)
        }
    }

    private fun startVpnService(config: ConnectionConfig? = null) {
        val cfg = config ?: collectConfig() ?: return
        updateStatus("CONNECTING", "Requesting VPN permission & starting service...")

        val intent = Intent(this, SshVpnService::class.java).apply {
            action = SshVpnService.ACTION_CONNECT
            putExtra(ConnectionConfig.EXTRA_CONFIG, cfg)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun disconnect() {
        val intent = Intent(this, SshVpnService::class.java).apply {
            action = SshVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        updateStatus("DISCONNECTED", "Disconnect requested")
    }

    private fun collectConfig(): ConnectionConfig? {
        val host = binding.etSshHost.text?.toString()?.trim()
        if (host.isNullOrBlank()) {
            Toast.makeText(this, "SSH Host is required", Toast.LENGTH_SHORT).show()
            return null
        }
        val portStr = binding.etSshPort.text?.toString()?.trim() ?: "22"
        val port = portStr.toIntOrNull() ?: 22
        val user = binding.etUsername.text?.toString()?.trim()
        if (user.isNullOrBlank()) {
            Toast.makeText(this, "Username is required", Toast.LENGTH_SHORT).show()
            return null
        }
        val password = binding.etPassword.text?.toString()
        val privateKey = binding.etPrivateKey.text?.toString()?.takeIf { it.isNotBlank() }

        if (password.isNullOrBlank() && privateKey == null) {
            Toast.makeText(this, "Password or Private Key is required", Toast.LENGTH_SHORT).show()
            return null
        }

        val proxyType = when (binding.spinnerProxyType.selectedItemPosition) {
            1 -> ProxyType.HTTP
            2 -> ProxyType.HTTPS
            3 -> ProxyType.SOCKS5
            else -> ProxyType.NONE
        }

        val proxyHost = binding.etProxyHost.text?.toString()?.trim()
        val proxyPort = binding.etProxyPort.text?.toString()?.toIntOrNull() ?: 0
        val proxyUser = binding.etProxyUser.text?.toString()?.trim()
        val proxyPass = binding.etProxyPass.text?.toString()
        val payload = binding.etPayload.text?.toString()

        return ConnectionConfig(
            sshHost = host,
            sshPort = port,
            username = user,
            password = password,
            privateKey = privateKey,
            proxyType = proxyType,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUser = proxyUser,
            proxyPass = proxyPass,
            payload = payload
        )
    }

    private fun updateStatus(status: String, message: String) {
        currentStatus = status
        binding.tvStatus.text = status
        val color = when (status) {
            "CONNECTED" -> R.color.status_connected
            "CONNECTING" -> R.color.status_connecting
            "ERROR" -> R.color.status_error
            else -> R.color.status_disconnected
        }
        binding.tvStatus.setBackgroundResource(color)

        binding.btnConnect.isEnabled = status == "DISCONNECTED" || status == "ERROR"
        binding.btnDisconnect.isEnabled = status == "CONNECTED" || status == "CONNECTING"

        // Keep last 3000 characters of log
        val log = binding.tvLog.text.toString()
        val newLog = "$log\n[$status] $message".takeLast(3000)
        binding.tvLog.text = newLog
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
