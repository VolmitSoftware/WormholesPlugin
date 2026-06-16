package art.arcane.wormholes.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public record PortalCode(
    String serverName,
    String advertiseHost,
    List<String> fallbackHosts,
    int wormholePort,
    int gamePort,
    String publicKey,
    UUID portalId,
    String portalName
) {
    public static final String PREFIX = "WHP5.";
    private static final int MAX_CODE_LENGTH = 2048;
    private static final int MAX_FALLBACK_HOSTS = 4;

    public String encode() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(192);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF(serverName);
            out.writeUTF(advertiseHost);
            out.writeByte(Math.min(fallbackHosts.size(), MAX_FALLBACK_HOSTS));
            for (int i = 0; i < Math.min(fallbackHosts.size(), MAX_FALLBACK_HOSTS); i++) {
                out.writeUTF(fallbackHosts.get(i));
            }
            out.writeShort(wormholePort);
            out.writeShort(gamePort);
            out.writeUTF(publicKey);
            out.writeLong(portalId.getMostSignificantBits());
            out.writeLong(portalId.getLeastSignificantBits());
            out.writeUTF(portalName);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode portal code", e);
        }
    }

    public static PortalCode decode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        if (!trimmed.startsWith(PREFIX) || trimmed.length() > MAX_CODE_LENGTH) {
            return null;
        }
        try {
            byte[] data = Base64.getUrlDecoder().decode(trimmed.substring(PREFIX.length()));
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            String serverName = in.readUTF();
            String advertiseHost = in.readUTF();
            int fallbackCount = in.readUnsignedByte();
            if (fallbackCount > MAX_FALLBACK_HOSTS) {
                return null;
            }
            List<String> fallbackHosts = new ArrayList<>(fallbackCount);
            for (int i = 0; i < fallbackCount; i++) {
                fallbackHosts.add(in.readUTF());
            }
            int wormholePort = in.readUnsignedShort();
            int gamePort = in.readUnsignedShort();
            String publicKey = in.readUTF();
            UUID portalId = new UUID(in.readLong(), in.readLong());
            String portalName = in.readUTF();
            if (serverName.isBlank() || advertiseHost.isBlank() || wormholePort <= 0 || Handshake.decodePublicKeyText(publicKey) == null) {
                return null;
            }
            return new PortalCode(serverName, advertiseHost, List.copyOf(fallbackHosts), wormholePort, gamePort, publicKey, portalId, portalName);
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }
}
