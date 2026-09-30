package com.lowdragmc.mbd2.integration.ae2.trait;

import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.helpers.InterfaceLogic;
import appeng.helpers.InterfaceLogicHost;
import appeng.util.ConfigInventory;
import com.lowdragmc.lowdraglib.syncdata.IContentChangeAware;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.integration.appmek.AppliedMekanisticsCompat;
import com.lowdragmc.mbd2.core.mixins.ae2.InterfaceLogicAccessor;
import com.lowdragmc.mbd2.integration.ae2.MEOutputScheduler;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;

/**
 * Serializable AE2 interface logic with expanded item and fluid capacities for MBD machine interfaces.
 */
@Getter
@Setter
public class SerializableInterfaceLogic extends InterfaceLogic implements ITagSerializable<CompoundTag>, IContentChangeAware {
    private static final long MAX_INTERFACE_AMOUNT = Integer.MAX_VALUE;
    private static final long MAX_FLUID_AMOUNT = Long.MAX_VALUE;

    private Runnable onContentsChanged = () -> {
    };
    private int storageBatchDepth;
    private boolean storageBatchDirty;

    /**
     * Creates serializable interface logic with MBD-sized config and storage inventories.
     * <p>
     * Side effects: delegates to AE2 {@link InterfaceLogic}, then replaces its default config/storage rows with
     * {@link ConfigInventory} instances that use registered capacities and allow long fluid amounts. Construct and access
     * this object from the owning machine/server context because AE2 inventory callbacks are not thread-safe.
     *
     * @param gridNode managed grid node that owns the interface logic
     * @param host     AE2 host used for callbacks, neighbor notifications, and save requests
     * @param is       item used by AE2 as the interface representation
     * @param slots    number of config/storage slots; must be non-negative
     */
    public SerializableInterfaceLogic(IManagedGridNode gridNode, InterfaceLogicHost host, Item is, int slots) {
        super(gridNode, host, is, slots);
        replaceInterfaceInventories(slots);
    }

    private void replaceInterfaceInventories(int slots) {
        var accessor = (InterfaceLogicAccessor) this;
        var config = ConfigInventory.configStacks(null, slots, accessor::mbd2$onConfigRowChanged, true);
        useExtendedCapacities(config);
        accessor.mbd2$setConfig(config);

        var storage = ConfigInventory.configStacks(null, slots, this::mbd2$onStorageChanged, true);
        useExtendedCapacities(storage);
        accessor.mbd2$setStorage(storage);
    }

    /**
     * Starts a batch of local storage mutations.
     *
     * <p>AE2 normally recalculates every interface plan after each storage
     * mutation. The ME output scheduler uses this scope to defer that callback
     * until all slots in the batch have been updated.</p>
     */
    public void beginStorageBatch() {
        storageBatchDepth++;
    }

    /**
     * Ends a local storage mutation batch and emits one deferred AE2 callback.
     */
    public void endStorageBatch() {
        if (storageBatchDepth <= 0) {
            return;
        }

        storageBatchDepth--;
        if (storageBatchDepth == 0 && storageBatchDirty) {
            storageBatchDirty = false;
            ((InterfaceLogicAccessor) this).mbd2$onStorageChanged();
        }
    }

    private void mbd2$onStorageChanged() {
        if (storageBatchDepth > 0) {
            storageBatchDirty = true;
            return;
        }
        ((InterfaceLogicAccessor) this).mbd2$onStorageChanged();
    }

    /**
     * Drops queued output when the interface is detached from its old grid.
     */
    @Override
    public void gridChanged() {
        MEOutputScheduler.removeInterface(this);
        super.gridChanged();
    }

    private static void useExtendedCapacities(ConfigInventory inventory) {
        inventory.useRegisteredCapacities();
        AEKeyTypes.getAll().forEach(keyType -> inventory.setCapacity(keyType, MAX_INTERFACE_AMOUNT));
        inventory.setCapacity(AEKeyType.fluids(), MAX_FLUID_AMOUNT);
        if (MBD2.isAppliedMekanisticsLoaded()) {
            AppliedMekanisticsCompat.extendChemicalCapacity(inventory);
        }
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        writeToNBT(tag);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        readFromNBT(nbt);
    }
}
