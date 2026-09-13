package com.lowdragmc.mbd2.api.recipe.content;

import com.lowdragmc.mbd2.api.capability.recipe.IO;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ContentTooltipTest {

    @Test
    void perTickTooltipUsesTheDisplayedRecipeSide() {
        var content = new Content("test", true, 1, 0);

        assertPerTickTooltipKey(content, IO.IN, "mbd2.gui.content.per_tick_input");
        assertPerTickTooltipKey(content, IO.OUT, "mbd2.gui.content.per_tick_output");
        assertPerTickTooltipKey(content, IO.BOTH, "mbd2.gui.content.per_tick");
        assertPerTickTooltipKey(content, null, "mbd2.gui.content.per_tick");
    }

    private static void assertPerTickTooltipKey(Content content, IO io, String expectedKey) {
        var tooltips = new ArrayList<Component>();
        content.appendTooltip(tooltips, false, io);

        var translation = assertInstanceOf(TranslatableContents.class, tooltips.get(0).getContents());
        assertEquals(expectedKey, translation.getKey());
    }
}
