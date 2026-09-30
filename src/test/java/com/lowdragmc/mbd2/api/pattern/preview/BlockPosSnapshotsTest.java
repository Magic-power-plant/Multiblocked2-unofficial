package com.lowdragmc.mbd2.api.pattern.preview;

import com.lowdragmc.mbd2.XeiTestBootstrap;
import com.lowdragmc.mbd2.api.pattern.TraceabilityPredicate;
import com.lowdragmc.mbd2.api.pattern.predicates.SimplePredicate;
import com.lowdragmc.mbd2.client.xei.PatternPartHighlightSession;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BlockPosSnapshotsTest {
    @BeforeAll static void bootstrap() { XeiTestBootstrap.init(); }

    @Test void snapshotCopiesMutablePositionsAndRejectsMutation() {
        var mutable = new BlockPos.MutableBlockPos(3, 7, 11);
        var input = new ArrayList<BlockPos>(List.of(mutable));
        var snapshot = BlockPosSnapshots.copyOf(input);
        mutable.set(9, 9, 9); input.clear();
        assertEquals(Set.of(new BlockPos(3, 7, 11)), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(BlockPos.ZERO));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.iterator().remove());
    }

    @Test @Timeout(10)
    void denseCuboidRegistersIndexAndBindsSessionWithoutQuadraticHashProbing() {
        var part = new ItemStack(Items.STONE);
        var branch = new SimplePredicate(state -> true, () -> new BlockInfo[0]) {
            @Override public List<ItemStack> getCandidates() { return List.of(part); }
            @Override public List<Component> getCandidateToolTips(TraceabilityPredicate predicate, ItemStack stack) { return List.of(); }
        };
        var predicate = new TraceabilityPredicate(branch);
        var shape = new HashMap<BlockPos, TraceabilityPredicate>();
        for (int x = 0; x < 48; x++) {
            for (int y = 0; y < 48; y++) {
                for (int z = 0; z < 48; z++) shape.put(new BlockPos(500 + x, 50 + y, 500 + z), predicate);
            }
        }
        long start = System.nanoTime();
        var shapeSnapshot = BlockPosSnapshots.copyOf(shape.keySet());
        var index = PartHighlightIndex.build(shape, shapeSnapshot, BlockPos.ZERO, stack -> true);
        assertEquals(1, index.entries().size());
        var session = new PatternPartHighlightSession();
        session.bind(() -> index);
        session.updateVisiblePositions(shapeSnapshot);
        session.updateHover(index.entries().get(0).key());
        assertEquals(48 * 48 * 48, session.highlightedPositions().size());
        assertTrue(session.highlightedPositions().contains(new BlockPos(547, 97, 547)));
        shape.clear();
        assertEquals(48 * 48 * 48, shapeSnapshot.size());
        System.out.printf("XEI dense cuboid: 110592 positions, index and hover binding %.3f ms%n", (System.nanoTime() - start) / 1e6);
    }
}