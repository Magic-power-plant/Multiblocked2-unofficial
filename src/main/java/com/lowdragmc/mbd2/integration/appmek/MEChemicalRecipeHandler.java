package com.lowdragmc.mbd2.integration.appmek;

import com.lowdragmc.mbd2.api.capability.recipe.IO;
import com.lowdragmc.mbd2.api.recipe.MBDRecipe;
import com.lowdragmc.mbd2.api.recipe.RecipeConsumptionTracker;
import com.lowdragmc.mbd2.common.trait.RecipeCapabilityTrait;
import com.lowdragmc.mbd2.common.trait.RecipeHandlerTrait;
import com.lowdragmc.mbd2.integration.ae2.storage.MERecipeKeyStorage;
import com.lowdragmc.mbd2.integration.mekanism.MekanismChemicalRecipeCapability;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.recipes.ingredients.ChemicalStackIngredient;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Bridges chemical recipe ingredients to AE keys without touching live storage during simulation. */
public final class MEChemicalRecipeHandler<C extends Chemical<C>, S extends ChemicalStack<C>>
        extends RecipeHandlerTrait<ChemicalStackIngredient<C, S>> {
    private final MekanismChemicalRecipeCapability<C, S> chemicalCapability;
    private final Class<S> stackType;
    private final Supplier<MERecipeKeyStorage> storage;
    private final boolean patternInput;

    public MEChemicalRecipeHandler(RecipeCapabilityTrait trait, MekanismChemicalRecipeCapability<C, S> capability,
            Class<S> stackType, Supplier<MERecipeKeyStorage> storage, boolean patternInput) {
        super(trait, capability);
        this.chemicalCapability = capability;
        this.stackType = stackType;
        this.storage = storage;
        this.patternInput = patternInput;
    }

    @Override
    public Set<String> getRecipeGroups() {
        return patternInput ? storage.get().getRecipeGroups() : super.getRecipeGroups();
    }

    @Override
    public List<ChemicalStackIngredient<C, S>> handleRecipeInner(IO io, MBDRecipe recipe,
            List<ChemicalStackIngredient<C, S>> left, @Nullable String slotName, boolean simulate) {
        return handle(io, left, slotName, simulate, null);
    }

    @Override
    public List<ChemicalStackIngredient<C, S>> handleRecipe(IO io, MBDRecipe recipe, List<?> left,
            @Nullable String slotName, boolean simulate, @Nullable String recipeGroup) {
        var copied = new ArrayList<ChemicalStackIngredient<C, S>>(left.size());
        for (var content : left) copied.add(copyContent(content));
        return handle(io, copied, slotName, simulate, recipeGroup);
    }

    private List<ChemicalStackIngredient<C, S>> handle(IO io, List<ChemicalStackIngredient<C, S>> left,
            @Nullable String slotName, boolean simulate, @Nullable String recipeGroup) {
        if (!compatibleWith(io) || (patternInput && io != IO.IN)) return left;
        var target = simulate ? storage.get().copy() : storage.get();
        var iterator = left.listIterator();
        while (iterator.hasNext()) {
            var ingredient = iterator.next();
            if (chemicalCapability.isEmptyIngredient(ingredient)) {
                iterator.remove();
                continue;
            }
            if (io == IO.IN) {
                for (var stored : target.getContents(recipeGroup)) {
                    if (!(stored.what() instanceof MekanismKey key) || !stackType.isInstance(key.getStack())) continue;
                    var chemical = stackType.cast(key.withAmount(stored.amount()));
                    if (!ingredient.testType(chemical)) continue;
                    long required = ingredient.getNeededAmount(chemical);
                    if (required <= 0) continue;
                    long extracted = target.extract(key, required, recipeGroup);
                    if (!simulate && extracted > 0) {
                        RecipeConsumptionTracker.record(chemicalCapability, key.withAmount(extracted), slotName);
                    }
                    long remaining = required - extracted;
                    if (remaining <= 0) {
                        iterator.remove();
                        break;
                    }
                    ingredient = chemicalCapability.withAmount(ingredient, remaining);
                    iterator.set(ingredient);
                }
            } else if (io == IO.OUT) {
                var output = chemicalCapability.getDisplayStack(ingredient);
                if (output == null || output.isEmpty()) continue;
                var key = MekanismKey.of(output);
                if (key == null) continue;
                long remaining = output.getAmount() - target.insert(key, output.getAmount(), recipeGroup);
                if (remaining <= 0) iterator.remove();
                else iterator.set(chemicalCapability.withAmount(ingredient, remaining));
            }
        }
        return left.isEmpty() ? null : left;
    }
}
