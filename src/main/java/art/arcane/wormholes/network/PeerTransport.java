package art.arcane.wormholes.network;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;

public interface PeerTransport extends Closeable {
    String name();

    boolean isListening();

    boolean isLoopback();

    SocketAddress localAddress();

    PeerChannel accept() throws IOException;

    PeerChannel connect(SocketAddress remote, int timeoutMillis) throws IOException;

    @Override
    void close() throws IOException;

    interface PeerChannel extends Closeable {
        InputStream getInputStream() throws IOException;

        OutputStream getOutputStream() throws IOException;

        void setReadTimeout(int millis) throws IOException;

        void setTcpNoDelay(boolean noDelay) throws IOException;

        String describeRemote();

        SocketAddress remoteAddress();

        boolean isLoopback();

        @Override
        void close() throws IOException;
    }
}
