package com.lowdragmc.mbd2.common.xei;

import com.lowdragmc.mbd2.XeiTestBootstrap;
import com.lowdragmc.mbd2.api.recipe.MBDRecipeType;
import com.lowdragmc.mbd2.api.registry.MBDRegistries;
import com.lowdragmc.mbd2.common.machine.definition.MBDMachineDefinition;
import com.lowdragmc.mbd2.common.machine.definition.config.ConfigRecipeLogicSettings;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XeiCatalystResolverTest {
    @BeforeAll static void bootstrap() { XeiTestBootstrap.init(); }

    @Test void invalidItemsFallBackAndDuplicatesMergeMemberMetadata() throws Exception {
        var type = new MBDRecipeType(id("fallback"));
        MBDRegistries.RECIPE_TYPES.unfreeze();
        try { MBDRegistries.RECIPE_TYPES.register(type.getRegistryName(), type); }
        finally { MBDRegistries.RECIPE_TYPES.freeze(); }
        try {
            var high = machine("high", null, "test:group", Integer.MAX_VALUE, type);
            var air = machine("air", Items.AIR, "test:group", 100, type);
            var valid = machine("valid", Items.STONE, "test:group", Integer.MIN_VALUE, type);
            var duplicate = machine("duplicate", Items.STONE, "", 0, type);
            var noValid = machine("no_valid", null, "test:empty", 0, type);
            var output = XeiCatalystResolver.resolve(type, List.of(high, air, valid, duplicate, noValid));
            assertEquals(1, output.size());
            assertEquals(java.util.Set.of(valid.id(), duplicate.id()), new java.util.HashSet<>(output.get(0).members()));
            assertEquals(1, output.get(0).stack().getCount());
            output.get(0).stack().setCount(50);
            assertEquals(1, output.get(0).stack().getCount());
        } finally { MBDRegistries.RECIPE_TYPES.remove(type.getRegistryName()); }
    }

    @Test void defaultsRemainIndependentAndBucketKindsCannotCollide() throws Exception {
        var type = new MBDRecipeType(id("defaults"));
        MBDRegistries.RECIPE_TYPES.unfreeze();
        try { MBDRegistries.RECIPE_TYPES.register(type.getRegistryName(), type); }
        finally { MBDRegistries.RECIPE_TYPES.freeze(); }
        try {
            var self = machine("same_name", Items.STONE, "", 0, type);
            var grouped = machine("grouped", Items.DIRT, self.id().toString(), 0, type);
            var another = machine("other", Items.GRANITE, "   ", 0, type);
            var output = XeiCatalystResolver.resolve(type, List.of(grouped, another, self));
            assertEquals(3, output.size());
            var oldTag = grouped.recipeLogicSettings().serializeNBT(); oldTag.putString("xeiCatalystGroup", "BAD ID");
            grouped.recipeLogicSettings().deserializeNBT(oldTag);
            output = XeiCatalystResolver.resolve(type, List.of(grouped, another, self));
            assertEquals(3, output.size());
            assertTrue(output.stream().allMatch(c -> c.group().isEmpty()));
        } finally { MBDRegistries.RECIPE_TYPES.remove(type.getRegistryName()); }
    }
    @Test
    void selectsPerTypeDeterministicallyAndRetainsUngroupedMachines() throws Exception {
        var t1 = new MBDRecipeType(id("type_one"));
        var t2 = new MBDRecipeType(id("type_two"));
        MBDRegistries.RECIPE_TYPES.unfreeze();
        try {
            MBDRegistries.RECIPE_TYPES.register(t1.getRegistryName(), t1);
            MBDRegistries.RECIPE_TYPES.register(t2.getRegistryName(), t2);
        } finally {
            MBDRegistries.RECIPE_TYPES.freeze();
        }
        try {
            var a = machine("a", Items.STONE, "test:group", Integer.MIN_VALUE, t1);
            var b = machine("b", Items.DIRT, "test:group", Integer.MAX_VALUE, t2);
            var c = machine("c", Items.GRANITE, "", 0, t1);
            var d = machine("d", Items.ANDESITE, "test:group", Integer.MIN_VALUE, t1);
            assertEquals(List.of(c.id(), a.id()), XeiCatalystResolver.resolve(t1, List.of(d, c, a, b)).stream()
                    .map(XeiCatalystResolver.ResolvedCatalyst::representative).toList());
            assertEquals(List.of(b.id()), XeiCatalystResolver.resolve(t2, List.of(a, b, c, d)).stream()
                    .map(XeiCatalystResolver.ResolvedCatalyst::representative).toList());
            assertEquals(a.id(), XeiCatalystResolver.resolve(t1, List.of(a, b, c, d)).get(1).representative());
        } finally {
            MBDRegistries.RECIPE_TYPES.remove(t1.getRegistryName());
            MBDRegistries.RECIPE_TYPES.remove(t2.getRegistryName());
        }
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("xei_test", path); }

    private static MBDMachineDefinition machine(String path, Item item, String group, int priority,
                                                MBDRecipeType type) throws Exception {
        var settings = ConfigRecipeLogicSettings.builder().recipeTypes(new ResourceLocation[]{type.getRegistryName()}).build();
        settings.setXeiCatalystGroup(group);
        settings.setXeiCatalystPriority(priority);
        var machine = MBDMachineDefinition.builder().id(id(path)).recipeLogicSettings(settings).build();
        Field field = MBDMachineDefinition.class.getDeclaredField("item");
        field.setAccessible(true);
        field.set(machine, item);
        return machine;
    }
}
