package com.lowdragmc.mbd2.api.recipe.event;

import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.mbd2.api.recipe.MBDRecipe;
import com.lowdragmc.mbd2.api.recipe.MBDRecipeBuilder;
import com.lowdragmc.mbd2.api.recipe.MBDRecipeType;
import com.lowdragmc.mbd2.common.graphprocessor.GraphParameterGet;
import com.lowdragmc.mbd2.common.graphprocessor.GraphParameterSet;
import dev.latvian.mods.rhino.util.HideFromJS;
import lombok.Getter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.eventbus.api.Cancelable;
import org.jetbrains.annotations.Nullable;

/**
 * Event fired while converting an external recipe into an MBD recipe.
 *
 * <p>The business goal is to let integrations inspect the original vanilla or
 * modded recipe, replace the generated {@link #mbdRecipe}, or cancel conversion
 * entirely. The event is posted during recipe-manager loading/conversion and
 * should avoid world mutation. Graph processors can read the proxy fields and
 * write {@link #mbdRecipe} through the annotated output parameter.</p>
 */
@Getter
@Cancelable
@LDLRegister(name = "TransferProxyRecipeEvent", group = "RecipeTypeEvent")
public class TransferProxyRecipeEvent extends RecipeTypeEvent {

    @GraphParameterGet
    public final ResourceLocation proxyTypeId;
    @GraphParameterGet
    public final RecipeType<?> proxyType;
    @GraphParameterGet
    public final ResourceLocation proxyRecipeId;
    @GraphParameterGet
    public final Recipe<?> proxyRecipe;
    @GraphParameterGet
    public final int traversal;
    @Nullable
    @GraphParameterSet(identity = "drops.out")
    public MBDRecipe mbdRecipe;

    /**
     * Creates a proxy-transfer event.
     *
     * @param recipeType    MBD recipe type receiving the converted recipe
     * @param proxyTypeId   registry id of the external recipe type
     * @param proxyType     external recipe type
     * @param proxyRecipeId external recipe id
     * @param proxyRecipe   original external recipe instance
     * @param traversal     one-based proxy traversal number
     * @param mbdRecipe     initially converted recipe, or {@code null} when the
     *                      default converter produced no recipe
     */
    public TransferProxyRecipeEvent(MBDRecipeType recipeType, ResourceLocation proxyTypeId, RecipeType<?> proxyType,
                                    ResourceLocation proxyRecipeId, Recipe<?> proxyRecipe, int traversal,
                                    @Nullable MBDRecipe mbdRecipe) {
        super(recipeType);
        if (traversal < 1) {
            throw new IllegalArgumentException("Proxy recipe traversal must be at least 1");
        }
        this.proxyTypeId = proxyTypeId;
        this.proxyType = proxyType;
        this.proxyRecipe = proxyRecipe;
        this.proxyRecipeId = proxyRecipeId;
        this.traversal = traversal;
        this.mbdRecipe = mbdRecipe;
    }

    /**
     * Creates a first-traversal proxy-transfer event.
     *
     * @deprecated use the constructor that explicitly supplies the traversal
     * number so listeners can be routed by {@code source_type@traversal}
     */
    @Deprecated
    public TransferProxyRecipeEvent(MBDRecipeType recipeType, ResourceLocation proxyTypeId, RecipeType<?> proxyType,
                                    ResourceLocation proxyRecipeId, Recipe<?> proxyRecipe, @Nullable MBDRecipe mbdRecipe) {
        this(recipeType, proxyTypeId, proxyType, proxyRecipeId, proxyRecipe, 1, mbdRecipe);
    }

    /**
     * Returns the recipe type's builder template for graph-side conversions.
     *
     * <p>Side effects: none. The returned builder is the recipe type's shared
     * template, so callers that need an independent recipe should copy it before
     * mutating long-lived defaults.</p>
     *
     * @return builder template owned by {@link #recipeType}
     */
    @HideFromJS
    public MBDRecipeBuilder recipeBuilder() {
        return recipeType.getRecipeBuilder();
    }
}
