package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes projection cone parameters.",
    "Controls how far the local fake-block volume reaches, how deep the transformed destination volume can be,",
    "and how often the projection is rebuilt for nearby players."
})
public class ProjectionConfig {
    @ConfigDescription({
        "Maximum local fake-block cone range in blocks.",
        "Higher values let the client see a deeper portal volume, but cost grows quickly because more local cells must be tested.",
        "This is capped by the player's negotiated client view distance when client-view-distance-cap is true."
    })
    public double range = 32.0;

    @ConfigDescription({
        "Projector refresh interval in server ticks.",
        "1 updates the viewport every tick for smooth observer movement, 2 updates ten times per second, and higher values reduce CPU/network load at the cost of responsiveness.",
        "Remote block data can still be coalesced separately by stable-cell-resample-interval-ticks."
    })
    public int refreshIntervalTicks = 1;

    @ConfigDescription({
        "Virtual-iris apex padding behind the player's eye in blocks.",
        "Higher values widen the cone when the player is close to the frame, reducing edge pop-out.",
        "Too high can project more cells than needed, so keep this near 1-3 unless close-up portals still clip."
    })
    public double nearPlanePadding = 2.0;

    @ConfigDescription({
        "Extra projection aperture padding in blocks along the two screen axes of the portal face.",
        "This does not enlarge the real portal, the traversal plane, or the protected portal slab.",
        "It only widens fake-block culling so players one block above/below or beside the frame can still see floors, ceilings, and side walls through the viewport.",
        "Higher values render more edge cells; 1.0 adds roughly one block of lateral and vertical visibility around the frame."
    })
    public double aperturePaddingBlocks = 1.0;

    @ConfigDescription({
        "Cube-face culling ratio for portal-frame face selection.",
        "Higher values test fewer frame faces and cost less, but can miss edge cases when viewing portals from steep angles.",
        "Lower values are more accurate for odd axes/heights but increase cell tests."
    })
    public double frustumCullingRatio = 0.2;

    @ConfigDescription({
        "Maximum transformed destination volume depth in blocks.",
        "A visible local cell farther behind the portal plane than this is skipped, even if projection.range is larger.",
        "Raise this when linked portals should show deeper rooms or terrain beyond the destination portal."
    })
    public int depthBlocks = 64;

    @ConfigDescription({
        "Maximum number of portal-through-portal block projection hops.",
        "When a projected destination view contains another linked local portal, Wormholes can continue sampling through that portal so nested portal contents render.",
        "If the nested view hits the depth limit, cycles back into the same portal chain, or has no valid destination, that nested aperture is masked as fake air instead of leaking the physical blocks behind it.",
        "The value is clamped from 3 to 64 to keep recursive portal chains bounded."
    })
    public int recursivePortalDepth = 3;

    @ConfigDescription({
        "Minimum server ticks between full remote block resamples for stable projected cells.",
        "When a local projected cell maps to the same destination coordinate as the previous pass, Wormholes can reuse the last projected BlockData instead of re-reading the remote block every refresh.",
        "1 resamples every projection pass for maximum live block-change accuracy. Higher values reduce CPU while still catching remote block edits on the next full resample.",
        "The default keeps the viewport moving every tick while refreshing stable remote block contents every 4 ticks."
    })
    public int stableCellResampleIntervalTicks = 4;

    @ConfigDescription({
        "Cap projection range/depth by the player's client view distance and the server view distance.",
        "Leave enabled for public servers; disabling lets operators force larger projection distances for controlled tests."
    })
    public boolean clientViewDistanceCap = true;

    @ConfigDescription({
        "Only keep portal projectors active when the observer has a live viewport into the portal.",
        "When false, players inside the portal view volume keep projection state even while looking away or edge-on.",
        "Enable for foveated unrendering on servers where projector count matters more than instant turn-back visibility."
    })
    public boolean foveatedUnrendering = false;

    @ConfigDescription({
        "Minimum dot product between the player's view direction and the vector from their eye to the portal center before a projector is kept active.",
        "1.0 means only looking exactly at the portal, 0.0 means the portal must be somewhere in front of the player, and negative values allow wide peripheral views.",
        "Wormholes applies this as a cheap first-pass interest gate and keeps a short grace window to avoid pop-in."
    })
    public double observerInterestDot = -0.2;

    @ConfigDescription({
        "Minimum absolute dot product between the portal face normal and the vector from the portal center to the player's eye.",
        "When the value is greater than 0, players almost perfectly side-on to a portal get no projection instead of a front/back side flip-flop.",
        "0 disables this grace band. 0.12 suppresses roughly the last 7 degrees on either side of an edge-on view.",
        "This is always used so edge-on portals do not fight over front/back packet ownership."
    })
    public double sideGraceDot = 0.12;

    @ConfigDescription({
        "Maximum eye-position movement, in blocks, that can reuse an already rendered projection frame.",
        "This only applies after the projector has rendered at least once and only until the stable-cell resample interval is due.",
        "The default is 0 because live block accuracy behind the pane is more important than idle-frame reuse.",
        "Raise this only when profiling shows idle observers are a bigger problem than delayed block updates."
    })
    public double stationaryReuseDistanceBlocks = 0.0;

    @ConfigDescription({
        "Maximum yaw or pitch movement, in degrees, that can reuse an already rendered projection frame.",
        "Small values avoid burning CPU for idle observers while still rebuilding quickly when they turn their view.",
        "Set to 0 to disable camera-angle reuse."
    })
    public double stationaryReuseAngleDegrees = 1.5;

    @ConfigDescription({
        "Maximum observer/portal projector updates run during one projection manager tick.",
        "This is the main server-scale safety valve: with many players near controlled portals, observers are updated round-robin instead of all in the same tick.",
        "Raise this on high-end servers for smoother portals; lower it when profiler samples show projection work crowding the main thread."
    })
    public int maxProjectorsPerTick = 24;

    @ConfigDescription({
        "Maximum portal projectors updated for one observer during one projection manager tick.",
        "This keeps one player looking at several portals from monopolizing the tick while still allowing controlled multi-portal views.",
        "Set this at or above the maximum number of intentionally visible portals in one room."
    })
    public int maxPortalsPerObserverTick = 4;

    @ConfigDescription({
        "Ticks to keep an existing observer/portal projector alive after a cheap interest gate temporarily fails.",
        "Small grace values prevent flicker when a player nudges their view while still allowing side-on portals to shut off quickly."
    })
    public int interestGraceTicks = 5;

    @ConfigDescription({
        "Number of first projection passes that resend all currently projected fake blocks, even if their data did not change.",
        "This protects players who join while already viewing a portal from chunk-load packets overwriting the first fake-block projection.",
        "0 disables startup resends; 3-5 is usually enough for login and teleport chunk-load races."
    })
    public int initialResendPasses = 4;
}
