#define LOG_TAG "jniBlockedSoBindToDeviceNotifier"

#include <nativehelper/JNIHelp.h>
#include "libblockedsobindtodevice/BlockedSoBindToDevicePoller.h"

// TODO: Possibly need to split some of this off into lib? Follow same structure as
//  NetworkTracePoller/NetworkTraceHandler which is called by native/jni
//  com_android_server_net_NetworkStatsService.cpp

// TODO: Are there ordering issues with when the bpf program is loaded? ClatCoordinator.cpp looks to do some loading itself.

namespace android {
    static void nativeStartBlockedSoBindToDevicePoller(JNIEnv* env, jclass clazz) {
        // TODO: Namespace to avoid this?

        net::blockedsobindtodevice::BlockedSoBindToDevicePoller poller;
        poller.Start();
    }

};  // namespace android
