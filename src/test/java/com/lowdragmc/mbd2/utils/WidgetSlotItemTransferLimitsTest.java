package com.lowdragmc.mbd2.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WidgetSlotItemTransferLimitsTest {

    @Test
    void configuredSlotLimitCapsVanillaInsertion() {
        assertEquals(1, WidgetSlotItemTransferLimits.effectiveStackLimit(1, 64));
        assertEquals(16, WidgetSlotItemTransferLimits.effectiveStackLimit(16, 64));
    }

    @Test
    void nativeItemLimitCapsOversizedStorageInteraction() {
        assertEquals(64, WidgetSlotItemTransferLimits.effectiveStackLimit(1_000, 64));
        assertEquals(1, WidgetSlotItemTransferLimits.effectiveStackLimit(1_000, 1));
    }

    @Test
    void menuInsertionUsesTheSameScaledCapacityAsAutomation() {
        assertEquals(4, WidgetSlotItemTransferLimits.effectiveStackLimit(16, 16));
        assertEquals(0, WidgetSlotItemTransferLimits.effectiveStackLimit(63, 1));
        assertEquals(1, WidgetSlotItemTransferLimits.effectiveStackLimit(64, 1));
        assertEquals(1, WidgetSlotItemTransferLimits.effectiveStackLimit(640, 1));
        assertEquals(16, WidgetSlotItemTransferLimits.effectiveStackLimit(640, 16));
    }

    @Test
    void invalidNegativeSlotLimitAllowsNoInsertion() {
        assertEquals(0, WidgetSlotItemTransferLimits.effectiveStackLimit(-1, 64));
    }

    @Test
    void helperIsOutsideTheMixinPackage() {
        assertFalse(WidgetSlotItemTransferLimits.class.getPackageName()
                .startsWith("com.lowdragmc.mbd2.core.mixins"));
    }
}
