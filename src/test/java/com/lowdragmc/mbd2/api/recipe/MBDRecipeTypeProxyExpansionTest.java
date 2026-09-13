package com.lowdragmc.mbd2.api.recipe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MBDRecipeTypeProxyExpansionTest {

    @Test
    void normalRecipeTablesExpandChainedProxiesRegardlessOfOrder() {
        var sourceType = type("source");
        var first = new ProxyTestRecipeType(id("first"), sourceType);
        var second = new ProxyTestRecipeType(id("second"), first);
        var sourceId = id("source_recipe");
        var sourceRecipe = recipe(sourceType, sourceId);

        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipes = new LinkedHashMap<>();
        recipes.put(sourceType, new LinkedHashMap<>(Map.of(sourceId, sourceRecipe)));

        MBDRecipeType.onRecipeManagerLoaded(recipes, List.of(second, first));

        var firstId = id("first/source_recipe");
        var secondId = id("second/first/source_recipe");
        assertNotNull(recipes.get(first).get(firstId));
        assertNotNull(recipes.get(second).get(secondId));
        assertEquals(first, ((MBDRecipe) recipes.get(first).get(firstId)).recipeType);
        assertEquals(second, ((MBDRecipe) recipes.get(second).get(secondId)).recipeType);
    }

    @Test
    void kubeJsRecipeTablesExpandChainedProxiesRegardlessOfOrder() {
        var sourceType = type("kjs_source");
        var first = new ProxyTestRecipeType(id("kjs_first"), sourceType);
        var second = new ProxyTestRecipeType(id("kjs_second"), first);
        var sourceId = id("kjs_recipe");
        var sourceRecipe = recipe(sourceType, sourceId);
        Map<ResourceLocation, Recipe<?>> recipes = new LinkedHashMap<>();
        recipes.put(sourceId, sourceRecipe);

        MBDRecipeType.onRecipeManagerLoadedKjs(recipes, List.of(second, first));

        assertNotNull(recipes.get(id("kjs_first/kjs_recipe")));
        assertNotNull(recipes.get(id("kjs_second/kjs_first/kjs_recipe")));
    }

    @Test
    void normalRecipeTablesTraverseEachProxySourceByPolicy() {
        var sourceType = type("traversal_source");
        var target = new ProxyTestRecipeType(id("traversal_target"), sourceType);
        var sourceId = id("traversal_recipe");
        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipes = new LinkedHashMap<>();
        recipes.put(sourceType, new LinkedHashMap<>(Map.of(sourceId, recipe(sourceType, sourceId))));

        MBDRecipeType.onRecipeManagerLoaded(recipes, List.of(target), (receivingType, source) -> 2);

        assertEquals(List.of(1, 2), target.traversals);
        assertEquals(2, ((MBDRecipe) recipes.get(target).get(id("traversal_target/traversal_recipe"))).priority);
    }

    @Test
    void kubeJsRecipeTablesTraverseEachProxySourceByPolicy() {
        var sourceType = type("kjs_traversal_source");
        var target = new ProxyTestRecipeType(id("kjs_traversal_target"), sourceType);
        var sourceId = id("kjs_traversal_recipe");
        Map<ResourceLocation, Recipe<?>> recipes = new LinkedHashMap<>();
        recipes.put(sourceId, recipe(sourceType, sourceId));

        MBDRecipeType.onRecipeManagerLoadedKjs(recipes, List.of(target), (receivingType, source) -> 2);

        assertEquals(List.of(1, 2), target.traversals);
        assertEquals(2, ((MBDRecipe) recipes.get(id("kjs_traversal_target/kjs_traversal_recipe"))).priority);
    }

    @Test
    void cyclicProxyConfigurationTerminatesWithFiniteEntries() {
        var sourceType = type("cycle_source");
        var first = new ProxyTestRecipeType(id("cycle_first"), sourceType);
        var second = new ProxyTestRecipeType(id("cycle_second"), first);
        // Add the back edge after construction so the two types form a cycle.
        addProxyType(first, second);
        var sourceId = id("cycle_recipe");
        var recipes = new LinkedHashMap<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>();
        recipes.put(sourceType, new LinkedHashMap<>(Map.of(sourceId, recipe(sourceType, sourceId))));

        MBDRecipeType.onRecipeManagerLoaded(recipes, List.of(second, first));

        // The source, first-level, and second-level recipes are retained; no
        // path can repeat a type after the cycle guard has seen it.
        assertEquals(3, recipes.values().stream().mapToInt(Map::size).sum());
    }

    @Test
    void traversalPolicyIsScopedToReceivingRecipeType() {
        var sourceType = type("target_scoped_source");
        var first = new ProxyTestRecipeType(id("target_scoped_first"), sourceType);
        var second = new ProxyTestRecipeType(id("target_scoped_second"), sourceType);
        var sourceId = id("target_scoped_recipe");
        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipes = new LinkedHashMap<>();
        recipes.put(sourceType, new LinkedHashMap<>(Map.of(sourceId, recipe(sourceType, sourceId))));

        MBDRecipeType.onRecipeManagerLoaded(recipes, List.of(first, second),
                (receivingType, source) -> receivingType == first ? 2 : 1);

        assertEquals(List.of(1, 2), first.traversals);
        assertEquals(List.of(1), second.traversals);
    }

    @Test
    void kubeJsTraversalPolicyIsScopedToReceivingRecipeType() {
        var sourceType = type("kjs_target_scoped_source");
        var first = new ProxyTestRecipeType(id("kjs_target_scoped_first"), sourceType);
        var second = new ProxyTestRecipeType(id("kjs_target_scoped_second"), sourceType);
        var sourceId = id("kjs_target_scoped_recipe");
        Map<ResourceLocation, Recipe<?>> recipes = new LinkedHashMap<>();
        recipes.put(sourceId, recipe(sourceType, sourceId));

        MBDRecipeType.onRecipeManagerLoadedKjs(recipes, List.of(first, second),
                (receivingType, source) -> receivingType == first ? 2 : 1);

        assertEquals(List.of(1, 2), first.traversals);
        assertEquals(List.of(1), second.traversals);
    }

    private static MBDRecipeType type(String path) {
        return new ProxyTestRecipeType(id(path));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mbd2", path);
    }

    private static MBDRecipe recipe(MBDRecipeType type, ResourceLocation id) {
        return new MBDRecipe(type, id, Map.of(), Map.of(), List.of(), new CompoundTag(), 20, false, false, 0);
    }

    @SuppressWarnings("unchecked")
    private static void addProxyType(MBDRecipeType target, RecipeType<?> source) {
        try {
            var field = MBDRecipeType.class.getDeclaredField("proxyRecipeTypes");
            field.setAccessible(true);
            ((List<RecipeType<?>>) field.get(target)).add(source);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to configure cyclic proxy test", e);
        }
    }

    /**
     * Unit tests do not bootstrap Forge registries, while production conversion
     * emits a Forge event when its source type is registered. This test variant
     * preserves the MBD-to-MBD conversion behavior without requiring that
     * runtime registry bootstrap.
     */
    private static final class ProxyTestRecipeType extends MBDRecipeType {
        private final List<Integer> traversals = new java.util.ArrayList<>();

        private ProxyTestRecipeType(ResourceLocation registryName, RecipeType<?>... proxyRecipes) {
            super(registryName, proxyRecipes);
        }

        @Override
        public MBDRecipe toMBDrecipe(RecipeType<?> recipeType, ResourceLocation sourceId, Recipe<?> sourceRecipe, int traversal) {
            traversals.add(traversal);
            if (!(sourceRecipe instanceof MBDRecipe source)
                    || source.recipeType == this
                    || sourceRecipe.getType() == this) {
                return null;
            }
            var targetId = ResourceLocation.fromNamespaceAndPath(
                    getRegistryName().getNamespace(),
                    getRegistryName().getPath() + "/" + sourceId.getPath());
            var converted = source.copy(targetId);
            converted.recipeType = this;
            converted.priority = traversal;
            return converted;
        }
    }
}
