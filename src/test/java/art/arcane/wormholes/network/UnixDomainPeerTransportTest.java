package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UnixDomainPeerTransportTest {
    private static boolean udsSupported() {
        try {
            ServerSocketChannel probe = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            probe.close();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Test
    void roundTripsBytesOverUnixDomainSocket(@TempDir Path tempDir) throws Exception {
        assumeTrue(udsSupported(), "UNIX domain sockets unsupported on this JVM");
        Path socketPath = tempDir.resolve("peer.sock");
        UnixDomainPeerTransport server = UnixDomainPeerTransport.bind(socketPath);
        AtomicReference<byte[]> received = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread serverThread = new Thread(() -> {
            try {
                ready.countDown();
                PeerTransport.PeerChannel channel = server.accept();
                try {
                    InputStream input = channel.getInputStream();
                    byte[] buffer = new byte[5];
                    int read = 0;
                    while (read < buffer.length) {
                        int next = input.read(buffer, read, buffer.length - read);
                        if (next < 0) {
                            break;
                        }
                        read += next;
                    }
                    received.set(buffer);
                } finally {
                    channel.close();
                }
            } catch (IOException ignored) {
            } finally {
                done.countDown();
            }
        }, "uds-server");
        serverThread.setDaemon(true);
        try {
            serverThread.start();
            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            PeerTransport.PeerChannel client = UnixDomainPeerTransport.dial(socketPath);
            try {
                OutputStream output = client.getOutputStream();
                byte[] payload = new byte[]{1, 2, 3, 4, 5};
                output.write(payload);
                output.flush();
            } finally {
                client.close();
            }
            assertTrue(done.await(5L, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, received.get());
        } finally {
            server.close();
            serverThread.join(1_000L);
        }
    }

    @Test
    void socketFileHasZeroSixHundredPermissions(@TempDir Path tempDir) throws Exception {
        assumeTrue(udsSupported(), "UNIX domain sockets unsupported on this JVM");
        Path socketPath = tempDir.resolve("permcheck.sock");
        UnixDomainPeerTransport transport = UnixDomainPeerTransport.bind(socketPath);
        try {
            Set<PosixFilePermission> permissions;
            try {
                permissions = Files.getPosixFilePermissions(socketPath);
            } catch (UnsupportedOperationException ignored) {
                return;
            }
            assertEquals(PosixFilePermissions.fromString("rw-------"), permissions);
        } finally {
            transport.close();
        }
    }

    @Test
    void closeDeletesSocketFile(@TempDir Path tempDir) throws Exception {
        assumeTrue(udsSupported(), "UNIX domain sockets unsupported on this JVM");
        Path socketPath = tempDir.resolve("cleanup.sock");
        UnixDomainPeerTransport transport = UnixDomainPeerTransport.bind(socketPath);
        assertTrue(Files.exists(socketPath));
        transport.close();
        assertTrue(!Files.exists(socketPath), "socket file should be removed on close");
    }

    @Test
    void bindReplacesExistingSocketFile(@TempDir Path tempDir) throws Exception {
        assumeTrue(udsSupported(), "UNIX domain sockets unsupported on this JVM");
        Path socketPath = tempDir.resolve("replace.sock");
        Files.write(socketPath, new byte[]{1, 2, 3});
        UnixDomainPeerTransport transport = UnixDomainPeerTransport.bind(socketPath);
        try {
            assertTrue(Files.exists(socketPath));
        } finally {
            transport.close();
        }
    }

    @Test
    void defaultServerSocketPathSanitizesPeerName(@TempDir Path tempDir) {
        Path expected = tempDir.resolve("uds").resolve("peer-some_peer_-name.sock");
        Path computed = UnixDomainPeerTransport.defaultServerSocketPath(tempDir, "some/peer*-name");
        assertEquals(expected, computed);
    }
}
