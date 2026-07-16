package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraversalServiceDestinationAdmissionTest {
    @Test
    void directTransferRequiresDestinationSupport() {
        TraversalService.DestinationPlayerState state = state(true, false, false, false, false, false, 0, 20);

        assertEquals("destination does not accept direct transfers", TraversalService.destinationPlayerDenialReason(state));
    }

    @Test
    void proxyTransferDoesNotRequireNativeTransferSupport() {
        TraversalService.DestinationPlayerState state = state(false, false, false, false, false, false, 0, 20);

        assertNull(TraversalService.destinationPlayerDenialReason(state));
    }

    @Test
    void bannedProfileIsDeniedBeforeWhitelistAndCapacity() {
        TraversalService.DestinationPlayerState state = state(true, true, true, true, false, false, 20, 20);

        assertEquals("player is banned", TraversalService.destinationPlayerDenialReason(state));
    }

    @Test
    void whitelistRequiresMembershipForNonOperator() {
        TraversalService.DestinationPlayerState state = state(true, true, false, true, false, false, 0, 20);

        assertEquals("player is not whitelisted", TraversalService.destinationPlayerDenialReason(state));
    }

    @Test
    void reservationsCountTowardExactCapacityBoundary() {
        TraversalService.DestinationPlayerState state = state(true, true, false, false, false, false, 20, 20);

        assertEquals("destination server is full", TraversalService.destinationPlayerDenialReason(state));
    }

    @Test
    void operatorBypassesWhitelistWhenCapacityRemains() {
        TraversalService.DestinationPlayerState state = state(true, true, false, true, false, true, 19, 20);

        assertNull(TraversalService.destinationPlayerDenialReason(state));
    }

    @Test
    void operatorIsDeniedWhenPlayerLimitBypassCannotBeVerified() {
        TraversalService.DestinationPlayerState state = state(true, true, false, true, false, true, 20, 20);

        assertEquals("destination server is full", TraversalService.destinationPlayerDenialReason(state));
    }

    private static TraversalService.DestinationPlayerState state(
        boolean directTransfer,
        boolean transferSupported,
        boolean banned,
        boolean whitelistEnabled,
        boolean whitelisted,
        boolean operator,
        int admittedPlayers,
        int maxPlayers
    ) {
        return new TraversalService.DestinationPlayerState(
            directTransfer,
            transferSupported,
            banned,
            whitelistEnabled,
            whitelisted,
            operator,
            admittedPlayers,
            maxPlayers
        );
    }
}
