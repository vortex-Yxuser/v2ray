package com.simplesshvpn.app

import java.io.Serializable

enum class ProxyType {
    NONE,
    HTTP,
    HTTPS,
    SOCKS5
}

data class ConnectionConfig(
    val sshHost: String,
    val sshPort: Int,

    val username: String,

    val password: String?,
    val privateKey: String?,

    val proxyType: ProxyType,

    val proxyHost: String?,
    val proxyPort: Int,

    val proxyUser: String?,
    val proxyPass: String?,

    val payload: String?
) : Serializable {

    companion object {
        const val EXTRA_CONFIG =
            "extra_connection_config"
    }
}
