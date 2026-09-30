package com.lowdragmc.mbd2.integration.ae2.trait;

import appeng.api.behaviors.GenericInternalInventory;
import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.util.AECableType;
import appeng.capabilities.Capabilities;
import appeng.core.definitions.AEBlocks;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import com.lowdragmc.lowdraglib.misc.FluidStorage;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.fluid.forge.FluidHelperImpl;
import com.lowdragmc.lowdraglib.syncdata.IContentChangeAware;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.DropSaved;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.capability.recipe.IO;
import com.lowdragmc.mbd2.api.capability.recipe.IRecipeHandlerTrait;
import com.lowdragmc.mbd2.api.recipe.MBDRecipe;
import com.lowdragmc.mbd2.api.recipe.RecipeConsumptionTracker;
import com.lowdragmc.mbd2.api.recipe.RecipeGroup;
import com.lowdragmc.mbd2.api.recipe.ingredient.FluidIngredient;
import com.lowdragmc.mbd2.common.capability.recipe.FluidRecipeCapability;
import com.lowdragmc.mbd2.common.capability.recipe.ItemRecipeCapability;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.trait.ICapabilityProviderTrait;
import com.lowdragmc.mbd2.common.trait.RecipeHandlerTrait;
import com.lowdragmc.mbd2.common.trait.RecipeCapabilityTrait;
import com.lowdragmc.mbd2.common.trait.SimpleCapabilityTrait;
import com.lowdragmc.mbd2.common.trait.item.ItemHandlerWrapper;
import com.lowdragmc.mbd2.integration.ae2.storage.MEPatternKeyStorage;
import com.lowdragmc.mbd2.integration.appmek.AppliedMekanisticsCompat;
import lombok.Getter;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AE2 pattern provider trait that accepts pushed crafting inputs into machine-readable item, fluid and addon-key buffers.
 */
@Getter
public class MEPatternInputTrait extends SimpleCapabilityTrait implements IGridConnectedBlockEntity, InternalInventoryHost, PatternContainer, ICraftingProvider {
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(MEPatternInputTrait.class, RecipeCapabilityTrait.MANAGED_FIELD_HOLDER);
    private static final String NBT_PATTERNS = "patterns";
    private static final String NBT_AE_INPUTS = "aeInputs";
    private static final String NBT_ITEM_STORAGE = "itemStorage";
    private static final String NBT_FLUID_STORAGE = "fluidStorage";
    private static final String NBT_ITEM_GROUPS = "itemRecipeGroups";
    private static final String NBT_PATTERN_GROUPS = "patternRecipeGroups";
    private static final String NBT_PATTERN_KEY = "patternKey";
    private static final String NBT_RECIPE_GROUP = "mbd2RecipeGroup";
    private static final String NBT_SLOT = "slot";

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @DropSaved
    @Persisted
    private final SerializableManagedGridNode mainNode;
    @DropSaved
    @Persisted
    private final SerializableInputBuffer inputBuffer;
    @DropSaved
    @Persisted
    private final SerializablePatternInventory patternInventory;
    @DropSaved
    @Persisted
    private final SerializablePatternRecipeGroups patternRecipeGroups;
    @DropSaved
    @Persisted
    @DescSynced
    private final DynamicItemStorage itemStorage;
    @DropSaved
    @Persisted
    @DescSynced
    private final DynamicFluidStorage fluidStorage;
    @DropSaved
    @Persisted
    @DescSynced
    private final MEPatternKeyStorage chemicalStorage;
    @DropSaved
    @Persisted
    private int recipeGroupSeed = 0;

    private final List<IPatternDetails> patterns = new ArrayList<>();
    private final IActionSource actionSource;
    private boolean syncingFromInputBuffer;
    @Nullable
    private String currentRecipeGroup;
    private boolean blockingMode;
    private boolean smartBlocking;
    @Nullable
    private AEItemKey blockingPattern;

    private final ManagedGridNodeCap managedGridNodeCap = new ManagedGridNodeCap();
    private final GenericInternalInventoryCap genericInternalInventoryCap = new GenericInternalInventoryCap();
    private final StorageCap storageCap = new StorageCap();
    private final ItemHandlerCap itemHandlerCap = new ItemHandlerCap();
    private final FluidHandlerCap fluidHandlerCap = new FluidHandlerCap();
    private final ItemRecipeHandler itemRecipeHandler = new ItemRecipeHandler();
    private final FluidRecipeHandler fluidRecipeHandler = new FluidRecipeHandler();
    private final List<IRecipeHandlerTrait<?>> recipeHandlers;

    /**
     * Creates a pattern-input trait for a machine definition.
     * <p>
     * Side effects: creates the persisted AE2 grid node, AE-facing input buffer, encoded-pattern inventory, recipe-group
     * map, and typed item/fluid buffers. The grid node is attached to the world later from the server load lifecycle.
     * Runtime mutation should happen from the machine/server thread or AE2 callback context.
     *
     * @param machine    machine instance that owns this trait
     * @param definition pattern-input definition that supplies pattern slots and buffer limits
     */
    public MEPatternInputTrait(MBDMachine machine, MEPatternInputTraitDefinition definition) {
        super(machine, definition);
        mainNode = createMainNode();
        actionSource = IActionSource.ofMachine(mainNode::getNode);
        inputBuffer = createInputBuffer();
        patternInventory = createPatternInventory();
        patternRecipeGroups = new SerializablePatternRecipeGroups();
        itemStorage = createItemStorage();
        fluidStorage = createFluidStorage();
        chemicalStorage = new MEPatternKeyStorage(getDefinition().getChemicalInputLimit(),
                key -> MBD2.isAppliedMekanisticsLoaded() && AppliedMekanisticsCompat.isChemicalKey(key));
        chemicalStorage.setOnContentsChanged(this::onTypedStorageChanged);
        var handlers = new ArrayList<IRecipeHandlerTrait<?>>();
        handlers.add(itemRecipeHandler);
        handlers.add(fluidRecipeHandler);
        if (MBD2.isAppliedMekanisticsLoaded()) {
            handlers.addAll(AppliedMekanisticsCompat.createRecipeHandlers(this, () -> chemicalStorage, true));
        }
        recipeHandlers = List.copyOf(handlers);
    }

    /**
     * Builds the AE2 grid node that advertises this trait as a crafting provider.
     * <p>
     * The node is in-world, uses the machine drop item for display, and requests provider refreshes whenever AE2 reports
     * grid state changes.
     *
     * @return configured, not-yet-created managed grid node
     */
    protected SerializableManagedGridNode createMainNode() {
        return (SerializableManagedGridNode) new SerializableManagedGridNode(this, (nodeOwner, node) -> nodeOwner.updateCraftingProvider())
                .setVisualRepresentation(getMachine().getDropItem())
                .setInWorldNode(true)
                .setTagName("pattern_input")
                .addService(ICraftingProvider.class, this);
    }

    /**
     * Creates the AE-facing buffer that accepts generic pattern inputs.
     *
     * @return input buffer wired to this trait's content-change callback
     */
    protected SerializableInputBuffer createInputBuffer() {
        return new SerializableInputBuffer(this::onContentsChanged);
    }

    /**
     * Creates the encoded-pattern inventory exposed to AE2 pattern terminals.
     *
     * @return persistent pattern inventory sized from the trait definition
     */
    protected SerializablePatternInventory createPatternInventory() {
        return new SerializablePatternInventory(this, getDefinition().getPatternSlotSize());
    }

