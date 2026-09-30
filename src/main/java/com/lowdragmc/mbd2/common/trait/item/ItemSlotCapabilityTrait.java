package com.lowdragmc.mbd2.common.trait.item;

import com.google.common.base.Predicates;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.misc.ItemTransferList;
import com.lowdragmc.lowdraglib.side.item.forge.ItemTransferHelperImpl;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.DropSaved;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.mbd2.api.capability.recipe.IO;
import com.lowdragmc.mbd2.api.capability.recipe.IRecipeHandlerTrait;
import com.lowdragmc.mbd2.api.recipe.MBDRecipe;
import com.lowdragmc.mbd2.api.recipe.RecipeConsumptionTracker;
import com.lowdragmc.mbd2.common.capability.recipe.ItemDurabilityRecipeCapability;
import com.lowdragmc.mbd2.common.capability.recipe.ItemRecipeCapability;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.trait.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Runtime trait that gives a machine item storage, item recipe handlers, Forge
 * item-handler capability access, and optional automatic item transfer.
 *
 * <p>The business goal is to use one configured item slot definition for editor
 * slots, recipe input/output matching, durability-based recipe handling,
 * side-aware Forge item IO, neighboring block transfer, and world item pickup or
 * dropping. The trait owns mutable inventory state and is not thread-safe; all
 * mutation should run on the owning machine's logical server thread, while
 * preview initialization runs on the editor/client thread.</p>
 */
public class ItemSlotCapabilityTrait extends SimpleCapabilityTrait implements IAutoIOTrait {
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(ItemSlotCapabilityTrait.class, RecipeCapabilityTrait.MANAGED_FIELD_HOLDER);
    private final Random random = new Random();

    /**
     * Returns the sync-data field holder for item slot traits.
     *
     * @return static managed field holder used by LowDragLib sync/persistence
     */
    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @DropSaved
    @Persisted
    @DescSynced
    public final ItemStackTransfer storage;
    private Boolean isEmpty;
    private final ItemRecipeHandler itemRecipeHandler = new ItemRecipeHandler();
    private final ItemDurabilityRecipeHandler durabilityRecipeHandler = new ItemDurabilityRecipeHandler();
    private final ItemHandlerCap itemHandlerCap = new ItemHandlerCap();

    /**
     * Creates item storage and binds it to a machine.
     *
     * <p>Side effects: allocates an {@link ItemStackTransfer}, applies the
     * configured item filter, and registers a content-change callback that
     * invalidates the empty cache and notifies recipe listeners.</p>
     *
     * @param machine    owning machine
     * @param definition item slot definition controlling size, limits, filters,
     *                   UI, and auto IO
     */
    public ItemSlotCapabilityTrait(MBDMachine machine, ItemSlotCapabilityTraitDefinition definition) {
        super(machine, definition);
        this.storage = createStorage();
        this.storage.setOnContentsChanged(this::onContentsChanged);
    }

    /**
     * Adds all stored item stacks to the machine drop list.
     *
     * <p>Side effects: appends non-empty stored stacks to {@code drops}; it does
     * not clear the internal storage.</p>
     *
     * @param entity entity that caused the drop, when available
     * @param drops  mutable drop list
     */
    @Override
    public void onMachineDrop(Entity entity, List<ItemStack> drops) {
        if (getMachine().shouldKeepDropItemNbt()) {
            return;
        }
        for (int i = 0; i < storage.getSlots(); i++) {
            ItemStack stackInSlot = storage.getStackInSlot(i);
            if (!stackInSlot.isEmpty()) {
                drops.add(stackInSlot);
            }
        }
    }

    /**
     * Returns this trait's concrete definition type.
     *
     * @return item slot capability definition
     */
    @Override
    public ItemSlotCapabilityTraitDefinition getDefinition() {
        return (ItemSlotCapabilityTraitDefinition) super.getDefinition();
    }

