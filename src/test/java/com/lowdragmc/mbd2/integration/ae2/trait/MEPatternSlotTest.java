package com.lowdragmc.mbd2.integration.ae2.trait;

import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.mbd2.XeiTestBootstrap;
import com.lowdragmc.mbd2.utils.WidgetSlotItemTransferLimits;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MEPatternSlotTest {
    @BeforeAll
    static void bootstrap() {
        XeiTestBootstrap.init();
    }

    @Test
    void patternInventorySlotUsesDirectOneItemCapacity() {
        var inventory = new MEPatternInputTrait.SerializablePatternInventory(null, 1);
        var transfer = MEPatternInputTraitDefinition.createPatternTransfer(inventory, 0);
        var stack = new ItemStack(Items.STONE);

        assertEquals(1, transfer.getSlotLimit(0));
        assertEquals(1, WidgetSlotItemTransferLimits.effectiveStackLimit(transfer, 0, 1));
        assertFalse(transfer.isItemValid(0, stack));
        assertTrue(ItemStack.matches(stack, transfer.insertItem(0, stack, false)));
        assertTrue(inventory.isEmpty());
    }

    @Test
    void patternSlotStillRejectsOrdinaryItems() {
        var inventory = new MEPatternInputTrait.SerializablePatternInventory(null, 1);
        var transfer = MEPatternInputTraitDefinition.createPatternTransfer(inventory, 0);
        for (var item : new net.minecraft.world.item.Item[]{Items.STONE, Items.DIAMOND_SWORD}) {
            var stack = new ItemStack(item);
            assertFalse(transfer.isItemValid(0, stack));
            assertTrue(ItemStack.matches(stack, transfer.insertItem(0, stack, false)));
        }
        assertTrue(inventory.isEmpty());
    }

    @Test
    void ordinaryMachineStorageRetainsProportionalCapacity() {
        var storage = new ItemStackTransfer(1) {
            @Override
            public int getSlotLimit(int slot) {
                return 16;
            }
        };
        assertEquals(4, WidgetSlotItemTransferLimits.effectiveStackLimit(storage, 0, 16));
        assertEquals(0, WidgetSlotItemTransferLimits.effectiveStackLimit(storage, 0, 1));
        assertEquals(16, WidgetSlotItemTransferLimits.effectiveStackLimit(storage, 0, 64));
    }
}
