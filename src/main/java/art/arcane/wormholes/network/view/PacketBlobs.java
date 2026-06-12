package art.arcane.wormholes.network.view;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.buffer.UnpooledByteBufAllocationHelper;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class PacketBlobs {
    public static final byte[] EMPTY = new byte[0];

    private PacketBlobs() {
    }

    public static byte[] writeMetadata(List<EntityData<?>> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return EMPTY;
        }
        Object buffer = UnpooledByteBufAllocationHelper.buffer();
        try {
            PacketWrapper<?> wrapper = PacketWrapper.createUniversalPacketWrapper(buffer, PacketEvents.getAPI().getServerManager().getVersion());
            wrapper.writeEntityMetadata(metadata);
            return ByteBufHelper.copyBytes(buffer);
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    public static List<EntityData<?>> readMetadata(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return List.of();
        }
        Object buffer = UnpooledByteBufAllocationHelper.wrappedBuffer(blob);
        try {
            PacketWrapper<?> wrapper = PacketWrapper.createUniversalPacketWrapper(buffer, PacketEvents.getAPI().getServerManager().getVersion());
            return wrapper.readEntityMetadata();
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    public static byte[] captureMetadata(Entity entity) {
        try {
            return writeMetadata(SpigotConversionUtil.getEntityMetadata(entity));
        } catch (Throwable e) {
            return EMPTY;
        }
    }

    public static byte[] writeEquipment(List<Equipment> equipment) {
        if (equipment == null || equipment.isEmpty()) {
            return EMPTY;
        }
        Object buffer = UnpooledByteBufAllocationHelper.buffer();
        try {
            PacketWrapper<?> wrapper = PacketWrapper.createUniversalPacketWrapper(buffer, PacketEvents.getAPI().getServerManager().getVersion());
            wrapper.writeByte(equipment.size());
            for (Equipment piece : equipment) {
                wrapper.writeByte(piece.getSlot().ordinal());
                wrapper.writeItemStack(piece.getItem());
            }
            return ByteBufHelper.copyBytes(buffer);
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    public static List<Equipment> readEquipment(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return List.of();
        }
        Object buffer = UnpooledByteBufAllocationHelper.wrappedBuffer(blob);
        try {
            PacketWrapper<?> wrapper = PacketWrapper.createUniversalPacketWrapper(buffer, PacketEvents.getAPI().getServerManager().getVersion());
            int count = wrapper.readByte();
            List<Equipment> equipment = new ArrayList<>(count);
            EquipmentSlot[] slots = EquipmentSlot.values();
            for (int i = 0; i < count; i++) {
                int slotOrdinal = wrapper.readByte();
                com.github.retrooper.packetevents.protocol.item.ItemStack item = wrapper.readItemStack();
                if (slotOrdinal >= 0 && slotOrdinal < slots.length) {
                    equipment.add(new Equipment(slots[slotOrdinal], item));
                }
            }
            return equipment;
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    public static byte[] captureEquipment(Entity entity) {
        try {
            return writeEquipment(collectEquipment(entity));
        } catch (Throwable e) {
            return EMPTY;
        }
    }

    public static List<Equipment> collectEquipment(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return List.of();
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return List.of();
        }
        List<Equipment> collected = new ArrayList<>(8);
        addEquipment(collected, living, equipment, org.bukkit.inventory.EquipmentSlot.HAND, EquipmentSlot.MAIN_HAND);
        addEquipment(collected, living, equipment, org.bukkit.inventory.EquipmentSlot.OFF_HAND, EquipmentSlot.OFF_HAND);
        addEquipment(collected, living, equipment, org.bukkit.inventory.EquipmentSlot.FEET, EquipmentSlot.BOOTS);
        addEquipment(collected, living, equipment, org.bukkit.inventory.EquipmentSlot.LEGS, EquipmentSlot.LEGGINGS);
        addEquipment(collected, living, equipment, org.bukkit.inventory.EquipmentSlot.CHEST, EquipmentSlot.CHEST_PLATE);
        addEquipment(collected, living, equipment, org.bukkit.inventory.EquipmentSlot.HEAD, EquipmentSlot.HELMET);
        addEquipment(collected, living, equipment, org.bukkit.inventory.EquipmentSlot.BODY, EquipmentSlot.BODY);
        addEquipment(collected, living, equipment, org.bukkit.inventory.EquipmentSlot.SADDLE, EquipmentSlot.SADDLE);
        return collected;
    }

    private static void addEquipment(List<Equipment> collected,
                                     LivingEntity living,
                                     EntityEquipment equipment,
                                     org.bukkit.inventory.EquipmentSlot bukkitSlot,
                                     EquipmentSlot packetSlot) {
        if (!living.canUseEquipmentSlot(bukkitSlot)) {
            return;
        }
        ItemStack item = equipment.getItem(bukkitSlot);
        if (item == null || item.getType().isAir()) {
            collected.add(new Equipment(packetSlot, com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY));
            return;
        }
        collected.add(new Equipment(packetSlot, SpigotConversionUtil.fromBukkitItemStack(item)));
    }
}
