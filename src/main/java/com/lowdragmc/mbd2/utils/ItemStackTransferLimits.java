package com.lowdragmc.mbd2.utils;

/**
 * Storage limits shared by machine IO, recipe simulation and menu insertion.
 *
 * <p>Configured capacity is measured in items with a native stack size of 64.
 * Every item, including items with a native limit of one, scales proportionally.
 * This helper lives outside the Mixin package so transformed LDLib code can use it.</p>
 */
public final class ItemStackTransferLimits {

    private ItemStackTransferLimits() {
    }

    public static int effectiveStackLimit(int slotLimit, int itemLimit) {
        long capacity = (long) Math.max(slotLimit, 0) * Math.max(itemLimit, 0) / 64;
        return (int) Math.min(capacity, Integer.MAX_VALUE);
    }
}
