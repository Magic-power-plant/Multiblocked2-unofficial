package com.lowdragmc.mbd2.client.xei;

import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.lowdragmc.mbd2.api.pattern.preview.PartHighlightEntry;
import com.lowdragmc.mbd2.api.pattern.preview.PartTooltipProvider;
import com.lowdragmc.mbd2.common.block.MBDMachineBlock;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;

import java.util.ArrayList;
import java.util.List;

/** Mirrors LDLib's slot item tooltip flow, then appends overview-only context. */
public final class PartHighlightTooltipComposer {
    private PartHighlightTooltipComposer() {}

    public static List<Component> compose(PartHighlightEntry entry, int visible,
                                          PartTooltipProvider.Context context) {
        var stack = entry.displayStack();
        PartTooltipProvider provider = (candidate, ignoredContext) -> {
            if (candidate.getItem() instanceof BlockItem item && item.getBlock() instanceof MBDMachineBlock block) {
                var settings = block.getDefinition().partSettings();
                if (settings != null) return settings.xeiPartTooltips();
            }
            return entry.customTooltips();
        };
        return append(DrawerHelper.getItemToolTip(stack), entry, visible, provider, context);
    }

    public static List<Component> append(List<Component> standard,
                                        PartHighlightEntry entry, int visible,
                                        PartTooltipProvider provider, PartTooltipProvider.Context context) {
        var lines = new ArrayList<>(standard);
        lines.addAll(provider.tooltips(entry.displayStack(), context));
        lines.add(Component.translatable("pattern_preview.part_positions", entry.positions().size(), visible));
        entry.hints().forEach(hint -> {
            lines.add(Component.translatable("pattern_preview.part_constraint_source", hint.source()));
            lines.addAll(hint.lines());
        });
        lines.add(Component.translatable("pattern_preview.parts_not_quantity"));
        if (visible == 0) lines.add(Component.translatable("pattern_preview.parts_hidden"));
        return lines;
    }
}