    /**
     * Creates the dynamic item buffer used by MBD recipes.
     * <p>
     * Side effect: installs a content-change callback that mirrors changes back to the AE-facing input buffer.
     *
     * @return empty dynamic item storage with recipe-group tracking
     */
    protected DynamicItemStorage createItemStorage() {
        var storage = new DynamicItemStorage();
        storage.setOnContentsChanged(this::onTypedStorageChanged);
        return storage;
    }

    /**
     * Creates the dynamic fluid buffer used by MBD recipes.
     * <p>
     * Side effect: installs a content-change callback that mirrors changes back to the AE-facing input buffer.
     *
     * @return empty dynamic fluid storage with recipe-group tracking
     */
    protected DynamicFluidStorage createFluidStorage() {
        var storage = new DynamicFluidStorage();
        storage.setOnContentsChanged(this::onTypedStorageChanged);
        return storage;
    }

    @Override
    public MEPatternInputTraitDefinition getDefinition() {
        return (MEPatternInputTraitDefinition) super.getDefinition();
    }

    /**
     * Handles any persisted inventory or buffer content change.
     * <p>
     * Side effects: marks the trait dirty through LDLib listeners and asks AE2 to refresh this crafting provider if the
     * node is active.
     */
    public void onContentsChanged() {
        notifyListeners();
        updateCraftingProvider();
    }

    /**
     * Handles changes to the typed item/fluid buffers.
     * <p>
     * Unless the change already came from {@link #inputBuffer}, this rebuilds the AE-facing generic buffer, refreshes
     * blocking-pattern state, and then runs the normal content-change path.
     */
    public void onTypedStorageChanged() {
        if (!syncingFromInputBuffer) {
            inputBuffer.rebuildFromTypedStorage();
        }
        updateBlockingPattern();
        onContentsChanged();
    }

    /**
     * Returns the block entity that hosts this in-world AE2 node.
     *
     * @return machine holder block entity
     */
    public BlockEntity getBlockEntity() {
        return getMachine().getHolder();
    }

    @Override
    public void saveChanges() {
        onChanged();
    }

    @Override
    public boolean isClientSide() {
        var level = getBlockEntity().getLevel();
        return level == null || level.isClientSide();
    }

    @Override
    public void onChangeInventory(InternalInventory inv, int slot) {
        updatePatterns();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        updateCraftingProvider();
    }

    /**
     * Requests an AE2 crafting-provider refresh for this node.
     * <p>
     * The request is skipped until the node is active. AE2 processes the update on its normal network tick path.
     */
    protected void updateCraftingProvider() {
        if (getMainNode().isActive()) {
            ICraftingProvider.requestUpdate(mainNode);
        }
    }

    /**
     * Rebuilds the decoded pattern cache from the persistent pattern inventory.
     * <p>
     * Invalid or undecodable stacks are ignored. Side effect: clears and repopulates {@link #patterns}, then notifies AE2
     * that the provider's advertised patterns may have changed.
     */
    protected void updatePatterns() {
        patterns.clear();
        for (var stack : patternInventory) {
            var details = PatternDetailsHelper.decodePattern(stack, getMachine().getLevel());
            if (details != null) {
                patterns.add(details);
            }
        }
        updateCraftingProvider();
    }

    /**
     * Gets the icon shown for this trait in machine UI menus.
     *
     * @return AE2 pattern provider block stack
     */
    public ItemStack getMainMenuIcon() {
        return AEBlocks.PATTERN_PROVIDER.stack();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART;
    }

    @Override
    public List<ICapabilityProviderTrait<?>> getCapabilityProviderTraits() {
        return List.of(managedGridNodeCap, genericInternalInventoryCap, storageCap, itemHandlerCap, fluidHandlerCap);
    }

    @Override
    public List<IRecipeHandlerTrait<?>> getRecipeHandlerTraits() {
        return recipeHandlers;
    }

    private String createNextRecipeGroup() {
        for (int attempts = 0; attempts < 4096; attempts++) {
            recipeGroupSeed++;
            var group = RecipeGroup.fromHash(recipeGroupSeed);
            if (!hasStoredRecipeGroup(group) && !patternRecipeGroups.containsGroup(group)) {
                return group;
            }
        }
        return RecipeGroup.fromHash(recipeGroupSeed);
    }

    private boolean hasStoredRecipeGroup(String recipeGroup) {
        return itemStorage.getRecipeGroups().contains(recipeGroup) || fluidStorage.getRecipeGroups().contains(recipeGroup)
                || chemicalStorage.getRecipeGroups().contains(recipeGroup);
    }

    private String getPatternRecipeGroup(IPatternDetails patternDetails) {
        var definition = patternDetails.getDefinition();
        var recipeGroup = patternRecipeGroups.get(definition);
        if (recipeGroup != null) {
            return recipeGroup;
        }
        recipeGroup = createNextRecipeGroup();
        patternRecipeGroups.put(definition, recipeGroup);
        onChanged();
        return recipeGroup;
    }

    private static boolean matchesRecipeGroup(@Nullable String requestedRecipeGroup, String storedRecipeGroup) {
        return RecipeGroup.matches(RecipeGroup.normalizeOrDefault(requestedRecipeGroup), storedRecipeGroup);
    }

    @Override
    public IO getCapabilityIO(@Nullable Direction side) {
        return super.getCapabilityIO(side).support(IO.IN) ? IO.IN : IO.NONE;
    }

    @Override
    public void onMachineDrop(Entity entity, List<ItemStack> drops) {
        if (getMachine().shouldKeepDropItemNbt()) {
            return;
        }
        for (var stack : patternInventory) {
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }
        // inputBuffer derives its visible contents from the typed item and fluid buffers.
        var stacks = new KeyCounter();
        inputBuffer.getAvailableStacks(stacks);
        for (var stack : stacks) {
            stack.getKey().addDrops(stack.getLongValue(), drops, getMachine().getLevel(), getMachine().getPos());
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        this.getMainNode().destroy();
    }

    @Override
    public void onMachineLoad() {
        super.onMachineLoad();
        if (getMachine().getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(0, () -> {
                this.getMainNode().create(serverLevel, getBlockEntity().getBlockPos());
                updatePatterns();
            }));
        }
    }

    @Override
    public void onMachineUnLoad() {
        super.onMachineUnLoad();
        this.getMainNode().destroy();
    }

