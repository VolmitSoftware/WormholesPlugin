package art.arcane.wormholes.portal.rtp;

import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;

public interface RtpDestinationAccessPolicy
{
	CompletableFuture<RtpAccessResult> canUse(Player player, RtpDestination destination);
}
