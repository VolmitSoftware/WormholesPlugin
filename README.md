# Wormholes
Now, you can see the other side.

## Survival Dimensional Doors

Dimensional Doors are real vanilla doors: they activate only while physically
open and only when a player crosses the threshold. After a successful crossing,
the source door closes with its normal material sound and the traveler hears
the player-teleport cue. The opaque portal plane sits on the door's closed
block edge and is inset slightly inside the frame so it never clips through the
swung-open door. Traversal preserves the crossed face and relative look direction.
There are no access lists, portal menus, or per-door configuration; normal door
interaction and any server protection plugin remain the authority.

- **Entangled Door Pair:** unpack the crafted bundle into automatically linked
  A/B Wormhole Doors. Either endpoint can be moved without losing the pairing.
- **Personal Dimension Door:** every traveler reaches their own persistent
  pocket in the shared void dimension. Lethal damage ejects the traveler
  through their saved return route at one heart instead of killing them.
- **Public Dimension Door:** the item itself owns one shared persistent pocket. Breaking
  and moving that specific door preserves its destination; crafting another
  creates a different pocket. Every traveler using that door enters the same space.

All three support travel between loaded worlds on the same server. Pocket
spaces are separated by 8,192 blocks, include a protected starter floor and
manually operable exit door, and use a fullbright dimension type without potion
effects. Tick speed remains vanilla.

### Recipes

Each recipe uses the existing exact **Wormhole Rune** item (`R`).

```text
Entangled pair       Personal door       Public dimension door
E D E                 _ R _               R D R
O R O                 C D E               _ E _
_ D _                                     _ L _
```

`E` is Ender Eye (pair), Ender Chest (other recipes); `D` is Oak Door for
the pair/personal recipe and Crimson Door for the public recipe; `O` is
Obsidian, `C` is Recovery Compass, and `L` is Lodestone. Pair endpoints default
to Oak, Personal Doors default to Warped, and Public Doors default to Crimson.

To change the appearance, combine one Dimensional Door with one ordinary
wooden, bamboo, crimson, or warped door in any crafting-table arrangement. The
result keeps the exact same pair, personal traveler mapping, or public pocket
identity while adopting the ordinary door's material. Iron and copper doors
cannot be selected as skins because every Dimensional Door must remain
hand-openable. Existing placed Iron Dimension Doors are converted to the new
Crimson Public Door default without changing their saved destination.

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
