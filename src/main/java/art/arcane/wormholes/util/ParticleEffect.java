package art.arcane.wormholes.util;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Settings;

import java.util.Collection;

public enum ParticleEffect {
    BLOCK_DUST(Particle.BLOCK),
    ENCHANTMENT_TABLE(Particle.ENCHANT),
    EXPLOSION_LARGE(Particle.EXPLOSION),
    FLAME(Particle.FLAME),
    ITEM_CRACK(Particle.ITEM),
    PORTAL(Particle.PORTAL),
    REDSTONE(Particle.DUST),
    SMOKE(Particle.SMOKE),
    TOWN_AURA(Particle.MYCELIUM),
    WATER_DROP(Particle.SPLASH),
    WATER_WAKE(Particle.SPLASH);
    private final Particle bukkitParticle;

    ParticleEffect(Particle bukkitParticle) {
        this.bukkitParticle = bukkitParticle;
    }

    public Particle bukkit() {
        return bukkitParticle;
    }

    public int getId() {
        return ordinal();
    }

    public void display(Vector direction, int amount, Location location, double range) {
        if (!Settings.ENABLE_PARTICLES) {
            return;
        }
        if (location == null || location.getWorld() == null) {
            return;
        }
        spawn(location, direction, amount, null);
    }

    public void display(Vector direction, float speed, Location location, int amount) {
        if (!Settings.ENABLE_PARTICLES) {
            return;
        }
        if (location == null || location.getWorld() == null) {
            return;
        }
        spawn(location, direction, amount, null);
    }

    public void display(float speed, int amount, Location location, double range) {
        if (!Settings.ENABLE_PARTICLES) {
            return;
        }
        if (location == null || location.getWorld() == null) {
            return;
        }
        location.getWorld().spawnParticle(bukkitParticle, location, amount, 0.0D, 0.0D, 0.0D, speed);
    }

    public void display(float speed, int amount, Location location, Player viewer) {
        if (!Settings.ENABLE_PARTICLES) {
            return;
        }
        if (location == null || location.getWorld() == null) {
            return;
        }
        if (viewer == null) {
            location.getWorld().spawnParticle(bukkitParticle, location, amount, 0.0D, 0.0D, 0.0D, speed);
            return;
        }
        viewer.spawnParticle(bukkitParticle, location, amount, 0.0D, 0.0D, 0.0D, speed);
    }

    public void display(BlockData blockData, Vector direction, int amount, Location location, double range) {
        if (!Settings.ENABLE_PARTICLES) {
            return;
        }
        if (location == null || location.getWorld() == null) {
            return;
        }
        spawn(location, direction, amount, blockData == null ? null : blockData.toBlockData());
    }

    public void display(ItemData itemData, Vector direction, int amount, Location location, double range) {
        if (!Settings.ENABLE_PARTICLES) {
            return;
        }
        if (location == null || location.getWorld() == null) {
            return;
        }
        Object data = itemData == null ? null : itemData.toItemStack();
        spawn(location, direction, amount, data);
    }

    public void display(OrdinaryColor color, Location location, double range) {
        if (!Settings.ENABLE_PARTICLES) {
            return;
        }
        if (location == null || location.getWorld() == null || color == null) {
            return;
        }
        Particle.DustOptions options = new Particle.DustOptions(Color.fromRGB(color.getRed(), color.getGreen(), color.getBlue()), 1.0F);
        location.getWorld().spawnParticle(Particle.DUST, location, 1, options);
    }

    public void display(OrdinaryColor color, Location location, Collection<? extends Player> players) {
        if (!Settings.ENABLE_PARTICLES) {
            return;
        }
        if (location == null || location.getWorld() == null || players == null || color == null) {
            return;
        }
        Particle.DustOptions options = new Particle.DustOptions(Color.fromRGB(color.getRed(), color.getGreen(), color.getBlue()), 1.0F);
        for (Player p : players) {
            p.spawnParticle(Particle.DUST, location, 1, options);
        }
    }

    private void spawn(Location location, Vector direction, int amount, Object particleData) {
        double dx = direction == null ? 0.0D : direction.getX();
        double dy = direction == null ? 0.0D : direction.getY();
        double dz = direction == null ? 0.0D : direction.getZ();
        if (particleData == null) {
            location.getWorld().spawnParticle(bukkitParticle, location, amount, dx, dy, dz, 1.0D);
        } else {
            location.getWorld().spawnParticle(bukkitParticle, location, amount, dx, dy, dz, 1.0D, particleData);
        }
    }

    public static class BlockData {
        private final Material material;
        private final byte data;

        public BlockData(Material material, byte data) {
            this.material = material == null ? Material.STONE : material;
            this.data = data;
        }

        public Material getMaterial() {
            return material;
        }

        public byte getData() {
            return data;
        }

        public org.bukkit.block.data.BlockData toBlockData() {
            return material.createBlockData();
        }
    }

    public static class ItemData {
        private final Material material;
        private final byte data;

        public ItemData(Material material) {
            this(material, (byte) 0);
        }

        public ItemData(Material material, byte data) {
            this.material = material == null ? Material.STONE : material;
            this.data = data;
        }

        public Material getMaterial() {
            return material;
        }

        public byte getData() {
            return data;
        }

        public ItemStack toItemStack() {
            return new ItemStack(material, 1);
        }
    }

    public static class OrdinaryColor {
        private final int red;
        private final int green;
        private final int blue;

        public OrdinaryColor(int red, int green, int blue) {
            this.red = clamp(red);
            this.green = clamp(green);
            this.blue = clamp(blue);
        }

        public OrdinaryColor(Color color) {
            this(color.getRed(), color.getGreen(), color.getBlue());
        }

        public int getRed() {
            return red;
        }

        public int getGreen() {
            return green;
        }

        public int getBlue() {
            return blue;
        }

        private static int clamp(int v) {
            return Math.max(0, Math.min(255, v));
        }
    }

    public static class NoteColor {
        private final int note;

        public NoteColor(int note) {
            this.note = Math.max(0, Math.min(24, note));
        }

        public int getNote() {
            return note;
        }
    }
}
