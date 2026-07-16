package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawOutboxTest {
    @Test
    void controlFramesOvertakeDataWithoutReversingEitherQueue() throws InterruptedException {
        RawOutbox outbox = new RawOutbox(8, 2);
        OutboundFrame firstData = new OutboundFrame(new WireMessage.ViewEntityAnimation(
            UUID.randomUUID(), UUID.randomUUID(), false, 0, 0.0F));
        OutboundFrame firstControl = new OutboundFrame(new WireMessage.HandoffAck(UUID.randomUUID()));
        OutboundFrame secondData = new OutboundFrame(new WireMessage.ViewBulkComplete(UUID.randomUUID()));
        OutboundFrame secondControl = new OutboundFrame(new WireMessage.HandoffAck(UUID.randomUUID()));

        assertTrue(outbox.offer(firstData));
        assertTrue(outbox.offer(firstControl));
        assertTrue(outbox.offer(secondData));
        assertTrue(outbox.offer(secondControl));
        assertEquals(4, outbox.size());

        assertSame(firstControl, outbox.poll(1L, TimeUnit.SECONDS));
        assertEquals(3, outbox.size());
        assertSame(secondControl, outbox.poll(1L, TimeUnit.SECONDS));
        assertSame(firstData, outbox.poll(1L, TimeUnit.SECONDS));
        assertSame(secondData, outbox.poll(1L, TimeUnit.SECONDS));
        assertEquals(0, outbox.size());
    }

    @Test
    void controlReserveRemainsAvailableWhenDataCapacityIsFull() throws InterruptedException {
        RawOutbox outbox = new RawOutbox(2, 1);
        OutboundFrame firstData = new OutboundFrame(new WireMessage.ViewBulkComplete(UUID.randomUUID()));
        OutboundFrame secondData = new OutboundFrame(new WireMessage.ViewBulkComplete(UUID.randomUUID()));
        OutboundFrame control = new OutboundFrame(new WireMessage.HandoffAck(UUID.randomUUID()));

        assertTrue(outbox.offer(firstData));
        assertTrue(outbox.offer(secondData));
        assertFalse(outbox.offer(new OutboundFrame(new WireMessage.ViewBulkComplete(UUID.randomUUID()))));
        assertTrue(outbox.offer(control));
        assertFalse(outbox.offer(new OutboundFrame(new WireMessage.HandoffAck(UUID.randomUUID()))));
        assertSame(control, outbox.poll(1L, TimeUnit.SECONDS));
    }
}
