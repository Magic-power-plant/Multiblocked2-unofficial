package com.lowdragmc.mbd2.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ItemStackTransferLimitsTest {

    @Test
    void defaultCapacityMatchesNativeStackSize() {
        for (int itemLimit : new int[]{1, 16, 64}) {
            assertEquals(itemLimit, ItemStackTransferLimits.effectiveStackLimit(64, itemLimit));
        }
    }

    @Test
    void bulkCapacityScalesEveryItemIncludingNonStackableItems() {
        assertEquals(10, ItemStackTransferLimits.effectiveStackLimit(640, 1));
        assertEquals(160, ItemStackTransferLimits.effectiveStackLimit(640, 16));
        assertEquals(640, ItemStackTransferLimits.effectiveStackLimit(640, 64));
    }

    @Test
    void fractionalCapacityRoundsDownWithoutAnImplicitMinimum() {
        assertEquals(15, ItemStackTransferLimits.effectiveStackLimit(1_000, 1));
        assertEquals(0, ItemStackTransferLimits.effectiveStackLimit(63, 1));
        assertEquals(1, ItemStackTransferLimits.effectiveStackLimit(65, 1));
        assertEquals(4, ItemStackTransferLimits.effectiveStackLimit(17, 16));
        assertEquals(1, ItemStackTransferLimits.effectiveStackLimit(1, 64));
    }

    @Test
    void largeCapacitiesDoNotOverflow() {
        assertEquals(33_554_431, ItemStackTransferLimits.effectiveStackLimit(Integer.MAX_VALUE, 1));
        assertEquals(536_870_911, ItemStackTransferLimits.effectiveStackLimit(Integer.MAX_VALUE, 16));
        assertEquals(Integer.MAX_VALUE, ItemStackTransferLimits.effectiveStackLimit(Integer.MAX_VALUE, 64));
        assertEquals(Integer.MAX_VALUE, ItemStackTransferLimits.effectiveStackLimit(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void disabledOrInvalidLimitsNeverAcceptItems() {
        for (int invalid : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertEquals(0, ItemStackTransferLimits.effectiveStackLimit(invalid, 64));
            assertEquals(0, ItemStackTransferLimits.effectiveStackLimit(64, invalid));
        }
    }

    @Test
    void helperIsAccessibleToTransformedLdlibClasses() {
        assertFalse(ItemStackTransferLimits.class.getPackageName()
                .startsWith("com.lowdragmc.mbd2.core.mixins"));
    }
}
