package com.lowdragmc.mbd2.core.mixins.jei;

import com.lowdragmc.lowdraglib.gui.ingredient.IRecipeIngredientSlot;
import com.lowdragmc.lowdraglib.jei.IRecipeSlotBuilderAccessor;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotRichTooltipCallback;
import mezz.jei.library.gui.recipes.layout.builder.RecipeSlotBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Restores LDLib slot tooltips in JEI recipe slots.
 *
 * <p>JEI changed {@code build}'s internal {@code Pair} package during the
 * 15.x line. Targeting the overload name keeps this mixin compatible with
 * both descriptors, while the guard below prevents duplicate callbacks.
 * JEI 15.58 also added a {@code Player} parameter to
 * {@code IIngredientRenderer#getTooltip}; older LDLib renderers therefore
 * fall back to their empty legacy tooltip. The callback below supplies the
 * complete slot tooltip independently of that renderer method.</p>
 */
@Mixin(value = RecipeSlotBuilder.class, remap = false)
public abstract class RecipeSlotBuilderTooltipMixin {

    @Unique
    private boolean mbd2$tooltipCallbackAdded;

    @Shadow @Final
    private List<IRecipeSlotRichTooltipCallback> tooltipCallbacks;

    @Inject(
            method = "build",
            at = @At("HEAD"),
            remap = false
    )
    @SuppressWarnings("removal")
    private void mbd2$appendSlotTooltipToJeiTooltip(CallbackInfoReturnable<?> cir) {
        if (mbd2$tooltipCallbackAdded) {
            return;
        }

        if (!((Object) this instanceof IRecipeSlotBuilderAccessor accessor)) {
            return;
        }
        IRecipeIngredientSlot slot = accessor.lowDragLib$getRecipeIngredientSlot();
        if (slot == null) {
            return;
        }

        // JEI adds output metadata, including the recipe id, before this point.
        // Its normal renderer tooltip is rendered before those callbacks, so the
        // LDLib fallback must use the same ordering.
        var existingCallbacks = new ArrayList<>(tooltipCallbacks);
        tooltipCallbacks.clear();

        mbd2$tooltipCallbackAdded = true;
        IRecipeSlotBuilder builder = (IRecipeSlotBuilder) (Object) this;
        builder.addRichTooltipCallback((view, tooltip) -> {
            var slotTooltip = slot.getFullTooltipTexts();
            if (slotTooltip.isEmpty()) {
                return;
            }
            tooltip.removeAll(slotTooltip);
            tooltip.addAll(slotTooltip);
        });
        tooltipCallbacks.addAll(existingCallbacks);
    }
}
