/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import static android.net.ConnectivitySettingsManager.DNS_RESOLVER_MAX_SAMPLES;
import static android.net.ConnectivitySettingsManager.DNS_RESOLVER_MIN_SAMPLES;
import static android.net.ConnectivitySettingsManager.DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS;
import static android.net.ConnectivitySettingsManager.DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT;
import static android.net.ConnectivitySettingsManager.PRIVATE_DNS_DEFAULT_MODE;
import static android.net.ConnectivitySettingsManager.PRIVATE_DNS_MODE;
import static android.net.ConnectivitySettingsManager.PRIVATE_DNS_MODE_OFF;
import static android.net.ConnectivitySettingsManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;
import static android.net.ConnectivitySettingsManager.PRIVATE_DNS_SPECIFIER;
import static android.net.ConnectivitySettingsManager.VPN_PRIVATE_DNS_DEFAULT_MODE;
import static android.net.ConnectivitySettingsManager.VPN_PRIVATE_DNS_MODE;
import static android.net.ConnectivitySettingsManager.VPN_PRIVATE_DNS_SPECIFIER;
import static android.net.resolv.aidl.IDnsResolverUnsolicitedEventListener.VALIDATION_RESULT_FAILURE;
import static android.net.resolv.aidl.IDnsResolverUnsolicitedEventListener.VALIDATION_RESULT_SUCCESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.ext.ConnectivityUtil;
import android.ext.ConnectivityUtil.NetworkType;
import android.net.ConnectivityManager;
import android.net.ConnectivitySettingsManager;
import android.net.GlobalOrUserId;
import android.net.IDnsResolver;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ResolverParamsParcel;
import android.net.Uri;
import android.net.resolv.aidl.DohParamsParcel;
import android.net.shared.PrivateDnsConfig;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.server.ConnectivityService;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// TODO: Review [C]. Which part bypasses private DNS?
/**
 * Encapsulate the management of DNS settings for networks.
 *
 * This class it NOT designed for concurrent access. Furthermore, all non-static
 * methods MUST be called from ConnectivityService's thread. However, an exceptional
 * case is getPrivateDnsConfig(Network) which is exclusively for
 * ConnectivityService#dumpNetworkDiagnostics() on a random binder thread.
 *
 * [ Private DNS ]
 * The code handling Private DNS is spread across several components, but this
 * seems like the least bad place to collect all the observations.
 *
 * Private DNS handling and updating occurs in response to several different
 * events. Each is described here with its corresponding intended handling.
 *
 * [A] Event: A new network comes up.
 * Mechanics:
 *     [1] ConnectivityService gets notifications from NetworkAgents.
 *     [2] in updateNetworkInfo(), the first time the NetworkAgent goes into
 *         into CONNECTED state, the Private DNS configuration is retrieved,
 *         programmed, and strict mode hostname resolution (if applicable) is
 *         enqueued in NetworkAgent's NetworkMonitor, via a call to
 *         handlePerNetworkPrivateDnsConfig().
 *     [3] Re-resolution of strict mode hostnames that fail to return any
 *         IP addresses happens inside NetworkMonitor; it sends itself a
 *         delayed CMD_EVALUATE_PRIVATE_DNS message in a simple backoff
 *         schedule.
 *     [4] Successfully resolved hostnames are sent to ConnectivityService
 *         inside an EVENT_PRIVATE_DNS_CONFIG_RESOLVED message. The resolved
 *         IP addresses are programmed into netd via:
 *
 *             updatePrivateDns() -> updateDnses()
 *
 *         both of which make calls into DnsManager.
 *     [5] Upon a successful hostname resolution NetworkMonitor initiates a
 *         validation attempt in the form of a lookup for a one-time hostname
 *         that uses Private DNS.
 *
 * [B] Event: Private DNS settings are changed.
 * Mechanics:
 *     [1] ConnectivityService gets notifications from its SettingsObserver.
 *     [2] handlePrivateDnsSettingsChanged() is called, which calls
 *         handlePerNetworkPrivateDnsConfig() and the process proceeds
 *         as if from A.3 above.
 *
 * [C] Event: An application calls ConnectivityManager#reportBadNetwork().
 * Mechanics:
 *     [1] NetworkMonitor is notified and initiates a reevaluation, which
 *         always bypasses Private DNS.
 *     [2] Once completed, NetworkMonitor checks if strict mode is in operation
 *         and if so enqueues another evaluation of Private DNS, as if from
 *         step A.5 above.
 *
 * @hide
 */