    /**
     * Seeds preview storage with a representative item stack.
     *
     * <p>Editor-only side effect: inserts iron ingots into slot {@code 0} when at
     * least one slot exists so the preview UI and renderer have visible content.</p>
     */
    @Override
    public void onLoadingTraitInPreview() {
        if (storage.getSlots() > 0) {
            this.storage.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 32));
        }
    }

    /**
     * Creates the backing inventory for this trait.
     *
     * <p>Side effects: applies the configured slot limit and optional item filter.
     * The returned transfer is empty and has no listeners until the constructor
     * attaches them.</p>
     *
     * @return new item transfer sized by the definition
     */
    protected ItemStackTransfer createStorage() {
        var transfer = new ItemStackTransfer(getDefinition().getSlotSize()) {
            @Override
            public int getSlotLimit(int slot) {
                return getDefinition().getSlotLimit();
            }

            @Override
            public ItemStackTransfer copy() {
                // LDLib's base copy loses this trait's configured capacity. Recipe
                // simulation needs the same limits and filter as the real inventory.
                var copied = createStorage();
                copied.setSize(getSlots());
                for (int slot = 0; slot < getSlots(); slot++) {
                    copied.setStackInSlot(slot, getStackInSlot(slot).copy());
                }
                return copied;
            }
        };
        if (getDefinition().getItemFilterSettings().isEnable()) {
            transfer.setFilter(getDefinition().getItemFilterSettings()::test);
        }
        return transfer;
    }

    /**
     * Handles storage mutations.
     *
     * <p>Side effects: invalidates the cached empty-state value and notifies
     * recipe listeners so active recipe logic can re-check item availability.</p>
     */
    public void onContentsChanged() {
        isEmpty = null;
        notifyListeners();
    }

    /**
     * Returns whether all item slots are empty.
     *
     * <p>Side effects: lazily computes and caches the answer until
     * {@link #onContentsChanged()} invalidates it.</p>
     *
     * @return {@code true} when every storage slot is empty
     */
    public boolean isEmpty() {
        if (isEmpty == null) {
            isEmpty = true;
            for (int i = 0; i < storage.getSlots(); i++) {
                if (!storage.getStackInSlot(i).isEmpty()) {
                    isEmpty = false;
                    break;
                }
            }
        }
        return isEmpty;
    }

    /**
     * Returns recipe handlers contributed by this item storage.
     *
     * @return item stack handler and durability handler
     */
    @Override
    public List<IRecipeHandlerTrait<?>> getRecipeHandlerTraits() {
        return List.of(itemRecipeHandler, durabilityRecipeHandler);
    }

    /**
     * Returns Forge capabilities contributed by this item storage.
     *
     * @return item handler capability provider
     */
    @Override
    public List<ICapabilityProviderTrait<?>> getCapabilityProviderTraits() {
        return List.of(itemHandlerCap);
    }

    //////////////////////////////////////
    //********     AUTO IO     *********//
    //////////////////////////////////////

    /**
     * Returns enabled side-based automatic item IO configuration.
     *
     * @return auto IO configuration, or {@code null} when side auto IO is
     * disabled
     */
    @Override
    public @Nullable AutoIO getAutoIO() {
        return getDefinition().getAutoIO().isEnable() ? getDefinition().getAutoIO() : null;
    }

    /**
     * Performs side auto IO and world item pickup/drop automation.
     *
     * <p>Server-side side effects include moving items through neighboring item
     * handlers, absorbing nearby {@link ItemEntity} stacks up to the configured
     * speed, discarding emptied entities, and spawning dropped item entities from
     * inventory slots. Work is throttled by the configured intervals and the
     * machine offset timer.</p>
     */
    @Override
    public void serverTick() {
        IAutoIOTrait.super.serverTick();
        var timer = getMachine().getOffsetTimer();
        var autoInput = getDefinition().getAutoInput();
        var autoOutput = getDefinition().getAutoOutput();
        if (autoInput.isEnable() && timer % autoInput.getInterval() == 0) {
            var items = getMachine().getLevel().getEntitiesOfClass(ItemEntity.class,
                    autoInput.getRotatedRange(getMachine().getFrontFacing().orElse(Direction.NORTH)).move(getMachine().getPos()),
                    EntitySelector.ENTITY_STILL_ALIVE);
            var leftCount = autoInput.getSpeed();
            for (ItemEntity itemEntity : items) {
                if (leftCount <= 0) break;
                var stored = itemEntity.getItem().copy();
                var remaining = stored.copyWithCount(Math.min(leftCount, stored.getCount()));
                var inserted = 0;
                for (int i = 0; i < storage.getSlots(); i++) {
                    var beforeCount = remaining.getCount();
                    remaining = storage.insertItem(i, remaining, false);
                    if (remaining.getCount() < beforeCount) {
                        inserted += beforeCount - remaining.getCount();
                        if (remaining.isEmpty()) break;
                    }
                }
                if (inserted > 0) {
                    stored.shrink(inserted);
                    if (stored.isEmpty()) {
                        itemEntity.discard();
                    } else {
                        itemEntity.setItem(stored);
                    }
                }
                leftCount -= inserted;
            }
        }
        if (autoOutput.isEnable() && timer % autoOutput.getInterval() == 0) {
            var leftCount = autoOutput.getSpeed();
            var range = autoOutput.getRotatedRange(getMachine().getFrontFacing().orElse(Direction.NORTH)).move(getMachine().getPos());
            for (int i = 0; i < storage.getSlots(); i++) {
                if (leftCount <= 0) break;
                var stored = storage.getStackInSlot(i);
                if (stored.isEmpty()) continue;
                var pop = stored.copyWithCount(Math.min(leftCount, stored.getCount()));
                leftCount -= pop.getCount();
                storage.extractItem(i, pop.getCount(), false);
                // drop items
                var level = getMachine().getLevel();
                var d0 = (double) EntityType.ITEM.getHeight() / 2.0D;
                var x = level.random.nextFloat() * range.getXsize() + range.minX;
                var y = level.random.nextFloat() * range.getYsize() + range.minY - d0;
                var z = level.random.nextFloat() * range.getZsize() + range.minZ;
                var itemEntity = new ItemEntity(level, x, y, z, pop);
                itemEntity.setDefaultPickUpDelay();
                level.addFreshEntity(itemEntity);
            }
        }
    }

    /**
     * Performs one neighboring block item transfer pass.
     *
     * <p>Side effects: for input IO, imports items from the neighbor into this
     * storage using the configured item filter; for output IO, exports from this
     * storage to the neighbor. Calls are expected on the logical server thread.</p>
     *
     * @param port position whose neighbor is accessed
     * @param side side of {@code port} used for transfer
     * @param io   transfer direction to perform
     */
    @Override
    public void handleAutoIO(BlockPos port, Direction side, IO io) {
        if (io.support(IO.IN)) {
            ItemTransferHelperImpl.importToTarget(new ItemTransferList(storage), Integer.MAX_VALUE,
                    getDefinition().getItemFilterSettings().isEnable() ? getDefinition().getItemFilterSettings() : Predicates.alwaysTrue(),
                    getMachine().getLevel(), port.relative(side), side.getOpposite());
        }
        if (io.support(IO.OUT)) {
            ItemTransferHelperImpl.exportToTarget(new ItemTransferList(storage), Integer.MAX_VALUE, Predicates.alwaysTrue(),
                    getMachine().getLevel(), port.relative(side), side.getOpposite());
        }
    }

    /**
     * Recipe handler that consumes or produces item ingredient stacks.
     *
     * <p>Business goal: match item recipe content against this trait's storage.
     * Simulation uses a copy of the storage; commit mode mutates the real storage
     * and records consumed input stacks through {@link RecipeConsumptionTracker}.</p>
     */
    public class ItemRecipeHandler extends RecipeHandlerTrait<Ingredient> {

        /**
         * Creates the item recipe handler bound to the outer trait.
         */
        protected ItemRecipeHandler() {
            super(ItemSlotCapabilityTrait.this, ItemRecipeCapability.CAP);
        }

        /**
         * Matches or commits item recipe content.
         *
         * <p>For {@link IO#IN}, matching extracts the required ingredient counts
         * from storage. For {@link IO#OUT}, matching inserts an output stack into
         * storage; when multiple candidate output stacks exist, the choice is
         * shuffled deterministically from the machine offset timer. Returning
         * {@code null} marks all content satisfied.</p>
         *
         * @param io       recipe IO direction
         * @param recipe   recipe being matched or committed
         * @param left     mutable ingredient list still unsatisfied
         * @param slotName optional recipe slot name, passed to consumption
         *                 tracking
         * @param simulate {@code true} to check using copied storage;
         *                 {@code false} to mutate real storage
         * @return remaining unsatisfied ingredients, or {@code null} when
         * complete
         */
        @Override
        public List<Ingredient> handleRecipeInner(IO io, MBDRecipe recipe, List<Ingredient> left, @Nullable String slotName, boolean simulate) {
            if (!compatibleWith(io)) return left;
            var capability = simulate ? storage.copy() : storage;
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
                            // Also reserve space in the copied inventory during
                            // simulation, so later outputs cannot reuse these slots.
                            var output = shuffledItems.get(index);
                            for (int slot = 0; slot < capability.getSlots(); slot++) {
                                ItemStack leftStack = capability.insertItem(slot, output.copy(), false);
                                output.setCount(leftStack.getCount());
                                if (output.isEmpty()) break;
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
     * Recipe handler that treats item durability as consumable or restorable
     * recipe content.
     *
     * <p>For input recipes, matching damages acceptable damageable items. For
     * output recipes, matching repairs acceptable damageable items. Simulation
     * uses copied storage; commit mode mutates the real storage.</p>
     */
    public class ItemDurabilityRecipeHandler extends RecipeHandlerTrait<Ingredient> {
        /**
         * Creates the durability recipe handler bound to the outer trait.
         */
        protected ItemDurabilityRecipeHandler() {
            super(ItemSlotCapabilityTrait.this, ItemDurabilityRecipeCapability.CAP);
        }

        /**
         * Matches or commits durability recipe content.
         *
         * @param io       recipe IO direction
         * @param recipe   recipe being matched or committed
         * @param left     mutable ingredient list still unsatisfied
         * @param slotName optional recipe slot name, passed to consumption
         *                 tracking for input durability use
         * @param simulate {@code true} to check using copied storage;
         *                 {@code false} to mutate real storage
         * @return remaining unsatisfied ingredients, or {@code null} when
         * complete
         */
        @Override
        public List<Ingredient> handleRecipeInner(IO io, MBDRecipe recipe, List<Ingredient> left, @Nullable String slotName, boolean simulate) {
            if (!compatibleWith(io)) return left;
            var capability = simulate ? storage.copy() : storage;
            Iterator<Ingredient> iterator = left.iterator();
            if (io == IO.IN) {
                while (iterator.hasNext()) {
                    Ingredient ingredient = iterator.next();
                    SLOT_LOOKUP:
                    for (int i = 0; i < capability.getSlots(); i++) {
                        ItemStack itemStack = capability.getStackInSlot(i);
                        //Does not look like a good implementation, but I think it's at least equal to vanilla Ingredient::test
                        if (itemStack.isDamageableItem() && ingredient.test(itemStack)) {
                            ItemStack[] ingredientStacks = Arrays.stream(ingredient.getItems()).filter(ItemStack::isDamageableItem).toArray(ItemStack[]::new);
                            for (ItemStack ingredientStack : ingredientStacks) {
                                if (ingredientStack.is(itemStack.getItem())) {
                                    var damage = itemStack.getDamageValue();
                                    var maxDamage = itemStack.getMaxDamage();
                                    var availableDamage = Math.min(maxDamage - damage, ingredientStack.getCount());
                                    if (availableDamage <= 0) continue;
                                    itemStack.setDamageValue(damage + availableDamage);
                                    capability.setStackInSlot(i, itemStack);
                                    capability.onContentsChanged(i);
                                    if (!simulate) {
                                        RecipeConsumptionTracker.record(ItemDurabilityRecipeCapability.CAP, itemStack.copyWithCount(availableDamage), slotName);
                                    }
                                    ingredientStack.setCount(ingredientStack.getCount() - availableDamage);
                                    if (ingredientStack.isEmpty()) {
                                        iterator.remove();
                                        break SLOT_LOOKUP;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            } else if (io == IO.OUT) {
                while (iterator.hasNext()) {
                    Ingredient ingredient = iterator.next();
                    SLOT_LOOKUP:
                    for (int i = 0; i < capability.getSlots(); i++) {
                        ItemStack itemStack = capability.getStackInSlot(i);
                        //Does not look like a good implementation, but I think it's at least equal to vanilla Ingredient::test
                        if (itemStack.isDamageableItem() && ingredient.test(itemStack)) {
                            ItemStack[] ingredientStacks = Arrays.stream(ingredient.getItems()).filter(ItemStack::isDamageableItem).toArray(ItemStack[]::new);
                            for (ItemStack ingredientStack : ingredientStacks) {
                                if (ingredientStack.is(itemStack.getItem())) {
                                    var damage = itemStack.getDamageValue();
                                    var availableDamage = Math.min(damage, ingredientStack.getCount());
                                    if (availableDamage <= 0) continue;
                                    itemStack.setDamageValue(damage - availableDamage);
                                    capability.setStackInSlot(i, itemStack);
                                    capability.onContentsChanged(i);
                                    ingredientStack.setCount(ingredientStack.getCount() - availableDamage);
                                    if (ingredientStack.isEmpty()) {
                                        iterator.remove();
                                        break SLOT_LOOKUP;
                                    }
                                    break;
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
     * Forge capability provider for this trait's item storage.
     */
    public class ItemHandlerCap implements ICapabilityProviderTrait<IItemHandler> {

        /**
         * Resolves item capability IO for a queried side.
         *
         * @param side queried side, or {@code null} for internal access
         * @return effective item handler IO
         */
        @Override
        public IO getCapabilityIO(@Nullable Direction side) {
            return ItemSlotCapabilityTrait.this.getCapabilityIO(side);
        }

        /**
         * Returns the Forge item handler capability token.
         *
         * @return item handler capability
         */
        @Override
        public Capability<IItemHandler> getCapability() {
            return ForgeCapabilities.ITEM_HANDLER;
        }

        /**
         * Creates a side-filtered item handler wrapper.
         *
         * @param capbilityIO effective IO for the queried side
         * @return wrapper over this trait's storage
         */
        @Override
        public IItemHandler getCapContent(IO capbilityIO) {
            return new ItemHandlerWrapper(storage, capbilityIO);
        }

        /**
         * Merges multiple item handler providers into one flattened handler.
         *
         * @param contents item handlers collected from compatible traits
         * @return concatenated item handler list
         */
        @Override
        public IItemHandler mergeContents(List<IItemHandler> contents) {
            return new ItemHandlerList(contents.toArray(new IItemHandler[0]));
        }
    }

}
