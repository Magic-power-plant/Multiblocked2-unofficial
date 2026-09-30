package com.lowdragmc.mbd2.api.pattern.preview;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/** Value snapshot of one item/NBT variant and all of its candidate positions. */
public record PartHighlightEntry(PartHighlightIndex.ItemKey key, ItemStack displayStack,
                                 Set<BlockPos> positions, List<PartHighlightIndex.ConstraintHint> hints,
                                 List<Component> customTooltips) {
    public PartHighlightEntry {
        displayStack = displayStack.copyWithCount(1);
        positions = BlockPosSnapshots.copyOf(positions);
        hints = List.copyOf(hints);
        customTooltips = ComponentSnapshots.copy(customTooltips);
    }

    @Override public ItemStack displayStack() { return displayStack.copy(); }
    @Override public List<Component> customTooltips() { return ComponentSnapshots.copy(customTooltips); }
}
