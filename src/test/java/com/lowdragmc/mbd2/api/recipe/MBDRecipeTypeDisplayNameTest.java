package com.lowdragmc.mbd2.api.recipe;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MBDRecipeTypeDisplayNameTest {

    @Test
    void usesConfiguredNameOnlyWhenTranslationIsMissing() {
        var recipeType = new MBDRecipeType(ResourceLocation.fromNamespaceAndPath("mbd2", "test_recipe_type"));
        Component localized = Component.literal("Localized recipe type");

        assertSame(localized, recipeType.getDisplayName(localized, "recipe.mbd2.test_recipe_type"));

        recipeType.setDisplayName("Fallback recipe type");
        assertEquals("Fallback recipe type", recipeType.getDisplayName(
                localized, "recipe.mbd2.test_recipe_type").getString());
    }

    @Test
    void displayNameIsConfigurableAndPersisted() throws ReflectiveOperationException {
        Field field = MBDRecipeType.class.getDeclaredField("displayName");

        assertTrue(field.isAnnotationPresent(Persisted.class));
        Configurable configurable = field.getAnnotation(Configurable.class);
        assertEquals("recipe_type.display_name", configurable.name());
    }

}
