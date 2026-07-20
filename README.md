# Wormholes
Now, you can see the other side.

## Survival Dimensional Doors

Dimensional Doors are real vanilla doors: they activate only while physically
open and only when an eligible traveler crosses the visible portal surface.
After a successful crossing, the source door closes with its normal material
sound and the traveler hears the player-teleport cue. The open doorway uses an
opaque, fullbright Crying Obsidian backing with a client-animated Nether Portal
veil. It is recessed one pixel inside the closed-door edge, keeps one pixel of
clearance beside the hinge, and extends flush to the opposite latch edge.
Every crossing preserves the entered closed-door face at the destination:
front-to-front and back-to-back, facing outward while preserving relative look
direction and safely adjusting upward or downward for uneven terrain.
There are no access lists, portal menus, or per-door configuration; normal door
interaction and any server protection plugin remain the authority.

- **Entangled Door Pair:** right-click either the air or a block with the crafted
  bundle to unpack automatically linked A/B Wormhole Doors. Either endpoint can
  be moved without losing the pairing. Ordinary mobs and empty vehicles that
  physically fit through a one-block-wide, two-block-high door can use it from
  either side.
- **Personal Dimension Door:** every traveler reaches their own persistent
  pocket in the shared void dimension.
- **Public Dimension Door:** the item itself owns one shared persistent pocket. Breaking
  and moving that specific door preserves its destination; crafting another
  creates a different pocket. Every traveler using that door enters the same space.

All three support cross-dimensional player travel on the same server, and Pair
doors load a far-away or unloaded destination chunk before transit. Pocket
spaces are separated by 8,192 blocks and use protected 32x32x32 shells with
buildable 30x30x30 interiors. A manually operable return door is centered at
floor level on one wall, with arrivals placed safely just inside it. Lethal
damage anywhere in the shared pocket world leaves the player at one heart and
ejects them through their saved return route, falling back to a safe loaded
non-pocket world spawn when that route is unavailable. The dimension is
fullbright without potion effects, and tick speed remains vanilla.

### Recipes

Each recipe uses the existing exact **Wormhole Rune** item (`R`).

```text
Entangled pair       Personal door       Public dimension door
E D E                 _ R _               R D R
O R O                 C D E               _ E _
_ D _                                     _ L _
```

`E` is Ender Eye (pair), Ender Chest (other recipes); `D` is any vanilla door
for every recipe; `O` is Obsidian, `C` is Recovery Compass, and `L` is
Lodestone. Pair endpoints default to Oak, Personal Doors default to Dark Oak,
and Public Doors default to Pale Oak.

To change the appearance, combine one Dimensional Door with one ordinary
wooden, bamboo, crimson, or warped door in any crafting-table arrangement. The
result keeps the exact same pair, personal traveler mapping, or public pocket
identity while adopting the ordinary door's material. Iron and copper doors
cannot be selected as skins because every Dimensional Door must remain
hand-openable. Existing placed Iron Dimension Doors are converted to the new
Pale Oak Public Door default without changing their saved destination.

Identity-bearing kits and placed doors are consumed normally in Creative mode.
Breaking a Pair, Personal, or Public door always drops that exact door item,
including in Creative, so its link or pocket destination survives being moved.

Administrators can issue test items with
`/wormholes door type=<pair|personal|public>`. Installing the feature or changing
its bundled dimension data requires a full server restart so Paper can rebuild
the world registries.

Set `[main] dimensional-doors-enabled = false` in `wormholes.toml` to disable
the complete feature live. New entries stop immediately; active travelers and
pocket occupants may finish through their return route before recipes,
protection, and portal displays shut down. Existing blocks then behave as
ordinary doors, while saved door and pocket identities remain available if the
setting is re-enabled.

## Cross-server handoff

Imported portal codes retain both public and LAN route candidates. Direct player
handoffs use a private fallback when the player connected from loopback or the
LAN, avoiding routers that cannot hairpin their own public address; internet
players continue to receive the destination's public endpoint. Game-port
sideband traffic tries the same fallbacks only when a socket connection was
never established.

The source does not dispatch the client until the destination grants a
rate-limited admission lease for that exact transfer. The destination checks
that the live portal can receive, the selected direct or proxy method is
supported, the profile passes the built-in ban and whitelist gates, and online
players plus pending arrivals remain below the player limit. A denial, timeout,
or active cooldown returns the traveler to the source-facing side of the portal
instead of letting them remain inside the plane or disconnecting them. An
accepted arrival remains reserved until its destination-portal teleport succeeds,
with transient placement failures retried before falling back to the destination
spawn. Both servers must run the same wire protocol version.

Native Paper transfer admission should use `accepts-transfers=true` in every
destination server's `server.properties` followed by a restart. Wormholes keeps
its `network.autoAcceptTransfers` compatibility gate enabled by default for
hosts where that property cannot be changed.

Run `/wormhole debug` on both servers before reproducing a failed handoff. The
command is a silent toggle in game and writes one-second projection, network,
queue, peer, and handoff telemetry to each server console. Direct transfers log
the client's address, LAN classification, selected `host:port`, and every
configured endpoint; the destination logs when its transfer gate receives and
rewrites the incoming handshake. Run the same command again to stop the stream.

## Runtime

Java 25 server launch commands should include
`--enable-native-access=ALL-UNNAMED` so zstd-jni can continue loading its native
compression library without restricted-access warnings.
