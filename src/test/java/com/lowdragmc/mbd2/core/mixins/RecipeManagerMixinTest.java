package com.lowdragmc.mbd2.core.mixins;

import com.lowdragmc.mbd2.api.recipe.MBDRecipe;
import com.lowdragmc.mbd2.api.recipe.MBDRecipeType;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecipeManagerMixinTest {
    private static final ResourceLocation SOAK_ID = ResourceLocation.fromNamespaceAndPath("croparia", "soak/sculk_sensor");

    @Test
    void reloadedTypeAliasesDoNotConflict() throws Throwable {
        var soakType = externalType();
        var extraType = externalType();
        var soak = new ExternalRecipe(SOAK_ID, soakType);
        var unrelated = new ExternalRecipe(ResourceLocation.fromNamespaceAndPath("croparia", "soak/stone"), soakType);
        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> original = new LinkedHashMap<>();
        original.put(soakType, Map.of(SOAK_ID, soak, unrelated.getId(), unrelated));
        original.put(extraType, Map.of(SOAK_ID, soak));
        // The reload hook copies maps, retaining recipe references in extra type buckets.
        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> copied = new LinkedHashMap<>();
        original.forEach((type, entries) -> copied.put(type, new HashMap<>(entries)));
        var manager = new TestManager();
        manager.set("recipes", copied);

        manager.reload();

        assertEquals(original, manager.recipes());
        assertEquals(Map.of(SOAK_ID, soak, unrelated.getId(), unrelated), manager.byName());
        assertSame(soak, original.get(extraType).get(SOAK_ID));
        assertEquals(2, original.size());
    }

    @Test
    void reloadRetainsDatapackKeysWhenDistinctRecipesReportTheSameId() throws Throwable {
        var type = externalType();
        var first = new ExternalRecipe(SOAK_ID, type);
        var second = new ExternalRecipe(SOAK_ID, type);
        var calibratedId = ResourceLocation.fromNamespaceAndPath("croparia", "soak/calibrated_sculk_sensor");
        // Some serializers keep IDs outside their value objects. Equal recipe
        // values can then report one ID despite having distinct datapack keys.
        assertNotSame(first, second);
        assertEquals(first, second);
        Map<ResourceLocation, Recipe<?>> entries = Map.of(SOAK_ID, first, calibratedId, second);
        var manager = new TestManager();
        manager.set("recipes", Map.of(type, entries));

        manager.reload();

        assertEquals(entries, manager.byName());
        assertSame(first, manager.byName().get(SOAK_ID));
        assertSame(second, manager.byName().get(calibratedId));
        assertEquals(Map.of(type, entries), manager.recipes());
    }

    @Test
    void reloadRetainsDifferentKeysForTheSameRecipeInstance() throws Throwable {
        var recipe = new ExternalRecipe(SOAK_ID, externalType());
        var aliasId = ResourceLocation.fromNamespaceAndPath("croparia", "soak/alias");
        Map<ResourceLocation, Recipe<?>> entries = Map.of(SOAK_ID, recipe, aliasId, recipe);
        var manager = new TestManager();
        manager.set("recipes", Map.of(recipe.getType(), entries));

        manager.reload();

        assertEquals(entries, manager.byName());
        assertEquals(Map.of(recipe.getType(), entries), manager.recipes());
    }

    @Test
    void reloadPreservesLoadingErrorsAndEmptyTypeBucketsAndDropsStaleNames() throws Throwable {
        var recipe = new ExternalRecipe(SOAK_ID, externalType());
        var manager = new TestManager();
        manager.set("recipes", Map.of(recipe.getType(), Map.of(SOAK_ID, recipe)));
        manager.reload();
        assertSame(recipe, manager.byName().get(SOAK_ID));
        manager.set("recipes", Map.of(recipe.getType(), Map.of()));
        manager.set("hasErrors", true);

        manager.reload();

        assertEquals(true, manager.get("hasErrors"));
        assertEquals(Map.of(recipe.getType(), Map.of()), manager.recipes());
        assertTrue(manager.byName().isEmpty());
    }

    @Test
    void reloadResolvesMbdCollisionsWithoutChangingExternalDatapackKeys() throws Throwable {
        var type = externalType();
        var external = new ExternalRecipe(SOAK_ID, type);
        var datapackId = ResourceLocation.fromNamespaceAndPath("croparia", "soak/calibrated_sculk_sensor");
        var proxyType = new MBDRecipeType(ResourceLocation.fromNamespaceAndPath("mbd2", "proxy"));
        var proxy = mbdRecipe(proxyType, datapackId);
        var unique = mbdRecipe(proxyType, ResourceLocation.fromNamespaceAndPath("mbd2", "proxy/unique"));
        for (var externalFirst : List.of(true, false)) {
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> tables = new LinkedHashMap<>();
            tables.put(type, Map.of(datapackId, external));
            tables.put(proxyType, Map.of(datapackId, proxy, unique.getId(), unique));
            if (!externalFirst) {
                tables.remove(type);
                tables.put(type, Map.of(datapackId, external));
            }
            var manager = new TestManager();
            manager.set("recipes", tables);

            manager.reload();

            assertEquals(Map.of(datapackId, external, unique.getId(), unique), manager.byName());
            assertEquals(Map.of(type, Map.of(datapackId, external),
                    proxyType, Map.of(unique.getId(), unique)), manager.recipes());
            assertSame(proxy, tables.get(proxyType).get(datapackId));
        }
    }

    @Test
    void synchronizedRepeatedReferenceIsIdempotent() throws Throwable {
        var soak = new ExternalRecipe(SOAK_ID, externalType());
        var manager = new TestManager();
        manager.set("hasErrors", true);
        var callback = new CallbackInfo("replaceRecipes", true);

        manager.invoke("mbd2$replaceRecipes", new Class<?>[]{Iterable.class, CallbackInfo.class},
                List.of(soak, soak, soak), callback);

        assertTrue(callback.isCancelled());
        assertEquals(Map.of(soak.getType(), Map.of(SOAK_ID, soak)), manager.recipes());
        assertEquals(Map.of(SOAK_ID, soak), manager.byName());
        assertEquals(false, manager.get("hasErrors"));
    }

    @Test
    void distinctExternalRecipesWithSameTypeAndIdStillFail() {
        var type = externalType();
        var first = new ExternalRecipe(SOAK_ID, type);
        var second = new ExternalRecipe(SOAK_ID, type);
        // Equal values are still distinct recipes; identity, not equals(), defines an alias.
        assertEquals(first, second);
        var error = assertThrows(IllegalStateException.class,
                () -> new TestManager().replace(List.of(first, first, second)));
        assertTrue(error.getMessage().contains(SOAK_ID.toString()));
    }

    @Test
    void distinctExternalRecipesWithSameIdAcrossTypesStillFail() {
        var first = new ExternalRecipe(SOAK_ID, externalType());
        var second = new ExternalRecipe(SOAK_ID, externalType());
        var error = assertThrows(IllegalArgumentException.class,
                () -> new TestManager().replace(List.of(first, second)));
        assertTrue(error.getMessage().contains(SOAK_ID.toString()));
    }

    @Test
    void externalRecipeStillWinsAgainstMbdRecipeInEitherOrder() throws Throwable {
        var external = new ExternalRecipe(SOAK_ID, externalType());
        var mbd = mbdRecipe(new MBDRecipeType(ResourceLocation.fromNamespaceAndPath("mbd2", "proxy")), SOAK_ID);
        for (List<Recipe<?>> order : List.<List<Recipe<?>>>of(
                List.of(external, mbd, external), List.of(mbd, external, mbd))) {
            var manager = new TestManager();
            manager.replace(order);
            assertEquals(Map.of(SOAK_ID, external), manager.byName());
            assertEquals(Map.of(external.getType(), Map.of(SOAK_ID, external)), manager.recipes());
        }
    }

    @Test
    void mbdRecipeOwnedByItsTypeStillWinsInEitherOrder() throws Throwable {
        var id = ResourceLocation.fromNamespaceAndPath("mbd2", "owner/sculk_sensor");
        var owner = mbdRecipe(new MBDRecipeType(ResourceLocation.fromNamespaceAndPath("mbd2", "owner")), id);
        var proxy = mbdRecipe(new MBDRecipeType(ResourceLocation.fromNamespaceAndPath("mbd2", "proxy")), id);
        for (List<Recipe<?>> order : List.<List<Recipe<?>>>of(
                List.of(owner, proxy, owner), List.of(proxy, owner, proxy))) {
            var manager = new TestManager();
            manager.replace(order);
            assertEquals(Map.of(id, owner), manager.byName());
            assertEquals(Map.of(owner.getType(), Map.of(id, owner)), manager.recipes());
        }
    }

    @Test
    void repeatedAliasDoesNotRestoreAnOlderMbdRecipe() throws Throwable {
        var type = new MBDRecipeType(ResourceLocation.fromNamespaceAndPath("mbd2", "owner"));
        var id = ResourceLocation.fromNamespaceAndPath("mbd2", "owner/sculk_sensor");
        var first = mbdRecipe(type, id);
        var latest = mbdRecipe(type, id);
        var manager = new TestManager();

        manager.replace(List.of(first, latest, first));

        assertSame(latest, manager.byName().get(id));
        assertSame(latest, manager.recipes().get(type).get(id));
    }

    private static RecipeType<ExternalRecipe> externalType() {
        return new RecipeType<>() {};
    }

    private static MBDRecipe mbdRecipe(MBDRecipeType type, ResourceLocation id) {
        return new MBDRecipe(type, id, Map.of(), Map.of(), List.of(), new CompoundTag(), 20, false, false, 0);
    }

    /** Exercises the actual injected methods without launching a Minecraft client. */
    private static final class TestManager extends RecipeManagerMixin {
        private Object invoke(String name, Class<?>[] parameterTypes, Object... args) throws Throwable {
            var method = RecipeManagerMixin.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            try {
                return method.invoke(this, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        private void replace(Iterable<Recipe<?>> recipes) throws Throwable {
            invoke("mbd2$replaceRecipeTables", new Class<?>[]{Iterable.class}, recipes);
        }

        private void reload() throws Throwable {
            invoke("mbd2$cloneVanillaRecipes",
                    new Class<?>[]{Map.class, ResourceManager.class, ProfilerFiller.class, CallbackInfo.class},
                    Map.of(), null, null, new CallbackInfo("apply", false));
        }

        private void set(String name, Object value) throws ReflectiveOperationException {
            var field = RecipeManagerMixin.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(this, value);
        }

        private Object get(String name) throws ReflectiveOperationException {
            var field = RecipeManagerMixin.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(this);
        }

        @SuppressWarnings("unchecked")
        private Map<ResourceLocation, Recipe<?>> byName() throws ReflectiveOperationException {
            return (Map<ResourceLocation, Recipe<?>>) get("byName");
        }

        @SuppressWarnings("unchecked")
        private Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipes() throws ReflectiveOperationException {
            return (Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>) get("recipes");
        }
    }

    private record ExternalRecipe(ResourceLocation id, RecipeType<?> type) implements Recipe<Container> {
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeType<?> getType() { return type; }
        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public ItemStack assemble(Container container, RegistryAccess registries) { return ItemStack.EMPTY; }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public ItemStack getResultItem(RegistryAccess registries) { return ItemStack.EMPTY; }
        @Override public RecipeSerializer<?> getSerializer() { throw new UnsupportedOperationException(); }
    }
}
