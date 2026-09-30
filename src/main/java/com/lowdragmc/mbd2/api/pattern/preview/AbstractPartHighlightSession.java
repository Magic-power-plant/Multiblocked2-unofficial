package com.lowdragmc.mbd2.api.pattern.preview;

import net.minecraft.core.BlockPos;

import java.util.Set;

/** Owns the binding and hover lifecycle of a single preview page. */
public abstract class AbstractPartHighlightSession {
    public enum State { UNBOUND, READY, EMPTY, UNAVAILABLE }
    private PartHighlightIndex index = PartHighlightIndex.unavailable();
    private State state = State.UNBOUND;
    private PartHighlightSource source;
    private PartHighlightIndex.ItemKey hovered;
    private Set<BlockPos> visible = Set.of();
    private Set<BlockPos> highlighted = Set.of();

    public final void bind(PartHighlightSource source) {
        invalidate();
        this.source = source;
        index = loadIndex(source);
        state = State.valueOf(index.status().name());
    }

    protected abstract PartHighlightIndex loadIndex(PartHighlightSource source);

    public final void updateHover(PartHighlightIndex.ItemKey key) {
        if (java.util.Objects.equals(hovered, key)) return;
        hovered = key == null || index.get(key) == null ? null : key;
        var entry = hoveredEntry();
        if (entry == null) {
            highlighted = Set.of();
        } else {
            var positions = new java.util.HashSet<>(entry.positions());
            positions.retainAll(visible);
            highlighted = BlockPosSnapshots.copyOf(positions);
        }
    }

    public final void clearHover() { hovered = null; highlighted = Set.of(); }
    public final void updateVisiblePositions(Set<BlockPos> positions) {
        visible = BlockPosSnapshots.copyOf(positions);
        clearHover();
    }
    public final void invalidate() {
        state = State.UNBOUND;
        source = null;
        index = PartHighlightIndex.unavailable();
        visible = Set.of();
        hovered = null;
        highlighted = Set.of();
    }
    public final PartHighlightIndex index() { return index; }
    public final State state() { return state; }
    public final PartHighlightSource source() { return source; }
    public final Set<BlockPos> visiblePositions() { return visible; }
    public final PartHighlightEntry hoveredEntry() { return hovered == null ? null : index.get(hovered); }
    public final Set<BlockPos> highlightedPositions() {
        return highlighted;
    }
}
