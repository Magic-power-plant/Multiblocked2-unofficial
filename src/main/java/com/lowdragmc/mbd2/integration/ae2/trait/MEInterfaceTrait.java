package com.lowdragmc.mbd2.integration.ae2.trait;

import appeng.api.behaviors.GenericInternalInventory;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.storage.MEStorage;
import appeng.api.util.AECableType;
import appeng.capabilities.Capabilities;
import appeng.helpers.InterfaceLogicHost;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.util.ConfigInventory;
import com.lowdragmc.lowdraglib.misc.ItemTransferList;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.annotation.DropSaved;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.capability.recipe.IO;
import com.lowdragmc.mbd2.api.capability.recipe.IRecipeHandlerTrait;
import com.lowdragmc.mbd2.api.recipe.MBDRecipe;
import com.lowdragmc.mbd2.api.recipe.RecipeConsumptionTracker;
import com.lowdragmc.mbd2.api.recipe.ingredient.FluidIngredient;
import com.lowdragmc.mbd2.common.capability.recipe.FluidRecipeCapability;
import com.lowdragmc.mbd2.common.capability.recipe.ItemRecipeCapability;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.trait.ICapabilityProviderTrait;
import com.lowdragmc.mbd2.common.trait.RecipeHandlerTrait;
import com.lowdragmc.mbd2.common.trait.RecipeCapabilityTrait;
import com.lowdragmc.mbd2.common.trait.SimpleCapabilityTrait;
import com.lowdragmc.mbd2.integration.ae2.MEOutputScheduler;
import com.lowdragmc.mbd2.integration.ae2.storage.MEInterfaceKeyStorage;
import com.lowdragmc.mbd2.integration.appmek.AppliedMekanisticsCompat;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.Direction;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * MBD trait that exposes a machine as an AE2 ME interface with item and fluid recipe storage.
 */
@Getter
@Setter
public class MEInterfaceTrait extends SimpleCapabilityTrait implements IGridConnectedBlockEntity, InterfaceLogicHost {
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(MEInterfaceTrait.class, RecipeCapabilityTrait.MANAGED_FIELD_HOLDER);

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    private final Random random = new Random();

    @DropSaved
    @Persisted
    private final SerializableManagedGridNode mainNode;
    @DropSaved
    @Persisted
    private final SerializableInterfaceLogic interfaceLogic;
    private final ItemRecipeHandler itemRecipeHandler = new ItemRecipeHandler();
    private final FluidRecipeHandler fluidRecipeHandler = new FluidRecipeHandler();
    private final List<IRecipeHandlerTrait<?>> recipeHandlers;

    private final ManagedGridNodeCap managedGridNodeCap = new ManagedGridNodeCap();
    private final GenericInternalInventoryCap genericInternalInventoryCap = new GenericInternalInventoryCap();
    private final StorageCap storageCap = new StorageCap();

    /**
     * Creates an ME interface trait for a machine definition.
     * <p>
     * Side effects: creates the persisted AE2 grid node and interface logic immediately, but the node is attached to the
     * world later from the server load lifecycle. Runtime access is expected from the machine/server thread.
     *
     * @param machine    machine instance that owns the trait and block entity
     * @param definition interface definition that supplies IO settings and slot count
     */
    public MEInterfaceTrait(MBDMachine machine, MEInterfaceTraitDefinition definition) {
        super(machine, definition);
        mainNode = createMainNode();
        interfaceLogic = createLogic();
        var handlers = new ArrayList<IRecipeHandlerTrait<?>>();
        handlers.add(itemRecipeHandler);
        handlers.add(fluidRecipeHandler);
        if (MBD2.isAppliedMekanisticsLoaded()) {
            handlers.addAll(AppliedMekanisticsCompat.createRecipeHandlers(this,
                    () -> new MEInterfaceKeyStorage(interfaceLogic.getStorage()), false));
        }
        recipeHandlers = List.copyOf(handlers);
    }

    /**
     * Builds the persisted AE2 grid node used by this interface.
     * <p>
     * The node is configured as an in-world node with the machine drop item as its visual representation. Grid-state
     * changes notify the interface logic so AE2 neighbor and storage state stay in sync.
     *
     * @return configured, not-yet-created managed grid node
     */
    protected SerializableManagedGridNode createMainNode() {
        return (SerializableManagedGridNode) new SerializableManagedGridNode(this, (nodeOwner, node) -> nodeOwner.interfaceLogic.gridChanged())
                .setVisualRepresentation(getMachine().getDropItem())
                .setInWorldNode(true)
                .setTagName("proxy");
    }

