package art.arcane.wormholes.door;

/** Pure destination key resolved before any Bukkit world or chunk work. */
public sealed interface DoorDestination permits PairedDoorDestination, PocketDoorDestination, ReturnDoorDestination {
}
