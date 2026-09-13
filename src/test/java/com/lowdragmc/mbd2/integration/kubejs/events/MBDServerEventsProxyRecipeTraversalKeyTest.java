package com.lowdragmc.mbd2.integration.kubejs.events;

import com.lowdragmc.mbd2.api.recipe.MBDRecipeType;
import com.lowdragmc.mbd2.api.recipe.event.TransferProxyRecipeEvent;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MBDServerEventsProxyRecipeTraversalKeyTest {

    @Test
    void parsesReceivingRecipeTypeAndTraversal() {
        var key = MBDServerEvents.ProxyRecipeTraversalKey.of("mbd2:raw_recipe_type@2");

        assertEquals(ResourceLocation.fromNamespaceAndPath("mbd2", "raw_recipe_type"), key.recipeTypeId());
        assertEquals(2, key.traversal());
        assertEquals("mbd2:raw_recipe_type@2", key.toString());
    }

    @Test
    void defaultsMissingTraversalToFirstTraversal() {
        var key = MBDServerEvents.ProxyRecipeTraversalKey.of("mbd2:raw_recipe_type");

        assertEquals(ResourceLocation.fromNamespaceAndPath("mbd2", "raw_recipe_type"), key.recipeTypeId());
        assertEquals(1, key.traversal());
        assertEquals("mbd2:raw_recipe_type@1", key.toString());
    }

    @Test
    void rejectsInvalidTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> MBDServerEvents.ProxyRecipeTraversalKey.of("mbd2:raw_recipe_type@0"));
    }

    @Test
    void routesByReceivingMbdRecipeTypeInsteadOfExternalSourceType() {
        var target = new MBDRecipeType(ResourceLocation.fromNamespaceAndPath("mbd2", "fluid_gas"));
        var source = new MBDRecipeType(ResourceLocation.fromNamespaceAndPath("mekanism", "rotary"));
        var event = new TransferProxyRecipeEvent(target, source.getRegistryName(), source,
                ResourceLocation.fromNamespaceAndPath("mekanism", "water"), null, 2, null);

        var key = MBDServerEvents.ProxyRecipeTraversalKey.from(event);

        assertEquals(target.getRegistryName(), key.recipeTypeId());
        assertEquals(2, key.traversal());
    }
}
