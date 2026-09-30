package com.lowdragmc.mbd2.integration.appmek;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.util.ConfigInventory;
import com.lowdragmc.mbd2.api.recipe.RecipeGroup;
import com.lowdragmc.mbd2.integration.ae2.storage.MEInterfaceKeyStorage;
import com.lowdragmc.mbd2.integration.ae2.storage.MEPatternKeyStorage;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.slurry.SlurryStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MEChemicalStorageTest {
    @BeforeAll static void bootstrap() { AppliedMekanisticsTestBootstrap.init(); }

    @Test void allChemicalFormsRoundTripWithLongAmountsAndGroups() {
        long amount = (long) Integer.MAX_VALUE + 1500;
        var stacks = List.of(new GasStack(AppliedMekanisticsTestBootstrap.gas, amount),
                new InfusionStack(AppliedMekanisticsTestBootstrap.infusion, amount),
                new PigmentStack(AppliedMekanisticsTestBootstrap.pigment, amount),
                new SlurryStack(AppliedMekanisticsTestBootstrap.slurry, amount));
        var buffer = buffer(Long.MAX_VALUE);
        for (var chemical : stacks) assertEquals(amount, buffer.insert(MekanismKey.of(chemical), amount, "Ab12"));
        var restored = buffer(1); // Lowered limits must not destroy saved contents.
        restored.deserializeNBT(buffer.serializeNBT());
        assertEquals(buffer.getEntries(), restored.getEntries());
        assertEquals(Set.of("Ab12"), restored.getRecipeGroups());
        assertTrue(restored.getContents("Cd34").isEmpty());
        assertEquals(4, restored.getContents(RecipeGroup.ANY).size());
    }

    @Test void simulationIsCumulativeAndNeverMutatesLiveStorageOrItsListener() {
        var changes = new AtomicInteger();
        var live = buffer(100);
        var key = gasKey();
        live.setOnContentsChanged(changes::incrementAndGet);
        live.insert(key, 70, "AAAA");
        var simulation = live.copy();
        assertEquals(50, simulation.extract(key, 50, "AAAA"));
        assertEquals(20, simulation.extract(key, 50, "AAAA"));
        assertTrue(simulation.isEmpty());
        assertEquals(70, live.getContents("AAAA").get(0).amount());
        assertEquals(1, changes.get());
    }

    @Test void groupsRemainIsolatedAndWildcardExtractionIsConservative() {
        var buffer = buffer(100);
        var key = gasKey();
        buffer.insert(key, 40, "AAAA");
        buffer.insert(key, 60, "BBBB");
        assertEquals(0, buffer.extract(key, 100, null));
        assertEquals(40, buffer.extract(key, 70, "AAAA"));
        assertEquals(Set.of("BBBB"), buffer.getRecipeGroups());
        assertEquals(60, buffer.extract(key, 100, RecipeGroup.ANY));
        assertTrue(buffer.isEmpty());
    }

    @Test void capacityAndCrossGroupCountersCannotOverflow() {
        var buffer = buffer(Long.MAX_VALUE);
        var key = gasKey();
        assertEquals(Long.MAX_VALUE - 5, buffer.insert(key, Long.MAX_VALUE - 5, "AAAA"));
        assertEquals(5, buffer.insert(key, 100, "BBBB"));
        assertEquals(0, buffer.insert(key, 1, "CCCC"));
        var available = new KeyCounter();
        buffer.getAvailableStacks(available);
        assertEquals(Long.MAX_VALUE, available.get(key));
        assertEquals(Long.MAX_VALUE, buffer.extract(key, Long.MAX_VALUE, RecipeGroup.ANY));
        assertTrue(buffer.isEmpty());
        assertEquals(Long.MAX_VALUE, MEPatternKeyStorage.saturatedAdd(Long.MAX_VALUE - 3, 10));
    }

    @Test void configuredLimitAppliesPerChemicalAndGroupAndRejectsForeignKeys() {
        var buffer = buffer(100);
        assertEquals(100, buffer.insert(gasKey(), 120, "AAAA"));
        assertEquals(0, buffer.insert(gasKey(), 1, "AAAA"));
        assertEquals(100, buffer.insert(gasKey(), 120, "BBBB"));
        assertEquals(0, buffer.insert(AEItemKey.of(Items.STONE), 1, "AAAA"));
        assertEquals(0, buffer.insert(gasKey(), -1, "AAAA"));
    }

    @Test void interfaceSimulationPreservesMixedSlotOccupancyAndCapacity() {
        var inv = ConfigInventory.configStacks(null, 3, null, true);
        var key = gasKey();
        inv.setCapacity(key.getType(), 100);
        inv.setStack(0, new GenericStack(AEItemKey.of(Items.STONE), 5));
        inv.setStack(1, new GenericStack(AEFluidKey.of(Fluids.WATER), 1000));
        inv.setStack(2, new GenericStack(key, 80));
        var live = new MEInterfaceKeyStorage(inv);
        var snapshot = live.copy();
        assertEquals(20, snapshot.insert(key, 40, null));
        assertEquals(0, snapshot.insert(key, 1, null));
        assertEquals(80, inv.getAmount(2));
        assertEquals(20, live.insert(key, 40, null));
        assertEquals(snapshot.getContents(null), live.getContents(null));
        assertEquals(100, live.extract(key, 150, null));
        assertEquals(2, live.getContents(null).size());
    }

    @Test void interfaceNearLongMaxAcceptsOnlyAvailableSpace() {
        var inv = ConfigInventory.configStacks(null, 1, null, true);
        var key = gasKey();
        inv.setCapacity(key.getType(), Long.MAX_VALUE);
        inv.setStack(0, new GenericStack(key, Long.MAX_VALUE - 7));
        var storage = new MEInterfaceKeyStorage(inv);
        assertEquals(7, storage.insert(key, Long.MAX_VALUE, null));
        assertEquals(Long.MAX_VALUE, inv.getAmount(0));
    }

    @Test void legacySaveWithoutChemicalStorageLoadsEmpty() {
        var buffer = buffer(100);
        buffer.insert(gasKey(), 10, null);
        buffer.deserializeNBT(new net.minecraft.nbt.CompoundTag());
        assertTrue(buffer.isEmpty());
    }

    @Test void chemicalIngredientsAndEmiKeysConvertWithoutTruncatingAmounts() {
        long amount = (1L << 54) + 37;
        var chemical = new GasStack(AppliedMekanisticsTestBootstrap.gas, amount);
        var direct = AppliedMekanisticsCompat.toGenericStack(chemical);
        var fromEmiKey = AppliedMekanisticsCompat.toGenericStack(AppliedMekanisticsTestBootstrap.gas, amount);
        assertEquals(direct, fromEmiKey);
        assertEquals(amount, direct.amount());
        assertEquals(amount, chemical.getAmount());
        assertNull(AppliedMekanisticsCompat.toGenericStack(GasStack.EMPTY));
        assertNull(AppliedMekanisticsCompat.toGenericStack(AppliedMekanisticsTestBootstrap.gas, 0));
    }

    @Test void interfaceCopiesRetainItemNbtAndChemicalOccupancyForItemAndFluidViews() {
        var inv = ConfigInventory.configStacks(null, 2, null, true);
        var tagged = new net.minecraft.world.item.ItemStack(Items.STONE);
        tagged.getOrCreateTag().putString("mbd2_test", "preserved");
        inv.setStack(0, new GenericStack(AEItemKey.of(tagged), 10));
        inv.setStack(1, new GenericStack(gasKey(), 50));
        var snapshot = MEInterfaceKeyStorage.copyInventory(inv);
        var item = com.lowdragmc.mbd2.integration.ae2.trait.AEInterfaceSlotWidget.createAEItemTransfer(snapshot, 0);
        assertEquals(tagged.getTag(), item.getStackInSlot(0).getTag());
        assertEquals(tagged.getTag(), item.extractItem(0, 5, false).getTag());
        assertEquals(10, inv.getAmount(0));
        var blockedItem = com.lowdragmc.mbd2.integration.ae2.trait.AEInterfaceSlotWidget.createAEItemTransfer(snapshot, 1);
        assertEquals(1, blockedItem.insertItem(0, new net.minecraft.world.item.ItemStack(Items.STONE), false).getCount());
        var blockedFluid = com.lowdragmc.mbd2.integration.ae2.trait.AEInterfaceSlotWidget.createAEFluidTransfer(snapshot, 1);
        assertEquals(0, blockedFluid.fill(com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(Fluids.WATER, 100), false));
        assertEquals(50, snapshot.getAmount(1));
    }

    static MEPatternKeyStorage buffer(long capacity) {
        return new MEPatternKeyStorage(capacity, AppliedMekanisticsCompat::isChemicalKey);
    }

    static MekanismKey gasKey() {
        return MekanismKey.of(new GasStack(AppliedMekanisticsTestBootstrap.gas, 1));
    }
}
