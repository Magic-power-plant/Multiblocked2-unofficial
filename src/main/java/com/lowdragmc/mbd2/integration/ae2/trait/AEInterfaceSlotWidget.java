package com.lowdragmc.mbd2.integration.ae2.trait;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.util.ConfigInventory;
import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import lombok.Getter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

import static com.lowdragmc.lowdraglib.gui.widget.SlotWidget.ITEM_SLOT_TEXTURE;


/**
 * Two-slot AE2 interface widget that binds one config stack and one stored stack from {@link SerializableInterfaceLogic}.
 */
@LDLRegister(name = "ae_interface_slot", group = "widget.container", modID = "ae2")
public class AEInterfaceSlotWidget extends WidgetGroup {
    private static final String CONFIG_SLOT_ID = "config_slot";
    private static final String STORAGE_SLOT_ID = "storage_slot";
    private static final String FLUID_CONFIG_SLOT_ID = "fluid_config_slot";
    private static final String FLUID_STORAGE_SLOT_ID = "fluid_storage_slot";

    private AEKeySlotWidget configSlot = new AEKeySlotWidget();
    private AEKeySlotWidget storageSlot = new AEKeySlotWidget();
    @Getter
    @Nullable
    private SerializableInterfaceLogic interfaceLogic;
    @Getter
    private int slotIndex;

    /**
     * Creates a two-row AE2 interface slot widget.
     * <p>
     * Side effects: creates a config slot and a storage slot, disables phantom/amount editing on the storage slot, and
     * adds both children. Bind it to live interface data with {@link #setInterfaceLogic(SerializableInterfaceLogic, int)}
     * before use.
     */
    public AEInterfaceSlotWidget() {
        super(0, 0, 20, 18 * 2 + 2);
        setupKeySlot(configSlot, CONFIG_SLOT_ID, 1, 1);
        setupKeySlot(storageSlot, STORAGE_SLOT_ID, 1, 19);
        storageSlot.setRealStorageSlot(true);
        addWidget(configSlot);
        addWidget(storageSlot);
    }

    @Override
    public void initTemplate() {
        configSlot.initTemplate();
        storageSlot.initTemplate();
        configSlot.setBackground(new GuiTextureGroup(ITEM_SLOT_TEXTURE, Icons.DOWN.copy().setColor(ColorPattern.GRAY.color).scale(0.8f)));
    }

    /**
     * Binds this widget to an item interface slot.
     * <p>
     * This is a compatibility alias for {@link #setInterfaceLogic(SerializableInterfaceLogic, int)}; it affects both the
     * config and storage child slots.
     *
     * @param interfaceLogic interface logic that owns the config/storage inventories
     * @param slotIndex      zero-based slot index inside both inventories
     */
    public void setItemInterfaceLogic(SerializableInterfaceLogic interfaceLogic, int slotIndex) {
        setInterfaceLogic(interfaceLogic, slotIndex);
    }

    /**
     * Binds the config and storage child slots to interface logic inventories.
     * <p>
     * Side effects: stores the logic reference and slot index, then points the top slot at {@code getConfig()} and the
     * bottom slot at {@code getStorage()}. The slot index must be valid for both inventories.
     *
     * @param interfaceLogic interface logic that owns the config/storage inventories
     * @param slotIndex      zero-based slot index inside both inventories
     */
    public void setInterfaceLogic(SerializableInterfaceLogic interfaceLogic, int slotIndex) {
        this.interfaceLogic = interfaceLogic;
        this.slotIndex = slotIndex;
        storageSlot.setConfigInventory(interfaceLogic.getStorage(), slotIndex);
        configSlot.setConfigInventory(interfaceLogic.getConfig(), slotIndex);
    }

    /**
     * Sets the ingredient role exposed by the storage slot to recipe viewers.
     *
     * @param ingredientIO role reported by the lower storage slot
     */
    public void setIngredientIO(IngredientIO ingredientIO) {
        storageSlot.setIngredientIO(ingredientIO);
    }

    /**
     * Controls whether users may extract or clear the storage slot through this widget.
     *
     * @param support {@code true} to allow taking/clearing from the storage slot
     */
    public void setCanTakeItems(boolean support) {
        storageSlot.setCanTakeItems(support);
    }

    /**
     * Controls whether users may insert or replace the storage slot through this widget.
     *
     * @param support {@code true} to allow putting/replacing the storage slot key
     */
    public void setCanPutItems(boolean support) {
        storageSlot.setCanPutItems(support);
    }

