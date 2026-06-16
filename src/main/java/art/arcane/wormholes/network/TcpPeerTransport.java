package art.arcane.wormholes.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

public final class TcpPeerTransport implements PeerTransport {
    private final ServerSocket serverSocket;
    private final InetSocketAddress boundAddress;

    private TcpPeerTransport(ServerSocket serverSocket, InetSocketAddress boundAddress) {
        this.serverSocket = serverSocket;
        this.boundAddress = boundAddress;
    }

    public static TcpPeerTransport bind(String host, int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        InetSocketAddress address = new InetSocketAddress(host, port);
        socket.bind(address);
        return new TcpPeerTransport(socket, address);
    }

    public static TcpPeerTransport outboundOnly() {
        return new TcpPeerTransport(null, null);
    }

    @Override
    public String name() {
        return "tcp";
    }

    @Override
    public boolean isListening() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    @Override
    public boolean isLoopback() {
        if (boundAddress == null) {
            return false;
        }
        InetAddress address = boundAddress.getAddress();
        return address != null && address.isLoopbackAddress();
    }

    @Override
    public SocketAddress localAddress() {
        return boundAddress;
    }

    @Override
    public PeerChannel accept() throws IOException {
        if (serverSocket == null) {
            throw new IOException("tcp transport is outbound-only");
        }
        Socket client = serverSocket.accept();
        return new TcpPeerChannel(client);
    }

    @Override
    public PeerChannel connect(SocketAddress remote, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(remote, timeoutMillis);
        return new TcpPeerChannel(socket);
    }

    @Override
    public void close() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    public static PeerChannel adopt(Socket socket) {
        return new TcpPeerChannel(socket);
    }

    public static PeerChannel dialDirect(String host, int port, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        return new TcpPeerChannel(socket);
    }

    private static final class TcpPeerChannel implements PeerChannel {
        private final Socket socket;

        private TcpPeerChannel(Socket socket) {
            this.socket = socket;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void setReadTimeout(int millis) throws IOException {
            socket.setSoTimeout(millis);
        }

        @Override
        public void setTcpNoDelay(boolean noDelay) throws IOException {
            socket.setTcpNoDelay(noDelay);
        }

        @Override
        public String describeRemote() {
            InetAddress address = socket.getInetAddress();
            if (address == null) {
                return "unknown";
            }
            return address.getHostAddress() + ":" + socket.getPort();
        }

        @Override
        public SocketAddress remoteAddress() {
            return socket.getRemoteSocketAddress();
        }

        @Override
        public boolean isLoopback() {
            InetAddress address = socket.getInetAddress();
            return address != null && address.isLoopbackAddress();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
