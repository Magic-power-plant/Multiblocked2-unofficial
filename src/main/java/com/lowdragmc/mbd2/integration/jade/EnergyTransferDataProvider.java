package com.lowdragmc.mbd2.integration.jade;

import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.machine.IMachine;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.trait.forgeenergy.ForgeEnergyCapabilityTrait;
import com.lowdragmc.mbd2.common.trait.forgeenergy.LongFeEnergyCapabilityTrait;
import com.lowdragmc.mbd2.utils.EnergyFormatUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade provider that appends configured transfer limits for normal and long Forge Energy containers.
 *
 * <p>Energy amounts themselves are rendered by {@link MBDJadeEnergyStorageProvider}. The limits are intentionally
 * disabled by default so the optional diagnostic data does not crowd the normal energy progress display.</p>
 */
public final class EnergyTransferDataProvider implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
    public static final EnergyTransferDataProvider INSTANCE = new EnergyTransferDataProvider();

    private static final ResourceLocation UID = MBD2.id("energy_transfer_limits");
    private static final String DATA_KEY = "mbd2_energy_transfer_limits";
    private static final String NAME_KEY = "name";
    private static final String RECEIVE_KEY = "max_receive";
    private static final String EXTRACT_KEY = "max_extract";

    private EnergyTransferDataProvider() {
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor blockAccessor) {
        IMachine.ofMachine(blockAccessor.getBlockEntity())
                .filter(MBDMachine.class::isInstance)
                .map(MBDMachine.class::cast)
                .ifPresent(machine -> {
                    var limits = new ListTag();
                    for (var trait : machine.getAdditionalTraits()) {
                        if (trait instanceof ForgeEnergyCapabilityTrait energyTrait) {
                            addLimits(limits, energyTrait.getDefinition().getName(),
                                    energyTrait.getDefinition().getMaxReceive(), energyTrait.getDefinition().getMaxExtract());
                        } else if (trait instanceof LongFeEnergyCapabilityTrait energyTrait) {
                            addLimits(limits, energyTrait.getDefinition().getName(),
                                    energyTrait.container.getMaxReceivePerTick(), energyTrait.container.getMaxExtractPerTick());
                        }
                    }
                    if (!limits.isEmpty()) {
                        data.put(DATA_KEY, limits);
                    }
                });
    }

    private static void addLimits(ListTag limits, String name, long maxReceive, long maxExtract) {
        var limit = new CompoundTag();
        limit.putString(NAME_KEY, name == null || name.isBlank() ? "FE" : name);
        limit.putLong(RECEIVE_KEY, Math.max(0L, maxReceive));
        limit.putLong(EXTRACT_KEY, Math.max(0L, maxExtract));
        limits.add(limit);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor blockAccessor, IPluginConfig config) {
        if (!config.get(getUid())) {
            return;
        }
        ListTag limits = blockAccessor.getServerData().getList(DATA_KEY, CompoundTag.TAG_COMPOUND);
        if (limits.isEmpty()) {
            return;
        }

        boolean raw = blockAccessor.getPlayer() != null && blockAccessor.getPlayer().isShiftKeyDown();
        String translationKey = raw ? "mbd2.jade.energy_limits.raw" : "mbd2.jade.energy_limits";
        for (int index = 0; index < limits.size(); index++) {
            CompoundTag limit = limits.getCompound(index);
            long maxReceive = limit.getLong(RECEIVE_KEY);
            long maxExtract = limit.getLong(EXTRACT_KEY);
            String receiveText = raw ? Long.toString(maxReceive) : EnergyFormatUtil.formatEnergy(maxReceive);
            String extractText = raw ? Long.toString(maxExtract) : EnergyFormatUtil.formatEnergy(maxExtract);
            tooltip.add(Component.translatable(translationKey, limit.getString(NAME_KEY), receiveText, extractText));
        }
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public int getDefaultPriority() {
        return 5000;
    }
}
