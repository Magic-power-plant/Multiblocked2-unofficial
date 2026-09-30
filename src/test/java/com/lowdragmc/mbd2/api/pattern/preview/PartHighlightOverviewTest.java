package com.lowdragmc.mbd2.api.pattern.preview;

import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.mbd2.XeiTestBootstrap;
import com.lowdragmc.mbd2.api.pattern.TraceabilityPredicate;
import com.lowdragmc.mbd2.client.xei.PartHighlightOverviewWidget;
import com.lowdragmc.mbd2.client.xei.PatternPartHighlightSession;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PartHighlightOverviewTest {
    @BeforeAll static void bootstrap() { XeiTestBootstrap.init(); }

    @Test void absoluteHitTestingScrollAndClickRoutingStayWithinViewport() {
        var stacks = new ArrayList<ItemStack>();
        for (int i = 0; i < 7; i++) { var stack = new ItemStack(Items.STONE); stack.getOrCreateTag().putInt("row", i); stacks.add(stack); }
        var pos = new BlockPos(1, 0, 0);
        var predicate = new TraceabilityPredicate(PartHighlightIndexTest.candidates(stacks, new AtomicInteger()));
        var index = PartHighlightIndex.build(Map.of(pos, predicate), Set.of(pos), BlockPos.ZERO, s -> true);
        var session = new PatternPartHighlightSession();
        session.bind(() -> index); session.updateVisiblePositions(Set.of(pos));
        var widget = new PartHighlightOverviewWidget(session, ResourceLocation.fromNamespaceAndPath("test", "controller"));
        var parent = new WidgetGroup(100, 50, 186, 160);
        parent.addWidget(widget);
        widget.updateHover(237, 60);
        assertEquals(index.entries().get(0), session.hoveredEntry());
        assertFalse(widget.mouseClicked(237, 60, 0));
        assertTrue(widget.mouseWheelMove(237, 60, -1));
        assertEquals(index.entries().get(1), session.hoveredEntry());
        assertFalse(widget.mouseWheelMove(235, 60, -1));
        widget.updateHover(257, 60); // Scrollbar is not an item.
        assertNull(session.hoveredEntry());
        widget.updateHover(237, 150); // Below the viewport.
        assertNull(session.hoveredEntry());
        widget.reset(); widget.updateHover(237, 60);
        assertEquals(index.entries().get(0), session.hoveredEntry());
        parent.setVisible(false); widget.updateHover(237, 60);
        assertNull(session.hoveredEntry());
    }
}