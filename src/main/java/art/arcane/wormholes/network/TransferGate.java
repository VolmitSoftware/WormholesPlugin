package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake;

public final class TransferGate extends PacketListenerAbstract {
    public TransferGate() {
        super(PacketListenerPriority.LOW);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Handshaking.Client.HANDSHAKE) {
            return;
        }
        NetworkConfig config = Wormholes.settings == null ? null : Wormholes.settings.getNetwork();
        if (config == null || !config.enabled || !config.autoAcceptTransfers) {
            return;
        }
        WrapperHandshakingClientHandshake handshake = new WrapperHandshakingClientHandshake(event);
        if (handshake.getIntention() != WrapperHandshakingClientHandshake.ConnectionIntention.TRANSFER) {
            return;
        }
        handshake.setIntention(WrapperHandshakingClientHandshake.ConnectionIntention.LOGIN);
        event.markForReEncode(true);
    }
}
