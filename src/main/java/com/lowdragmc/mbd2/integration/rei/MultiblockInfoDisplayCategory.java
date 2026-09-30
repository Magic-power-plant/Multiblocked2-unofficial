package com.lowdragmc.mbd2.integration.rei;

import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.rei.IGui2Renderer;
import com.lowdragmc.lowdraglib.rei.ModularDisplay;
import com.lowdragmc.lowdraglib.rei.ModularUIDisplayCategory;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.client.xei.MultiblockPreviewLayoutMetrics;
import com.lowdragmc.mbd2.api.pattern.IMultiblockXEI;
import com.lowdragmc.mbd2.api.registry.MultiblockXEIRegistry;
import com.lowdragmc.mbd2.common.machine.definition.MultiblockMachineDefinition;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * REI display category that shows preview pages for registered multiblock machine structures.
 */
public class MultiblockInfoDisplayCategory extends ModularUIDisplayCategory<MultiblockInfoDisplayCategory.MultiblockInfoDisplay> {

    /**
     * REI display wrapper for a single multiblock XEI entry.
     */
    public static class MultiblockInfoDisplay extends ModularDisplay<WidgetGroup> {

        public final IMultiblockXEI entry;
        @Nullable
        public final MultiblockMachineDefinition definition;

        public MultiblockInfoDisplay(MultiblockMachineDefinition definition) {
            this(IMultiblockXEI.of(definition), definition);
        }

        public MultiblockInfoDisplay(IMultiblockXEI entry) {
            this(entry, null);
        }

        private MultiblockInfoDisplay(IMultiblockXEI entry, @Nullable MultiblockMachineDefinition definition) {
            super(entry::createWidget, MultiblockInfoDisplayCategory.CATEGORY);
            this.entry = entry;
            this.definition = definition;
        }

        @Override
        public Optional<ResourceLocation> getDisplayLocation() {
            return Optional.of(entry.getId());
        }

        @Override
        public void clearSlotWidgetHandler(SlotWidget slotW, int slotIndex) {
            super.clearSlotWidgetHandler(slotW, slotIndex);
        }
    }

    public static final CategoryIdentifier<MultiblockInfoDisplay> CATEGORY = CategoryIdentifier
            .of(MBD2.id("multiblock_info"));
    private final Renderer icon;

    public MultiblockInfoDisplayCategory() {
        this.icon = IGui2Renderer.toDrawable(new ResourceTexture("mbd2:textures/gui/multiblock_info_page.png"));
    }

    public static void registerDisplays(DisplayRegistry registry) {
        MultiblockXEIRegistry.entries().stream()
                .map(MultiblockInfoDisplay::new)
                .forEach(registry::add);
    }

    public static void registerWorkStations(CategoryRegistry registry) {
        for (var entry : MultiblockXEIRegistry.entries()) {
            var workstation = entry.getWorkstation();
            if (workstation != null && !workstation.isEmpty()) {
                registry.addWorkstations(CATEGORY, EntryStacks.of(workstation));
            }
        }
    }

    @Override
    public int getDisplayHeight() {
        return MultiblockPreviewLayoutMetrics.HEIGHT + 8;
    }

    @Override
    public int getDisplayWidth(MultiblockInfoDisplay display) {
        return MultiblockPreviewLayoutMetrics.WIDTH + 8;
    }

    @Override
    public CategoryIdentifier<? extends MultiblockInfoDisplay> getCategoryIdentifier() {
        return CATEGORY;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("mbd2.jei.multiblock_info");
    }

    @Override
    public Renderer getIcon() {
        return icon;
    }
}
