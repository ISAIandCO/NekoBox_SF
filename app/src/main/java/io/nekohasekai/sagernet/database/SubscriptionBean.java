package io.nekohasekai.sagernet.database;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.SubscriptionUpdateMode;
import io.nekohasekai.sagernet.fmt.Serializable;

public class SubscriptionBean extends Serializable {

    public Integer type;
    public String link;
    public String token;
    public Boolean forceResolve;
    public Boolean deduplication;
    public Boolean updateWhenConnectedOnly;
    public String customUserAgent;
    public Boolean sendHwid;
    public String customHwidParams;
    public String serverDnsResolver;
    public String customHeaders;
    public Boolean autoUpdate;
    public Integer autoUpdateDelay;
    public Integer lastUpdated;
    public Integer updateMode;
    public Integer lastChecked;
    public Boolean reviewRequired;
    public long[] deferredProfileIds;
    public Boolean partialUpdateApplied;
    public Integer filterMode;
    public String filterRegex;

    // SIP008

    public Long bytesUsed;
    public Long bytesRemaining;

    // Open Online Config

    public String username;
    public Integer expiryDate;
    public List<String> protocols;


    // https://github.com/crossutility/Quantumult/blob/master/extra-subscription-feature.md

    public String subscriptionUserinfo;

    public SubscriptionBean() {
    }

    @Override
    public void serializeToBuffer(ByteBufferOutput output) {
        output.writeInt(5);

        output.writeInt(type);

        output.writeString(link);

        output.writeBoolean(forceResolve);
        output.writeBoolean(deduplication);
        output.writeBoolean(updateWhenConnectedOnly);
        output.writeString(customUserAgent);
        output.writeBoolean(autoUpdate);
        output.writeInt(autoUpdateDelay);
        output.writeInt(lastUpdated);

        output.writeString(subscriptionUserinfo);

        // v2
        output.writeInt(filterMode);
        output.writeString(filterRegex);

        // v3
        output.writeBoolean(sendHwid);
        output.writeString(customHwidParams);
        // v4: do not reuse upstream v3, occupied by this fork's HWID fields.
        output.writeString(serverDnsResolver);
        output.writeString(customHeaders);

        output.writeInt(updateMode);
        output.writeInt(lastChecked);
        output.writeBoolean(reviewRequired);
        output.writeVarInt(deferredProfileIds.length, true);
        for (long profileId : deferredProfileIds) output.writeLong(profileId);
        output.writeBoolean(partialUpdateApplied);
    }

    public void serializeForShare(ByteBufferOutput output) {
        output.writeInt(2);

        output.writeInt(type);

        output.writeString(link);

        output.writeBoolean(forceResolve);
        output.writeBoolean(deduplication);
        output.writeBoolean(updateWhenConnectedOnly);
        output.writeString(customUserAgent);
        output.writeBoolean(sendHwid);
        output.writeString(customHwidParams);
        output.writeString(serverDnsResolver);
        output.writeString(customHeaders);
    }

    @Override
    public void deserializeFromBuffer(ByteBufferInput input) {
        int version = input.readInt();

        type = input.readInt();
        link = input.readString();
        forceResolve = input.readBoolean();
        deduplication = input.readBoolean();
        updateWhenConnectedOnly = input.readBoolean();
        customUserAgent = input.readString();
        autoUpdate = input.readBoolean();
        autoUpdateDelay = input.readInt();
        lastUpdated = input.readInt();
        subscriptionUserinfo = input.readString();

        // v2
        if (version >= 2) {
            filterMode = input.readInt();
            filterRegex = input.readString();
        }
        if (version >= 3) {
            sendHwid = input.readBoolean();
            customHwidParams = input.readString();
        }
        if (version >= 4) {
            serverDnsResolver = input.readString();
            customHeaders = input.readString();
        }
        if (version >= 5) {
            updateMode = input.readInt();
            lastChecked = input.readInt();
            reviewRequired = input.readBoolean();
            int count = input.readVarInt(true);
            if (count < 0 || count > (input.limit() - input.position()) / Long.BYTES) {
                throw new IllegalArgumentException("Invalid deferred profile count");
            }
            deferredProfileIds = new long[count];
            for (int i = 0; i < count; i++) deferredProfileIds[i] = input.readLong();
            partialUpdateApplied = input.readBoolean();
        }
    }

    public void resetUpdateStatus() {
        lastChecked = 0;
        reviewRequired = false;
        deferredProfileIds = new long[0];
        partialUpdateApplied = false;
    }

    public void deserializeFromShare(ByteBufferInput input) {
        int version = input.readInt();

        type = input.readInt();
        link = input.readString();
        forceResolve = input.readBoolean();
        deduplication = input.readBoolean();
        updateWhenConnectedOnly = input.readBoolean();
        customUserAgent = input.readString();
        if (version >= 1) {
            sendHwid = input.readBoolean();
            customHwidParams = input.readString();
        }
        if (version >= 2) {
            serverDnsResolver = input.readString();
            customHeaders = input.readString();
        }
    }

    @Override
    public void initializeDefaultValues() {
        if (type == null) type = 0;
        if (link == null) link = "";
        if (token == null) token = "";
        if (forceResolve == null) forceResolve = false;
        if (deduplication == null) deduplication = false;
        if (updateWhenConnectedOnly == null) updateWhenConnectedOnly = false;
        if (customUserAgent == null) customUserAgent = "";
        if (sendHwid == null) sendHwid = false;
        if (customHwidParams == null) customHwidParams = "";
        if (serverDnsResolver == null) serverDnsResolver = "";
        if (customHeaders == null) customHeaders = "";
        if (autoUpdate == null) autoUpdate = false;
        if (autoUpdateDelay == null) autoUpdateDelay = 1440;
        if (lastUpdated == null) lastUpdated = 0;
        if (updateMode == null || updateMode < SubscriptionUpdateMode.REVIEW_CRITICAL
                || updateMode > SubscriptionUpdateMode.APPLY_IMMEDIATELY) {
            updateMode = SubscriptionUpdateMode.APPLY_IMMEDIATELY;
        }
        if (lastChecked == null) lastChecked = 0;
        if (reviewRequired == null) reviewRequired = false;
        if (deferredProfileIds == null) deferredProfileIds = new long[0];
        if (partialUpdateApplied == null) partialUpdateApplied = false;
        if (filterMode == null) filterMode = 0;
        if (filterRegex == null) filterRegex = "";

        if (bytesUsed == null) bytesUsed = 0L;
        if (bytesRemaining == null) bytesRemaining = 0L;

        if (username == null) username = "";
        if (expiryDate == null) expiryDate = 0;
        if (protocols == null) protocols = new ArrayList<>();
    }

    public static final Creator<SubscriptionBean> CREATOR = new CREATOR<SubscriptionBean>() {
        @NonNull
        @Override
        public SubscriptionBean newInstance() {
            return new SubscriptionBean();
        }

        @Override
        public SubscriptionBean[] newArray(int size) {
            return new SubscriptionBean[size];
        }
    };

}
