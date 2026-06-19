#pragma once

#include <string>
#include <unordered_map>

//#include "android-base/thread_annotations.h"
#include "bpf/BpfMap.h"
#include "bpf/BpfRingbuf.h"

// For PacketTrace struct definition
#include "netd.h"

// TODO: net?
namespace android {
namespace net {
namespace blockedsobindtodevice {

// NetworkTracePoller is responsible for interactions with the BPF ring buffer
// including polling. This class is an internal helper for NetworkTraceHandler,
// it is not meant to be used elsewhere.
class BlockedSoBindToDevicePoller {
 public:
  bool Start();
  bool Stop();

 private:
  // The BPF ring buffer handle.
//  std::unique_ptr<BpfRingbuf<BlockedSoBindToDevice>> mRingBuffer;
};

}  // namespace blockedsobindtodevice
}  // namespace net
}  // namespace android
