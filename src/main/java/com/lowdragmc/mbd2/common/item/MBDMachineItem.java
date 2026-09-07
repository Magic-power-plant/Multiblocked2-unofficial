package com.lowdragmc.mbd2.common.item;

import com.lowdragmc.lowdraglib.client.renderer.IItemRendererProvider;
import com.lowdragmc.lowdraglib.client.renderer.IRenderer;
import com.lowdragmc.mbd2.common.block.MBDMachineBlock;
import com.lowdragmc.mbd2.common.machine.definition.MBDMachineDefinition;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * @author KilaBash
 * @date 2023/2/18
 * @implNote MetaMachineItem
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class MBDMachineItem extends BlockItem implements IItemRendererProvider {

    /**
     * Creates the item form for an MBD machine block.
     *
     * @param block      machine block whose definition backs this item
     * @param properties vanilla item properties used for creative tabs, stack size, and durability flags
     */
    public MBDMachineItem(MBDMachineBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    public MBDMachineBlock getBlock() {
        return (MBDMachineBlock) super.getBlock();
    }

    /**
     * Returns the machine definition associated with this block item.
     *
     * @return definition owned by the backing {@link MBDMachineBlock}
     */
    public MBDMachineDefinition getDefinition() {
        return getBlock().getDefinition();
    }

    /**
     * Returns the localized block-item name with the definition's configured display name as its fallback.
     *
     * <p>{@link ItemStack#getHoverName()} continues to prefer a player-provided custom name, so this only affects the
     * normal name shown in inventories, creative tabs, recipe viewers, and dropped-item labels.</p>
     *
     * @param stack machine item stack being named
     * @return translated item name, or the configured display name when its translation key is absent
     */
    @Override
    public Component getName(ItemStack stack) {
        return getDefinition().getDisplayName(super.getName(stack), getDescriptionId(stack));
    }

    @Nullable
    @Override
    public IRenderer getRenderer(ItemStack stack) {
        return getDefinition().itemRenderer();
    }

}
