package com.lowdragmc.mbd2.integration.jade;

import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.machine.IMachine;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.trait.forgeenergy.ForgeEnergyCapabilityTrait;
import com.lowdragmc.mbd2.common.trait.forgeenergy.LongFeEnergyCapabilityTrait;
import com.lowdragmc.mbd2.utils.EnergyFormatUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.Identifiers;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.BoxStyle;

/**
 * Jade energy integration following Mekanism's server-data and client-renderer arrangement.
 *
 * <p>Jade's generic Forge Energy lookup is limited by the capability's {@code int} API. MBD sends the concrete
 * Forge Energy and Long FE trait values as {@code long}s, then renders the data as Jade progress elements on the
 * client. This also replaces Jade's automatic Forge Energy row so it cannot duplicate or truncate Long FE.</p>
 */
public final class MBDJadeEnergyStorageProvider implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
    public static final MBDJadeEnergyStorageProvider INSTANCE = new MBDJadeEnergyStorageProvider();

    private static final ResourceLocation UID = MBD2.id("energy_storage");
    private static final String DATA_KEY = "mbd2Energy";
    private static final String FE_UNIT = "FE";
    private static final String TITLE_KEY = "Title";
    private static final String CURRENT_KEY = "Cur";
    private static final String CAPACITY_KEY = "Capacity";
    private static final int ENERGY_COLOR = 0xFFAA0000;
    private static final int ENERGY_COLOR_DARK = 0xFF660000;

    private MBDJadeEnergyStorageProvider() {
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        IMachine.ofMachine(accessor.getBlockEntity())
                .filter(MBDMachine.class::isInstance)
                .map(MBDMachine.class::cast)
                .map(MBDJadeEnergyStorageProvider::createEnergyData)
                .filter(tag -> !tag.isEmpty())
                .ifPresent(tag -> data.put(DATA_KEY, tag));
    }

    private static ListTag createEnergyData(MBDMachine machine) {
        var energyData = new ListTag();
        for (var trait : machine.getAdditionalTraits()) {
            if (trait instanceof ForgeEnergyCapabilityTrait energyTrait) {
                addEnergyData(energyData, energyTrait.getDefinition().getName(),
                        energyTrait.storage.getEnergyStored(), energyTrait.storage.getMaxEnergyStored());
            } else if (trait instanceof LongFeEnergyCapabilityTrait energyTrait) {
                addEnergyData(energyData, energyTrait.getDefinition().getName(),
                        energyTrait.container.getEnergyStored(), energyTrait.container.getEnergyCapacity());
            }
        }
        return energyData;
    }

    private static void addEnergyData(ListTag energyData, String name, long stored, long capacity) {
        if (capacity <= 0L) {
            return;
        }
        CompoundTag energy = createEnergyData(stored, capacity);
        energy.putString(TITLE_KEY, name == null || name.isBlank() ? FE_UNIT : name);
        energyData.add(energy);
    }

    static CompoundTag createEnergyData(long stored, long capacity) {
        long normalizedCapacity = Math.max(0L, capacity);
        long normalizedStored = Math.max(0L, Math.min(normalizedCapacity, stored));
        var data = new CompoundTag();
        data.putLong(CURRENT_KEY, normalizedStored);
        data.putLong(CAPACITY_KEY, normalizedCapacity);
        return data;
    }

    static Component createEnergyDisplayText(long stored, long capacity, boolean raw) {
        long normalizedCapacity = Math.max(0L, capacity);
        long normalizedStored = Math.max(0L, Math.min(normalizedCapacity, stored));
        String storedText = raw ? Long.toString(normalizedStored) : EnergyFormatUtil.formatEnergy(normalizedStored).stripTrailing();
        String capacityText = raw ? Long.toString(normalizedCapacity) : EnergyFormatUtil.formatEnergy(normalizedCapacity).stripTrailing();
        return Component.literal(storedText + " FE / " + capacityText + " FE");
    }

    static float calculateProgress(long stored, long capacity) {
        if (capacity <= 0L || stored <= 0L) {
            return 0F;
        }
        if (stored >= capacity) {
            return 1F;
        }
        return (float) ((double) stored / capacity);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag serverData = accessor.getServerData();
        if (!serverData.contains(DATA_KEY, Tag.TAG_LIST)) {
            return;
        }

        ListTag energyData = serverData.getList(DATA_KEY, Tag.TAG_COMPOUND);
        if (energyData.isEmpty()) {
            return;
        }

        // Match Mekanism's integration: our long-based data replaces Jade's Forge capability entry.
        tooltip.remove(Identifiers.UNIVERSAL_ENERGY_STORAGE);
        boolean raw = accessor.getPlayer() != null && accessor.getPlayer().isShiftKeyDown();
        boolean showTitle = energyData.size() > 1;
        for (int i = 0; i < energyData.size(); i++) {
            CompoundTag energy = energyData.getCompound(i);
            long stored = energy.getLong(CURRENT_KEY);
            long capacity = energy.getLong(CAPACITY_KEY);
            if (capacity <= 0L) {
                continue;
            }
            if (showTitle && energy.contains(TITLE_KEY, Tag.TAG_STRING)) {
                tooltip.add(Component.literal(energy.getString(TITLE_KEY)).withStyle(ChatFormatting.GRAY));
            }
            tooltip.add(tooltip.getElementHelper().progress(
                    calculateProgress(stored, capacity),
                    createEnergyDisplayText(stored, capacity, raw),
                    tooltip.getElementHelper().progressStyle().color(ENERGY_COLOR, ENERGY_COLOR_DARK),
                    BoxStyle.DEFAULT,
                    true));
        }
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public int getDefaultPriority() {
        // Jade's built-in energy entry uses 1000; keep our replacement in the body before the mod-name tail.
        return 5000;
    }
}
