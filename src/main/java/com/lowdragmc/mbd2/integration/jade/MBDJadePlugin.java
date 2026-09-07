package com.lowdragmc.mbd2.integration.jade;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade plugin entry point that wires MBD server data and client tooltip providers.
 */
@WailaPlugin
public class MBDJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(new RecipeLogicProvider(), BlockEntity.class);
        registration.registerBlockDataProvider(EnergyTransferDataProvider.INSTANCE, BlockEntity.class);
        registration.registerBlockDataProvider(MBDJadeEnergyStorageProvider.INSTANCE, BlockEntity.class);
        registration.registerFluidStorage(LongFluidStorageProvider.INSTANCE, BlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.addConfig(EnergyTransferDataProvider.INSTANCE.getUid(), false);
        registration.registerBlockComponent(new RecipeLogicProvider(), Block.class);
        registration.registerBlockComponent(new MultiblockProvider(), Block.class);
        registration.registerBlockComponent(EnergyTransferDataProvider.INSTANCE, Block.class);
        registration.registerBlockComponent(MBDJadeEnergyStorageProvider.INSTANCE, Block.class);
        registration.registerFluidStorageClient(LongFluidStorageProvider.INSTANCE);
    }
}