    private static void setupKeySlot(AEKeySlotWidget slot, String id, int x, int y) {
        slot.setSelfPosition(x, y);
        slot.setId(id);
    }

    /**
     * Adapts one AE2 config inventory slot to LDLib item transfer APIs used by recipe handlers and widgets.
     * <p>
     * The returned transfer exposes a single logical slot backed by {@code inventory}. Insert and extract operations
     * delegate to AE2 and respect the inventory's insert/extract flags, capacity, and allowed-key filter. Snapshot
     * methods are no-ops because the backing AE2 config inventory is the source of truth.
     *
     * @param inventory AE2 inventory to adapt; must remain valid for the lifetime of the transfer
     * @param slotIndex zero-based slot index in {@code inventory}
     * @return single-slot item transfer view over the requested AE2 slot
     */
    public static @NotNull IItemTransfer createAEItemTransfer(ConfigInventory inventory, int slotIndex) {
        return new IItemTransfer() {
            @Override
            public int getSlots() {
                return 1;
            }

            @Override
            public @NotNull ItemStack getStackInSlot(int slot) {
                return Optional.ofNullable(inventory.getStack(slotIndex)).map(stack -> {
                    if (stack.what() instanceof AEItemKey itemKey) {
                        return itemKey.toStack((int) stack.amount());
                    }
                    return ItemStack.EMPTY;
                }).orElse(ItemStack.EMPTY);
            }

            @Override
            public void setStackInSlot(int index, ItemStack stack) {
                if (index != 0) return;
                if (stack.isEmpty()) {
                    inventory.setStack(slotIndex, null);
                } else {
                    inventory.setStack(slotIndex, new GenericStack(AEItemKey.of(stack), stack.getCount()));
                }
            }

            @Override
            public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate, boolean notifyChanges) {
                if (!inventory.canInsert()) return stack;
                var inserted = inventory.insert(slotIndex, AEItemKey.of(stack), stack.getCount(), simulate ? Actionable.SIMULATE : Actionable.MODULATE);
                return stack.copyWithCount((int) (stack.getCount() - inserted));
            }

            @Override
            public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate, boolean notifyChanges) {
                if (!inventory.canExtract() || !(inventory.getKey(slotIndex) instanceof AEItemKey itemKey) || itemKey.getItem() == Items.AIR)
                    return ItemStack.EMPTY;
                var extracted = inventory.extract(slotIndex, itemKey, amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE);
                return itemKey.toStack((int) extracted);
            }

