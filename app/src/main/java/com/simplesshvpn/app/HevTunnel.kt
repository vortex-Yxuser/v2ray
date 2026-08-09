package com.simplesshvpn.app

object HevTunnel {

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    external fun start(
        config: String,
        tunFd: Int
    ): Int

    external fun stop()
}
