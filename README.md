# SimpleSSHVPN

Simple Android VPN client that uses **Android VpnService** + real **SSH tunnel** (sshj 0.40.0).

## Features

- Real SSH connection using [sshj](https://github.com/hierynomus/sshj) 0.40.0
- Support for Password and Private Key authentication
- Proxy support for the SSH connection itself:
  - None
  - HTTP CONNECT
  - HTTPS CONNECT
  - SOCKS5
- Customizable Payload (useful for HTTP CONNECT obfuscation)
- VpnService with TUN interface
- Foreground service + notification
- Single Activity UI with connection status

## Project Structure

```
SimpleSSHVPN/
├── app/
│   ├── src/main/java/com/simplesshvpn/app/
│   │   ├── MainActivity.kt
│   │   ├── SshVpnService.kt
│   │   ├── ConnectionConfig.kt
│   │   └── ProxySocketFactory.kt
│   ├── src/main/res/
│   └── build.gradle.kts
├── gradle/wrapper/
├── .github/workflows/android.yml
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Build

```bash
./gradlew clean
./gradlew assembleDebug
```

APK will be generated at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Codemagic / CI

The included GitHub Actions workflow:

- Triggers on `push` to `main` and `workflow_dispatch`
- Uses Java 17
- Builds with `./gradlew assembleDebug`
- Uploads artifact named **SimpleSSHVPN-APK**

## Important Notes

- This project creates a real SSH session and a real TUN interface.
- Full system-wide traffic tunneling (all TCP/UDP) typically requires an additional userspace TCP/IP stack (tun2socks / hev-socks5-tunnel / gVisor). The current packet loop is the foundation; you can extend `SshVpnService.startPacketLoop` to open SSH `direct-tcpip` channels per TCP connection.
- UDP is not supported without extra server-side setup (e.g. `ssh -w` or socks5-udp).
- No secrets are hardcoded.
- All library versions are pinned (no `latest`).

## License

MIT (or as you prefer)
