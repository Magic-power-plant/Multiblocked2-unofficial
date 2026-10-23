package com.lowdragmc.mbd2.utils;

import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;

/**
 * Computes the stack limit exposed by LDLib item-transfer slots to vanilla menu logic.
 *
 * <p>This class must remain outside the Mixin package because its static call is emitted into
 * the transformed LDLib target class at runtime.</p>
 */
public final class WidgetSlotItemTransferLimits {

    private WidgetSlotItemTransferLimits() {
    }

    /**
     * Only ItemStackTransfer uses MBD's proportional storage capacity. Other handlers,
     * including AE2 pattern inventories, report their capacity directly in items.
     */
    public static int effectiveStackLimit(IItemTransfer handler, int slot, int itemLimit) {
        int slotLimit = handler.getSlotLimit(slot);
        if (handler instanceof ItemStackTransfer) {
            return effectiveStackLimit(slotLimit, itemLimit);
        }
        return Math.min(Math.max(slotLimit, 0), Math.max(itemLimit, 0));
    }

    public static int effectiveStackLimit(int slotLimit, int itemLimit) {
        return Math.min(ItemStackTransferLimits.effectiveStackLimit(slotLimit, itemLimit), Math.max(itemLimit, 0));
    }
}
