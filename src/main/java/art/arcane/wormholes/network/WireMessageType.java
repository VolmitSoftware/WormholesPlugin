package art.arcane.wormholes.network;

public enum WireMessageType {
    HELLO(0),
    CHALLENGE(1),
    AUTH(2),
    READY(3),
    PING(4),
    PONG(5),
    PORTAL_DIRECTORY(10),
    PORTAL_UPSERT(11),
    PORTAL_REMOVE(12),
    VIEW_SUBSCRIBE(20),
    VIEW_UNSUBSCRIBE(21),
    VIEW_SNAPSHOT(22),
    VIEW_DELTA(23),
    VIEW_ENTITIES(24),
    VIEW_ENTITY_ANIMATION(25),
    HANDOFF_REQUEST(30),
    HANDOFF_ACK(31),
    HANDOFF_DENY(32),
    HANDOFF_CANCEL(33),
    ENTITY_TRANSFER(34),
    ENTITY_TRANSFER_ACK(35);

    private static final WireMessageType[] BY_ID = new WireMessageType[64];

    static {
        for (WireMessageType type : values()) {
            BY_ID[type.id] = type;
        }
    }

    private final int id;

    WireMessageType(int id) {
        this.id = id;
    }

    public byte id() {
        return (byte) id;
    }

    public static WireMessageType byId(byte id) {
        int index = id & 0xFF;
        if (index >= BY_ID.length) {
            return null;
        }
        return BY_ID[index];
    }
}
