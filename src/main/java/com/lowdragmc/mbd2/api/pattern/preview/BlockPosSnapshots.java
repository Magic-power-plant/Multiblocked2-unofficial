package com.lowdragmc.mbd2.api.pattern.preview;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Defensive coordinate snapshots without the linear probing of JDK immutable sets. */
public final class BlockPosSnapshots {
    private BlockPosSnapshots() {}

    public static Set<BlockPos> copyOf(Collection<? extends BlockPos> positions) {
        if (positions.isEmpty()) return Set.of();
        // BlockPos hashes form dense clusters for cuboids. Set.copyOf uses SetN's
        // open addressing and can spend minutes probing during XEI registration.
        var snapshot = new HashSet<BlockPos>(positions.size());
        for (var position : positions) snapshot.add(position.immutable());
        return Collections.unmodifiableSet(snapshot);
    }
}
