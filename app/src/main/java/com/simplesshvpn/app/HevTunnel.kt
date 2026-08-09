package com.simplesshvpn.app

object HevTunnel {

init {
    System.loadLibrary("hev-socks5-tunnel")
}

/**
 * Starts TUN -> SOCKS5.
 *
 * This function blocks until the tunnel stops,
 * so call it from a background thread.
 *
 * @param config Hev YAML configuration
 * @param tunFd Android VpnService TUN file descriptor
 */
external fun start(
    config: String,
    tunFd: Int
): Int

external fun stop()

}
