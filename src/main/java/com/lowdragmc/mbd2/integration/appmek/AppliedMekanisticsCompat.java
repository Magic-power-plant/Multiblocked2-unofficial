package com.lowdragmc.mbd2.integration.appmek;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.util.ConfigInventory;
import me.ramidzkh.mekae2.ae2.MekanismKeyType;
import com.lowdragmc.mbd2.api.capability.recipe.IRecipeHandlerTrait;
import com.lowdragmc.mbd2.common.trait.RecipeCapabilityTrait;
import com.lowdragmc.mbd2.integration.ae2.storage.MERecipeKeyStorage;
import com.lowdragmc.mbd2.integration.mekanism.MekanismChemicalRecipeCapability;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.infuse.InfuseType;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.pigment.Pigment;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.slurry.Slurry;
import mekanism.api.chemical.slurry.SlurryStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/** Optional integration entry point. Call only after checking that appmek, AE2 and Mekanism are loaded. */
public final class AppliedMekanisticsCompat {
    private AppliedMekanisticsCompat() {}

    public static void extendChemicalCapacity(ConfigInventory inventory) {
        inventory.setCapacity(MekanismKeyType.TYPE, Long.MAX_VALUE);
    }

    public static boolean isChemicalKey(AEKey key) {
        return key instanceof MekanismKey;
    }

    public static List<IRecipeHandlerTrait<?>> createRecipeHandlers(RecipeCapabilityTrait trait,
            Supplier<MERecipeKeyStorage> storage, boolean patternInput) {
        return List.of(
                new MEChemicalRecipeHandler<>(trait, MekanismChemicalRecipeCapability.CAP_GAS, GasStack.class, storage, patternInput),
                new MEChemicalRecipeHandler<>(trait, MekanismChemicalRecipeCapability.CAP_INFUSE, InfusionStack.class, storage, patternInput),
                new MEChemicalRecipeHandler<>(trait, MekanismChemicalRecipeCapability.CAP_PIGMENT, PigmentStack.class, storage, patternInput),
                new MEChemicalRecipeHandler<>(trait, MekanismChemicalRecipeCapability.CAP_SLURRY, SlurryStack.class, storage, patternInput));
    }

    @Nullable
    public static GenericStack toGenericStack(@Nullable Object ingredient) {
        if (ingredient instanceof ChemicalStack<?> chemical && !chemical.isEmpty()) {
            var key = MekanismKey.of(chemical);
            return key == null ? null : new GenericStack(key, chemical.getAmount());
        }
        return null;
    }

    /** EMI exposes the chemical type as its key and keeps the amount separately. */
    @Nullable
    public static GenericStack toGenericStack(@Nullable Object ingredient, long amount) {
        if (amount <= 0) return null;
        if (ingredient instanceof Gas gas) return toGenericStack(new GasStack(gas, amount));
        if (ingredient instanceof InfuseType infusion) return toGenericStack(new InfusionStack(infusion, amount));
        if (ingredient instanceof Pigment pigment) return toGenericStack(new PigmentStack(pigment, amount));
        if (ingredient instanceof Slurry slurry) return toGenericStack(new SlurryStack(slurry, amount));
        var stack = toGenericStack(ingredient);
        return stack == null ? null : new GenericStack(stack.what(), amount);
    }
}
