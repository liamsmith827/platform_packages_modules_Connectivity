package android.net.ext;

/** @hide */
interface INetdExt {
   /**
    * Set the uid that owns a network.
    *
    * It would have been preferred to reimplement networkCreate() and add the uid to a type which
    * also wrapped NativeNetworkConfig. Unfortunately upstream does not provide a variable
    * containing the latest version of netd_aidl_interface, so keeping our interface in sync would
    * be error prone.
    */
    void networkSetOwner(int netId, int uid);
}
