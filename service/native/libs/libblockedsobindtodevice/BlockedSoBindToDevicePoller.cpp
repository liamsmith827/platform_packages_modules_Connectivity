// TODO: Test file

#define LOG_TAG "BlockedSoBindToDevice"

#include "libblockedsobindtodevice/BlockedSoBindToDevicePoller.h"

#include <bpf/BpfUtils.h>
#include <log/log.h>

#include <unordered_map>
#include <unordered_set>


namespace android {
namespace net {
namespace blockedsobindtodevice {
//using ::android::base::StringPrintf;

bool BlockedSoBindToDevicePoller::Start() {
    // TODO: Namespace to avoid having to use bpf::?
  auto rb = bpf::BpfRingbuf<BlockedSoBindToDevice>::Create(BLOCKED_SO_BIND_TO_DEVICE_RINGBUF_PATH);
  if (!rb.ok()) {
    ALOGW("Failed to create ringbuf: %s", rb.error().message().c_str());
    return false;
  }

  //mRingBuffer = std::move(*rb);



  return true;
}

bool BlockedSoBindToDevicePoller::Stop() {
  ALOGD("Stopping datasource");

  // Drain remaining events from the ring buffer now that tracing is disabled.
  // This prevents the next trace from seeing stale events and allows writing
  // the last batch of events to Perfetto.
 // ConsumeAll();

 // mRingBuffer.reset();

  //return res.ok();
  return true;
}

/*
void BlockedSoBindToDevicePoller::TraceIfaces(const std::vector<PacketTrace>& packets) {
  if (packets.empty()) return;

  std::unordered_set<uint32_t> uniqueIfindex;
  for (const PacketTrace& pkt : packets) {
    uniqueIfindex.insert(pkt.ifindex);
  }

  for (uint32_t ifindex : uniqueIfindex) {
    char ifname[IF_NAMESIZE] = {};
    if (if_indextoname(ifindex, ifname) != ifname) continue;

    StatsValue stats = {};
    if (bpfGetIfIndexStats(ifindex, &stats) != 0) continue;

    std::string rxTrack = StringPrintf("%s [%d] Rx Bytes", ifname, ifindex);
    std::string txTrack = StringPrintf("%s [%d] Tx Bytes", ifname, ifindex);
    ATRACE_INT64(rxTrack.c_str(), stats.rxBytes);
    ATRACE_INT64(txTrack.c_str(), stats.txBytes);
  }
}
 */

/*
bool BlockedSoBindToDevicePoller::ConsumeAll() {
  std::vector<PacketTrace> packets;
  {
    std::scoped_lock<std::mutex> lock(mBufferMutex);
    if (mRingBuffer == nullptr) {
      ALOGW("Tracing is not active");
      return false;
    }

    base::Result<int> ret = mRingBuffer->ConsumeAll(
        [&](const PacketTrace& pkt) { packets.push_back(pkt); });
    if (!ret.ok()) {
      ALOGW("Failed to poll ringbuf: %s", ret.error().message().c_str());
      return false;
    }
  }

  ATRACE_INT("NetworkTracePackets", packets.size());

  TraceIfaces(packets);
  mCallback(packets);

  return true;
}
 */

}  // namespace blockedsobindtodevice
}  // namespace bpf
}  // namespace android
