package art.arcane.wormholes.network;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PeerConnection {
    public enum State {
        HANDSHAKING,
        READY,
        CLOSED
    }

    public interface Listener {
        boolean approvePeer(PeerConnection connection, String peerName, String mcVersion, String pluginVersion, byte[] publicKey);

        void onReady(PeerConnection connection);

        void onMessage(PeerConnection connection, WireMessage message);

        void onClosed(PeerConnection connection, String reason);
    }

    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int WRITE_QUEUE_CAPACITY = 1024;
    private static final byte[] CLOSE_SENTINEL = new byte[0];

    private final Socket socket;
    private final boolean dialer;
    private final LocalIdentity identity;
    private final byte[] expectedPeerPublicKey;
    private final Listener listener;
    private final LinkedBlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>(WRITE_QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<State> state = new AtomicReference<>(State.HANDSHAKING);
    private final AtomicLong lastInboundMillis = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong rttMillis = new AtomicLong(-1L);

    private volatile String peerName;
    private volatile String expectedPeerName;
    private volatile String peerAdvertiseHost;
    private volatile byte[] peerPublicKey;
    private volatile int peerWormholePort = -1;
    private volatile int peerGamePort = -1;
    private Thread readerThread;
    private Thread writerThread;

    public PeerConnection(Socket socket, boolean dialer, LocalIdentity identity, String expectedPeerName, byte[] expectedPeerPublicKey, Listener listener) {
        this.socket = socket;
        this.dialer = dialer;
        this.identity = identity;
        this.expectedPeerName = expectedPeerName;
        this.expectedPeerPublicKey = expectedPeerPublicKey == null ? null : expectedPeerPublicKey.clone();
        this.peerName = expectedPeerName;
        this.listener = listener;
    }

    public void start() {
        Thread writer = new Thread(this::runWriter, "Wormholes-Net-Writer-" + describeRemote());
        writer.setDaemon(true);
        writerThread = writer;
        writer.start();

        Thread reader = new Thread(this::runReader, "Wormholes-Net-Reader-" + describeRemote());
        reader.setDaemon(true);
        readerThread = reader;
        reader.start();
    }

    public boolean send(WireMessage message) {
        if (state.get() == State.CLOSED) {
            return false;
        }
        byte[] frame;
        try {
            frame = WireCodec.encodeFrame(message);
        } catch (IOException e) {
            return false;
        }
        if (!writeQueue.offer(frame)) {
            close("write queue overflow");
            return false;
        }
        return true;
    }

    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state.set(State.CLOSED);
        writeQueue.clear();
        writeQueue.offer(CLOSE_SENTINEL);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        listener.onClosed(this, reason);
    }

    public State getState() {
        return state.get();
    }

    public boolean isDialer() {
        return dialer;
    }

    public String getPeerName() {
        return peerName;
    }

    public String getPeerAdvertiseHost() {
        String advertised = peerAdvertiseHost;
        if (advertised != null && !advertised.isBlank()) {
            return advertised;
        }
        return socket.getInetAddress() == null ? null : socket.getInetAddress().getHostAddress();
    }

    public int getPeerWormholePort() {
        return peerWormholePort;
    }

    public int getPeerGamePort() {
        return peerGamePort;
    }

    public byte[] getPeerPublicKey() {
        return peerPublicKey == null ? null : peerPublicKey.clone();
    }

    public long getLastInboundMillis() {
        return lastInboundMillis.get();
    }

    public long getRttMillis() {
        return rttMillis.get();
    }

    public String describeRemote() {
        return socket.getInetAddress() == null ? "unknown" : socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    private void runReader() {
        String failure = null;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));

            if (dialer) {
                handshakeAsDialer(in);
            } else {
                handshakeAsAcceptor(in);
            }

            socket.setSoTimeout(READ_TIMEOUT_MS);
            state.set(State.READY);
            lastInboundMillis.set(System.currentTimeMillis());
            listener.onReady(this);

            while (!closed.get()) {
                WireMessage message = WireCodec.readFrame(in);
                lastInboundMillis.set(System.currentTimeMillis());
                if (message instanceof WireMessage.Ping ping) {
                    send(new WireMessage.Pong(ping.sentAtMillis()));
                    continue;
                }
                if (message instanceof WireMessage.Pong pong) {
                    rttMillis.set(Math.max(0L, System.currentTimeMillis() - pong.echoMillis()));
                    continue;
                }
                listener.onMessage(this, message);
            }
        } catch (IOException e) {
            failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } catch (HandshakeException e) {
            failure = e.getMessage();
        }
        close(failure == null ? "connection ended" : failure);
    }

    private void handshakeAsDialer(DataInputStream in) throws IOException, HandshakeException {
        byte[] dialerNonce = Handshake.newNonce();
        sendNow(new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, identity.mcVersion(), identity.pluginVersion(), identity.serverName(),
            identity.advertiseHost(), identity.wormholePort(), identity.gamePort(), dialerNonce, identity.publicKey()));

        WireMessage response = WireCodec.readFrame(in);
        if (!(response instanceof WireMessage.Challenge challenge)) {
            throw new HandshakeException("Expected CHALLENGE, got " + response.type());
        }
        if (expectedPeerName != null && !expectedPeerName.equals(challenge.serverName())) {
            throw new HandshakeException("Peer identified as '" + challenge.serverName() + "', expected '" + expectedPeerName + "'");
        }
        if (expectedPeerPublicKey != null && !Handshake.sameKey(expectedPeerPublicKey, challenge.publicKey())) {
            throw new HandshakeException("Peer '" + challenge.serverName() + "' used an unexpected public key");
        }
        if (!Handshake.verify(challenge.publicKey(), challenge.signature(), Handshake.ROLE_ACCEPTOR, challenge.serverName(), identity.serverName(), dialerNonce, challenge.nonce(), challenge.publicKey(), identity.publicKey())) {
            throw new HandshakeException("Peer failed authentication");
        }
        peerName = challenge.serverName();
        peerPublicKey = challenge.publicKey();
        if (expectedPeerPublicKey == null && !listener.approvePeer(this, challenge.serverName(), identity.mcVersion(), identity.pluginVersion(), challenge.publicKey())) {
            throw new HandshakeException("Peer '" + challenge.serverName() + "' rejected");
        }

        sendNow(new WireMessage.Auth(Handshake.sign(identity.privateKey(), Handshake.ROLE_DIALER, identity.serverName(), challenge.serverName(), dialerNonce, challenge.nonce(), identity.publicKey(), challenge.publicKey())));

        WireMessage ready = WireCodec.readFrame(in);
        if (!(ready instanceof WireMessage.Ready)) {
            throw new HandshakeException("Expected READY, got " + ready.type());
        }
    }

    private void handshakeAsAcceptor(DataInputStream in) throws IOException, HandshakeException {
        WireMessage first = WireCodec.readFrame(in);
        if (!(first instanceof WireMessage.Hello hello)) {
            throw new HandshakeException("Expected HELLO, got " + first.type());
        }
        if (hello.protocolVersion() != WireCodec.PROTOCOL_VERSION) {
            throw new HandshakeException("Protocol mismatch: peer " + hello.protocolVersion() + ", local " + WireCodec.PROTOCOL_VERSION);
        }
        if (!identity.mcVersion().equals(hello.mcVersion())) {
            throw new HandshakeException("MC version mismatch: peer " + hello.mcVersion() + ", local " + identity.mcVersion());
        }
        if (!identity.pluginVersion().equals(hello.pluginVersion())) {
            throw new HandshakeException("Wormholes version mismatch: peer " + hello.pluginVersion() + ", local " + identity.pluginVersion());
        }
        peerName = hello.serverName();
        peerAdvertiseHost = hello.advertiseHost();
        peerPublicKey = hello.publicKey();
        peerWormholePort = hello.wormholePort();
        peerGamePort = hello.gamePort();

        byte[] acceptorNonce = Handshake.newNonce();
        sendNow(new WireMessage.Challenge(identity.serverName(), acceptorNonce, identity.publicKey(),
            Handshake.sign(identity.privateKey(), Handshake.ROLE_ACCEPTOR, identity.serverName(), hello.serverName(), hello.nonce(), acceptorNonce, identity.publicKey(), hello.publicKey())));

        WireMessage second = WireCodec.readFrame(in);
        if (!(second instanceof WireMessage.Auth auth)) {
            throw new HandshakeException("Expected AUTH, got " + second.type());
        }
        if (!Handshake.verify(hello.publicKey(), auth.signature(), Handshake.ROLE_DIALER, hello.serverName(), identity.serverName(), hello.nonce(), acceptorNonce, hello.publicKey(), identity.publicKey())) {
            throw new HandshakeException("Peer failed authentication");
        }
        if (!listener.approvePeer(this, hello.serverName(), hello.mcVersion(), hello.pluginVersion(), hello.publicKey())) {
            throw new HandshakeException("Peer '" + hello.serverName() + "' rejected");
        }

        sendNow(new WireMessage.Ready());
    }

    private void sendNow(WireMessage message) throws IOException {
        byte[] frame = WireCodec.encodeFrame(message);
        if (!writeQueue.offer(frame)) {
            throw new IOException("write queue overflow during handshake");
        }
    }

    private void runWriter() {
        try {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
            while (!closed.get()) {
                byte[] frame = writeQueue.poll(500, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                if (frame == CLOSE_SENTINEL) {
                    return;
                }
                out.write(frame);
                if (writeQueue.isEmpty()) {
                    out.flush();
                }
            }
        } catch (IOException e) {
            close("write failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class HandshakeException extends Exception {
        private HandshakeException(String message) {
            super(message);
        }
    }
}
