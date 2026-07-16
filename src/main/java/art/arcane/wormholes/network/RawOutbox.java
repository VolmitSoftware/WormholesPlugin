package art.arcane.wormholes.network;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

final class RawOutbox {
    private final int nonControlCapacity;
    private final int totalCapacity;
    private final ArrayDeque<OutboundFrame> control = new ArrayDeque<>();
    private final ArrayDeque<OutboundFrame> data = new ArrayDeque<>();

    RawOutbox(int nonControlCapacity, int controlReserve) {
        if (nonControlCapacity <= 0) {
            throw new IllegalArgumentException("nonControlCapacity must be positive");
        }
        if (controlReserve <= 0) {
            throw new IllegalArgumentException("controlReserve must be positive");
        }
        this.nonControlCapacity = nonControlCapacity;
        this.totalCapacity = nonControlCapacity + controlReserve;
    }

    synchronized boolean offer(OutboundFrame frame) {
        boolean controlFrame = isControl(frame);
        if (size() >= totalCapacity || (!controlFrame && data.size() >= nonControlCapacity)) {
            return false;
        }
        if (controlFrame) {
            control.addLast(frame);
        } else {
            data.addLast(frame);
        }
        notifyAll();
        return true;
    }

    synchronized OutboundFrame poll(long timeout, TimeUnit unit) throws InterruptedException {
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        while (control.isEmpty() && data.isEmpty()) {
            if (remainingNanos <= 0L) {
                return null;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            remainingNanos = deadline - System.nanoTime();
        }
        return control.isEmpty() ? data.removeFirst() : control.removeFirst();
    }

    synchronized void clear() {
        control.clear();
        data.clear();
    }

    synchronized boolean isEmpty() {
        return control.isEmpty() && data.isEmpty();
    }

    static boolean isControl(OutboundFrame frame) {
        if (frame == OutboundFrame.CLOSE) {
            return true;
        }
        WireMessage message = frame.message();
        WireMessageType type = message instanceof WireMessage.Routed routed ? routed.innerType() : message.type();
        return switch (type) {
            case VIEW_ENTITIES, VIEW_ENTITY_ANIMATION, CHUNK_HASH_PROBE, CHUNK_BULK, CHUNK_DIFF,
                 VIEW_BULK_COMPLETE, SIDEBAND_FRAGMENT -> false;
            default -> true;
        };
    }

    private int size() {
        return control.size() + data.size();
    }
}
