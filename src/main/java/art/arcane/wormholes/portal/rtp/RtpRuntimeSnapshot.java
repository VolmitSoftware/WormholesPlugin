package art.arcane.wormholes.portal.rtp;

import java.util.List;
import java.util.Objects;

public record RtpRuntimeSnapshot(
		long generation,
		RtpAllocationMode allocationMode,
		RtpRotationMode rotationMode,
		boolean ready,
		RtpDestination active,
		RtpDestination standby,
		long routeRevision,
		long nextRotationAtMillis,
		boolean timedRotationPending,
		boolean rerolling,
		boolean searchInFlight,
		int candidateCount,
		int targetCandidateCount,
		int interestedPlayers,
		int freeCandidates,
		List<RtpDestination> freeEntries,
		int reservedPlayers,
		int releasingPlayers,
		int sharedClaims,
		int playerClaims,
		int anonymousClaims)
{
	public RtpRuntimeSnapshot
	{
		Objects.requireNonNull(allocationMode);
		Objects.requireNonNull(rotationMode);
		freeEntries = List.copyOf(Objects.requireNonNull(freeEntries));
		if (candidateCount < 0 || targetCandidateCount < 0 || interestedPlayers < 0 || freeCandidates < 0 || reservedPlayers < 0
				|| releasingPlayers < 0 || sharedClaims < 0 || playerClaims < 0 || anonymousClaims < 0)
		{
			throw new IllegalArgumentException("Runtime snapshot counts cannot be negative");
		}
		if (freeEntries.size() != freeCandidates)
		{
			throw new IllegalArgumentException("Free-entry count does not match snapshot entries");
		}
	}
}
