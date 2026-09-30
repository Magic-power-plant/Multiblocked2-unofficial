package com.lowdragmc.mbd2.utils;

/**
 * Computes the stack limit exposed by LDLib item-transfer slots to vanilla menu logic.
 *
 * <p>This class must remain outside the Mixin package because its static call is emitted into
 * the transformed LDLib target class at runtime.</p>
 */
public final class WidgetSlotItemTransferLimits {

    private WidgetSlotItemTransferLimits() {
    }

    public static int effectiveStackLimit(int slotLimit, int itemLimit) {
        return Math.min(ItemStackTransferLimits.effectiveStackLimit(slotLimit, itemLimit), Math.max(itemLimit, 0));
    }
}