    /**
     * Creates the serializable AE2 interface logic bound to the main grid node.
     *
     * @return interface logic using the definition slot count and machine drop item as its representation
     */
    protected SerializableInterfaceLogic createLogic() {
        return new SerializableInterfaceLogic(getMainNode(), this, getMachine().getDropItem().getItem(), getDefinition().getSlotSize());
    }

    /**
     * Maps a logical item recipe slot to the AE2 interface storage slot.
     *
     * @param slotIndex zero-based recipe slot index in {@code [0, getDefinition().getSlotSize())}
     * @return zero-based AE2 item storage slot index
     */
    protected int getItemSlotIndex(int slotIndex) {
        return slotIndex;
    }

    /**
     * Maps a logical fluid recipe slot to the AE2 interface storage slot.
     *
     * @param slotIndex zero-based recipe slot index in {@code [0, getDefinition().getSlotSize())}
     * @return zero-based AE2 fluid storage slot index
     */
    protected int getFluidSlotIndex(int slotIndex) {
        return slotIndex;
    }

    @Override
    public MEInterfaceTraitDefinition getDefinition() {
        return (MEInterfaceTraitDefinition) super.getDefinition();
    }

    @Override
    public BlockEntity getBlockEntity() {
        return getMachine().getHolder();
    }

    @Override
    public void saveChanges() {
        onChanged();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        if (getMainNode().hasGridBooted()) {
            this.interfaceLogic.notifyNeighbors();
        }
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return getMachine().getDropItem();
    }

    @Override
    public void onMachineDrop(Entity entity, List<ItemStack> drops) {
        if (getMachine().shouldKeepDropItemNbt()) {
            return;
        }
        this.interfaceLogic.addDrops(drops);
        this.interfaceLogic.clearContent();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return this.interfaceLogic.getCableConnectionType(dir);
    }

    @Override
    public List<ICapabilityProviderTrait<?>> getCapabilityProviderTraits() {
        return List.of(managedGridNodeCap, genericInternalInventoryCap, storageCap);
    }

    @Override
    public List<IRecipeHandlerTrait<?>> getRecipeHandlerTraits() {
        return recipeHandlers;
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        MEOutputScheduler.removeInterface(this.interfaceLogic);
        this.getMainNode().destroy();
    }

