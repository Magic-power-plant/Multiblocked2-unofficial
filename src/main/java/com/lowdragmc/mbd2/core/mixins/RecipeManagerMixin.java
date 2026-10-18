package com.lowdragmc.mbd2.core.mixins;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.recipe.MBDRecipe;
import com.lowdragmc.mbd2.api.recipe.MBDRecipeType;
import com.lowdragmc.mbd2.api.registry.MBDRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Injects MBD2 built-in recipe types after vanilla datapack recipe loading.
 *
 * <p>The mixin copies the recipe map before handing the registered
 * {@link MBDRecipeType} set to its fixed-point loader, allowing chained proxy
 * recipes to be appended without mutating vanilla's immutable reload table.
 * Reloaded entries retain their datapack keys, which can differ from the ID
 * returned by a third-party recipe object.</p>
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {

    @Shadow private Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipes;
    @Shadow private Map<ResourceLocation, Recipe<?>> byName;
    @Shadow private boolean hasErrors;

    /**
     * Lets registered MBD recipe types append their built-in recipes after vanilla loading.
     *
     * @param map raw JSON recipe data from the reload
     * @param resourceManager resource manager used by vanilla loading
     * @param profiler reload profiler
     * @param ci mixin callback info
     */
    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "TAIL"))
    private void mbd2$cloneVanillaRecipes(Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler, CallbackInfo ci) {
        var mutableRecipes = new HashMap<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>();
        recipes.forEach((type, recipesByName) -> mutableRecipes.put(type, new HashMap<>(recipesByName)));
        recipes = mutableRecipes;
        MBDRecipeType.onRecipeManagerLoaded(recipes, MBDRegistries.RECIPE_TYPES.values());
        mbd2$rebuildReloadedRecipeTables();
    }

    /**
     * Rebuilds the client-side recipe manager table while tolerating duplicate
     * IDs introduced by MBD proxy recipes.
     *
     * @param loadedRecipes synchronized recipes from the server
     * @param ci mixin callback info
     */
    @Inject(method = "replaceRecipes(Ljava/lang/Iterable;)V", at = @At("HEAD"), cancellable = true)
    private void mbd2$replaceRecipes(Iterable<Recipe<?>> loadedRecipes, CallbackInfo ci) {
        mbd2$replaceRecipeTables(loadedRecipes);
        ci.cancel();
    }

    @Unique
    private void mbd2$rebuildReloadedRecipeTables() {
        // apply() keys recipes by their datapack resource locations. Flattening
        // values and calling getId() can collapse distinct entries when a mod
        // reports the same ID for multiple recipe objects (e.g. Croparia soak
        // recipes). Keep both the resource keys and the original type buckets.
        var recipesByName = new LinkedHashMap<ResourceLocation, Recipe<?>>();
        recipes.values().forEach(entries -> entries.forEach((id, recipe) -> {
            var previous = recipesByName.putIfAbsent(id, recipe);
            if (previous != null && previous != recipe) {
                var selected = mbd2$selectDuplicateRecipe(id, previous, recipe);
                if (selected == null) {
                    throw new IllegalArgumentException("Multiple entries with same key: " + id + "=" + recipe + " and " + previous);
                }
                recipesByName.put(id, selected);
            }
        }));

        var recipesByType = new HashMap<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>();
        recipes.forEach((type, entries) -> {
            var selectedEntries = new HashMap<ResourceLocation, Recipe<?>>();
            entries.forEach((id, recipe) -> {
                if (recipesByName.get(id) == recipe) {
                    selectedEntries.put(id, recipe);
                }
            });
            recipesByType.put(type, selectedEntries);
        });
        recipes = ImmutableMap.copyOf(recipesByType);
        byName = ImmutableMap.copyOf(recipesByName);
        // Preserve apply()'s error state; only replaceRecipes() starts a fresh
        // synchronized table and resets hasErrors.
    }

    @Unique
    private void mbd2$replaceRecipeTables(Iterable<Recipe<?>> loadedRecipes) {
        hasErrors = false;
        var seenByType = new HashMap<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>();
        var recipesByName = new LinkedHashMap<ResourceLocation, Recipe<?>>();
        var seenRecipes = Collections.newSetFromMap(new IdentityHashMap<Recipe<?>, Boolean>());

        for (var loadedRecipe : loadedRecipes) {
            // Type buckets can alias the same recipe instance. Shallow map copies
            // retain those references, so flattening must not turn them into ID
            // conflicts or let an old MBD alias override a newer recipe again.
            // Distinct objects still go through the normal duplicate-ID policy.
            if (!seenRecipes.add(loadedRecipe)) {
                continue;
            }
            var id = loadedRecipe.getId();
            var recipesForType = seenByType.computeIfAbsent(loadedRecipe.getType(), type -> new HashMap<>());
            var previousForType = recipesForType.put(id, loadedRecipe);
            if (previousForType != null) {
                loadedRecipe = mbd2$selectDuplicateRecipe(id, previousForType, loadedRecipe);
                if (loadedRecipe == null) {
                    throw new IllegalStateException("Duplicate recipe ignored with ID " + id);
                }
                recipesForType.put(id, loadedRecipe);
            }

            var previousByName = recipesByName.putIfAbsent(id, loadedRecipe);
            if (previousByName != null) {
                var selectedRecipe = mbd2$selectDuplicateRecipe(id, previousByName, loadedRecipe);
                if (selectedRecipe == null) {
                    throw new IllegalArgumentException("Multiple entries with same key: " + id + "=" + loadedRecipe + " and " + previousByName);
                }
                recipesByName.put(id, selectedRecipe);
            }
        }

        var recipesByType = new HashMap<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>();
        recipesByName.values().forEach(recipe -> recipesByType.computeIfAbsent(recipe.getType(), type -> new HashMap<>()).put(recipe.getId(), recipe));
        recipes = ImmutableMap.copyOf(recipesByType);
        byName = ImmutableMap.copyOf(recipesByName);
    }

    @Unique
    private static Recipe<?> mbd2$selectDuplicateRecipe(ResourceLocation id, Recipe<?> previousRecipe, Recipe<?> newRecipe) {
        if (!(previousRecipe instanceof MBDRecipe) && !(newRecipe instanceof MBDRecipe)) {
            return null;
        }
        if (!(previousRecipe instanceof MBDRecipe)) {
            MBD2.LOGGER.warn("Ignoring duplicate MBD recipe {} because a non-MBD recipe already uses that id", id);
            return previousRecipe;
        }
        if (!(newRecipe instanceof MBDRecipe)) {
            MBD2.LOGGER.warn("Replacing duplicate MBD recipe {} with a non-MBD recipe that uses the same id", id);
            return newRecipe;
        }

        var previousOwnsId = mbd2$ownsRecipeId((MBDRecipe) previousRecipe);
        var newOwnsId = mbd2$ownsRecipeId((MBDRecipe) newRecipe);
        if (previousOwnsId != newOwnsId) {
            var selectedRecipe = previousOwnsId ? previousRecipe : newRecipe;
            MBD2.LOGGER.warn("Resolved duplicate MBD recipe id {} by keeping the recipe owned by its MBD recipe type", id);
            return selectedRecipe;
        }

        MBD2.LOGGER.warn("Resolved duplicate MBD recipe id {} by keeping the latest MBD recipe", id);
        return newRecipe;
    }

    @Unique
    private static boolean mbd2$ownsRecipeId(MBDRecipe recipe) {
        if (recipe.recipeType == null || recipe.recipeType.getRegistryName() == null) {
            return false;
        }
        var recipeTypeId = recipe.recipeType.getRegistryName();
        var recipeId = recipe.getId();
        return recipeId.getNamespace().equals(recipeTypeId.getNamespace()) &&
                (recipeId.getPath().equals(recipeTypeId.getPath()) || recipeId.getPath().startsWith(recipeTypeId.getPath() + "/"));
    }
}
