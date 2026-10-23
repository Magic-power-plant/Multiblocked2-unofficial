package com.lowdragmc.mbd2.common.trait.fluid;

import com.lowdragmc.lowdraglib.misc.FluidStorage;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.mbd2.XeiTestBootstrap;
import com.lowdragmc.mbd2.api.capability.recipe.IO;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FluidStorageTransferListTest {
    @BeforeAll
    static void bootstrap() {
        XeiTestBootstrap.init();
    }

    @Test
    void autoInputDoesNotSpreadFluidWhenExistingTankIsFull() {
        var first = new FluidStorage(1000);
        first.setFluid(FluidStack.create(Fluids.WATER, 1000));
        var second = new FluidStorage(1000);
        var transfer = new FluidStorageTransferList(new FluidStorage[]{first, second}, false);

        assertEquals(0, transfer.fill(FluidStack.create(Fluids.WATER, 500), true, false));
        assertEquals(0, transfer.fill(FluidStack.create(Fluids.WATER, 500), false, true));
        assertTrue(second.getFluid().isEmpty());
    }

    @Test
    void autoInputAndForgeCapabilityChooseTheSameOccupiedTank() {
        var empty = new FluidStorage(1000);
        var occupied = new FluidStorage(1000);
        occupied.setFluid(FluidStack.create(Fluids.WATER, 600));
        var storages = new FluidStorage[]{empty, occupied};
        var transfer = new FluidStorageTransferList(storages, false);
        var forge = new FluidHandlerWrapper(storages, IO.BOTH, false);
        var resource = FluidStack.create(Fluids.WATER, 700);

        assertEquals(400, transfer.fill(resource, true, false));
        assertEquals(400, forge.fill(new net.minecraftforge.fluids.FluidStack(Fluids.WATER, 700),
                IFluidHandler.FluidAction.SIMULATE));
        assertEquals(600, occupied.getFluid().getAmount());
        assertEquals(400, transfer.fill(resource, false, true));
        assertEquals(700, resource.getAmount());
        assertEquals(1000, occupied.getFluid().getAmount());
        assertTrue(empty.getFluid().isEmpty());
    }

    @Test
    void duplicatePolicyHandlesEmptyTanksAndLongAmounts() {
        long capacity = (long) Integer.MAX_VALUE + 500;
        var first = new FluidStorage(capacity);
        var second = new FluidStorage(capacity);
        var storages = new FluidStorage[]{first, second};
        var resource = FluidStack.create(Fluids.WATER, capacity + 250);

        assertEquals(capacity, new FluidStorageTransferList(storages, false).fill(resource, true, false));
        assertEquals(capacity + 250, new FluidStorageTransferList(storages, true).fill(resource, false, true));
        assertEquals(capacity, first.getFluid().getAmount());
        assertEquals(250, second.getFluid().getAmount());
    }

    @Test
    void fluidNbtDistinguishesTankContentsAndNotificationsAreRespected() {
        var first = new FluidStorage(1000);
        var second = new FluidStorage(1000);
        var firstTag = new CompoundTag();
        firstTag.putString("kind", "first");
        first.setFluid(FluidStack.create(Fluids.WATER, 500, firstTag));
        var otherTag = new CompoundTag();
        otherTag.putString("kind", "other");
        var transfer = new FluidStorageTransferList(new FluidStorage[]{first, second}, false);
        var changes = new AtomicInteger();
        first.setOnContentsChanged(changes::incrementAndGet);
        second.setOnContentsChanged(changes::incrementAndGet);

        assertEquals(100, transfer.fill(FluidStack.create(Fluids.WATER, 100, otherTag), false, false));
        assertEquals(0, changes.get());
        assertEquals(otherTag, second.getFluid().getTag());
        assertEquals(500, first.getFluid().getAmount());
        assertEquals(50, transfer.fill(FluidStack.create(Fluids.WATER, 50, otherTag), true, true));
        assertEquals(0, changes.get());
        assertEquals(100, second.getFluid().getAmount());
        assertEquals(50, transfer.fill(FluidStack.create(Fluids.WATER, 50, otherTag), false, true));
        assertEquals(1, changes.get());
    }
}