            @Override
            public int getSlotLimit(int slot) {
                return (int) inventory.getCapacity(AEKeyType.items());
            }

            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                return inventory.isAllowed(new GenericStack(AEItemKey.of(stack), stack.getCount()));
            }

            @Override
            public @NotNull Object createSnapshot() {
                return new Object();
            }

            @Override
            public void restoreFromSnapshot(Object snapshot) {

            }
        };
    }

    /**
     * Adapts one AE2 config inventory slot to LDLib fluid transfer APIs while preserving AE fluid keys and tags.
     * <p>
     * The returned transfer exposes a single logical tank backed by {@code inventory}. Fill and drain operations delegate
     * to AE2 and respect insert/extract flags, per-key capacity, and allowed-key filters. Snapshot methods are no-ops
     * because the backing AE2 config inventory is the source of truth.
     *
     * @param inventory AE2 inventory to adapt; must remain valid for the lifetime of the transfer
     * @param slotIndex zero-based slot index in {@code inventory}
     * @return single-tank fluid transfer view over the requested AE2 slot
     */
    public static @NotNull IFluidTransfer createAEFluidTransfer(ConfigInventory inventory, int slotIndex) {
        return new IFluidTransfer() {
            @Override
            public int getTanks() {
                return 1;
            }

            @Override
            public @NotNull FluidStack getFluidInTank(int tank) {
                return Optional.ofNullable(inventory.getStack(slotIndex)).map(stack -> {
                    if (stack.what() instanceof AEFluidKey fluidKey) {
                        return FluidStack.create(fluidKey.getFluid(), stack.amount(), fluidKey.copyTag());
                    }
                    return FluidStack.empty();
                }).orElse(FluidStack.empty());
            }

            @Override
            public void setFluidInTank(int tank, @NotNull FluidStack fluidStack) {
                if (tank != 0) return;
                if (fluidStack.isEmpty()) {
                    inventory.setStack(slotIndex, null);
                } else {
                    inventory.setStack(slotIndex, new GenericStack(AEFluidKey.of(fluidStack.getFluid(), fluidStack.getTag()), fluidStack.getAmount()));
                }
            }

            @Override
            public long getTankCapacity(int tank) {
                return inventory.getCapacity(AEKeyType.fluids());
            }

            @Override
            public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
                return !stack.isEmpty() && inventory.isAllowed(new GenericStack(AEFluidKey.of(stack.getFluid(), stack.getTag()), stack.getAmount()));
            }

            @Override
            public long fill(int tank, FluidStack resource, boolean simulate, boolean notifyChanges) {
                if (!inventory.canInsert() || resource.isEmpty()) return 0;
                return inventory.insert(slotIndex, AEFluidKey.of(resource.getFluid(), resource.getTag()), resource.getAmount(), simulate ? Actionable.SIMULATE : Actionable.MODULATE);
            }

            @Override
            public boolean supportsFill(int tank) {
                return inventory.canInsert();
            }

            @Override
            public @NotNull FluidStack drain(int tank, FluidStack resource, boolean simulate, boolean notifyChanges) {
                if (!inventory.canExtract() || !(inventory.getKey(slotIndex) instanceof AEFluidKey fluidKey) || resource.isEmpty() || !fluidKey.getFluid().isSame(resource.getFluid()) || !Objects.equals(fluidKey.getTag(), resource.getTag()))
                    return FluidStack.empty();
                var drained = inventory.extract(slotIndex, fluidKey, resource.getAmount(), simulate ? Actionable.SIMULATE : Actionable.MODULATE);
                return FluidStack.create(fluidKey.getFluid(), drained, fluidKey.copyTag());
            }

            @Override
            public @NotNull FluidStack drain(long maxDrain, boolean simulate, boolean notifyChanges) {
                if (!inventory.canExtract() || !(inventory.getKey(slotIndex) instanceof AEFluidKey fluidKey))
                    return FluidStack.empty();
                var extracted = inventory.extract(slotIndex, fluidKey, maxDrain, simulate ? Actionable.SIMULATE : Actionable.MODULATE);
                return FluidStack.create(fluidKey.getFluid(), extracted, fluidKey.copyTag());
            }

            @Override
            public boolean supportsDrain(int tank) {
                return inventory.canExtract();
            }

            @Override
            public @NotNull Object createSnapshot() {
                return new Object();
            }

            @Override
            public void restoreFromSnapshot(Object snapshot) {

            }
        };
    }

    @Override
    public void deserializeInnerNBT(CompoundTag nbt) {
        super.deserializeInnerNBT(nbt);
        widgets.stream()
                .filter(widget -> widget instanceof PhantomSlotWidget
                        || widget instanceof SlotWidget
                        || widget instanceof PhantomTankWidget
                        || widget instanceof TankWidget
                        || widget instanceof AEKeySlotWidget keySlotWidget
                        && (FLUID_CONFIG_SLOT_ID.equals(keySlotWidget.getId())
                        || FLUID_STORAGE_SLOT_ID.equals(keySlotWidget.getId())))
                .toList()
                .forEach(this::removeWidget);
        for (Widget widget : widgets) {
            if (widget instanceof AEKeySlotWidget keySlotWidget) {
                if (keySlotWidget.getId().equals(CONFIG_SLOT_ID)) {
                    this.configSlot = keySlotWidget;
                } else if (keySlotWidget.getId().equals(STORAGE_SLOT_ID)) {
                    this.storageSlot = keySlotWidget;
                }
            }
        }
        ensureKeySlot(configSlot, CONFIG_SLOT_ID, 1, 1);
        ensureKeySlot(storageSlot, STORAGE_SLOT_ID, 1, 19);
        configSlot.setCanAcceptPhantom(true);
        configSlot.setCanSetAmount(true);
        storageSlot.setRealStorageSlot(true);
        setSize(20, 18 * 2 + 2);
    }

    private void ensureKeySlot(AEKeySlotWidget slot, String id, int x, int y) {
        if (widgets.contains(slot)) {
            return;
        }
        setupKeySlot(slot, id, x, y);
        slot.initTemplate();
        if (id.equals(CONFIG_SLOT_ID)) {
            slot.setBackground(new GuiTextureGroup(ITEM_SLOT_TEXTURE, Icons.DOWN.copy().setColor(ColorPattern.GRAY.color).scale(0.8f)));
        }
        addWidget(slot);
    }

}
