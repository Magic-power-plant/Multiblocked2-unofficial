package com.lowdragmc.mbd2.api.pattern.preview;

import com.lowdragmc.mbd2.api.pattern.TraceabilityPredicate;
import com.lowdragmc.mbd2.api.pattern.predicates.SimplePredicate;
import com.lowdragmc.mbd2.common.block.MBDMachineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Immutable per-shape index; coordinates refer to the shape's own dummy world. */
public final class PartHighlightIndex {
    public enum Status { READY, EMPTY, UNAVAILABLE }

    public record ItemKey(ResourceLocation item, CompoundTag tag) {
        public ItemKey {
            tag = tag == null ? null : tag.copy();
        }
        public static ItemKey of(ItemStack stack) {
            return new ItemKey(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getTag());
        }
        @Override public CompoundTag tag() { return tag == null ? null : tag.copy(); }
    }

    public record ConstraintHint(String source, List<Component> lines) {
        public ConstraintHint { lines = ComponentSnapshots.copy(lines); }
        @Override public List<Component> lines() { return ComponentSnapshots.copy(lines); }
    }

    private final Status status;
    private final List<PartHighlightEntry> entries;
    private final Map<ItemKey, PartHighlightEntry> byKey;

    private PartHighlightIndex(Status status, List<PartHighlightEntry> entries) {
        this.status = status;
        this.entries = List.copyOf(entries);
        var map = new LinkedHashMap<ItemKey, PartHighlightEntry>();
        entries.forEach(entry -> map.put(entry.key(), entry));
        byKey = Map.copyOf(map);
    }

    public static PartHighlightIndex unavailable() { return new PartHighlightIndex(Status.UNAVAILABLE, List.of()); }
    public Status status() { return status; }
    public List<PartHighlightEntry> entries() { return entries; }
    public PartHighlightEntry get(ItemKey key) { return key == null ? null : byKey.get(key); }

    public static PartHighlightIndex build(Map<BlockPos, TraceabilityPredicate> predicates,
                                           Set<BlockPos> shape, BlockPos controller) {
        return build(predicates, shape, controller, PartHighlightIndex::isEnabledPart);
    }

    static PartHighlightIndex build(Map<BlockPos, TraceabilityPredicate> predicates,
                                    Set<BlockPos> shape, BlockPos controller, Predicate<ItemStack> accepted) {
        if (predicates == null || shape == null) return unavailable();
        Map<SimplePredicate, List<ItemStack>> candidates = new IdentityHashMap<>();
        Map<TraceabilityPredicate, Map<SimplePredicate, Map<ItemKey, ConstraintHint>>> hintCache = new IdentityHashMap<>();
        Map<ItemKey, MutableEntry> collected = new LinkedHashMap<>();
        var orderedPositions = predicates.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<BlockPos, TraceabilityPredicate> e) -> e.getKey().getX())
                        .thenComparingInt(e -> e.getKey().getY()).thenComparingInt(e -> e.getKey().getZ()))
                .toList();
        Map<SimplePredicate, Integer> sources = new IdentityHashMap<>();
        for (var position : orderedPositions) {
            BlockPos pos = position.getKey();
            var trace = position.getValue();
            if (!shape.contains(pos) || pos.equals(controller) || trace == null || trace.isController) continue;
            var branches = new ArrayList<SimplePredicate>(trace.common);
            branches.addAll(trace.limited);
            for (var branch : branches) {
                if (branch == null || branch.candidates == null) continue;
                for (var stack : candidates.computeIfAbsent(branch, SimplePredicate::getCandidates)) {
                    if (stack == null || stack.isEmpty() || !accepted.test(stack)) continue;
                    var key = ItemKey.of(stack);
                    var entry = collected.computeIfAbsent(key, ignored -> new MutableEntry(stack));
                    entry.positions.add(pos.immutable());
                    int source = sources.computeIfAbsent(branch, ignored -> sources.size() + 1);
                    var hintsForBranch = hintCache.computeIfAbsent(trace, ignored -> new IdentityHashMap<>())
                            .computeIfAbsent(branch, ignored -> new LinkedHashMap<>());
                    var hint = hintsForBranch.computeIfAbsent(key, ignored -> {
                                var lines = new ArrayList<>(branch.getCandidateToolTips(trace, stack));
                                if (branch.minLayerCount != -1 || branch.maxLayerCount != -1) {
                                    lines.add(Component.translatable("pattern_preview.part_layer_limit",
                                            branch.minLayerCount == -1 ? 0 : branch.minLayerCount,
                                            branch.maxLayerCount == -1 ? "∞" : branch.maxLayerCount));
                                }
                                return new ConstraintHint(branch.getClass().getSimpleName() + " #" + source, lines);
                            });
                    if (!hint.lines.isEmpty() && entry.hintKeys.add(hint)) entry.hints.add(hint);
                }
            }
        }
        var entries = collected.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<ItemKey, MutableEntry> e) -> e.getKey().item().toString())
                        .thenComparing(e -> canonical(e.getKey().tag())))
                .map(e -> new PartHighlightEntry(e.getKey(), e.getValue().stack, e.getValue().positions,
                        e.getValue().hints, partTooltips(e.getValue().stack)))
                .toList();
        return new PartHighlightIndex(entries.isEmpty() ? Status.EMPTY : Status.READY, entries);
    }

    private static boolean isEnabledPart(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof MBDMachineBlock machineBlock)) return false;
        var settings = machineBlock.getDefinition().partSettings();
        return settings != null && settings.isEnable();
    }

    private static List<Component> partTooltips(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof MBDMachineBlock block) {
            var settings = block.getDefinition().partSettings();
            if (settings != null) return settings.xeiPartTooltips();
        }
        return List.of();
    }

    private static String canonical(Tag tag) {
        if (tag == null) return "";
        if (tag instanceof CompoundTag compound) {
            var keys = compound.getAllKeys().stream().sorted().toList();
            var out = new StringBuilder("{");
            for (var key : keys) out.append(key.length()).append(":").append(key).append(canonical(compound.get(key))).append(";");
            return out.append("}").toString();
        }
        if (tag instanceof ListTag list) {
            var out = new StringBuilder("[");
            for (var child : list) out.append(canonical(child)).append(";");
            return out.append("]").toString();
        }
        return tag.getId() + ":" + tag;
    }

    private static final class MutableEntry {
        final ItemStack stack;
        final Set<BlockPos> positions = new LinkedHashSet<>();
        final List<ConstraintHint> hints = new ArrayList<>();
        final Set<ConstraintHint> hintKeys = new LinkedHashSet<>();
        MutableEntry(ItemStack stack) { this.stack = stack.copyWithCount(1); }
    }
}
