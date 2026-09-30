package com.lowdragmc.mbd2.integration.ae2.trait;

import appeng.core.definitions.AEBlocks;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.mbd2.api.capability.recipe.IO;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.trait.ITrait;
import com.lowdragmc.mbd2.common.trait.SimpleCapabilityTrait;
import com.lowdragmc.mbd2.common.trait.SimpleCapabilityTraitDefinition;
import com.lowdragmc.mbd2.utils.WidgetUtils;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Configurable definition for an AE2 pattern input trait.
 */
@LDLRegister(name = "ae2_me_pattern_input", group = "trait", modID = "ae2")
public class MEPatternInputTraitDefinition extends SimpleCapabilityTraitDefinition {
    @Getter
    @Setter
    @Configurable(name = "config.definition.trait.ae2_me_pattern_input.pattern_slot_size")
    @NumberRange(range = {1, Integer.MAX_VALUE})
    private int patternSlotSize = 9;

    @Getter
    @Setter
    @Configurable(name = "config.definition.trait.ae2_me_pattern_input.item_input_limit")
    @NumberRange(range = {1, Integer.MAX_VALUE})
    private int itemInputLimit = Integer.MAX_VALUE;

    @Getter
    @Setter
    @Configurable(name = "config.definition.trait.ae2_me_pattern_input.fluid_input_limit")
    @NumberRange(range = {1, Long.MAX_VALUE})
    private long fluidInputLimit = Long.MAX_VALUE;

    @Getter
    @Setter
    @Configurable(name = "config.definition.trait.ae2_me_pattern_input.chemical_input_limit")
    @NumberRange(range = {1, Long.MAX_VALUE})
    private long chemicalInputLimit = Long.MAX_VALUE;

    @Override
    public SimpleCapabilityTrait createTrait(MBDMachine machine) {
        return new MEPatternInputTrait(machine, this);
    }

    @Override
    public IGuiTexture getIcon() {
        return new ItemStackTexture(AEBlocks.PATTERN_PROVIDER.asItem());
    }

    @Override
    public boolean allowMultiple() {
        return false;
    }

    @Override
    public void createTraitUITemplate(WidgetGroup ui) {
        var prefix = uiPrefixName();
        var patternRow = Math.ceil(Math.sqrt(patternSlotSize));
        for (var i = 0; i < this.patternSlotSize; i++) {
            var slotWidget = new SlotWidget();
            slotWidget.setSelfPosition(new Position(10 + i % (int) patternRow * 18, 10 + i / (int) patternRow * 18));
            slotWidget.initTemplate();
            slotWidget.setId(prefix + "_pattern_" + i);
            ui.addWidget(slotWidget);
        }
    }

    @Override
    public void initTraitUI(ITrait trait, WidgetGroup group) {
        if (trait instanceof MEPatternInputTrait patternInputTrait) {
            var prefix = uiPrefixName();
            WidgetUtils.widgetByIdForEach(group, "^%s_pattern_[0-9]+$".formatted(prefix), SlotWidget.class, slotWidget -> {
                var index = WidgetUtils.widgetIdIndex(slotWidget);
                if (index >= 0 && index < patternInputTrait.getPatternInventory().size()) {
                    slotWidget.setHandlerSlot(createPatternTransfer(patternInputTrait.getPatternInventory(), index), 0);
                    slotWidget.setIngredientIO(IngredientIO.INPUT);
                    slotWidget.setCanTakeItems(true);
                    slotWidget.setCanPutItems(true);
                }
            });
        }
    }

    @Override
    public IO getGuiIO() {
        return IO.IN;
    }

    /**
     * Adapts one encoded-pattern inventory slot to LDLib item transfer APIs.
     */
    public static IItemTransfer createPatternTransfer(MEPatternInputTrait.SerializablePatternInventory inventory, int slotIndex) {
        return new IItemTransfer() {
            @Override
            public int getSlots() {
                return 1;
            }

            @Override
            public @NotNull ItemStack getStackInSlot(int slot) {
                return inventory.getStackInSlot(slotIndex);
            }

            @Override
            public void setStackInSlot(int slot, ItemStack stack) {
                inventory.setItemDirect(slotIndex, stack);
            }

            @Override
            public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate, boolean notifyChanges) {
                return inventory.insertItem(slotIndex, stack, simulate);
            }

            @Override
            public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate, boolean notifyChanges) {
                return inventory.extractItem(slotIndex, amount, simulate);
            }

            @Override
            public int getSlotLimit(int slot) {
                return inventory.getSlotLimit(slotIndex);
            }

            @Override
            public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                return inventory.isItemValid(slotIndex, stack);
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
}
