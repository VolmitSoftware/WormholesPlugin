package art.arcane.wormholes.util;

import net.md_5.bungee.api.ChatColor;

public final class PhantomSpinner {
    private static final String[] FRAMES = new String[] {
        "\u2581", "\u2582", "\u2583", "\u2584", "\u2585", "\u2586", "\u2587", "\u2588",
        "\u2587", "\u2586", "\u2585", "\u2584", "\u2583", "\u2582"
    };

    private final ChatColor lightColor;
    private final ChatColor midColor;
    private final ChatColor darkColor;
    private long lastFrame;
    private int frame;

    public PhantomSpinner(ChatColor light, ChatColor mid, ChatColor dark) {
        this.lightColor = light;
        this.midColor = mid;
        this.darkColor = dark;
        this.lastFrame = 0L;
        this.frame = 0;
    }

    @Override
    public String toString() {
        long now = System.currentTimeMillis();
        if (now - lastFrame >= 60L) {
            frame = (frame + 1) % FRAMES.length;
            lastFrame = now;
        }
        ChatColor color = pickColor(frame);
        return color + FRAMES[frame];
    }

    private ChatColor pickColor(int idx) {
        int section = idx % 12;
        if (section < 4) {
            return lightColor;
        }
        if (section < 8) {
            return midColor;
        }
        return darkColor;
    }
}