public class DnsManager {
    private static final String TAG = DnsManager.class.getSimpleName();
    private static final PrivateDnsConfig PRIVATE_DNS_OFF = new PrivateDnsConfig();

    /* Defaults for resolver parameters. */
    private static final int DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS = 1800;
    private static final int DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT = 25;
    private static final int DNS_RESOLVER_DEFAULT_MIN_SAMPLES = 8;
    private static final int DNS_RESOLVER_DEFAULT_MAX_SAMPLES = 64;

    /**
     * Get PrivateDnsConfig.
     */
    public static PrivateDnsConfig getPrivateDnsConfig(Context context, GlobalOrUserId target) {
        final int mode = ConnectivitySettingsManager.getPrivateDnsMode(context, target);

        final boolean useTls = mode != PRIVATE_DNS_MODE_OFF;

        if (PRIVATE_DNS_MODE_PROVIDER_HOSTNAME == mode) {
            final String specifier = ConnectivitySettingsManager.getPrivateDnsHostname(context,
                    target);
            return new PrivateDnsConfig(specifier, null);
        }

        return new PrivateDnsConfig(useTls);
    }

    public static Uri[] getPrivateDnsSettingsUris(NetworkType networkType) {
        if (networkType == NetworkType.PHYSICAL) {
            return new Uri[]{
                    Settings.Global.getUriFor(PRIVATE_DNS_DEFAULT_MODE),
                    Settings.Global.getUriFor(PRIVATE_DNS_MODE),
                    Settings.Global.getUriFor(PRIVATE_DNS_SPECIFIER),
            };
        }
        return new Uri[]{
                Settings.Secure.getUriFor(VPN_PRIVATE_DNS_DEFAULT_MODE),
                Settings.Secure.getUriFor(VPN_PRIVATE_DNS_MODE),
                Settings.Secure.getUriFor(VPN_PRIVATE_DNS_SPECIFIER),
        };
    }

    public static class PrivateDnsValidationUpdate {
        public final int netId;
        public final InetAddress ipAddress;
        public final String hostname;
        // Refer to IDnsResolverUnsolicitedEventListener.VALIDATION_RESULT_*.
        public final int validationResult;

        public PrivateDnsValidationUpdate(int netId, InetAddress ipAddress,
                String hostname, int validationResult) {
            this.netId = netId;
            this.ipAddress = ipAddress;
            this.hostname = hostname;
            this.validationResult = validationResult;
        }
    }

    private static class PrivateDnsValidationStatuses {
        enum ValidationStatus {
            IN_PROGRESS,
            FAILED,
            SUCCEEDED
        }

        // Validation statuses of <hostname, ipAddress> pairs for a single netId
        // Caution : not thread-safe. As mentioned in the top file comment, all
        // methods of this class must only be called on ConnectivityService's thread.
        private Map<Pair<String, InetAddress>, ValidationStatus> mValidationMap;

        private PrivateDnsValidationStatuses() {
            mValidationMap = new HashMap<>();
        }

        private boolean hasValidatedServer() {
            for (ValidationStatus status : mValidationMap.values()) {
                if (status == ValidationStatus.SUCCEEDED) {
                    return true;
                }
            }
            return false;
        }

        private void updateTrackedDnses(String[] ipAddresses, String hostname) {
            Set<Pair<String, InetAddress>> latestDnses = new HashSet<>();
            for (String ipAddress : ipAddresses) {
                try {
                    latestDnses.add(new Pair(hostname,
                            InetAddresses.parseNumericAddress(ipAddress)));
                } catch (IllegalArgumentException e) {}
            }
            // Remove <hostname, ipAddress> pairs that should not be tracked.
            for (Iterator<Map.Entry<Pair<String, InetAddress>, ValidationStatus>> it =
                    mValidationMap.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Pair<String, InetAddress>, ValidationStatus> entry = it.next();
                if (!latestDnses.contains(entry.getKey())) {
                    it.remove();
                }
            }
            // Add new <hostname, ipAddress> pairs that should be tracked.
            for (Pair<String, InetAddress> p : latestDnses) {
                if (!mValidationMap.containsKey(p)) {
                    mValidationMap.put(p, ValidationStatus.IN_PROGRESS);
                }
            }
        }

