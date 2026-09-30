package com.lowdragmc.mbd2.common.xei;

import com.lowdragmc.mbd2.XeiTestBootstrap;
import com.lowdragmc.mbd2.common.machine.definition.MBDMachineDefinition;
import com.lowdragmc.mbd2.common.machine.definition.config.ConfigRecipeLogicSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.*;

class XeiCatalystSettingsTest {
    @org.junit.jupiter.api.Test
    void rejectsImplicitOrEmptyNamespaceAndPathWithoutChangingOldGroup() {
        var settings = ConfigRecipeLogicSettings.builder().build();
        settings.setXeiCatalystGroup("test:valid");
        for (var invalid : java.util.List.of("implicit", ":path", "space:", "A:b", "a:b:c", "a:b c")) {
            assertThrows(IllegalArgumentException.class, () -> settings.setXeiCatalystGroup(invalid));
            assertEquals("test:valid", settings.xeiCatalystGroup());
        }
        settings.setXeiCatalystGroup("  ");
        assertEquals("", settings.xeiCatalystGroup());
    }
    @BeforeAll static void bootstrap() { XeiTestBootstrap.init(); }
    @Test
    void roundTripsAndResetsMissingFieldsOnExistingInstance() {
        var config = ConfigRecipeLogicSettings.builder().build();
        config.setXeiCatalystGroup(" test:machines ");
        config.setXeiCatalystPriority(Integer.MIN_VALUE);
        var snapshot = config.serializeNBT();
        var restored = ConfigRecipeLogicSettings.builder().build();
        restored.deserializeNBT(snapshot);
        assertEquals("test:machines", restored.xeiCatalystGroup());
        assertEquals(Integer.MIN_VALUE, restored.xeiCatalystPriority());
        restored.deserializeNBT(new CompoundTag());
        assertEquals("", restored.xeiCatalystGroup());
        assertEquals(0, restored.xeiCatalystPriority());
    }

    @Test
    void builderRejectsInvalidGroupsWithoutDiscardingRecipeTypes() {
        var type = ResourceLocation.fromNamespaceAndPath("test", "recipe");
        var settings = ConfigRecipeLogicSettings.builder().recipeTypes(new ResourceLocation[]{type}).build();
        var builder = MBDMachineDefinition.builder().id(ResourceLocation.fromNamespaceAndPath("test", "machine"))
                .recipeLogicSettings(settings);
        assertThrows(IllegalArgumentException.class, () -> builder.xeiCatalystGroup("missing_namespace"));
        builder.xeiCatalystGroup("test:group").xeiCatalystPriority(7);
        assertArrayEquals(new ResourceLocation[]{type}, builder.build().recipeLogicSettings().recipeTypes());
    }
}
