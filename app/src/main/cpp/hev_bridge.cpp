#include <jni.h>

#include "hev-socks5-tunnel.h"

extern "C"
JNIEXPORT jint JNICALL
Java_com_simplesshvpn_app_HevTunnel_start(
        JNIEnv *env,
        jobject,
        jstring config,
        jint tun_fd) {

    const char *config_str =
            env->GetStringUTFChars(
                    config,
                    nullptr
            );

    if (config_str == nullptr) {
        return -1;
    }

    int result =
            hev_socks5_tunnel_main_from_str(
                    reinterpret_cast<
                            const unsigned char *
                    >(config_str),

                    static_cast<unsigned int>(
                            env->GetStringUTFLength(
                                    config
                            )
                    ),

                    tun_fd
            );

    env->ReleaseStringUTFChars(
            config,
            config_str
    );

    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_simplesshvpn_app_HevTunnel_stop(
        JNIEnv *,
        jobject) {

    hev_socks5_tunnel_quit();
}
