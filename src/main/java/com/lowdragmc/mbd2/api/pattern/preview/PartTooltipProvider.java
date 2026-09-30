package com.lowdragmc.mbd2.api.pattern.preview;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Supplies per-part XEI-only description lines without changing item identity. */
public interface PartTooltipProvider {
    record Context(ResourceLocation controllerId, PartHighlightSource shape) {}

    List<Component> tooltips(ItemStack stack, Context context);
}