    @Override
    public IGrid getGrid() {
        return mainNode.getGrid();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return patternInventory;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        var icon = AEItemKey.of(getMainMenuIcon());
        return new PatternContainerGroup(icon, Component.literal(getMachine().getDefinition().id().toString()), List.of());
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return patterns;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!mainNode.isActive() || !patterns.contains(patternDetails)) {
            return false;
        }
        if (isBlockingPattern(patternDetails)) {
            return false;
        }
        var recipeGroup = getPatternRecipeGroup(patternDetails);
        if (!canAcceptInputs(inputHolder, recipeGroup)) {
            return false;
        }
        if (pushInputs(patternDetails, inputHolder, inputBuffer, recipeGroup)) {
            updateBlockingPattern(patternDetails);
            return true;
        }
        return false;
    }

    /**
     * Reports whether strict blocking mode is enabled.
     *
     * @return {@code true} when any stored inputs should block additional pattern pushes until cleared
     */
    public boolean isBlockingMode() {
        return blockingMode;
    }

    /**
     * Enables or disables strict blocking mode.
     * <p>
     * Side effect: clears the tracked blocking pattern when both blocking modes are disabled, otherwise refreshes it from
     * current stored inputs.
     *
     * @param blockingMode {@code true} to reject new pattern pushes while inputs remain buffered
     */
    public void setBlockingMode(boolean blockingMode) {
        this.blockingMode = blockingMode;
        if (!blockingMode && !smartBlocking) {
            clearBlockingPattern();
        } else {
            updateBlockingPattern();
        }
    }

    /**
     * Reports whether smart blocking mode is enabled.
     *
     * @return {@code true} when stored inputs only block pushes from different pattern definitions
     */
    public boolean isSmartBlocking() {
        return smartBlocking;
    }

    /**
     * Enables or disables smart blocking mode.
     * <p>
     * Side effect: clears the tracked blocking pattern when both blocking modes are disabled, otherwise refreshes it from
     * current stored inputs.
     *
     * @param smartBlocking {@code true} to allow repeats of the same pattern while blocking different patterns
     */
    public void setSmartBlocking(boolean smartBlocking) {
        this.smartBlocking = smartBlocking;
        if (!blockingMode && !smartBlocking) {
            clearBlockingPattern();
        } else {
            updateBlockingPattern();
        }
    }

    private boolean isBlockingPattern(IPatternDetails patternDetails) {
        if (!blockingMode && !smartBlocking) {
            return false;
        }
        if (!hasStoredInputs()) {
            clearBlockingPattern();
            return false;
        }
        if (!smartBlocking) {
            return blockingMode;
        }
        updateBlockingPattern();
        return blockingPattern != null && !blockingPattern.equals(patternDetails.getDefinition());
    }

    private boolean hasStoredInputs() {
        if (itemStorage.hasStoredInputs()) {
            return true;
        }
        return fluidStorage.hasStoredInputs() || !chemicalStorage.isEmpty();
    }

    private void updateBlockingPattern(IPatternDetails patternDetails) {
        if ((blockingMode || smartBlocking) && hasStoredInputs() && blockingPattern == null) {
            blockingPattern = patternDetails.getDefinition();
        }
    }

    private void updateBlockingPattern() {
        if (!hasStoredInputs()) {
            clearBlockingPattern();
        }
    }

    private void clearBlockingPattern() {
        blockingPattern = null;
    }

    /**
     * Ejects all buffered item, fluid and chemical inputs into the connected AE2 network.
     * <p>
     * Side effects: removes successfully inserted inputs from local buffers and may clear or update blocking-pattern
     * state. Nothing is ejected when the node is inactive or has no grid.
     *
     * @return total item-count plus fluid-amount inserted into the network
     */
    public long ejectInputsToNetwork() {
        return MEPatternKeyStorage.saturatedAdd(
                MEPatternKeyStorage.saturatedAdd(ejectItemsToNetwork(), ejectFluidsToNetwork()), ejectChemicalsToNetwork());
    }

    /**
     * Ejects buffered item inputs into the connected AE2 network.
     * <p>
     * Side effects: removes successfully inserted item counts from {@link #itemStorage}. The method must run with a live
     * server-side grid and returns {@code 0} when no network storage is available.
     *
     * @return total item count inserted into AE2 storage
     */
    public long ejectItemsToNetwork() {
        if (!mainNode.isActive() || getGrid() == null) {
            return 0;
        }
        var networkStorage = getGrid().getStorageService().getInventory();
        var ejected = 0L;
        for (int slot = itemStorage.getSlots() - 1; slot >= 0; slot--) {
            var stack = itemStorage.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var inserted = networkStorage.insert(AEItemKey.of(stack), stack.getCount(), Actionable.MODULATE, actionSource);
            if (inserted > 0) {
                itemStorage.extractItem(slot, (int) Math.min(inserted, Integer.MAX_VALUE), false);
                ejected = MEPatternKeyStorage.saturatedAdd(ejected, inserted);
            }
        }
        return ejected;
    }

    /**
     * Ejects buffered fluid inputs into the connected AE2 network.
     * <p>
     * Side effects: drains successfully inserted fluid amounts from {@link #fluidStorage}, compacts empty tanks, and
     * refreshes blocking-pattern state. The method returns {@code 0} when the node is inactive or has no grid.
     *
     * @return total fluid amount inserted into AE2 storage
     */
    public long ejectFluidsToNetwork() {
        if (!mainNode.isActive() || getGrid() == null) {
            return 0;
        }
        var networkStorage = getGrid().getStorageService().getInventory();
        var ejected = 0L;
        for (int tank = fluidStorage.getStorages().size() - 1; tank >= 0; tank--) {
            var storage = fluidStorage.getStorages().get(tank);
            var stack = storage.getFluid();
            if (stack.isEmpty()) {
                continue;
            }
            var key = AEFluidKey.of(FluidHelperImpl.toFluidStack(stack));
            if (key == null) {
                continue;
            }
            var inserted = networkStorage.insert(key, stack.getAmount(), Actionable.MODULATE, actionSource);
            if (inserted > 0) {
                storage.drain(0, stack.copy(inserted), false, true);
                ejected = MEPatternKeyStorage.saturatedAdd(ejected, inserted);
            }
        }
        fluidStorage.removeEmptyStorages();
        updateBlockingPattern();
        return ejected;
    }

    /** Returns buffered chemicals to AE2, retaining any amount the network cannot accept. */
    public long ejectChemicalsToNetwork() {
        if (!mainNode.isActive() || getGrid() == null) return 0;
        var networkStorage = getGrid().getStorageService().getInventory();
        long ejected = 0;
        for (var entry : chemicalStorage.getEntries()) {
            var stack = entry.stack();
            long inserted = networkStorage.insert(stack.what(), stack.amount(), Actionable.MODULATE, actionSource);
            if (inserted > 0) {
                chemicalStorage.extract(stack.what(), inserted, entry.recipeGroup());
                ejected = MEPatternKeyStorage.saturatedAdd(ejected, inserted);
            }
        }
        return ejected;
    }

    private boolean canAcceptInputs(KeyCounter[] inputHolder, String recipeGroup) {
        var simulatedItems = itemStorage.copy();
        var simulatedFluids = fluidStorage.copy();
        var simulatedChemicals = chemicalStorage.copy();
        for (var counter : inputHolder) {
            for (var entry : counter) {
                if (insertTypedStorage(entry.getKey(), entry.getLongValue(), simulatedItems, simulatedFluids, simulatedChemicals, recipeGroup) < entry.getLongValue()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean pushInputs(IPatternDetails patternDetails, KeyCounter[] inputHolder, MEStorage storage, String recipeGroup) {
        var accepted = new boolean[]{true};
        var previousRecipeGroup = currentRecipeGroup;
        currentRecipeGroup = recipeGroup;
        try {
            patternDetails.pushInputsToExternalInventory(inputHolder, (what, amount) -> {
                if (accepted[0] && storage.insert(what, amount, Actionable.MODULATE, actionSource) < amount) {
                    accepted[0] = false;
                }
            });
            return accepted[0];
        } finally {
            currentRecipeGroup = previousRecipeGroup;
        }
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Override
    public Set<AEKey> getEmitableItems() {
        return Collections.emptySet();
    }

    /**
     * Exposes this pattern input as an in-world AE2 grid node host.
     */
    public class ManagedGridNodeCap implements ICapabilityProviderTrait<IInWorldGridNodeHost> {
        @Override
        public IO getCapabilityIO(@Nullable Direction side) {
            return MEPatternInputTrait.this.getCapabilityIO(side);
        }

        @Override
        public Capability<IInWorldGridNodeHost> getCapability() {
            return Capabilities.IN_WORLD_GRID_NODE_HOST;
        }

        @Override
        public IInWorldGridNodeHost getCapContent(IO capabilityIO) {
            return capabilityIO != IO.NONE ? MEPatternInputTrait.this : null;
        }
    }

    /**
     * Exposes the AE input buffer as AE2's generic internal inventory.
     */
    public class GenericInternalInventoryCap implements ICapabilityProviderTrait<GenericInternalInventory> {
        @Override
        public IO getCapabilityIO(@Nullable Direction side) {
            return MEPatternInputTrait.this.getCapabilityIO(side);
        }

        @Override
        public Capability<GenericInternalInventory> getCapability() {
            return Capabilities.GENERIC_INTERNAL_INV;
        }

        @Override
        public GenericInternalInventory getCapContent(IO capabilityIO) {
            return capabilityIO != IO.NONE ? inputBuffer : null;
        }
    }

    /**
     * Exposes the AE input buffer as ME storage so the network can push pattern inputs.
     */
    public class StorageCap implements ICapabilityProviderTrait<MEStorage> {
        @Override
        public IO getCapabilityIO(@Nullable Direction side) {
            return MEPatternInputTrait.this.getCapabilityIO(side);
        }

        @Override
        public Capability<MEStorage> getCapability() {
            return Capabilities.STORAGE;
        }

        @Override
        public MEStorage getCapContent(IO capabilityIO) {
            return capabilityIO != IO.NONE ? inputBuffer : null;
        }
    }

    /**
     * Provides a Forge item handler view over stored pattern inputs.
     */
    public class ItemHandlerCap implements ICapabilityProviderTrait<IItemHandler> {
        @Override
        public IO getCapabilityIO(@Nullable Direction side) {
            return MEPatternInputTrait.this.getCapabilityIO(side);
        }

        @Override
        public Capability<IItemHandler> getCapability() {
            return ForgeCapabilities.ITEM_HANDLER;
        }

        @Override
        public IItemHandler getCapContent(IO capabilityIO) {
            return capabilityIO != IO.NONE ? new ItemHandlerWrapper(itemStorage, IO.IN) : null;
        }
    }

    /**
     * Provides a Forge fluid handler view over stored pattern inputs.
     */
    public class FluidHandlerCap implements ICapabilityProviderTrait<IFluidHandler> {
        @Override
        public IO getCapabilityIO(@Nullable Direction side) {
            return MEPatternInputTrait.this.getCapabilityIO(side);
        }

        @Override
        public Capability<IFluidHandler> getCapability() {
            return ForgeCapabilities.FLUID_HANDLER;
        }

        @Override
        public IFluidHandler getCapContent(IO capabilityIO) {
            return capabilityIO != IO.NONE ? fluidStorage : null;
        }
    }

    /**
     * Consumes item inputs produced by AE2 patterns, optionally filtered by recipe group.
     */
    public class ItemRecipeHandler extends RecipeHandlerTrait<net.minecraft.world.item.crafting.Ingredient> {
        /**
         * Creates the item recipe handler attached to the outer pattern-input trait.
         * <p>
         * The handler consumes only stored inputs; it does not request new inputs from AE2. Use it from normal
         * recipe-processing/server logic.
         */
        protected ItemRecipeHandler() {
            super(MEPatternInputTrait.this, ItemRecipeCapability.CAP);
        }

        /**
         * Selects the item buffer used for recipe matching.
         * <p>
         * Simulation receives a serialized copy so extraction attempts cannot mutate persisted inputs. Non-simulated
         * handling returns the live dynamic storage and will remove consumed items.
         *
         * @param simulate {@code true} to return an isolated copy, {@code false} to return live storage
         * @return item storage used by this recipe pass
         */
        protected DynamicItemStorage getStorage(boolean simulate) {
            return simulate ? itemStorage.copy() : itemStorage;
        }

        @Override
        public List<net.minecraft.world.item.crafting.Ingredient> handleRecipeInner(IO io, MBDRecipe recipe, List<net.minecraft.world.item.crafting.Ingredient> left, @Nullable String slotName, boolean simulate) {
            return handleRecipeInner(io, recipe, left, slotName, simulate, null);
        }

        @Override
        public List<net.minecraft.world.item.crafting.Ingredient> handleRecipe(IO io, MBDRecipe recipe, List<?> left, @Nullable String slotName, boolean simulate, @Nullable String recipeGroup) {
            var copied = new ArrayList<net.minecraft.world.item.crafting.Ingredient>(left.size());
            for (var content : left) {
                copied.add(copyContent(content));
            }
            return handleRecipeInner(io, recipe, copied, slotName, simulate, recipeGroup);
        }

        @Override
        public Set<String> getRecipeGroups() {
            return itemStorage.getRecipeGroups();
        }

        private List<net.minecraft.world.item.crafting.Ingredient> handleRecipeInner(IO io, MBDRecipe recipe, List<net.minecraft.world.item.crafting.Ingredient> left, @Nullable String slotName, boolean simulate, @Nullable String recipeGroup) {
            if (!compatibleWith(io)) return left;
            var capability = getStorage(simulate);
            var iterator = left.iterator();
            if (io == IO.IN) {
                while (iterator.hasNext()) {
                    var ingredient = iterator.next();
                    SLOT_LOOKUP:
                    for (int i = 0; i < capability.getSlots(); i++) {
                        if (!capability.matchesRecipeGroup(i, recipeGroup)) {
                            continue;
                        }
                        var itemStack = capability.getStackInSlot(i);
                        if (ingredient.test(itemStack)) {
                            for (var ingredientStack : ingredient.getItems()) {
                                if (ingredientStack.is(itemStack.getItem())) {
                                    var extracted = capability.extractItem(i, ingredientStack.getCount(), false);
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
            }
            return left.isEmpty() ? null : left;
        }
    }

    /**
     * Consumes fluid inputs produced by AE2 patterns, optionally filtered by recipe group.
     */
    public class FluidRecipeHandler extends RecipeHandlerTrait<FluidIngredient> {
        /**
         * Creates the fluid recipe handler attached to the outer pattern-input trait.
         * <p>
         * The handler consumes only stored inputs; it does not request new inputs from AE2. Use it from normal
         * recipe-processing/server logic.
         */
        protected FluidRecipeHandler() {
            super(MEPatternInputTrait.this, FluidRecipeCapability.CAP);
        }

        /**
         * Selects the fluid buffer used for recipe matching.
         * <p>
         * Simulation receives a serialized copy so drain attempts cannot mutate persisted inputs. Non-simulated handling
         * returns the live dynamic storage and will remove consumed fluids.
         *
         * @param simulate {@code true} to return an isolated copy, {@code false} to return live storage
         * @return fluid storage used by this recipe pass
         */
        protected DynamicFluidStorage getStorage(boolean simulate) {
            return simulate ? fluidStorage.copy() : fluidStorage;
        }

        @Override
        public List<FluidIngredient> handleRecipeInner(IO io, MBDRecipe recipe, List<FluidIngredient> left, @Nullable String slotName, boolean simulate) {
            return handleRecipeInner(io, recipe, left, slotName, simulate, null);
        }

        @Override
        public List<FluidIngredient> handleRecipe(IO io, MBDRecipe recipe, List<?> left, @Nullable String slotName, boolean simulate, @Nullable String recipeGroup) {
            var copied = new ArrayList<FluidIngredient>(left.size());
            for (var content : left) {
                copied.add(copyContent(content));
            }
            return handleRecipeInner(io, recipe, copied, slotName, simulate, recipeGroup);
        }

        @Override
        public Set<String> getRecipeGroups() {
            return fluidStorage.getRecipeGroups();
        }

        private List<FluidIngredient> handleRecipeInner(IO io, MBDRecipe recipe, List<FluidIngredient> left, @Nullable String slotName, boolean simulate, @Nullable String recipeGroup) {
            if (!compatibleWith(io)) return left;
            var capability = getStorage(simulate);
            Iterator<FluidIngredient> iterator = left.iterator();
            if (io == IO.IN) {
                while (iterator.hasNext()) {
                    var fluidStack = iterator.next();
                    if (fluidStack.isEmpty()) {
                        iterator.remove();
                        continue;
                    }
                    com.lowdragmc.lowdraglib.side.fluid.FluidStack foundStack = null;
                    for (int i = 0; i < capability.getStorages().size(); i++) {
                        if (!capability.matchesRecipeGroup(i, recipeGroup)) {
                            continue;
                        }
                        var storage = capability.getStorages().get(i);
                        var stored = storage.getFluid();
                        if (fluidStack.test(stored)) {
                            foundStack = stored;
                            break;
                        }
                    }
                    if (foundStack == null) continue;
                    var drained = capability.drainStored(foundStack.copy(fluidStack.getAmount()), false, recipeGroup);
                    if (!simulate && !drained.isEmpty()) {
                        RecipeConsumptionTracker.record(FluidRecipeCapability.CAP, drained.copy(), slotName);
                    }

                    fluidStack.setAmount(fluidStack.getAmount() - drained.getAmount());
                    if (fluidStack.getAmount() <= 0) {
                        iterator.remove();
                    }
                }
            }
            return left.isEmpty() ? null : left;
        }
    }

    /**
     * AE-facing input buffer that routes pushed stacks to item, fluid and chemical storage.
     */
    public class SerializableInputBuffer extends GenericStackInv implements ITagSerializable<CompoundTag>, IContentChangeAware {
        @Getter
        private Runnable onContentsChanged = () -> {
        };

        /**
         * Creates an AE2 generic-stack input buffer.
         * <p>
         * The buffer starts with zero direct slots because pushed inputs are mirrored into typed item/fluid buffers.
         * Passing {@code null} installs a no-op listener.
         *
         * @param listener callback invoked when AE-facing contents change, or {@code null} for no callback
         */
        public SerializableInputBuffer(@Nullable Runnable listener) {
            super(listener, 0);
            setOnContentsChanged(listener == null ? () -> {
            } : listener);
        }

        @Override
        public void setOnContentsChanged(Runnable onContentsChanged) {
            this.onContentsChanged = onContentsChanged == null ? () -> {
            } : onContentsChanged;
        }

        @Override
        protected void notifyListener() {
            super.notifyListener();
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            var typedInserted = insertTypedStorage(what, amount, true);
            if (typedInserted <= 0) {
                return 0;
            }
            if (mode == Actionable.MODULATE) {
                var inserted = 0L;
                try {
                    syncingFromInputBuffer = true;
                    inserted = insertTypedStorage(what, typedInserted, false);
                    if (inserted > 0) {
                        rebuildFromTypedStorage();
                    }
                } finally {
                    syncingFromInputBuffer = false;
                }
                return inserted;
            }
            return typedInserted;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            for (int i = 0; i < itemStorage.getSlots(); i++) {
                var stack = itemStorage.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    out.add(AEItemKey.of(stack), stack.getCount());
                }
            }
            for (var storage : fluidStorage.getStorages()) {
                var stack = storage.getFluid();
                if (!stack.isEmpty()) {
                    var key = AEFluidKey.of(FluidHelperImpl.toFluidStack(stack));
                    if (key != null) {
                        out.add(key, stack.getAmount());
                    }
                }
            }
            chemicalStorage.getAvailableStacks(out);
        }

        @Override
        public MutableComponent getDescription() {
            return Component.translatable("config.definition.trait.ae2_me_pattern_input");
        }

        /**
         * Rebuilds the AE-facing generic-stack view from typed item and fluid buffers.
         * <p>
         * Side effects: clears existing generic stacks and reinserts all non-empty typed stacks with this trait's
         * {@link #actionSource}. Recipe-group metadata remains owned by the typed buffers.
         */
        public void rebuildFromTypedStorage() {
            clear();
            for (int i = 0; i < itemStorage.getSlots(); i++) {
                var stack = itemStorage.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    super.insert(AEItemKey.of(stack), stack.getCount(), Actionable.MODULATE, actionSource);
                }
            }
            for (var storage : fluidStorage.getStorages()) {
                var stack = storage.getFluid();
                if (!stack.isEmpty()) {
                    var key = AEFluidKey.of(FluidHelperImpl.toFluidStack(stack));
                    if (key != null) {
                        super.insert(key, stack.getAmount(), Actionable.MODULATE, actionSource);
                    }
                }
            }
        }

        /**
         * Creates a detached copy of this input buffer.
         *
         * @return new buffer populated from this buffer's serialized AE stack data
         */
        public SerializableInputBuffer copy() {
            var copied = new SerializableInputBuffer(null);
            copied.readFromTag(writeToTag());
            return copied;
        }

        private long insertTypedStorage(AEKey what, long amount, boolean simulate) {
            return MEPatternInputTrait.this.insertTypedStorage(what, amount,
                    simulate ? itemStorage.copy() : itemStorage, simulate ? fluidStorage.copy() : fluidStorage,
                    simulate ? chemicalStorage.copy() : chemicalStorage, currentRecipeGroup);
        }

        /**
         * Serializes the AE-facing generic-stack data.
         *
         * @return tag containing the generic-stack list under {@link #NBT_AE_INPUTS}
         */
        public CompoundTag serializeNBT() {
            var tag = new CompoundTag();
            tag.put(NBT_AE_INPUTS, writeToTag());
            return tag;
        }

        /**
         * Restores the AE-facing generic-stack data.
         * <p>
         * The caller is responsible for separately restoring typed buffers when loading a full trait snapshot.
         *
         * @param tag serialized buffer tag; missing input data restores an empty generic-stack list
         */
        public void deserializeNBT(CompoundTag tag) {
            readFromTag(tag.getList(NBT_AE_INPUTS, net.minecraft.nbt.Tag.TAG_COMPOUND));
        }
    }

    /**
     * Persistent encoded-pattern inventory used by the AE2 terminal and crafting provider.
     */
    public static class SerializablePatternInventory extends AppEngInternalInventory implements ITagSerializable<CompoundTag>, IContentChangeAware {
        @Getter
        private Runnable onContentsChanged = () -> {
        };

        /**
         * Creates a persistent encoded-pattern inventory.
         * <p>
         * The inventory accepts one item per slot and filters inserts to encoded AE2 patterns.
         *
         * @param host AE2 internal-inventory host notified when slots change
         * @param size number of pattern slots; must be non-negative
         */
        public SerializablePatternInventory(InternalInventoryHost host, int size) {
            super(host, size, 1, new PatternItemFilter());
        }

        @Override
        public void setOnContentsChanged(Runnable onContentsChanged) {
            this.onContentsChanged = onContentsChanged == null ? () -> {
            } : onContentsChanged;
        }

        @Override
        protected void onContentsChanged(int slot) {
            super.onContentsChanged(slot);
            onContentsChanged.run();
        }

        /**
         * Serializes encoded pattern stacks.
         *
         * @return tag containing the inventory under {@link #NBT_PATTERNS}
         */
        public CompoundTag serializeNBT() {
            var tag = new CompoundTag();
            writeToNBT(tag, NBT_PATTERNS);
            return tag;
        }

        /**
         * Restores encoded pattern stacks from NBT.
         *
         * @param tag serialized inventory tag; missing pattern data leaves slots empty
         */
        public void deserializeNBT(CompoundTag tag) {
            readFromNBT(tag, NBT_PATTERNS);
        }
    }

    /**
     * Persistent mapping from encoded pattern definitions to MBD recipe groups.
     */
    public static class SerializablePatternRecipeGroups implements ITagSerializable<CompoundTag>, IContentChangeAware {
        @Getter
        private Runnable onContentsChanged = () -> {
        };
        private final Map<AEItemKey, String> groups = new LinkedHashMap<>();

        @Override
        public void setOnContentsChanged(Runnable onContentsChanged) {
            this.onContentsChanged = onContentsChanged == null ? () -> {
            } : onContentsChanged;
        }

        /**
         * Looks up the MBD recipe group assigned to an encoded pattern definition.
         *
         * @param definition AE item key representing the encoded pattern stack
         * @return normalized recipe group, or {@code null} when the pattern has no assigned group
         */
        @Nullable
        public String get(AEItemKey definition) {
            return groups.get(definition);
        }

        /**
         * Assigns a recipe group to an encoded pattern definition.
         * <p>
         * Side effect: normalizes the group name, updates the persistent map, and invokes the content-change listener.
         *
         * @param definition  AE item key representing the encoded pattern stack
         * @param recipeGroup group name to store; blank or invalid values normalize through {@link RecipeGroup#normalize(String)}
         */
        public void put(AEItemKey definition, String recipeGroup) {
            groups.put(definition, RecipeGroup.normalize(recipeGroup));
            onContentsChanged.run();
        }

        /**
         * Checks whether any encoded pattern is assigned to a recipe group.
         *
         * @param recipeGroup requested group name; {@code null} or blank maps to the default group
         * @return {@code true} when a stored pattern uses the normalized group
         */
        public boolean containsGroup(String recipeGroup) {
            return groups.containsValue(RecipeGroup.normalizeOrDefault(recipeGroup));
        }

        /**
         * Serializes pattern-definition to recipe-group assignments.
         *
         * @return tag containing all assignments under {@link #NBT_PATTERN_GROUPS}
         */
        public CompoundTag serializeNBT() {
            var tag = new CompoundTag();
            var list = new ListTag();
            for (var entry : groups.entrySet()) {
                var groupTag = new CompoundTag();
                groupTag.put(NBT_PATTERN_KEY, entry.getKey().toTagGeneric());
                groupTag.putString(NBT_RECIPE_GROUP, RecipeGroup.normalize(entry.getValue()));
                list.add(groupTag);
            }
            tag.put(NBT_PATTERN_GROUPS, list);
            return tag;
        }

        /**
         * Restores pattern-definition to recipe-group assignments.
         * <p>
         * Invalid or non-item AE keys are ignored. Existing assignments are cleared before loading.
         *
         * @param tag serialized assignment tag
         */
        public void deserializeNBT(CompoundTag tag) {
            groups.clear();
            var list = tag.getList(NBT_PATTERN_GROUPS, net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                var groupTag = list.getCompound(i);
                var patternKey = AEKey.fromTagGeneric(groupTag.getCompound(NBT_PATTERN_KEY));
                if (patternKey instanceof AEItemKey itemKey) {
                    groups.put(itemKey, RecipeGroup.normalizeOrDefault(groupTag.getString(NBT_RECIPE_GROUP)));
                }
            }
        }
    }

    private long insertTypedStorage(AEKey what, long amount, DynamicItemStorage itemStorage, DynamicFluidStorage fluidStorage, MEPatternKeyStorage chemicalStorage, @Nullable String recipeGroup) {
        var normalizedRecipeGroup = RecipeGroup.normalizeOrDefault(recipeGroup);
        if (what instanceof AEItemKey itemKey) {
            var remaining = amount;
            while (remaining > 0) {
                var stack = itemKey.toStack((int) Math.min(remaining, Integer.MAX_VALUE));
                var inserted = itemStorage.insertDynamic(stack, normalizedRecipeGroup);
                if (inserted <= 0) {
                    break;
                }
                remaining -= inserted;
            }
            return amount - remaining;
        } else if (what instanceof AEFluidKey fluidKey) {
            var remaining = amount;
            while (remaining > 0) {
                var stack = FluidHelperImpl.toFluidStack(fluidKey.toStack((int) Math.min(remaining, Integer.MAX_VALUE)));
                var inserted = fluidStorage.fillDynamic(stack, normalizedRecipeGroup);
                if (inserted <= 0) {
                    break;
                }
                remaining -= inserted;
            }
            return amount - remaining;
        }
        return chemicalStorage.insert(what, amount, normalizedRecipeGroup);
    }

    /**
     * Dynamic item input storage that keeps each compacted slot associated with its recipe group.
     */
    public class DynamicItemStorage extends ItemStackTransfer {
        private List<String> recipeGroups = new ArrayList<>();

        /**
         * Creates an empty compacting item buffer.
         * <p>
         * Slots are added lazily when inputs arrive, and each non-empty slot has matching recipe-group metadata.
         */
        public DynamicItemStorage() {
            super(0);
        }

        @Override
        public int getSlotLimit(int slot) {
            return MEPatternInputTrait.this.getDefinition().getItemInputLimit();
        }

        @Override
        public void setSize(int size) {
            var resized = NonNullList.withSize(size, ItemStack.EMPTY);
            var resizedRecipeGroups = new ArrayList<String>(size);
            for (int i = 0; i < Math.min(size, stacks.size()); i++) {
                resized.set(i, stacks.get(i));
            }
            for (int i = 0; i < size; i++) {
                resizedRecipeGroups.add(i < recipeGroups.size() ? RecipeGroup.normalizeOrDefault(recipeGroups.get(i)) : RecipeGroup.DEFAULT);
            }
            stacks = resized;
            recipeGroups = resizedRecipeGroups;
        }

        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            return getSlotLimit(slot);
        }

        /**
         * Inserts an item stack into the default recipe group.
         *
         * @param stack stack to store; empty stacks are ignored
         * @return number of items accepted, in {@code [0, stack.getCount()]}
         */
        public long insertDynamic(ItemStack stack) {
            return insertDynamic(stack, RecipeGroup.DEFAULT);
        }

        /**
         * Inserts an item stack into a recipe-group aware dynamic slot.
         * <p>
         * Existing compatible slots are reused only when item, tag, and recipe group all match. Otherwise an empty or new
         * slot is assigned. Side effects: may grow the storage, update slot recipe-group metadata, and fire the inherited
         * content-change callback through {@link #insertItem(int, ItemStack, boolean)}.
         *
         * @param stack       stack to store; empty stacks are ignored
         * @param recipeGroup recipe group to associate with accepted items; {@code null} maps to the default group
         * @return number of items accepted, in {@code [0, stack.getCount()]}
         */
        public long insertDynamic(ItemStack stack, @Nullable String recipeGroup) {
            if (stack.isEmpty()) {
                return 0;
            }
            var normalizedRecipeGroup = RecipeGroup.normalizeOrDefault(recipeGroup);
            var remaining = stack.copy();
            var slot = findSlot(remaining, normalizedRecipeGroup);
            if (slot < 0) {
                setSize(getSlots() + 1);
                slot = getSlots() - 1;
            }
            setSlotRecipeGroup(slot, normalizedRecipeGroup);
            var originalCount = remaining.getCount();
            remaining = insertItem(slot, remaining, false);
            return originalCount - remaining.getCount();
        }

        @Override
        public void onContentsChanged(int slot) {
            compactEmptySlots();
            super.onContentsChanged(Math.min(slot, Math.max(getSlots() - 1, 0)));
        }

        private void compactEmptySlots() {
            var compacted = NonNullList.<ItemStack>create();
            var compactedRecipeGroups = new ArrayList<String>();
            for (int i = 0; i < stacks.size(); i++) {
                var stored = stacks.get(i);
                if (!stored.isEmpty()) {
                    compacted.add(stored);
                    compactedRecipeGroups.add(getSlotRecipeGroup(i));
                }
            }
            stacks = compacted;
            recipeGroups = compactedRecipeGroups;
        }

        /**
         * Reports whether any item inputs are currently buffered.
         *
         * @return {@code true} when at least one compacted slot is non-empty
         */
        public boolean hasStoredInputs() {
            for (var stored : stacks) {
                if (!stored.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the recipe groups currently represented by non-empty item slots.
         *
         * @return ordered set of normalized recipe-group names
         */
        public Set<String> getRecipeGroups() {
            var groups = new LinkedHashSet<String>();
            for (int i = 0; i < getSlots(); i++) {
                if (!getStackInSlot(i).isEmpty()) {
                    groups.add(getSlotRecipeGroup(i));
                }
            }
            return groups;
        }

        /**
         * Checks whether a slot may satisfy a recipe-group request.
         *
         * @param slot        zero-based slot index
         * @param recipeGroup requested group; {@code null} or blank maps to the default group
         * @return {@code true} when the slot's stored group matches the normalized request
         */
        public boolean matchesRecipeGroup(int slot, @Nullable String recipeGroup) {
            return MEPatternInputTrait.matchesRecipeGroup(recipeGroup, getSlotRecipeGroup(slot));
        }

        private int findSlot(ItemStack stack, String recipeGroup) {
            var emptySlot = -1;
            for (int i = 0; i < getSlots(); i++) {
                var stored = getStackInSlot(i);
                if (stored.isEmpty()) {
                    if (emptySlot < 0) {
                        emptySlot = i;
                    }
                    continue;
                }
                if (ItemStack.isSameItemSameTags(stored, stack) && recipeGroup.equals(getSlotRecipeGroup(i))) {
                    return i;
                }
            }
            return emptySlot;
        }

        private String getSlotRecipeGroup(int slot) {
            ensureRecipeGroupSize();
            if (slot < 0 || slot >= recipeGroups.size()) {
                return RecipeGroup.DEFAULT;
            }
            return RecipeGroup.normalizeOrDefault(recipeGroups.get(slot));
        }

        private void setSlotRecipeGroup(int slot, @Nullable String recipeGroup) {
            ensureRecipeGroupSize();
            if (slot >= 0 && slot < recipeGroups.size()) {
                recipeGroups.set(slot, RecipeGroup.normalizeOrDefault(recipeGroup));
            }
        }

        private void ensureRecipeGroupSize() {
            while (recipeGroups.size() < getSlots()) {
                recipeGroups.add(RecipeGroup.DEFAULT);
            }
            while (recipeGroups.size() > getSlots()) {
                recipeGroups.remove(recipeGroups.size() - 1);
            }
        }

        @Override
        public CompoundTag serializeNBT() {
            var tag = new CompoundTag();
            tag.put(NBT_ITEM_STORAGE, super.serializeNBT());
            var groups = new ListTag();
            for (int i = 0; i < getSlots(); i++) {
                if (!getStackInSlot(i).isEmpty()) {
                    var groupTag = new CompoundTag();
                    groupTag.putInt(NBT_SLOT, i);
                    groupTag.putString(NBT_RECIPE_GROUP, getSlotRecipeGroup(i));
                    groups.add(groupTag);
                }
            }
            tag.put(NBT_ITEM_GROUPS, groups);
            return tag;
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            super.deserializeNBT(nbt.getCompound(NBT_ITEM_STORAGE));
            recipeGroups.clear();
            for (int i = 0; i < getSlots(); i++) {
                recipeGroups.add(RecipeGroup.DEFAULT);
            }
            var groups = nbt.getList(NBT_ITEM_GROUPS, net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < groups.size(); i++) {
                var groupTag = groups.getCompound(i);
                setSlotRecipeGroup(groupTag.getInt(NBT_SLOT), groupTag.getString(NBT_RECIPE_GROUP));
            }
            compactEmptySlots();
        }

        @Override
        public DynamicItemStorage copy() {
            var copied = new DynamicItemStorage();
            copied.deserializeNBT(serializeNBT());
            return copied;
        }
    }

    /**
     * Dynamic fluid input storage that keeps each tank associated with its recipe group.
     */
    public class DynamicFluidStorage implements IFluidHandler, ITagSerializable<CompoundTag>, IContentChangeAware {
        @Getter
        private Runnable onContentsChanged = () -> {
        };
        private final List<FluidStorage> storages = new ArrayList<>();
        private final List<String> recipeGroups = new ArrayList<>();

        /**
         * Returns the mutable tank list backing this dynamic fluid buffer.
         * <p>
         * Callers that mutate individual {@link FluidStorage} entries must stay on the owning machine/server context and
         * let the storage callbacks compact empty tanks.
         *
         * @return live list of compacted fluid storages
         */
        public List<FluidStorage> getStorages() {
            return storages;
        }

        /**
         * Fills fluid into the default recipe group.
         *
         * @param stack fluid stack to store; empty stacks are ignored
         * @return amount accepted, in {@code [0, stack.getAmount()]}
         */
        public long fillDynamic(com.lowdragmc.lowdraglib.side.fluid.FluidStack stack) {
            return fillDynamic(stack, RecipeGroup.DEFAULT);
        }

        /**
         * Fills fluid into a recipe-group aware dynamic tank.
         * <p>
         * Existing tanks are reused only when fluid identity and recipe group match. Otherwise an empty or new tank is
         * assigned. Side effects: may grow the tank list, update tank recipe-group metadata, compact empty tanks, and
         * invoke storage change callbacks.
         *
         * @param stack       fluid stack to store; empty stacks are ignored
         * @param recipeGroup recipe group to associate with accepted fluid; {@code null} maps to the default group
         * @return amount accepted, in {@code [0, stack.getAmount()]}
         */
        public long fillDynamic(com.lowdragmc.lowdraglib.side.fluid.FluidStack stack, @Nullable String recipeGroup) {
            if (stack.isEmpty()) {
                return 0;
            }
            var normalizedRecipeGroup = RecipeGroup.normalizeOrDefault(recipeGroup);
            var storage = findStorage(stack, normalizedRecipeGroup);
            if (storage == null) {
                storage = createStorage();
                storages.add(storage);
                recipeGroups.add(normalizedRecipeGroup);
            }
            var filled = storage.fill(stack.copy(), false);
            removeEmptyStorages();
            return filled;
        }

        @Nullable
        private FluidStorage findStorage(com.lowdragmc.lowdraglib.side.fluid.FluidStack stack, String recipeGroup) {
            FluidStorage emptyStorage = null;
            var emptyIndex = -1;
            for (int i = 0; i < storages.size(); i++) {
                var storage = storages.get(i);
                var stored = storage.getFluid();
                if (!stored.isEmpty() && stored.isFluidEqual(stack) && recipeGroup.equals(getStorageRecipeGroup(i))) {
                    return storage;
                }
                if (stored.isEmpty() && emptyStorage == null) {
                    emptyStorage = storage;
                    emptyIndex = i;
                }
            }
            if (emptyIndex >= 0) {
                setStorageRecipeGroup(emptyIndex, recipeGroup);
            }
            return emptyStorage;
        }

        private FluidStorage createStorage() {
            var storage = new FluidStorage(MEPatternInputTrait.this.getDefinition().getFluidInputLimit());
            storage.setOnContentsChanged(this::onContentsChanged);
            return storage;
        }

        @Override
        public void setOnContentsChanged(Runnable onContentsChanged) {
            this.onContentsChanged = onContentsChanged == null ? () -> {
            } : onContentsChanged;
            for (var storage : storages) {
                storage.setOnContentsChanged(this::onContentsChanged);
            }
        }

        /**
         * Handles changes from child tanks.
         * <p>
         * Side effects: removes empty tanks, keeps recipe-group metadata aligned with the tank list, and invokes the
         * registered content-change callback synchronously.
         */
        public void onContentsChanged() {
            removeEmptyStorages();
            onContentsChanged.run();
        }

        private void removeEmptyStorages() {
            for (int i = storages.size() - 1; i >= 0; i--) {
                if (storages.get(i).getFluid().isEmpty()) {
                    storages.remove(i);
                    if (i < recipeGroups.size()) {
                        recipeGroups.remove(i);
                    }
                }
            }
            ensureRecipeGroupSize();
        }

        /**
         * Reports whether any fluid inputs are currently buffered.
         *
         * @return {@code true} when at least one compacted tank contains fluid
         */
        public boolean hasStoredInputs() {
            for (var storage : storages) {
                if (!storage.getFluid().isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the recipe groups currently represented by non-empty fluid tanks.
         *
         * @return ordered set of normalized recipe-group names
         */
        public Set<String> getRecipeGroups() {
            var groups = new LinkedHashSet<String>();
            for (int i = 0; i < storages.size(); i++) {
                if (!storages.get(i).getFluid().isEmpty()) {
                    groups.add(getStorageRecipeGroup(i));
                }
            }
            return groups;
        }

        /**
         * Checks whether a tank may satisfy a recipe-group request.
         *
         * @param tank        zero-based tank index
         * @param recipeGroup requested group; {@code null} or blank maps to the default group
         * @return {@code true} when the tank's stored group matches the normalized request
         */
        public boolean matchesRecipeGroup(int tank, @Nullable String recipeGroup) {
            return MEPatternInputTrait.matchesRecipeGroup(recipeGroup, getStorageRecipeGroup(tank));
        }

        private String getStorageRecipeGroup(int tank) {
            ensureRecipeGroupSize();
            if (tank < 0 || tank >= recipeGroups.size()) {
                return RecipeGroup.DEFAULT;
            }
            return RecipeGroup.normalizeOrDefault(recipeGroups.get(tank));
        }

        private void setStorageRecipeGroup(int tank, @Nullable String recipeGroup) {
            ensureRecipeGroupSize();
            if (tank >= 0 && tank < recipeGroups.size()) {
                recipeGroups.set(tank, RecipeGroup.normalizeOrDefault(recipeGroup));
            }
        }

        private void ensureRecipeGroupSize() {
            while (recipeGroups.size() < storages.size()) {
                recipeGroups.add(RecipeGroup.DEFAULT);
            }
            while (recipeGroups.size() > storages.size()) {
                recipeGroups.remove(recipeGroups.size() - 1);
            }
        }

        @Override
        public int getTanks() {
            return storages.size();
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return FluidHelperImpl.toFluidStack(storages.get(tank).getFluid());
        }

        @Override
        public int getTankCapacity(int tank) {
            return (int) Math.min(storages.get(tank).getCapacity(), Integer.MAX_VALUE);
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return storages.get(tank).isFluidValid(FluidHelperImpl.toFluidStack(stack));
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            var storage = action.simulate() ? copy() : this;
            return (int) Math.min(storage.fillDynamic(FluidHelperImpl.toFluidStack(resource), currentRecipeGroup), Integer.MAX_VALUE);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public CompoundTag serializeNBT() {
            var tag = new CompoundTag();
            var list = new ListTag();
            for (int i = 0; i < storages.size(); i++) {
                var storage = storages.get(i);
                if (!storage.getFluid().isEmpty()) {
                    var storageTag = storage.serializeNBT();
                    storageTag.putString(NBT_RECIPE_GROUP, getStorageRecipeGroup(i));
                    list.add(storageTag);
                }
            }
            tag.put(NBT_FLUID_STORAGE, list);
            return tag;
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            storages.clear();
            recipeGroups.clear();
            var list = nbt.getList(NBT_FLUID_STORAGE, net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                var storageTag = list.getCompound(i);
                var storage = createStorage();
                storage.deserializeNBT(storageTag);
                if (!storage.getFluid().isEmpty()) {
                    storages.add(storage);
                    recipeGroups.add(RecipeGroup.normalizeOrDefault(storageTag.getString(NBT_RECIPE_GROUP)));
                }
            }
            ensureRecipeGroupSize();
        }

        /**
         * Creates a detached copy of this fluid buffer.
         *
         * @return new dynamic fluid storage populated from this buffer's serialized tanks and recipe groups
         */
        public DynamicFluidStorage copy() {
            var copied = new DynamicFluidStorage();
            copied.deserializeNBT(serializeNBT());
            return copied;
        }

        /**
         * Drains matching stored fluid from the default recipe group.
         *
         * @param resource requested fluid and maximum amount to drain
         * @param simulate {@code true} to compute the drain without mutating storage
         * @return drained fluid stack, or an empty stack when no matching stored fluid exists
         */
        public com.lowdragmc.lowdraglib.side.fluid.FluidStack drainStored(com.lowdragmc.lowdraglib.side.fluid.FluidStack resource, boolean simulate) {
            return drainStored(resource, simulate, RecipeGroup.DEFAULT);
        }

        /**
         * Drains matching stored fluid from tanks in the requested recipe group.
         * <p>
         * The fluid identity must match the requested resource and the tank recipe group must match the normalized
         * request. Side effects: when {@code simulate} is {@code false}, drains the matched tank and compacts empty tanks.
         *
         * @param resource    requested fluid and maximum amount to drain
         * @param simulate    {@code true} to compute the drain without mutating storage
         * @param recipeGroup requested group; {@code null} maps to the default group
         * @return drained fluid stack, or an empty stack when no matching stored fluid exists
         */
        public com.lowdragmc.lowdraglib.side.fluid.FluidStack drainStored(com.lowdragmc.lowdraglib.side.fluid.FluidStack resource, boolean simulate, @Nullable String recipeGroup) {
            if (resource.isEmpty()) {
                return com.lowdragmc.lowdraglib.side.fluid.FluidStack.empty();
            }
            for (int i = 0; i < storages.size(); i++) {
                var storage = storages.get(i);
                var stored = storage.getFluid();
                if (stored.isEmpty() || !stored.isFluidEqual(resource) || !matchesRecipeGroup(i, recipeGroup)) {
                    continue;
                }
                var drained = storage.drain(resource, simulate);
                removeEmptyStorages();
                return drained;
            }
            return com.lowdragmc.lowdraglib.side.fluid.FluidStack.empty();
        }
    }

    /**
     * Restricts pattern inventory slots to encoded AE2 patterns.
     */
    public static class PatternItemFilter implements appeng.util.inv.filter.IAEItemFilter {
        @Override
        public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
            return PatternDetailsHelper.isEncodedPattern(stack);
        }
    }
}
