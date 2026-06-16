package art.arcane.wormholes.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class UnixDomainPeerTransport implements PeerTransport {
    private static final Set<PosixFilePermission> SOCKET_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    private final ServerSocketChannel serverChannel;
    private final UnixDomainSocketAddress socketAddress;
    private final Path socketPath;

    private UnixDomainPeerTransport(ServerSocketChannel serverChannel, UnixDomainSocketAddress socketAddress, Path socketPath) {
        this.serverChannel = serverChannel;
        this.socketAddress = socketAddress;
        this.socketPath = socketPath;
    }

    public static UnixDomainPeerTransport bind(Path socketPath) throws IOException {
        Path parent = socketPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.deleteIfExists(socketPath);
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.bind(address);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        try {
            Files.setPosixFilePermissions(socketPath, SOCKET_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
        }
        return new UnixDomainPeerTransport(channel, address, socketPath);
    }

    public static PeerChannel dial(Path socketPath) throws IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(address);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        return new UnixDomainPeerChannel(channel, address);
    }

    public static Path defaultServerSocketPath(Path dataDirectory, String localPeerId) {
        return dataDirectory.resolve("uds").resolve("peer-" + sanitize(localPeerId) + ".sock");
    }

    private static String sanitize(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean safe = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.';
            builder.append(safe ? ch : '_');
        }
        return builder.toString();
    }

    public Path socketPath() {
        return socketPath;
    }

    @Override
    public String name() {
        return "unix";
    }

    @Override
    public boolean isListening() {
        return serverChannel != null && serverChannel.isOpen();
    }

    @Override
    public boolean isLoopback() {
        return true;
    }

    @Override
    public SocketAddress localAddress() {
        return socketAddress;
    }

    @Override
    public PeerChannel accept() throws IOException {
        if (serverChannel == null) {
            throw new IOException("uds transport is outbound-only");
        }
        SocketChannel client = serverChannel.accept();
        return new UnixDomainPeerChannel(client, (UnixDomainSocketAddress) client.getRemoteAddress());
    }

    @Override
    public PeerChannel connect(SocketAddress remote, int timeoutMillis) throws IOException {
        if (!(remote instanceof UnixDomainSocketAddress unixRemote)) {
            throw new IOException("uds transport requires UnixDomainSocketAddress, got " + (remote == null ? "null" : remote.getClass().getName()));
        }
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.socket().setSoTimeout(Math.max(1, timeoutMillis));
            channel.connect(unixRemote);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        return new UnixDomainPeerChannel(channel, unixRemote);
    }

    @Override
    public void close() throws IOException {
        IOException firstError = null;
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                firstError = e;
            }
        }
        if (socketPath != null) {
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        if (firstError != null) {
            throw firstError;
        }
    }

    private static final class UnixDomainPeerChannel implements PeerChannel {
        private final SocketChannel channel;
        private final UnixDomainSocketAddress remoteAddress;
        private volatile int readTimeoutMillis;

        private UnixDomainPeerChannel(SocketChannel channel, UnixDomainSocketAddress remoteAddress) throws IOException {
            this.channel = channel;
            this.remoteAddress = remoteAddress;
            this.channel.configureBlocking(true);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new TimedInputStream(Channels.newInputStream(channel), () -> readTimeoutMillis);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return Channels.newOutputStream(channel);
        }

        @Override
        public void setReadTimeout(int millis) throws IOException {
            readTimeoutMillis = millis;
        }

        @Override
        public void setTcpNoDelay(boolean noDelay) throws IOException {
        }

        @Override
        public String describeRemote() {
            if (remoteAddress == null || remoteAddress.getPath() == null) {
                return "uds:?";
            }
            return "uds:" + remoteAddress.getPath();
        }

        @Override
        public SocketAddress remoteAddress() {
            return remoteAddress;
        }

        @Override
        public boolean isLoopback() {
            return true;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private static final class TimedInputStream extends InputStream {
        private final InputStream delegate;
        private final ReadTimeoutSupplier timeoutSupplier;

        private TimedInputStream(InputStream delegate, ReadTimeoutSupplier timeoutSupplier) {
            this.delegate = delegate;
            this.timeoutSupplier = timeoutSupplier;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    @FunctionalInterface
    private interface ReadTimeoutSupplier {
        int millis();
    }
}