        private void updateStatus(PrivateDnsValidationUpdate update) {
            Pair<String, InetAddress> p = new Pair(update.hostname,
                    update.ipAddress);
            if (!mValidationMap.containsKey(p)) {
                return;
            }
            if (update.validationResult == VALIDATION_RESULT_SUCCESS) {
                mValidationMap.put(p, ValidationStatus.SUCCEEDED);
            } else if (update.validationResult == VALIDATION_RESULT_FAILURE) {
                mValidationMap.put(p, ValidationStatus.FAILED);
            } else {
                Log.e(TAG, "Unknown private dns validation operation="
                        + update.validationResult);
            }
        }

        private LinkProperties fillInValidatedPrivateDns(LinkProperties lp) {
            lp.setValidatedPrivateDnsServers(Collections.EMPTY_LIST);
            mValidationMap.forEach((key, value) -> {
                    if (value == ValidationStatus.SUCCEEDED) {
                        lp.addValidatedPrivateDnsServer(key.second);
                    }
                });
            return lp;
        }
    }

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final IDnsResolver mDnsResolver;
    private final ConcurrentHashMap<Integer, PrivateDnsConfig> mPrivateDnsMap;
    // TODO: Replace the Map with SparseArrays.
    private final Map<Integer, PrivateDnsValidationStatuses> mPrivateDnsValidationMap;
    private final Map<Integer, LinkProperties> mLinkPropertiesMap;
    private final Map<Integer, NetworkCapabilities> mNetworkCapabilitiesMap;

    private final UserManager mUserManager;

    private int mSampleValidity;
    private int mSuccessThreshold;
    private int mMinSamples;
    private int mMaxSamples;

    @Nullable private NetworkAgentInfo mDefaultNetwork;

    public DnsManager(Context ctx, IDnsResolver dnsResolver) {
        mContext = ctx;
        mContentResolver = mContext.getContentResolver();
        mDnsResolver = dnsResolver;

        mPrivateDnsMap = new ConcurrentHashMap<>();
        mPrivateDnsValidationMap = new HashMap<>();
        mLinkPropertiesMap = new HashMap<>();
        mNetworkCapabilitiesMap = new HashMap<>();

        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);

