package com.lowdragmc.mbd2.integration.jei;

import com.lowdragmc.lowdraglib.jei.ModularUIRecipeCategory;
import com.lowdragmc.lowdraglib.jei.ModularWrapper;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.client.xei.MultiblockPreviewLayoutMetrics;
import com.lowdragmc.mbd2.api.pattern.IMultiblockXEI;
import com.lowdragmc.mbd2.api.registry.MultiblockXEIRegistry;
import com.lowdragmc.mbd2.common.machine.definition.MultiblockMachineDefinition;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * JEI category that shows preview pages for registered multiblock machine structures.
 */
public class MultiblockInfoCategory extends ModularUIRecipeCategory<MultiblockInfoCategory.MultiblockInfoWrapper> {

    /**
     * JEI wrapper for a single multiblock XEI entry.
     */
    public static class MultiblockInfoWrapper extends ModularWrapper<WidgetGroup> {

        public final IMultiblockXEI entry;
        @Nullable
        public final MultiblockMachineDefinition definition;

        public MultiblockInfoWrapper(MultiblockMachineDefinition definition) {
            this(IMultiblockXEI.of(definition), definition);
        }

        public MultiblockInfoWrapper(IMultiblockXEI entry) {
            this(entry, null);
        }

        private MultiblockInfoWrapper(IMultiblockXEI entry, @Nullable MultiblockMachineDefinition definition) {
            super(entry.createWidget());
            this.entry = entry;
            this.definition = definition;
            setShouldRenderTooltips(false);
        }
    }

    /**
     * JEI-facing multiblock info entries are LDLib wrappers, matching LDLib's default JEI input path.
     */
    public final static RecipeType<MultiblockInfoWrapper> RECIPE_TYPE = new RecipeType<>(MBD2.id("multiblock_info"),
            MultiblockInfoWrapper.class);
    private final IDrawable background;
    private final IDrawable icon;

    public MultiblockInfoCategory(IJeiHelpers helpers) {
        IGuiHelper guiHelper = helpers.getGuiHelper();
        this.background = guiHelper.createBlankDrawable(MultiblockPreviewLayoutMetrics.WIDTH, MultiblockPreviewLayoutMetrics.HEIGHT);
        this.icon = helpers.getGuiHelper().drawableBuilder(MBD2.id("textures/gui/multiblock_info_page.png"), 0, 0, 16, 16).setTextureSize(16, 16).build();
    }

    public static List<MultiblockInfoWrapper> registerRecipes(IRecipeRegistration registry) {
        var recipes = MultiblockXEIRegistry.entries().stream()
                .map(MultiblockInfoWrapper::new)
                .toList();
        registry.addRecipes(RECIPE_TYPE, recipes);
        return recipes;
    }

    public static void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        for (var entry : MultiblockXEIRegistry.entries()) {
            var workstation = entry.getWorkstation();
            if (workstation != null && !workstation.isEmpty()) {
                registration.addRecipeCatalyst(workstation, RECIPE_TYPE);
            }
        }
    }

    @Override
    @NotNull
    public RecipeType<MultiblockInfoWrapper> getRecipeType() {
        return RECIPE_TYPE;
    }

    @NotNull
    @Override
    public Component getTitle() {
        return Component.translatable("mbd2.jei.multiblock_info");
    }

    @SuppressWarnings("removal")
    @NotNull
    @Override
    public IDrawable getBackground() {
        return background;
    }

    @NotNull
    @Override
    public IDrawable getIcon() {
        return icon;
    }
}