    @Override
    public void onMachineLoad() {
        super.onMachineLoad();
        if (getMachine().getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(0, () -> this.getMainNode().create(serverLevel, getBlockEntity().getBlockPos())));
        }
    }

    @Override
    public void onMachineUnLoad() {
        super.onMachineUnLoad();
        MEOutputScheduler.removeInterface(this.interfaceLogic);
        this.getMainNode().destroy();
    }

    @Override
    public void onMachineRemoved() {
        MEOutputScheduler.removeInterface(this.interfaceLogic);
    }

    ///////////////////////////
    /// Capability Provider ///
    ///////////////////////////

    /**
     * Exposes this machine as an in-world AE2 grid node host.
     */
    public class ManagedGridNodeCap implements ICapabilityProviderTrait<IInWorldGridNodeHost> {
        @Override
        public IO getCapabilityIO(@Nullable Direction side) {
            return MEInterfaceTrait.this.getCapabilityIO(side);
        }

        @Override
        public Capability<IInWorldGridNodeHost> getCapability() {
            return Capabilities.IN_WORLD_GRID_NODE_HOST;
        }

        @Override
        public IInWorldGridNodeHost getCapContent(IO capabilityIO) {
            return capabilityIO != IO.NONE ? MEInterfaceTrait.this : null;
        }
    }

    /**
     * Exposes the interface storage as AE2's generic internal inventory capability.
     */
    public class GenericInternalInventoryCap implements ICapabilityProviderTrait<GenericInternalInventory> {
        @Override
        public IO getCapabilityIO(@Nullable Direction side) {
            return MEInterfaceTrait.this.getCapabilityIO(side);
        }

        @Override
        public Capability<GenericInternalInventory> getCapability() {
            return Capabilities.GENERIC_INTERNAL_INV;
        }

        @Override
        public GenericInternalInventory getCapContent(IO capabilityIO) {
            return capabilityIO != IO.NONE ? interfaceLogic.getStorage() : null;
        }
    }

    /**
     * Exposes the interface network inventory as an ME storage capability.
     */
    public class StorageCap implements ICapabilityProviderTrait<MEStorage> {
        @Override
        public IO getCapabilityIO(@Nullable Direction side) {
            return MEInterfaceTrait.this.getCapabilityIO(side);
        }

        @Override
        public Capability<MEStorage> getCapability() {
            return Capabilities.STORAGE;
        }

        @Override
        public MEStorage getCapContent(IO capabilityIO) {
            return capabilityIO != IO.NONE ? interfaceLogic.getInventory() : null;
        }
    }


    /**
     * Handles MBD item recipe IO against the AE2 interface storage slots.
     */
    public class ItemRecipeHandler extends RecipeHandlerTrait<Ingredient> {

        /**
         * Creates the item recipe handler attached to the outer ME interface trait.
         * <p>
         * The handler delegates all storage access to the outer trait and should be used from recipe-processing/server
         * logic rather than from arbitrary worker threads.
         */
        protected ItemRecipeHandler() {
            super(MEInterfaceTrait.this, ItemRecipeCapability.CAP);
        }

        /**
         * Builds a detached item-storage snapshot for simulated recipe handling.
         * <p>
         * The snapshot preserves all occupied slots, including fluids and addon keys, so output simulation cannot
         * mistake a chemical-filled slot for free item storage.
         *
         * @return independent item transfer containing the current item stacks
         */
        protected IItemTransfer getSafeStorage() {
            return getStorage(MEInterfaceKeyStorage.copyInventory(interfaceLogic.getStorage()));
        }

        /**
         * Builds a live item transfer view over every AE2 interface storage slot.
         * <p>
         * Mutations through the returned transfer are applied to AE2 storage and therefore should only be used for
         * non-simulated recipe execution.
         *
         * @return item transfer list covering all configured interface slots
         */
        protected IItemTransfer getStorage() {
            return getStorage(interfaceLogic.getStorage());
        }

        private IItemTransfer getStorage(ConfigInventory inventory) {
            List<IItemTransfer> transfers = new ArrayList<>();
            for (int i = 0; i < getDefinition().getSlotSize(); i++) {
                transfers.add(AEInterfaceSlotWidget.createAEItemTransfer(inventory, getItemSlotIndex(i)));
            }
            return new ItemTransferList(transfers);
        }

        @Override
        public List<Ingredient> handleRecipeInner(IO io, MBDRecipe recipe, List<Ingredient> left, @Nullable String slotName, boolean simulate) {
            if (!compatibleWith(io)) return left;
            var capability = simulate ? getSafeStorage() : getStorage();
            Iterator<Ingredient> iterator = left.iterator();
            if (io == IO.IN) {
                while (iterator.hasNext()) {
                    Ingredient ingredient = iterator.next();
                    SLOT_LOOKUP:
                    for (int i = 0; i < capability.getSlots(); i++) {
                        ItemStack itemStack = capability.getStackInSlot(i);
                        //Does not look like a good implementation, but I think it's at least equal to vanilla Ingredient::test
                        if (ingredient.test(itemStack)) {
                            ItemStack[] ingredientStacks = ingredient.getItems();
                            for (ItemStack ingredientStack : ingredientStacks) {
                                if (ingredientStack.is(itemStack.getItem())) {
                                    ItemStack extracted = capability.extractItem(i, ingredientStack.getCount(), false);
                                    if (!simulate && !extracted.isEmpty()) {
                                        RecipeConsumptionTracker.record(ItemRecipeCapability.CAP, extracted.copy(), slotName);
                                    }
                                    ingredientStack.setCount(ingredientStack.getCount() - extracted.getCount());
                                    if (ingredientStack.isEmpty()) {
                                        iterator.remove();
                                        break SLOT_LOOKUP;
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (io == IO.OUT) {
                while (iterator.hasNext()) {
                    Ingredient ingredient = iterator.next();
                    var items = ingredient.getItems();
                    if (items.length == 0) {
                        iterator.remove();
                        continue;
                    }
                    if (items.length == 1) {
                        ItemStack output = items[0];
                        if (!output.isEmpty()) {
                            for (int i = 0; i < capability.getSlots(); i++) {
                                ItemStack leftStack = capability.insertItem(i, output.copy(), false);
                                output.setCount(leftStack.getCount());
                                if (output.isEmpty()) break;
                            }
                        }
                        if (output.isEmpty()) iterator.remove();
                    } else { // random output
                        var shuffledItems = Arrays.asList(Arrays.copyOf(items, items.length));
                        random.setSeed(getMachine().getOffsetTimer());
                        Collections.shuffle(shuffledItems, random);
                        // find index
                        var index = -1;
                        for (int i = 0; i < shuffledItems.size(); i++) {
                            var output = shuffledItems.get(i).copy();
                            if (!output.isEmpty()) {
                                for (int slot = 0; slot < capability.getSlots(); slot++) {
                                    var leftStack = capability.insertItem(slot, output.copy(), true);
                                    output.setCount(leftStack.getCount());
                                    if (output.isEmpty()) break;
                                }
                            }
                            if (output.isEmpty()) {
                                index = i;
                                break;
                            }
                        }
                        if (index != -1) {
                            if (!simulate) {
                                var output = shuffledItems.get(index);
                                for (int slot = 0; slot < capability.getSlots(); slot++) {
                                    ItemStack leftStack = capability.insertItem(slot, output.copy(), true);
                                    if (leftStack.getCount() < output.getCount()) {
                                        leftStack = capability.insertItem(slot, output.copy(), false);
                                        output.setCount(leftStack.getCount());
                                        if (output.isEmpty()) break;
                                    }
                                }
                            }
                            iterator.remove();
                        }
                    }
                }
            }
            return left.isEmpty() ? null : left;
        }
    }

    /**
     * Handles MBD fluid recipe IO against the AE2 interface storage slots.
     */
    public class FluidRecipeHandler extends RecipeHandlerTrait<FluidIngredient> {
        /**
         * Creates the fluid recipe handler attached to the outer ME interface trait.
         * <p>
         * The handler delegates all storage access to the outer trait and should be used from recipe-processing/server
         * logic rather than from arbitrary worker threads.
         */
        protected FluidRecipeHandler() {
            super(MEInterfaceTrait.this, FluidRecipeCapability.CAP);
        }

        /**
         * Builds detached fluid-storage snapshots for simulated recipe handling.
         * <p>
         * The snapshot preserves all occupied slots, including items and addon keys. Mutating these tanks does not
         * affect the live AE2 interface.
         *
         * @return independent fluid transfer list containing the current fluid stacks
         */
        protected List<IFluidTransfer> getSafeStorage() {
            return getStorage(MEInterfaceKeyStorage.copyInventory(interfaceLogic.getStorage()));
        }

        /**
         * Builds live fluid transfer views over every AE2 interface storage slot.
         * <p>
         * Mutations through the returned transfers are applied to AE2 storage and therefore should only be used for
         * non-simulated recipe execution.
         *
         * @return fluid transfer list covering all configured interface slots
         */
        protected List<IFluidTransfer> getStorage() {
            return getStorage(interfaceLogic.getStorage());
        }

        private List<IFluidTransfer> getStorage(ConfigInventory inventory) {
            List<IFluidTransfer> storages = new ArrayList<>();
            for (int i = 0; i < getDefinition().getSlotSize(); i++) {
                storages.add(AEInterfaceSlotWidget.createAEFluidTransfer(inventory, getFluidSlotIndex(i)));
            }
            return storages;
        }

        @Override
        public List<FluidIngredient> handleRecipeInner(IO io, MBDRecipe recipe, List<FluidIngredient> left, @Nullable String slotName, boolean simulate) {
            if (!compatibleWith(io)) return left;
            var capabilities = simulate ? getSafeStorage() : getStorage();
            for (var capability : capabilities) {
                Iterator<FluidIngredient> iterator = left.iterator();
                if (io == IO.IN) {
                    while (iterator.hasNext()) {
                        FluidIngredient fluidStack = iterator.next();
                        if (fluidStack.isEmpty()) {
                            iterator.remove();
                            continue;
                        }
                        boolean found = false;
                        FluidStack foundStack = null;
                        for (int i = 0; i < capability.getTanks(); i++) {
                            FluidStack stored = capability.getFluidInTank(i);
                            if (!fluidStack.test(stored)) {
                                continue;
                            }
                            found = true;
                            foundStack = stored;
                        }
                        if (!found) continue;
                        FluidStack drained = capability.drain(foundStack.copy(fluidStack.getAmount()), false);
                        if (!simulate && !drained.isEmpty()) {
                            RecipeConsumptionTracker.record(FluidRecipeCapability.CAP, drained.copy(), slotName);
                        }

                        fluidStack.setAmount(fluidStack.getAmount() - drained.getAmount());
                        if (fluidStack.getAmount() <= 0) {
                            iterator.remove();
                        }
                    }
                } else if (io == IO.OUT) {
                    while (iterator.hasNext()) {
                        FluidIngredient fluidStack = iterator.next();
                        if (fluidStack.isEmpty()) {
                            iterator.remove();
                            continue;
                        }
                        var fluids = fluidStack.getStacks();
                        if (fluids.length == 0) {
                            iterator.remove();
                            continue;
                        }
                        FluidStack output = fluids[0];
                        long filled = capability.fill(output.copy(), false);
                        if (!fluidStack.isEmpty()) {
                            fluidStack.setAmount(fluidStack.getAmount() - filled);
                        }
                        if (fluidStack.getAmount() <= 0) {
                            iterator.remove();
                        }
                    }
                }
                if (left.isEmpty()) break;
            }
            return left.isEmpty() ? null : left;
        }
    }
}