        // TODO: Create and register ContentObservers to track every setting
        // used herein, posting messages to respond to changes.
    }

    public PrivateDnsConfig getPrivateDnsConfig(GlobalOrUserId target) {
        return getPrivateDnsConfig(mContext, target);
    }

    /**
     * @return The network's target (global or a specific userId), or null if the target can't be
     *         determined.
     */
    public @Nullable GlobalOrUserId getNetworkTarget(@NonNull NetworkAgentInfo nai) {
        if (nai.isVPN()) {
            // If it's not one of the supported types then we can't be sure how to get the correct
            // config.
            ConnectivityService.checkSupportedVpnType(nai);

            // Both TYPE_VPN_PLATFORM (VpnManager) and TYPE_VPN_SERVICE (VpnService) VPN types are
            // created by VpnManagerService, where Vpn.getAppUid() is the source of the owner uid.
            // Per this method, the ownerUid will always be positive unless the package can't be
            // found. If the package can't be found then either the VPN app was uninstalled at the
            // the same time the tunnel was being created, or there's a bug.
            // For TYPE_VPN_PLATFORM, the owner uid is always system_server, so the VPN private DNS
            // config of user 0 applies to these VPNs, regardless of the user that created the
            // platform VPN.
            int ownerUid = nai.networkCapabilities.getOwnerUid();
            if (ownerUid < 0) {
                Log.e(TAG, "VPN target not found due to missing ownerUid");
                return null;
            }
            return GlobalOrUserId.userId(UserHandle.getUserId(ownerUid));
        }
        return GlobalOrUserId.GLOBAL;
    }

    public void removeNetwork(Network network) {
        mPrivateDnsMap.remove(network.getNetId());
        mPrivateDnsValidationMap.remove(network.getNetId());
        mNetworkCapabilitiesMap.remove(network.getNetId());
        mLinkPropertiesMap.remove(network.getNetId());
    }

    // This is exclusively called by ConnectivityService#dumpNetworkDiagnostics() which
    // is not on the ConnectivityService handler thread.
    public PrivateDnsConfig getPrivateDnsConfig(@NonNull Network network) {
        return mPrivateDnsMap.getOrDefault(network.getNetId(), PRIVATE_DNS_OFF);
    }

    public PrivateDnsConfig updatePrivateDns(Network network, PrivateDnsConfig cfg) {
        Log.w(TAG, "updatePrivateDns(" + network + ", " + cfg + ")");
        return (cfg != null)
                ? mPrivateDnsMap.put(network.getNetId(), cfg)
                : mPrivateDnsMap.remove(network.getNetId());
    }

    public void updatePrivateDnsStatus(int netId, LinkProperties lp) {
        // Use the PrivateDnsConfig data pushed to this class instance
        // from ConnectivityService.
        final PrivateDnsConfig privateDnsCfg = mPrivateDnsMap.getOrDefault(netId,
                PRIVATE_DNS_OFF);

        final boolean useTls = privateDnsCfg.mode != PRIVATE_DNS_MODE_OFF;
        final PrivateDnsValidationStatuses statuses =
                useTls ? mPrivateDnsValidationMap.get(netId) : null;
        final boolean validated = (null != statuses) && statuses.hasValidatedServer();
        final boolean strictMode = privateDnsCfg.inStrictMode();
        final String tlsHostname = strictMode ? privateDnsCfg.hostname : null;
        final boolean usingPrivateDns = strictMode || validated;

        lp.setUsePrivateDns(usingPrivateDns);
        lp.setPrivateDnsServerName(tlsHostname);
        if (usingPrivateDns && null != statuses) {
            statuses.fillInValidatedPrivateDns(lp);
        } else {
            lp.setValidatedPrivateDnsServers(Collections.EMPTY_LIST);
        }
    }

    public void updatePrivateDnsValidation(PrivateDnsValidationUpdate update) {
        final PrivateDnsValidationStatuses statuses = mPrivateDnsValidationMap.get(update.netId);
        if (statuses == null) return;
        statuses.updateStatus(update);
    }

    /**
     * Update {@link NetworkCapabilities} stored in this instance.
     *
     * In order to ensure that the resolver has access to necessary information when other events
     * occur, capabilities are always saved to a hashMap before updating the DNS configuration
     * whenever a new network is created, transport types are modified, or metered capabilities are
     * altered for a network. When a network is destroyed, the corresponding entry is removed from
     * the hashMap. To prevent concurrency issues, the hashMap should always be accessed from the
     * same thread.
     */
    public void updateCapabilitiesForNetwork(int netId, @NonNull final NetworkCapabilities nc) {
        mNetworkCapabilitiesMap.put(netId, nc);
        sendDnsConfigurationForNetwork(netId);
    }

    /**
     * When {@link LinkProperties} are changed in a specific network, they are
     * always saved to a hashMap before update dns config.
     * When destroying network, the specific network will be removed from the hashMap.
     * The hashMap is always accessed on the same thread.
     */
    public void noteDnsServersForNetwork(int netId, @NonNull LinkProperties lp) {
        mLinkPropertiesMap.put(netId, lp);
        sendDnsConfigurationForNetwork(netId);
    }

    public void setDefaultNetwork(@Nullable NetworkAgentInfo network) {
        mDefaultNetwork = network;
    }

    public @Nullable NetworkAgentInfo getDefaultNetwork() {
        return mDefaultNetwork;
    }

    /**
     * Send dns configuration parameters to resolver for a given network.
     */
    public void sendDnsConfigurationForNetwork(int netId) {
        final LinkProperties lp = mLinkPropertiesMap.get(netId);
        final NetworkCapabilities nc = mNetworkCapabilitiesMap.get(netId);
        if (lp == null || nc == null) return;

        updateParametersSettings();
        final ResolverParamsParcel paramsParcel = new ResolverParamsParcel();

        // We only use the PrivateDnsConfig data pushed to this class instance
        // from ConnectivityService because it works in coordination with
        // NetworkMonitor to decide which networks need validation and runs the
        // blocking calls to resolve Private DNS strict mode hostnames.
        //
        // At this time we do not attempt to enable Private DNS on non-Internet
        // networks like IMS.
        final PrivateDnsConfig privateDnsCfg = mPrivateDnsMap.getOrDefault(netId,
                PRIVATE_DNS_OFF);
        final boolean useTls = privateDnsCfg.mode != PRIVATE_DNS_MODE_OFF;
        final boolean strictMode = privateDnsCfg.inStrictMode();

        paramsParcel.netId = netId;
        paramsParcel.sampleValiditySeconds = mSampleValidity;
        paramsParcel.successThreshold = mSuccessThreshold;
        paramsParcel.minSamples = mMinSamples;
        paramsParcel.maxSamples = mMaxSamples;
        paramsParcel.servers = makeStrings(lp.getDnsServers());
        paramsParcel.domains = getDomainStrings(lp.getDomains());
        paramsParcel.tlsName = strictMode ? privateDnsCfg.hostname : "";

        paramsParcel.tlsServers =
                strictMode ? createStrictModeServers(nc, lp, privateDnsCfg, mDefaultNetwork)
                : useTls ? paramsParcel.servers  // Opportunistic
                : new String[0];            // Off
        paramsParcel.transportTypes = nc.getTransportTypes();
        paramsParcel.meteredNetwork = nc.isMetered();
        paramsParcel.interfaceNames = lp.getAllInterfaceNames().toArray(new String[0]);
        paramsParcel.dohParams = makeDohParamsParcel(privateDnsCfg, lp);

        // Prepare to track the validation status of the DNS servers in the
        // resolver config when private DNS is in opportunistic or strict mode.
        if (useTls) {
            if (!mPrivateDnsValidationMap.containsKey(netId)) {
                mPrivateDnsValidationMap.put(netId, new PrivateDnsValidationStatuses());
            }
            mPrivateDnsValidationMap.get(netId).updateTrackedDnses(paramsParcel.tlsServers,
                    paramsParcel.tlsName);
        } else {
            mPrivateDnsValidationMap.remove(netId);
        }

        Log.d(TAG, "sendDnsConfigurationForNetwork(" + paramsParcel + ")");
        try {
            mDnsResolver.setResolverConfiguration(paramsParcel);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Error setting DNS configuration: " + e);
        }
    }

    /**
     * @return The IP addresses from privateDnsCfg that the system DNS resolver can reach when doing
     *         doing private DNS on the network whose NetworkCapabilities are nc and LinkProperties
     *         are lp.
     */
    private String[] createStrictModeServers(NetworkCapabilities nc, LinkProperties lp,
            PrivateDnsConfig privateDnsCfg, @Nullable NetworkAgentInfo defaultNetwork) {
        String[] strictModeServers;

        // If it's a VPN without lockdown then traffic can go over the VPN or the default
        // network.
        if (isVpnWithoutLockdown(mContext, nc)) {
            List<InetAddress> reachableIps = getReachableAddressList(privateDnsCfg.ips, lp);

            // TODO: Investigate whether default network reachable IPs should always be included.
            // We would need to remove duplicates and sort them, instead of letting DnsResolver sort
            // them. Doing it for DoH is definitely fine as it only ever uses the first server. DoT
            // would attempt to validate every server which would mean bypassing the VPN where
            // possibly avoidable.
            if (reachableIps.isEmpty() && defaultNetwork != null) {
                reachableIps.addAll(getReachableAddressList(privateDnsCfg.ips,
                        defaultNetwork.linkProperties));
            }

            strictModeServers = makeStrings(reachableIps);
        } else {
            // If it's a physical network or lockdown VPN then traffic can only go over that
            // particular network.
            strictModeServers = makeStrings(getReachableAddressList(privateDnsCfg.ips, lp));
        }

        return strictModeServers;
    }

    /**
     * @return The netIds of non-lockdown VPNs which are using strict private DNS. Networks that
     *         have not yet had their DNS configuration sent to DnsResolver are excluded.
     */
    public List<Integer> getNonLockdownVpnsUsingStrictPrivateDns() {
        List<Integer> netIds = new ArrayList<>();
        // While there could be matching networks that aren't in mNetworkCapabilitiesMap, they can't
        // have had their DNS configuration sent to DnsResolver because
        // sendDnsConfigurationForNetwork() exits early.
        for (Map.Entry<Integer, NetworkCapabilities> entry : mNetworkCapabilitiesMap.entrySet()) {
            final PrivateDnsConfig privateDnsCfg = mPrivateDnsMap.getOrDefault(
                    entry.getKey(), PRIVATE_DNS_OFF);
            // Checking private DNS first ensures isVpnWithoutLockdown() is not passed a network
            // with a missing owner uid, as these networks would have always returned a null target
            // for which there is no private DNS config.
            // Similar to above, not being in mLinkPropertiesMap results in
            // sendDnsConfigurationForNetwork() exiting early, so skip those.
            if (privateDnsCfg.inStrictMode()
                    && mLinkPropertiesMap.get(entry.getKey()) != null
                    && isVpnWithoutLockdown(mContext, entry.getValue())) {
                netIds.add(entry.getKey());
            }
        }
        return netIds;
    }

    private boolean isVpnWithoutLockdown(@NonNull Context context,
            @NonNull NetworkCapabilities capabilities) {
        boolean isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        if (!isVpn) {
            return false;
        }

        int ownerUid = capabilities.getOwnerUid();
        // Refer to getNetworkTarget().
        if (ownerUid < 0) {
            // We ensure this isn't reached by only calling this method when mPrivateDnsConfigMap
            // contains an entry for the netId, as that can only happen if getNetworkTarget()
            // returns a non-null value.
            throw new IllegalArgumentException("caller must ensure capabilities has an ownerUid");
        }

        // This considers the service VPN lockdown status, not the platform VPN lockdown status.
        // As a result, platform VPNs will always return true.
        return !ConnectivityUtil.isLockdownVpnEnabled(context, UserHandle.getUserId(ownerUid));
    }

    public boolean shouldSendStrictModeUpdate(int netId, LinkProperties oldLp,
            LinkProperties newLp) {
        return shouldSendStrictModeUpdate(netId, oldLp, newLp, mDefaultNetwork, mDefaultNetwork);
    }

    public boolean shouldSendStrictModeUpdate(int netId, LinkProperties oldLp,
            LinkProperties newLp, @Nullable NetworkAgentInfo oldDefaultNetwork,
            @Nullable NetworkAgentInfo newDefaultNetwork) {
        NetworkCapabilities nc = mNetworkCapabilitiesMap.get(netId);
        PrivateDnsConfig privateDnsconfig = mPrivateDnsMap.getOrDefault(netId, PRIVATE_DNS_OFF);
        if (nc == null || !privateDnsconfig.inStrictMode()) {
            return false;
        }

        String[] oldReachableServers = createStrictModeServers(nc, oldLp, privateDnsconfig,
                oldDefaultNetwork);
        String[] newReachableServers = createStrictModeServers(nc, newLp, privateDnsconfig,
                newDefaultNetwork);

        return !Arrays.equals(oldReachableServers, newReachableServers);
    }

    /**
     * Flush DNS caches and events work before boot has completed.
     */
    public void flushVmDnsCache(NetworkAgentInfo nai) {
        /*
         * Tell the VMs to toss their DNS caches
         */
        final Intent intent = new Intent(ConnectivityManager.ACTION_CLEAR_DNS_CACHE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        /*
         * Connectivity events can happen before boot has completed ...
         */
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        final long ident = Binder.clearCallingIdentity();

        List<UserHandle> flushTargets = getDnsCacheFlushTargets(nai);
        for (UserHandle user : flushTargets) {
            mContext.sendBroadcastAsUser(intent, user);
        }

        Binder.restoreCallingIdentity(ident);
    }

    /**
     * @return The UserHandles that should have their VM DNS caches flushed as a result of a change
     *         in the DNS servers of nai.
     */
    public List<UserHandle> getDnsCacheFlushTargets(@NonNull NetworkAgentInfo nai) {
        List<UserHandle> flushTargets = new ArrayList<>();

        GlobalOrUserId networkTarget = getNetworkTarget(nai);
        if (networkTarget == null) {
            return flushTargets;
        }

        if (networkTarget.isGlobal()) {
            for (UserHandle user : mUserManager.getUserHandles(true)) {
                // Assume any user that doesn't have a lockdown VPN can use the global physical
                // networks for DNS. This isn't technically the case because VPNs have a
                // secure/bypassable attribute that restricts physical network access for DNS.
                // A problem with doing it this way is that system apps/components that are under
                // a lockdown VPN can still do DNS on the physical networks. This is unlikely to be
                // an issue in practice.
                if (!ConnectivityUtil.isLockdownVpnEnabled(mContext, user.getIdentifier())) {
                    if (mUserManager.isUserRunning(user)) {
                        flushTargets.add(user);
                    }
                }
            }
        } else {
            flushTargets.add(networkTarget.getUserHandle());
        }

        return flushTargets;
    }

    private void updateParametersSettings() {
        mSampleValidity = getIntSetting(
                DNS_RESOLVER_SAMPLE_VALIDITY_SECONDS,
                DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS);
        if (mSampleValidity < 0 || mSampleValidity > 65535) {
            Log.w(TAG, "Invalid sampleValidity=" + mSampleValidity + ", using default="
                    + DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS);
            mSampleValidity = DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS;
        }

        mSuccessThreshold = getIntSetting(
                DNS_RESOLVER_SUCCESS_THRESHOLD_PERCENT,
                DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT);
        if (mSuccessThreshold < 0 || mSuccessThreshold > 100) {
            Log.w(TAG, "Invalid successThreshold=" + mSuccessThreshold + ", using default="
                    + DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT);
            mSuccessThreshold = DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT;
        }

        mMinSamples = getIntSetting(DNS_RESOLVER_MIN_SAMPLES, DNS_RESOLVER_DEFAULT_MIN_SAMPLES);
        mMaxSamples = getIntSetting(DNS_RESOLVER_MAX_SAMPLES, DNS_RESOLVER_DEFAULT_MAX_SAMPLES);
        if (mMinSamples < 0 || mMinSamples > mMaxSamples || mMaxSamples > 64) {
            Log.w(TAG, "Invalid sample count (min, max)=(" + mMinSamples + ", " + mMaxSamples
                    + "), using default=(" + DNS_RESOLVER_DEFAULT_MIN_SAMPLES + ", "
                    + DNS_RESOLVER_DEFAULT_MAX_SAMPLES + ")");
            mMinSamples = DNS_RESOLVER_DEFAULT_MIN_SAMPLES;
            mMaxSamples = DNS_RESOLVER_DEFAULT_MAX_SAMPLES;
        }
    }

    private int getIntSetting(String which, int dflt) {
        return Settings.Global.getInt(mContentResolver, which, dflt);
    }

    /**
     * Create a string array of host addresses from a collection of InetAddresses
     *
     * @param addrs a Collection of InetAddresses
     * @return an array of Strings containing their host addresses
     */
    private String[] makeStrings(Collection<InetAddress> addrs) {
        String[] result = new String[addrs.size()];
        int i = 0;
        for (InetAddress addr : addrs) {
            result[i++] = addr.getHostAddress();
        }
        return result;
    }

    private static String getStringSetting(ContentResolver cr, String which) {
        return Settings.Global.getString(cr, which);
    }

    private static String[] getDomainStrings(String domains) {
        return (TextUtils.isEmpty(domains)) ? new String[0] : domains.split(" ");
    }

    @NonNull
    private List<InetAddress> getReachableAddressList(@NonNull InetAddress[] ips,
            @NonNull LinkProperties lp) {
        final ArrayList<InetAddress> out = new ArrayList<InetAddress>(Arrays.asList(ips));
        out.removeIf(ip -> !lp.isReachable(ip));
        return out;
    }

    @Nullable
    private DohParamsParcel makeDohParamsParcel(@NonNull PrivateDnsConfig cfg,
            @NonNull LinkProperties lp) {
        if (!cfg.ddrEnabled) {
            return null;
        }
        if (cfg.mode == PRIVATE_DNS_MODE_OFF) {
            return new DohParamsParcel.Builder().build();
        }
        return new DohParamsParcel.Builder()
                .setName(cfg.dohName)
                .setIps(makeStrings(getReachableAddressList(cfg.dohIps, lp)))
                .setDohpath(cfg.dohPath)
                .setPort(cfg.dohPort)
                .build();
    }
}
