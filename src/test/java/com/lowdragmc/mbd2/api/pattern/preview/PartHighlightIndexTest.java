package com.lowdragmc.mbd2.api.pattern.preview;

import com.lowdragmc.lowdraglib.utils.BlockInfo;
import com.lowdragmc.mbd2.XeiTestBootstrap;
import com.lowdragmc.mbd2.api.pattern.TraceabilityPredicate;
import com.lowdragmc.mbd2.api.pattern.predicates.SimplePredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PartHighlightIndexTest {
    @BeforeAll static void bootstrap() { XeiTestBootstrap.init(); }

    static SimplePredicate candidates(List<ItemStack> stacks, AtomicInteger calls) {
        return new SimplePredicate(state -> true, () -> new BlockInfo[0]) {
            @Override public List<ItemStack> getCandidates() { calls.incrementAndGet(); return stacks; }
            @Override public List<Component> getCandidateToolTips(TraceabilityPredicate trace, ItemStack stack) {
                return List.of(Component.literal("max=" + maxCount), Component.literal("branches=" + trace.common.size()));
            }
        };
    }

    @Test void itemIdentityIgnoresCountButKeepsNbtSnapshot() {
        var stack = new ItemStack(Items.STONE, 7);
        var tag = new CompoundTag();
        tag.putString("mode", "first");
        stack.setTag(tag);
        var key = PartHighlightIndex.ItemKey.of(stack);
        stack.setCount(1);
        assertEquals(key, PartHighlightIndex.ItemKey.of(stack));
        tag.putString("mode", "second");
        assertNotEquals(key, PartHighlightIndex.ItemKey.of(stack));
        key.tag().putString("mode", "third");
        assertEquals("first", key.tag().getString("mode"));
    }

    @Test void mergesPositionsAndBranchesWithoutMixingNbtOrControllerRegions() {
        var a = new ItemStack(Items.STONE, 12);
        a.getOrCreateTag().putString("variant", "a");
        var b = a.copy(); b.getOrCreateTag().putString("variant", "b");
        var calls = new AtomicInteger();
        var branch = candidates(List.of(a, b, ItemStack.EMPTY, new ItemStack(Items.DIRT)), calls);
        branch.maxCount = 2;
        branch.minLayerCount = 1;
        var trace = new TraceabilityPredicate(branch);
        trace.common.add(branch); // Same branch in both lists must not duplicate positions or hints.
        var controllerTrace = new TraceabilityPredicate(branch); controllerTrace.isController = true;
        var p = new BlockPos.MutableBlockPos(1, 0, 0);
        var q = new BlockPos(2, 0, 0);
        var excluded = new BlockPos(3, 0, 0);
        var outside = new BlockPos(100, 0, 0);
        var predicates = new LinkedHashMap<BlockPos, TraceabilityPredicate>();
        predicates.put(p, trace); predicates.put(q, trace); predicates.put(BlockPos.ZERO, trace);
        predicates.put(excluded, controllerTrace); predicates.put(outside, trace);
        var shape = new HashSet<>(List.of(p, q, BlockPos.ZERO, excluded));
        var index = PartHighlightIndex.build(predicates, shape, BlockPos.ZERO, s -> s.is(Items.STONE));
        assertEquals(PartHighlightIndex.Status.READY, index.status());
        assertEquals(2, index.entries().size());
        assertEquals(1, calls.get());
        assertEquals(Set.of(p.immutable(), q), index.entries().get(0).positions());
        assertEquals(1, index.entries().get(0).displayStack().getCount());
        assertEquals(1, index.entries().get(0).hints().size());
        assertEquals(3, index.entries().get(0).hints().get(0).lines().size());
        assertEquals("a", index.entries().get(0).key().tag().getString("variant"));
        p.set(900, 0, 0); a.setCount(30); a.getOrCreateTag().putString("variant", "changed");
        predicates.clear(); shape.clear();
        var returned = index.entries().get(0).displayStack(); returned.setCount(50); returned.getOrCreateTag().putString("variant", "mutated");
        assertEquals(Set.of(new BlockPos(1, 0, 0), q), index.entries().get(0).positions());
        assertEquals("a", index.entries().get(0).displayStack().getTag().getString("variant"));
    }

    @Test void distinguishesUnmatchedAndMatchedEmptyAndFiltersOrdinaryItems() {
        assertEquals(PartHighlightIndex.Status.UNAVAILABLE, PartHighlightIndex.build(null, Set.of(), BlockPos.ZERO).status());
        assertEquals(PartHighlightIndex.Status.EMPTY, PartHighlightIndex.build(Map.of(), Set.of(), BlockPos.ZERO).status());
        var trace = new TraceabilityPredicate(candidates(List.of(new ItemStack(Items.STONE)), new AtomicInteger()));
        var p = new BlockPos(1, 0, 0);
        assertTrue(PartHighlightIndex.build(Map.of(p, trace), Set.of(p), BlockPos.ZERO).entries().isEmpty());
        var noCandidates = new TraceabilityPredicate(new SimplePredicate(state -> true, null));
        assertTrue(PartHighlightIndex.build(Map.of(p, noCandidates), Set.of(p), BlockPos.ZERO, s -> true).entries().isEmpty());
    }

    @Test void onlyEnabledMbdMachinePartsAreIncluded() {
        var definition = com.lowdragmc.mbd2.common.machine.definition.MBDMachineDefinition.builder()
                .partSettings(() -> com.lowdragmc.mbd2.common.machine.definition.config.ConfigPartSettings.builder().enable(true).build())
                .build();
        definition.loadFactory();
        var blocks = (net.minecraftforge.registries.ForgeRegistry<net.minecraft.world.level.block.Block>) net.minecraftforge.registries.ForgeRegistries.BLOCKS;
        var items = (net.minecraftforge.registries.ForgeRegistry<net.minecraft.world.item.Item>) net.minecraftforge.registries.ForgeRegistries.ITEMS;
        boolean blocksLocked = blocks.isLocked(), itemsLocked = items.isLocked();
        blocks.unfreeze(); items.unfreeze();
        ((net.minecraft.core.MappedRegistry<?>) net.minecraft.core.registries.BuiltInRegistries.BLOCK).unfreeze();
        ((net.minecraft.core.MappedRegistry<?>) net.minecraft.core.registries.BuiltInRegistries.ITEM).unfreeze();
        com.lowdragmc.mbd2.common.machine.definition.MBDMachineDefinition.set(definition);
        try {
            var block = new com.lowdragmc.mbd2.common.block.MBDMachineBlock(
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties.of(), definition);
            var item = new net.minecraft.world.item.BlockItem(block, new net.minecraft.world.item.Item.Properties());
            var fixtureId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("xei_test", "enabled_part_fixture");
            net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.BLOCK, fixtureId, block);
            net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM, fixtureId, item);
            var trace = new TraceabilityPredicate(candidates(List.of(new ItemStack(item)), new AtomicInteger()));
            var pos = new BlockPos(1, 0, 0);
            assertEquals(PartHighlightIndex.Status.READY, PartHighlightIndex.build(Map.of(pos, trace), Set.of(pos), BlockPos.ZERO).status());
            definition.partSettings().setEnable(false);
            assertEquals(PartHighlightIndex.Status.EMPTY, PartHighlightIndex.build(Map.of(pos, trace), Set.of(pos), BlockPos.ZERO).status());
        } finally {
            com.lowdragmc.mbd2.common.machine.definition.MBDMachineDefinition.clear();
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.freeze();
            net.minecraft.core.registries.BuiltInRegistries.ITEM.freeze();
            if (blocksLocked) blocks.freeze();
            if (itemsLocked) items.freeze();
        }
    }

    @Test void retainsSeparateSourceConstraintsAndTraceContext() {
        var stack = new ItemStack(Items.STONE);
        var a = candidates(List.of(stack), new AtomicInteger()); a.maxCount = 2;
        var b = candidates(List.of(stack), new AtomicInteger()); b.maxCount = 5;
        var one = new TraceabilityPredicate(a);
        var two = new TraceabilityPredicate(a); two.common.add(b);
        var p = new BlockPos(1, 0, 0); var q = new BlockPos(2, 0, 0);
        var index = PartHighlightIndex.build(Map.of(p, one, q, two), Set.of(p, q), BlockPos.ZERO, s -> true);
        assertEquals(3, index.entries().get(0).hints().size());
        assertTrue(index.entries().get(0).hints().stream().flatMap(h -> h.lines().stream()).anyMatch(c -> c.getString().equals("max=5")));
    }

    @Test void canonicalNbtAndInputOrderProduceStableEntries() {
        var a = new ItemStack(Items.STONE); a.getOrCreateTag().putString("a", "1"); a.getOrCreateTag().putInt("z", 2);
        var b = new ItemStack(Items.STONE); b.getOrCreateTag().putInt("z", 2); b.getOrCreateTag().putString("a", "1");
        var c = new ItemStack(Items.STONE); c.getOrCreateTag().putString("a", "2");
        var p = new BlockPos(1, 0, 0);
        var first = PartHighlightIndex.build(Map.of(p, new TraceabilityPredicate(candidates(List.of(c, b, a), new AtomicInteger()))), Set.of(p), BlockPos.ZERO, s -> true);
        var second = PartHighlightIndex.build(Map.of(p, new TraceabilityPredicate(candidates(List.of(a, c, b), new AtomicInteger()))), Set.of(p), BlockPos.ZERO, s -> true);
        assertEquals(2, first.entries().size());
        assertEquals(first.entries().stream().map(PartHighlightEntry::key).toList(), second.entries().stream().map(PartHighlightEntry::key).toList());
    }

    @Test void thousandPositionsExpandUniquePredicateOnce() {
        var calls = new AtomicInteger();
        var stacks = new ArrayList<ItemStack>();
        for (int i = 0; i < 100; i++) { var stack = new ItemStack(Items.STONE); stack.getOrCreateTag().putInt("part", i); stacks.add(stack); }
        var trace = new TraceabilityPredicate(candidates(stacks, calls));
        var positions = new HashMap<BlockPos, TraceabilityPredicate>();
        for (int i = 1; i <= 1000; i++) positions.put(new BlockPos(i, 0, 0), trace);
        long start = System.nanoTime();
        var index = PartHighlightIndex.build(positions, positions.keySet(), BlockPos.ZERO, s -> true);
        long buildNanos = System.nanoTime() - start;
        assertEquals(100, index.entries().size());
        assertEquals(1, calls.get());
        assertTrue(index.entries().stream().allMatch(e -> e.positions().size() == 1000));
        var session = new com.lowdragmc.mbd2.client.xei.PatternPartHighlightSession();
        session.bind(() -> index); session.updateVisiblePositions(positions.keySet());
        session.updateHover(index.entries().get(0).key());
        var first = session.highlightedPositions();
        start = System.nanoTime();
        for (int i = 0; i < 10000; i++) { session.updateHover(index.entries().get(0).key()); assertSame(first, session.highlightedPositions()); }
        System.out.printf("XEI DATA BENCH: 1000 positions/100 variants build=%.3f ms; cached hover=%.3f us/op (no renderer)%n", buildNanos / 1e6, (System.nanoTime() - start) / 1e7);
    }
}
