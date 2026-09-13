package com.lowdragmc.mbd2.integration.jade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MBDJadeEnergyStorageProviderTest {

    @Test
    void preservesLongFeValuesBeyondForgeEnergyIntegerRange() {
        long stored = (long) Integer.MAX_VALUE + 4_096L;
        long capacity = stored + 8_192L;

        var view = MBDJadeEnergyStorageProvider.createEnergyData(stored, capacity);

        assertEquals(stored, view.getLong("Cur"));
        assertEquals(capacity, view.getLong("Capacity"));
    }

    @Test
    void normalizesInvalidEnergyValuesBeforeWritingJadeData() {
        var view = MBDJadeEnergyStorageProvider.createEnergyData(-1L, 5_000L);

        assertEquals(0L, view.getLong("Cur"));
        assertEquals(5_000L, view.getLong("Capacity"));
    }

    @Test
    void rawDisplayTextIncludesTheCurrentAndMaximumFeValues() {
        long stored = (long) Integer.MAX_VALUE + 4_096L;
        long capacity = stored + 8_192L;

        var text = MBDJadeEnergyStorageProvider.createEnergyDisplayText(stored, capacity, true);

        assertEquals(stored + " FE / " + capacity + " FE", text.getString());
    }

    @Test
    void calculatesLongFeProgressWithoutIntegerTruncation() {
        long capacity = (long) Integer.MAX_VALUE * 4L;
        long stored = capacity / 2L;

        assertEquals(0.5F, MBDJadeEnergyStorageProvider.calculateProgress(stored, capacity));
    }

}
