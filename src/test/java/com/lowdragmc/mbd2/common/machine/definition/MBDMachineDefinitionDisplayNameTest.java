package com.lowdragmc.mbd2.common.machine.definition;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.mbd2.common.machine.definition.config.ConfigMultiblockSettings;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MBDMachineDefinitionDisplayNameTest {

    @Test
    void usesConfiguredNameOnlyWhenTranslationIsMissing() throws ReflectiveOperationException {
        Component localized = Component.literal("Localized machine");
        assertSame(localized, MBDMachineDefinition.resolveDisplayNameFallback(
                localized, "machine.example", ""));
        assertEquals("Fallback machine", MBDMachineDefinition.resolveDisplayNameFallback(
                localized, "machine.example", "Fallback machine").getString());
    }

    @Test
    void displayNameIsConfigurableAndPersisted() throws ReflectiveOperationException {
        Field field = MBDMachineDefinition.class.getDeclaredField("displayName");

        assertTrue(field.isAnnotationPresent(Persisted.class));
        Configurable configurable = field.getAnnotation(Configurable.class);
        assertEquals("config.definition.display_name", configurable.name());
    }

    @Test
    void multiblockRenderSettingDefaultsToControllerVisible() {
        assertFalse(ConfigMultiblockSettings.builder().build().disableRenderFormed());
        assertTrue(ConfigMultiblockSettings.builder().disableRenderFormed(true).build().disableRenderFormed());
    }

}
