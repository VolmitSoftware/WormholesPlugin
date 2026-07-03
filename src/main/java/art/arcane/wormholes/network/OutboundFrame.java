package art.arcane.wormholes.network;

import java.io.IOException;

public final class OutboundFrame {
    public static final OutboundFrame CLOSE = new OutboundFrame(null);

    private final WireMessage message;
    private byte[] payload;
    private byte[] plainFrame;
    private byte[] dictlessFrame;
    private byte[] dictFrame;
    private int dictFrameVersion;
    private boolean sampled;

    public OutboundFrame(WireMessage message) {
        this.message = message;
    }

    public WireMessage message() {
        return message;
    }

    public synchronized byte[] payload() throws IOException {
        if (payload == null) {
            payload = WireCodec.encodePayload(message);
        }
        return payload;
    }

    public synchronized byte[] plainFrame() throws IOException {
        if (plainFrame == null) {
            plainFrame = WireCodec.buildPlainFrame(message.type(), payload());
        }
        return plainFrame;
    }

    public synchronized byte[] encodedFrame(WireCompression compression, int negotiatedDictVersion, WireCodec.PayloadSampler sampler) throws IOException {
        if (compression == null) {
            return plainFrame();
        }
        boolean dict = negotiatedDictVersion > 0;
        if (dict && dictFrame != null && dictFrameVersion == negotiatedDictVersion) {
            return dictFrame;
        }
        if (!dict && dictlessFrame != null) {
            return dictlessFrame;
        }
        byte[] framePayload = payload();
        if (!sampled && sampler != null) {
            sampler.sample(message.type(), framePayload);
            sampled = true;
        }
        byte[] frame = compression.encodeFramedFrame(message.type().id(), framePayload, negotiatedDictVersion);
        if (frame.length > WireCodec.MAX_FRAME_BYTES) {
            throw new IOException("Frame too large: " + frame.length + " bytes (" + message.type() + ")");
        }
        if (dict) {
            dictFrame = frame;
            dictFrameVersion = negotiatedDictVersion;
        } else {
            dictlessFrame = frame;
        }
        return frame;
    }
}
