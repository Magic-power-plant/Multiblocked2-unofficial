package com.lowdragmc.mbd2.api.pattern.preview;

import com.lowdragmc.mbd2.client.xei.PatternPartHighlightSession;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.lowdragmc.mbd2.XeiTestBootstrap;
import com.lowdragmc.mbd2.api.pattern.TraceabilityPredicate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PartHighlightSessionTest {
    @BeforeAll static void bootstrap() { XeiTestBootstrap.init(); }

    @Test void visibleLayersMasksAndPageRebindCannotLeakHighlights() {
        var a = new BlockPos(1, 0, 0); var b = new BlockPos(2, 1, 0); var c = new BlockPos(3, 1, 0);
        var predicate = new TraceabilityPredicate(PartHighlightIndexTest.candidates(java.util.List.of(new ItemStack(Items.STONE)), new java.util.concurrent.atomic.AtomicInteger()));
        var index = PartHighlightIndex.build(Map.of(a, predicate, b, predicate, c, predicate), Set.of(a, b, c), BlockPos.ZERO, s -> true);
        var session = new PatternPartHighlightSession();
        assertEquals(AbstractPartHighlightSession.State.UNBOUND, session.state());
        session.bind(() -> index);
        assertEquals(AbstractPartHighlightSession.State.READY, session.state());
        session.updateVisiblePositions(Set.of(b)); // Layer 1, with c masked by formed rendering.
        session.updateHover(index.entries().get(0).key());
        assertEquals(Set.of(b), session.highlightedPositions());
        session.updateVisiblePositions(Set.of());
        assertNull(session.hoveredEntry());
        session.updateHover(index.entries().get(0).key());
        assertTrue(session.highlightedPositions().isEmpty());
        assertNotNull(session.hoveredEntry());
        session.updateHover(null);
        assertNull(session.hoveredEntry());
        session.bind(PartHighlightIndex::unavailable);
        assertEquals(AbstractPartHighlightSession.State.UNAVAILABLE, session.state());
        assertTrue(session.visiblePositions().isEmpty());
        session.invalidate();
        assertEquals(AbstractPartHighlightSession.State.UNBOUND, session.state());
        assertNull(session.source());
    }
    @Test
    void bindingAndInvalidationAreIsolatedPerPage() {
        var one = new PatternPartHighlightSession();
        var two = new PatternPartHighlightSession();
        one.bind(() -> PartHighlightIndex.build(Map.of(BlockPos.ZERO, new com.lowdragmc.mbd2.api.pattern.TraceabilityPredicate()),
                Set.of(BlockPos.ZERO), BlockPos.ZERO));
        two.bind(PartHighlightIndex::unavailable);
        one.updateVisiblePositions(Set.of(BlockPos.ZERO));
        assertEquals(PartHighlightIndex.Status.EMPTY, one.index().status());
        assertEquals(PartHighlightIndex.Status.UNAVAILABLE, two.index().status());
        one.invalidate();
        assertEquals(PartHighlightIndex.Status.UNAVAILABLE, one.index().status());
        assertTrue(one.highlightedPositions().isEmpty());
    }
}
