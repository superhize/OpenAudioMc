package com.craftmend.openaudiomc.generic.client.session;

import com.craftmend.openaudiomc.OpenAudioMc;
import com.craftmend.openaudiomc.api.impl.event.events.MicrophoneMuteEvent;
import com.craftmend.openaudiomc.api.impl.event.events.MicrophoneUnmuteEvent;
import com.craftmend.openaudiomc.api.impl.event.events.PlayerEnterVoiceProximityEvent;
import com.craftmend.openaudiomc.api.impl.event.events.PlayerLeaveVoiceProximityEvent;
import com.craftmend.openaudiomc.api.impl.event.enums.VoiceEventCause;
import com.craftmend.openaudiomc.api.interfaces.AudioApi;
import com.craftmend.openaudiomc.generic.client.objects.ClientConnection;
import com.craftmend.openaudiomc.generic.craftmend.CraftmendService;
import com.craftmend.openaudiomc.generic.client.enums.RtcBlockReason;
import com.craftmend.openaudiomc.generic.client.enums.RtcStateFlag;
import com.craftmend.openaudiomc.generic.client.helpers.ClientRtcLocationUpdate;
import com.craftmend.openaudiomc.generic.networking.interfaces.NetworkingService;
import com.craftmend.openaudiomc.generic.networking.packets.client.voice.PacketClientDropVoiceStream;
import com.craftmend.openaudiomc.generic.networking.packets.client.voice.PacketClientSubscribeToVoice;
import com.craftmend.openaudiomc.generic.proxy.interfaces.UserHooks;
import com.craftmend.openaudiomc.generic.user.User;
import com.craftmend.openaudiomc.generic.utils.data.RandomString;
import com.craftmend.openaudiomc.spigot.services.world.Vector3;
import com.craftmend.openaudiomc.generic.networking.payloads.client.voice.ClientVoiceDropPayload;
import com.craftmend.openaudiomc.generic.networking.payloads.client.voice.ClientVoiceSubscribePayload;
import com.craftmend.openaudiomc.generic.node.packets.ForceMuteMicrophonePacket;
import com.craftmend.openaudiomc.generic.platform.Platform;
import com.craftmend.openaudiomc.generic.voicechat.bus.VoiceApiConnection;
import com.craftmend.openaudiomc.spigot.modules.players.SpigotPlayerService;
import com.craftmend.openaudiomc.spigot.modules.players.enums.PlayerLocationFollower;
import com.craftmend.openaudiomc.spigot.modules.players.objects.SpigotConnection;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RtcSessionManager implements Serializable {

    @Getter private boolean isMicrophoneEnabled = false;
    @Getter private final transient Set<UUID> subscriptions = new HashSet<>();
    @Getter private final transient Set<ClientRtcLocationUpdate> locationUpdateQueue = ConcurrentHashMap.newKeySet();
    @Getter private final transient Set<RtcBlockReason> blockReasons = new HashSet<>();
    @Getter private final transient Set<RtcStateFlag> stateFlags = new HashSet<>();
    @Getter private final transient Set<UUID> recentPeerAdditions = new HashSet<>();
    @Getter private final transient Set<UUID> recentPeerRemovals = new HashSet<>();
    @Setter @Getter private String streamKey;
    private transient Location lastPassedLocation = null;
    private final transient ClientConnection clientConnection;

    public RtcSessionManager(ClientConnection clientConnection) {
        this.streamKey = new RandomString(15).nextString();
        this.clientConnection = clientConnection;

        this.clientConnection.onDisconnect(() -> {
            // go over all other clients, check if we might have a relations ship and break up if thats the case
            subscriptions.clear();
            this.isMicrophoneEnabled = false;
            makePeersDrop();
            locationUpdateQueue.clear();
        });
    }

    /**
     * Makes two users listen to one another
     *
     * @param peer Who I should become friends with
     * @return If I became friends
     */
    public boolean linkTo(ClientConnection peer) {
        if (!isReady())
            return false;

        if (!peer.getRtcSessionManager().isReady())
            return false;

        if (subscriptions.contains(peer.getOwner().getUniqueId()))
            return false;

        if (peer.getRtcSessionManager().subscriptions.contains(clientConnection.getOwner().getUniqueId()))
            return false;

        peer.getRtcSessionManager().getSubscriptions().add(clientConnection.getOwner().getUniqueId());
        subscriptions.add(peer.getOwner().getUniqueId());

        peer.sendPacket(new PacketClientSubscribeToVoice(ClientVoiceSubscribePayload.fromClient(clientConnection, Vector3.from(peer))));
        clientConnection.sendPacket(new PacketClientSubscribeToVoice(ClientVoiceSubscribePayload.fromClient(peer, Vector3.from(clientConnection))));

        // throw events in both ways, since the two users are listening to eachother
        AudioApi.getInstance().getEventDriver().fire(new PlayerEnterVoiceProximityEvent(clientConnection, peer, VoiceEventCause.NORMAL));
        AudioApi.getInstance().getEventDriver().fire(new PlayerEnterVoiceProximityEvent(peer, clientConnection, VoiceEventCause.NORMAL));

        updateLocationWatcher();
        peer.getRtcSessionManager().updateLocationWatcher();

        if (peer.getDataCache() != null) peer.getDataCache().pushPeerName(clientConnection.getOwner().getName());
        if (clientConnection.getDataCache() != null) clientConnection.getDataCache().pushPeerName(peer.getOwner().getName());

        return true;
    }

    /**
     * Completely block/unblock speaking for a client.
     * This will forcefully block their microphone on the client and server side making them unable to speak
     * no matter what their microphone settings are
     *
     * @param allow If speaking is allowed
     */
    public void allowSpeaking(boolean allow) {
        // platform dependant
        if (OpenAudioMc.getInstance().getPlatform() == Platform.SPIGOT && OpenAudioMc.getInstance().getInvoker().isNodeServer()) {
            // forward to proxy
            User user = clientConnection.getUser();
            OpenAudioMc.resolveDependency(UserHooks.class).sendPacket(user, new ForceMuteMicrophonePacket(clientConnection.getOwner().getUniqueId(), allow));
            return;
        }
        VoiceApiConnection voiceService = OpenAudioMc.getService(CraftmendService.class).getVoiceApiConnection();

        if (allow) {
            voiceService.forceMute(clientConnection);
        } else {
            voiceService.forceUnmute(clientConnection);
        }
    }

    public void makePeersDrop() {
        for (ClientConnection peer : OpenAudioMc.getService(NetworkingService.class).getClients()) {
            if (peer.getOwner().getUniqueId() == clientConnection.getOwner().getUniqueId())
                continue;

            if (peer.getRtcSessionManager().subscriptions.contains(clientConnection.getOwner().getUniqueId())) {
                // send unsub packet
                peer.getRtcSessionManager().subscriptions.remove(clientConnection.getOwner().getUniqueId());
                peer.getRtcSessionManager().updateLocationWatcher();
                peer.sendPacket(new PacketClientDropVoiceStream(new ClientVoiceDropPayload(streamKey)));

                AudioApi.getInstance().getEventDriver().fire(new PlayerLeaveVoiceProximityEvent(clientConnection, peer, VoiceEventCause.NORMAL));
            }
        }
    }

    public void onLocationTick(Location location) {
        if (this.isReady() && this.isMicrophoneEnabled() && this.blockReasons.isEmpty()) {
            this.forceUpdateLocation(location);
        } else {
            lastPassedLocation = location;
        }
    }

    public void forceUpdateLocation(Location location) {
        for (ClientConnection peer : OpenAudioMc.getService(NetworkingService.class).getClients()) {
            if (peer.getOwner().getUniqueId() == clientConnection.getOwner().getUniqueId())
                continue;

            if (peer.getRtcSessionManager().subscriptions.contains(clientConnection.getOwner().getUniqueId())) {
                peer.getRtcSessionManager().locationUpdateQueue.add(
                        ClientRtcLocationUpdate
                                .fromClientWithLocation(clientConnection, location, Vector3.from(peer))
                );
            }
        }
    }

    public void updateLocationWatcher() {
        if (OpenAudioMc.getInstance().getPlatform() == Platform.SPIGOT) {
            SpigotConnection spigotConnection = OpenAudioMc.getService(SpigotPlayerService.class).getClient(clientConnection.getOwner().getUniqueId());
            if (subscriptions.isEmpty()) {
                spigotConnection.getLocationFollowers().remove(PlayerLocationFollower.PROXIMITY_VOICE_CHAT);
            } else {
                spigotConnection.getLocationFollowers().add(PlayerLocationFollower.PROXIMITY_VOICE_CHAT);
            }
        }
    }

    public boolean isReady() {
        if (clientConnection.getDataCache() != null && clientConnection.getDataCache().isVoiceBlocked()) {
            return false;
        }

        return clientConnection.isConnected() && clientConnection.getSession().isConnectedToRtc();
    }

    public void setMicrophoneEnabled(boolean state) {
        if (!this.isMicrophoneEnabled && state) {
            if (this.lastPassedLocation != null) {
                forceUpdateLocation(lastPassedLocation);
            }
        }

        this.isMicrophoneEnabled = state;

        if (!this.isReady()) return;

        if (state) {
            AudioApi.getInstance().getEventDriver().fire(new MicrophoneUnmuteEvent(clientConnection));
        } else {
            AudioApi.getInstance().getEventDriver().fire(new MicrophoneMuteEvent(clientConnection));
        }
    }
}
