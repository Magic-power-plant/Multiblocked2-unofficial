package com.lowdragmc.mbd2.integration.ae2.trait;

import appeng.api.behaviors.ContainerItemStrategies;
import appeng.api.client.AEKeyRendering;
import appeng.api.config.Actionable;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AmountFormat;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.common.StackSizeRenderer;
import appeng.core.AELog;
import appeng.util.ConfigInventory;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurableWidget;
import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.IRecipeIngredientSlot;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ResourceBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.ClickableIngredient;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.jei.JEIPlugin;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.integration.appmek.AppliedMekanisticsCompat;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.jemi.JemiStack;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * LDLib slot widget for displaying and editing AE2 {@link GenericStack} keys, including addon chemicals.
 */
@LDLRegister(name = "ae_key_slot", group = "widget.container", modID = "ae2")
@Accessors(chain = true)
public class AEKeySlotWidget extends Widget implements IRecipeIngredientSlot, IGhostIngredientTarget, IConfigurableWidget {
    private static final int UPDATE_STACK = 0;
    private static final int UPDATE_CARRIED_STACK = 1;
    private static final int ACTION_SET_STACK = 0;
    private static final int ACTION_SET_AMOUNT = 1;
    private static final int ACTION_STORAGE_CLICK = 2;
    private static final String SET_AMOUNT_TITLE = "mbd2.ae_key_slot.set_amount";
    private static final String SET_AMOUNT_TOOLTIP = "mbd2.ae_key_slot.set_amount.tooltip";
    private static final String SET_AMOUNT_VALUE = "mbd2.ae_key_slot.set_amount.value";

    @Nullable
    @Getter
    protected ConfigInventory inventory;
    @Getter
    protected int slotIndex;
    @Configurable(name = "ldlib.gui.editor.name.canTakeItems")
    protected boolean canTakeItems = true;
    @Configurable(name = "ldlib.gui.editor.name.canPutItems")
    protected boolean canPutItems = true;
    protected boolean canAcceptPhantom = true;
    protected boolean canSetAmount = true;
    protected boolean realStorageSlot;
    @Configurable(name = "ldlib.gui.editor.name.showAmount")
    @Setter
    protected boolean showAmount = true;
    @Configurable(name = "ldlib.gui.editor.name.drawHoverOverlay")
    @Setter
    public boolean drawHoverOverlay = true;
    @Configurable(name = "ldlib.gui.editor.name.drawHoverTips")
    @Setter
    protected boolean drawHoverTips = true;
    @Setter
    protected BiConsumer<AEKeySlotWidget, List<Component>> onAddedTooltips;
    @Setter
    @Getter
    protected IngredientIO ingredientIO = IngredientIO.RENDER_ONLY;
    @Setter
    @Getter
    protected float XEIChance = 1f;
    @Setter
    protected Runnable changeListener;
    @Nullable
    @Getter
    protected GenericStack lastStack;

    /**
     * Creates an unbound AE key slot at the origin.
     * <p>
     * The widget can still display a manually assigned stack through {@link #setAEStack(GenericStack)}. Bind it to a
     * {@link ConfigInventory} with {@link #setConfigInventory(ConfigInventory, int)} before using it as a live AE2 slot.
     */
    public AEKeySlotWidget() {
        this(null, 0, 0, 0);
    }

    /**
     * Creates an AE key slot bound to an optional config inventory.
     * <p>
     * The slot displays and edits one {@link GenericStack}. Item amounts are limited to {@link Integer#MAX_VALUE};
     * fluid and supported chemical amounts may use the full {@code long} range supported by AE2. Widget mutation should run on the UI thread or
     * through LDLib client actions.
     *
     * @param inventory backing AE2 config inventory, or {@code null} for display-only/manual stack mode
     * @param slotIndex zero-based slot index in {@code inventory}
     * @param x         widget x position in parent coordinates
     * @param y         widget y position in parent coordinates
     */
    public AEKeySlotWidget(@Nullable ConfigInventory inventory, int slotIndex, int x, int y) {
        super(x, y, 18, 18);
        this.inventory = inventory;
        this.slotIndex = slotIndex;
    }

    @Override
    public void initTemplate() {
        setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
    }

    @Override
    public AEKeySlotWidget setClientSideWidget() {
        super.setClientSideWidget();
        this.lastStack = getInventoryStack();
        return this;
    }

    /**
     * Rebinds this widget to a config inventory slot.
     * <p>
     * Side effect: if this is already a client-side widget, refreshes {@link #lastStack} from the new backing slot.
     *
     * @param inventory backing AE2 config inventory, or {@code null} to detach
     * @param slotIndex zero-based slot index in {@code inventory}
     * @return this widget for chaining
     */
    public AEKeySlotWidget setConfigInventory(@Nullable ConfigInventory inventory, int slotIndex) {
        this.inventory = inventory;
        this.slotIndex = slotIndex;
        if (isClientSideWidget) {
            this.lastStack = getInventoryStack();
        }
        return this;
    }

    /**
     * Sets the displayed/backing AE stack.
     * <p>
     * If a valid inventory slot is bound, normal permission checks apply and the inventory is updated. If no valid slot
     * is bound, the value is stored in {@link #lastStack} for display.
     *
     * @param stack AE key stack to set, or {@code null} to clear
     * @return this widget for chaining
     */
    public AEKeySlotWidget setAEStack(@Nullable GenericStack stack) {
        setStack(stack);
        return this;
    }

    /**
     * Controls whether the slot can be cleared or extracted from through this widget.
     *
     * @param canTakeItems {@code true} to allow clearing/extracting when the backing inventory permits extraction
     * @return this widget for chaining
     */
    public AEKeySlotWidget setCanTakeItems(boolean canTakeItems) {
        this.canTakeItems = canTakeItems;
        return this;
    }

    /**
     * Controls whether the slot can be filled or replaced through this widget.
     *
     * @param canPutItems {@code true} to allow inserting/replacing when the backing inventory permits insertion
     * @return this widget for chaining
     */
    public AEKeySlotWidget setCanPutItems(boolean canPutItems) {
        this.canPutItems = canPutItems;
        return this;
    }

    /**
     * Controls whether ghost ingredients from recipe viewers may set this slot.
     *
     * @param canAcceptPhantom {@code true} to allow phantom/ghost ingredient assignment
     * @return this widget for chaining
     */
    public AEKeySlotWidget setCanAcceptPhantom(boolean canAcceptPhantom) {
        this.canAcceptPhantom = canAcceptPhantom;
        return this;
    }

    /**
     * Controls whether the client amount dialog can change the stored stack amount.
     *
     * @param canSetAmount {@code true} to allow amount edits for the current stack key
     * @return this widget for chaining
     */
    public AEKeySlotWidget setCanSetAmount(boolean canSetAmount) {
        this.canSetAmount = canSetAmount;
        return this;
    }

    /**
     * Switches this widget between AE2-style configuration and real-storage interaction.
     * <p>
     * Real storage clicks never accept a stack or amount from the client. The server resolves every action from its
     * current carried stack and the backing {@link ConfigInventory}, matching AE2's separation between
     * {@code FakeSlot} configuration rows and {@code AppEngSlot} storage rows.
     *
     * @param realStorageSlot {@code true} for a real, conserving storage slot
     * @return this widget for chaining
     */
    public AEKeySlotWidget setRealStorageSlot(boolean realStorageSlot) {
        this.realStorageSlot = realStorageSlot;
        if (realStorageSlot) {
            this.canAcceptPhantom = false;
            this.canSetAmount = false;
        }
        return this;
    }

    /**
     * Lets callers append widget-specific tooltip lines.
     * <p>
     * The supplied list is mutated in place by {@link #onAddedTooltips} when a callback is installed, then returned for
     * further tooltip composition.
     *
     * @param list mutable tooltip list to extend
     * @return the same tooltip list after custom additions
     */
    public List<Component> getAdditionalToolTips(List<Component> list) {
        if (this.onAddedTooltips != null) {
            this.onAddedTooltips.accept(this, list);
        }
        return list;
    }

    @Override
    public List<Component> getTooltipTexts() {
        List<Component> tooltips = getAdditionalToolTips(new ArrayList<>());
        tooltips.addAll(tooltipTexts);
        return tooltips;
    }

    @Override
    public List<Component> getFullTooltipTexts() {
        var tooltips = new ArrayList<Component>();
        var stack = getDisplayStack();
        if (stack != null) {
            tooltips.addAll(AEKeyRendering.getTooltip(stack.what()));
            tooltips.add(Component.literal(stack.what().formatAmount(stack.amount(), AmountFormat.FULL)));
            if (canSetAmount && maySetStackOnThisSide(stack)) {
                tooltips.add(Component.translatable(SET_AMOUNT_TOOLTIP));
            }
        }
        tooltips.addAll(getTooltipTexts());
        return tooltips;
    }

    @Override
    public List<Object> getXEIIngredients() {
        var stack = getDisplayStack();
        if (stack == null) {
            return Collections.emptyList();
        }
        var jeiIngredient = getJEIClickableIngredient(stack);
        if (jeiIngredient != null) {
            return List.of(jeiIngredient);
        }
        var wrappedStack = GenericStack.wrapInItemStack(stack);
        if (LDLib.isReiLoaded()) {
            return SlotWidget.REICallWrapper.getReiIngredients(wrappedStack);
        } else if (LDLib.isEmiLoaded()) {
            return SlotWidget.EMICallWrapper.getEmiIngredients(wrappedStack, getXEIChance());
        }
        return List.of(wrappedStack);
    }

    @Override
    public @Nullable Object getXEICurrentIngredient() {
        var stack = getDisplayStack();
        if (stack == null) {
            return null;
        }
        var jeiIngredient = getJEIClickableIngredient(stack);
        if (jeiIngredient != null) {
            return jeiIngredient;
        }
        if (LDLib.isEmiLoaded()) {
            return SlotWidget.EMICallWrapper.getEmiIngredient(GenericStack.wrapInItemStack(stack), getXEIChance());
        }
        return null;
    }

    @Nullable
    private Object getJEIClickableIngredient(GenericStack stack) {
        if (LDLib.isJeiLoaded() && JEIPlugin.jeiHelpers != null) {
            var ingredient = appeng.integration.modules.jei.GenericEntryStackHelper.stackToIngredient(
                    JEIPlugin.jeiHelpers.getIngredientManager(), stack);
            if (ingredient != null) {
                var pos = getPosition();
                var size = getSize();
                return new ClickableIngredient<>(ingredient, pos.x, pos.y, size.width, size.height);
            }
        }
        return null;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void updateScreen() {
        super.updateScreen();
        if (isClientSideWidget && inventory != null) {
            this.lastStack = getInventoryStack();
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void drawInBackground(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        var stack = getDisplayStack();
        var pos = getPosition();
        if (stack != null) {
            AEKeyRendering.drawInGui(Minecraft.getInstance(), graphics, pos.x + 1, pos.y + 1, stack.what());
            if (showAmount && stack.amount() > 0) {
                StackSizeRenderer.renderSizeLabel(graphics, Minecraft.getInstance().font, pos.x + 1, pos.y + 1,
                        stack.what().formatAmount(stack.amount(), AmountFormat.SLOT));
            }
        }
        drawOverlay(graphics, mouseX, mouseY, partialTicks);
        if (drawHoverOverlay && isMouseOverElement(mouseX, mouseY) && getHoverElement(mouseX, mouseY) == this) {
            RenderSystem.colorMask(true, true, true, false);
            DrawerHelper.drawSolidRect(graphics, pos.x + 1, pos.y + 1, getSize().width - 2, getSize().height - 2, 0x80FFFFFF);
            RenderSystem.colorMask(true, true, true, true);
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void drawInForeground(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (drawHoverTips && isMouseOverElement(mouseX, mouseY) && getHoverElement(mouseX, mouseY) == this) {
            if (gui != null) {
                gui.getModularUIGui().setHoverTooltip(getFullTooltipTexts(), GenericStack.wrapInItemStack(getDisplayStack()), null, null);
            }
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1f);
        } else {
            super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOverElement(mouseX, mouseY) || gui == null) {
            return false;
        }
        if (realStorageSlot) {
            if ((button == 0 || button == 1) && (canTakeItems || canPutItems)) {
                writeClientAction(ACTION_STORAGE_CLICK, buffer -> buffer.writeVarInt(button));
                return true;
            }
            if (LDLib.isEmiLoaded()) {
                return SlotWidget.EMICallWrapper.mouseClicked(getXEICurrentIngredient(), button);
            }
            return false;
        }
        if (button == 1 && canTakeItems && mayClearStackOnThisSide()) {
            setStack(null);
            writeClientAction(ACTION_SET_STACK, buffer -> GenericStack.writeBuffer(null, buffer));
            playButtonClickSound();
            return true;
        }
        if (button == 2 && canSetAmount) {
            var stack = getDisplayStack();
            if (stack != null && maySetAmountOnThisSide(stack)) {
                openAmountDialog(stack);
                playButtonClickSound();
                return true;
            }
        }
        if (button == 0 && canPutItems) {
            var stack = toGenericStack(gui.getModularUIContainer().getCarried());
            if (stack != null && maySetStackOnThisSide(stack)) {
                setStack(stack);
                writeClientAction(ACTION_SET_STACK, buffer -> GenericStack.writeBuffer(stack, buffer));
                playButtonClickSound();
                return true;
            }
        }
        if (LDLib.isEmiLoaded()) {
            return SlotWidget.EMICallWrapper.mouseClicked(getXEICurrentIngredient(), button);
        }
        return false;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public List<Target> getPhantomTargets(Object ingredient) {
        var stack = toGenericStack(ingredient);
        if (stack == null || !mayAcceptPhantomOnThisSide(stack)) {
            return Collections.emptyList();
        }
        Rect2i rectangle = toRectangleBox();
        return List.of(new Target() {
            @Nonnull
            @Override
            public Rect2i getArea() {
                return rectangle;
            }

            @Override
            public void accept(Object ingredient) {
                var accepted = toGenericStack(ingredient);
                if (accepted != null && mayAcceptPhantomOnThisSide(accepted)) {
                    setStack(accepted);
                    writeClientAction(ACTION_SET_STACK, buffer -> GenericStack.writeBuffer(accepted, buffer));
                }
            }
        });
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        var stack = getInventoryStack();
        if (!Objects.equals(stack, lastStack)) {
            this.lastStack = stack;
            writeUpdateInfo(UPDATE_STACK, buffer -> GenericStack.writeBuffer(stack, buffer));
            notifyChangeListener();
        }
    }

    @Override
    public void writeInitialData(FriendlyByteBuf buffer) {
        var stack = getInventoryStack();
        this.lastStack = stack;
        GenericStack.writeBuffer(stack, buffer);
    }

    @Override
    public void readInitialData(FriendlyByteBuf buffer) {
        this.lastStack = GenericStack.readBuffer(buffer);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
        if (id == UPDATE_STACK) {
            this.lastStack = GenericStack.readBuffer(buffer);
            notifyChangeListener();
        } else if (id == UPDATE_CARRIED_STACK) {
            var carried = buffer.readItem();
            if (gui != null) {
                gui.getModularUIContainer().setCarried(carried);
            }
        } else {
            super.readUpdateInfo(id, buffer);
        }
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (realStorageSlot) {
            if (id == ACTION_STORAGE_CLICK) {
                var button = buffer.readVarInt();
                if (button == 0 || button == 1) {
                    handleStorageClick(button);
                }
                syncStackToClient();
                syncCarriedStackToClient();
            } else if (id == ACTION_SET_STACK || id == ACTION_SET_AMOUNT) {
                // Configuration actions carry client-authored stacks and must never mutate real storage.
                syncStackToClient();
                syncCarriedStackToClient();
            } else {
                super.handleClientAction(id, buffer);
            }
            return;
        }
        if (id == ACTION_SET_STACK) {
            setStack(GenericStack.readBuffer(buffer));
            syncStackToClient();
        } else if (id == ACTION_SET_AMOUNT) {
            var requested = GenericStack.readBuffer(buffer);
            if (requested != null) {
                setStackAmount(requested);
                syncStackToClient();
            }
        } else {
            super.handleClientAction(id, buffer);
        }
    }

    /**
     * Applies one real-storage click using only server-owned state.
     */
    protected void handleStorageClick(int button) {
        var inventory = this.inventory;
        if (!isValidSlot(inventory) || gui == null) {
            return;
        }

        var menu = gui.getModularUIContainer();
        var carried = menu.getCarried();
        var stored = inventory.getStack(slotIndex);

        // AE2 reserves right-click with a supported filled container for EMPTY_ITEM, even if no content can move.
        if (button == 1 && isContainerEmptyingClick(carried)) {
            emptyCarriedContainerIntoStorage();
            return;
        }

        if (stored != null && !(stored.what() instanceof AEItemKey)) {
            // Generic keys are never exposed as fake ItemStacks. A compatible carried container is filled instead.
            if (button == 0 && !carried.isEmpty()) {
                fillCarriedContainerFromStorage(stored);
            }
            return;
        }

        handleItemStorageClick(button, carried, stored);
    }

    private boolean isContainerEmptyingClick(ItemStack carried) {
        var inventory = this.inventory;
        if (carried.isEmpty() || inventory == null) {
            return false;
        }
        var content = ContainerItemStrategies.getContainedStack(carried);
        return content != null && inventory.isAllowed(content.what());
    }

    private void handleItemStorageClick(int button, ItemStack carried, @Nullable GenericStack stored) {
        var inventory = this.inventory;
        if (inventory == null || gui == null) {
            return;
        }

        if (carried.isEmpty()) {
            if (!canTakeItems || !inventory.canExtract() || stored == null
                    || !(stored.what() instanceof AEItemKey storedKey) || stored.amount() <= 0) {
                return;
            }

            long requested = button == 1
                    ? stored.amount() / 2 + stored.amount() % 2
                    : stored.amount();
            requested = Math.min(requested, storedKey.toStack().getMaxStackSize());
            long extracted = inventory.extract(slotIndex, storedKey, requested, Actionable.MODULATE);
            if (extracted > 0) {
                gui.getModularUIContainer().setCarried(storedKey.toStack((int) extracted));
            }
            return;
        }

        var carriedKey = AEItemKey.of(carried);
        if (carriedKey == null || !canPutItems || !inventory.canInsert()) {
            return;
        }

        if (stored == null || stored.what().equals(carriedKey)) {
            long requested = button == 1 ? 1 : carried.getCount();
            long inserted = inventory.insert(slotIndex, carriedKey, requested, Actionable.MODULATE);
            if (inserted > 0) {
                var remainder = carried.copy();
                remainder.shrink((int) inserted);
                gui.getModularUIContainer().setCarried(remainder);
            }
            return;
        }

        if (canTakeItems && inventory.canExtract()
                && stored.what() instanceof AEItemKey storedKey
                && stored.amount() > 0
                && stored.amount() <= storedKey.toStack().getMaxStackSize()) {
            var replacement = new GenericStack(carriedKey, carried.getCount());
            long maxReplacementAmount = Math.min(carried.getMaxStackSize(), inventory.getMaxAmount(carriedKey));
            if (inventory.isAllowed(replacement) && carried.getCount() <= maxReplacementAmount) {
                inventory.setStack(slotIndex, replacement);
                gui.getModularUIContainer().setCarried(storedKey.toStack((int) stored.amount()));
            }
        }
    }

    private boolean emptyCarriedContainerIntoStorage() {
        var inventory = this.inventory;
        if (!canPutItems || inventory == null || !inventory.canInsert() || gui == null) {
            return false;
        }

        var context = ContainerItemStrategies.findCarriedContext(
                null, gui.entityPlayer, gui.getModularUIContainer());
        if (context == null) {
            return false;
        }
        var content = context.getExtractableContent();
        if (content == null || content.amount() <= 0) {
            return false;
        }

        long accepted = inventory.insert(
                slotIndex, content.what(), content.amount(), Actionable.SIMULATE);
        if (accepted <= 0) {
            return false;
        }

        long extractable = context.extract(content.what(), accepted, Actionable.SIMULATE);
        long transferAmount = Math.min(accepted, extractable);
        if (transferAmount <= 0) {
            return false;
        }

        // Reserve the destination first. If a container violates its simulation contract, the surplus reservation can
        // be rolled back without having to reconstruct or locate the transformed container item.
        long reserved = inventory.insert(slotIndex, content.what(), transferAmount, Actionable.MODULATE);
        if (reserved <= 0) {
            return false;
        }
        long extracted = context.extract(content.what(), reserved, Actionable.MODULATE);
        if (extracted < reserved) {
            long surplus = reserved - Math.max(0, extracted);
            long rolledBack = inventory.extract(slotIndex, content.what(), surplus, Actionable.MODULATE);
            if (rolledBack != surplus) {
                AELog.error("Failed to roll back %d of %s after a container extraction mismatch", surplus,
                        content.what());
            }
        } else if (extracted > reserved) {
            long recovered = inventory.insert(
                    slotIndex, content.what(), extracted - reserved, Actionable.MODULATE);
            if (recovered != extracted - reserved) {
                AELog.error("Container extracted more %s than requested: requested=%d, extracted=%d",
                        content.what(), reserved, extracted);
            }
        }
        if (extracted <= 0) {
            return false;
        }
        context.playEmptySound(gui.entityPlayer, content.what());
        return true;
    }

    private boolean fillCarriedContainerFromStorage(GenericStack stored) {
        var inventory = this.inventory;
        if (!canTakeItems || inventory == null || !inventory.canExtract() || gui == null) {
            return false;
        }

        var context = ContainerItemStrategies.findCarriedContextForKey(
                stored.what(), gui.entityPlayer, gui.getModularUIContainer());
        if (context == null) {
            return false;
        }
        long available = inventory.extract(
                slotIndex, stored.what(), Long.MAX_VALUE, Actionable.SIMULATE);
        if (available <= 0) {
            return false;
        }
        long accepted = context.insert(stored.what(), available, Actionable.SIMULATE);
        if (accepted <= 0) {
            return false;
        }

        long extracted = inventory.extract(slotIndex, stored.what(), accepted, Actionable.MODULATE);
        if (extracted <= 0) {
            return false;
        }
        long inserted = context.insert(stored.what(), extracted, Actionable.MODULATE);
        if (inserted < extracted) {
            long remainder = extracted - Math.max(0, inserted);
            long rolledBack = inventory.insert(slotIndex, stored.what(), remainder, Actionable.MODULATE);
            if (rolledBack != remainder) {
                AELog.error("Failed to restore %d of %s after a container fill mismatch", remainder, stored.what());
            }
        } else if (inserted > extracted) {
            AELog.error("Container accepted more %s than provided: provided=%d, inserted=%d", stored.what(),
                    extracted, inserted);
        }
        if (inserted <= 0) {
            return false;
        }
        context.playFillSound(gui.entityPlayer, stored.what());
        return true;
    }

    /**
     * Converts cross-viewer item and fluid ingredients into AE2 generic stacks for display or phantom assignment.
     * <p>
     * Supports AE2 stacks, vanilla/Forge item and fluid stacks, LDLib fluid stacks, and optional EMI/JEI wrapper types
     * when those integrations are loaded. The conversion does not mutate the source ingredient.
     *
     * @param ingredient ingredient object supplied by a recipe viewer, carried stack, or AE2 caller
     * @return equivalent AE2 stack, or {@code null} when the object is empty or not convertible
     */
    @Nullable
    public static GenericStack toGenericStack(@Nullable Object ingredient) {
        if (ingredient instanceof GenericStack stack) {
            return stack;
        }
        if (ingredient instanceof ItemStack itemStack) {
            return GenericStack.fromItemStack(itemStack);
        }
        if (ingredient instanceof net.minecraftforge.fluids.FluidStack fluidStack) {
            return GenericStack.fromFluidStack(fluidStack);
        }
        if (ingredient instanceof FluidStack fluidStack) {
            return fluidStack.isEmpty() ? null : new GenericStack(AEFluidKey.of(fluidStack.getFluid(), fluidStack.getTag()), fluidStack.getAmount());
        }
        if (MBD2.isAppliedMekanisticsLoaded()) {
            var chemical = AppliedMekanisticsCompat.toGenericStack(ingredient);
            if (chemical != null) return chemical;
        }
        if (LDLib.isEmiLoaded() && ingredient instanceof EmiStack emiStack) {
            return toGenericStack(emiStack);
        }
        if (LDLib.isJeiLoaded()) {
            if (ingredient instanceof ITypedIngredient<?> typedIngredient) {
                return toGenericStack(typedIngredient.getIngredient());
            }
            return appeng.integration.modules.jei.GenericEntryStackHelper.ingredientToStack(ingredient);
        }
        return null;
    }

    @Nullable
    private static GenericStack toGenericStack(EmiStack emiStack) {
        Item item = emiStack.getKeyOfType(Item.class);
        if (item != null) {
            var itemStack = new ItemStack(item);
            itemStack.setTag(emiStack.getNbt());
            var key = AEItemKey.of(itemStack);
            return key == null ? null : new GenericStack(key, emiStack.getAmount());
        }
        Fluid fluid = emiStack.getKeyOfType(Fluid.class);
        if (fluid != null) {
            return new GenericStack(AEFluidKey.of(fluid, emiStack.getNbt()), emiStack.getAmount());
        }
        if (MBD2.isAppliedMekanisticsLoaded()) {
            // JEMI may expose a string UID as its key; the chemical and its amount live in the JEI ingredient.
            if (LDLib.isJeiLoaded() && emiStack instanceof JemiStack<?> jemiStack) {
                var chemical = AppliedMekanisticsCompat.toGenericStack(jemiStack.ingredient);
                if (chemical != null) return chemical;
            }
            return AppliedMekanisticsCompat.toGenericStack(emiStack.getKey(), emiStack.getAmount());
        }
        return null;
    }

    /**
     * Gets the stack that should currently be rendered by the widget.
     * <p>
     * Client-side bound widgets read directly from the backing inventory when the slot is valid so editor and screen
     * refreshes see local changes immediately. Detached or server-side display falls back to the last synchronized stack.
     *
     * @return stack to display, or {@code null} when the slot is empty or invalid
     */
    @Nullable
    protected GenericStack getDisplayStack() {
        return inventory != null && isValidSlot() && isClientSideWidget ? getInventoryStack() : lastStack;
    }

    /**
     * Reads the backing AE2 config inventory at {@link #slotIndex}.
     *
     * @return current inventory stack, or {@code null} when no inventory is bound or the index is outside its range
     */
    @Nullable
    protected GenericStack getInventoryStack() {
        var inventory = this.inventory;
        if (!isValidSlot(inventory)) {
            return null;
        }
        return inventory.getStack(slotIndex);
    }

    /**
     * Checks whether the currently bound inventory contains {@link #slotIndex}.
     *
     * @return {@code true} when this widget can safely read or write its configured slot
     */
    protected boolean isValidSlot() {
        return isValidSlot(inventory);
    }

    /**
     * Checks whether the supplied inventory can be addressed with this widget's slot index.
     *
     * @param inventory candidate config inventory, or {@code null}
     * @return {@code true} when {@code inventory} is non-null and {@link #slotIndex} is in {@code [0, size)}
     */
    protected boolean isValidSlot(@Nullable ConfigInventory inventory) {
        return inventory != null && slotIndex >= 0 && slotIndex < inventory.size();
    }

    /**
     * Applies a new stack to the backing inventory or detached display state.
     * <p>
     * Bound slots respect insert/extract permissions and allowed-key filters. Detached widgets keep the stack only in
     * {@link #lastStack}. Side effects: may mutate the backing {@link ConfigInventory}, updates {@link #lastStack}, and
     * invokes {@link #notifyChangeListener()} after an accepted change.
     *
     * @param stack stack to store, or {@code null} to clear
     */
    protected void setStack(@Nullable GenericStack stack) {
        var inventory = this.inventory;
        if (isValidSlot(inventory)) {
            if (stack == null) {
                if (!mayClearStack()) {
                    return;
                }
            } else if (!maySetStack(stack)) {
                return;
            }
            inventory.setStack(slotIndex, stack);
            this.lastStack = getInventoryStack();
        } else {
            this.lastStack = stack;
        }
        notifyChangeListener();
    }

    /**
     * Changes only the amount of the current stack key.
     * <p>
     * The requested stack must have the same AE key as the current display/inventory stack. Non-positive amounts clear
     * the slot, while positive amounts reuse {@link #setStack(GenericStack)} so normal permission checks and side effects
     * still apply.
     *
     * @param requested stack carrying the target key and desired amount; amount must be within AE2's valid range
     */
    protected void setStackAmount(GenericStack requested) {
        var current = isRemote() || isClientSideWidget ? getDisplayStack() : getInventoryStack();
        if (current == null || !current.what().equals(requested.what())) {
            return;
        }
        if (requested.amount() <= 0) {
            setStack(null);
        } else {
            setStack(requested);
        }
    }

    /**
     * Sends the current backing inventory stack to the client.
     * <p>
     * Called after server-side client actions mutate the slot. Side effects: refreshes {@link #lastStack} from the
     * inventory and writes an {@link #UPDATE_STACK} packet through LDLib.
     */
    protected void syncStackToClient() {
        var stack = getInventoryStack();
        this.lastStack = stack;
        writeUpdateInfo(UPDATE_STACK, buffer -> GenericStack.writeBuffer(stack, buffer));
    }

    /**
     * Synchronizes the server-authoritative cursor after a real-storage action.
     */
    protected void syncCarriedStackToClient() {
        if (gui == null) {
            return;
        }
        var carried = gui.getModularUIContainer().getCarried().copy();
        writeUpdateInfo(UPDATE_CARRIED_STACK, buffer -> buffer.writeItem(carried));
    }

    /**
     * Tests whether a non-null stack may replace or populate the bound inventory slot.
     *
     * @param stack stack requested for insertion or replacement
     * @return {@code true} when putting is enabled, the slot is valid, the inventory accepts insertion, replacement is
     * allowed for the current contents, and the key passes the inventory filter
     */
    protected boolean maySetStack(GenericStack stack) {
        var inventory = this.inventory;
        if (!canPutItems || !isValidSlot(inventory)) {
            return false;
        }
        var current = inventory.getStack(slotIndex);
        return inventory.canInsert()
                && (current == null || current.what().equals(stack.what()) || inventory.canExtract())
                && inventory.isAllowed(stack);
    }

    /**
     * Tests whether the bound inventory slot may be cleared.
     *
     * @return {@code true} when taking is enabled, the slot is valid, and the inventory permits extraction
     */
    protected boolean mayClearStack() {
        var inventory = this.inventory;
        return canTakeItems && isValidSlot(inventory) && inventory.canExtract();
    }

    /**
     * Tests whether the active side may set a stack.
     * <p>
     * Bound slots use the backing inventory permission checks. Detached slots rely on the widget flag only because no
     * inventory can reject the change.
     *
     * @param stack stack requested for insertion or replacement
     * @return {@code true} when this side may accept the stack
     */
    protected boolean maySetStackOnThisSide(GenericStack stack) {
        return isValidSlot() ? maySetStack(stack) : canPutItems;
    }

    /**
     * Tests whether a recipe-viewer phantom ingredient may populate this slot on the active side.
     *
     * @param stack phantom stack proposed by the recipe viewer
     * @return {@code true} when phantom input is enabled and normal set permissions allow the stack
     */
    protected boolean mayAcceptPhantomOnThisSide(GenericStack stack) {
        return canAcceptPhantom && maySetStackOnThisSide(stack);
    }

    /**
     * Tests whether the active side may clear the current stack.
     *
     * @return {@code true} when a bound slot can extract, or when a detached slot has taking enabled
     */
    protected boolean mayClearStackOnThisSide() {
        return isValidSlot() ? mayClearStack() : canTakeItems;
    }

    /**
     * Tests whether the active side may apply an amount edit for the current key.
     * <p>
     * The requested key must match the displayed key. A zero amount follows clear permissions; positive amounts follow
     * normal set permissions.
     *
     * @param requested stack carrying the current key and desired amount
     * @return {@code true} when the amount edit is valid for the current side and permissions
     */
    protected boolean maySetAmountOnThisSide(GenericStack requested) {
        var current = getDisplayStack();
        if (current == null || !current.what().equals(requested.what())) {
            return false;
        }
        if (requested.amount() <= 0) {
            return mayClearStackOnThisSide();
        }
        return maySetStackOnThisSide(requested);
    }

    /**
     * Opens the client-side amount editor for the displayed key.
     * <p>
     * Side effects: creates a modal dialog, validates numeric input in the range {@code [0, getMaxAmount(stack)]}, updates
     * local state on submit, and sends an {@link #ACTION_SET_AMOUNT} client action. Must only be called from the client UI
     * thread while {@link #gui} is available.
     *
     * @param stack current stack whose key will be preserved while editing the amount
     */
    @OnlyIn(Dist.CLIENT)
    protected void openAmountDialog(GenericStack stack) {
        var dialog = new DialogWidget(gui.mainGroup, true);
        dialog.addWidget(new ImageWidget(0, 0, dialog.getSize().width, dialog.getSize().height, new ColorRectTexture(0x66000000)));

        int width = 176;
        int height = 86;
        int x = (dialog.getSize().width - width) / 2;
        int y = (dialog.getSize().height - height) / 2;
        var panel = new WidgetGroup(x, y, width, height);
        panel.setBackground(ResourceBorderTexture.BORDERED_BACKGROUND);
        dialog.addWidget(panel);

        panel.addWidget(new ImageWidget(8, 7, width - 16, 12,
                new TextTexture(SET_AMOUNT_TITLE).setWidth(width - 16).setDropShadow(false).setType(TextTexture.TextType.LEFT)));
        panel.addWidget(new ImageWidget(8, 24, width - 16, 10,
                new TextTexture(SET_AMOUNT_VALUE).setWidth(width - 16).setDropShadow(false).setType(TextTexture.TextType.LEFT)));

        var textField = new TextFieldWidget(8, 38, width - 16, 18, null, null);
        textField.setCurrentString(Long.toString(stack.amount()));
        textField.setMaxStringLength(19);
        textField.setNumbersOnly(0L, getMaxAmount(stack));
        panel.addWidget(textField);

        Runnable submit = () -> {
            var amount = parseAmount(stack, textField.getCurrentString());
            if (amount == null) {
                return;
            }
            var requested = new GenericStack(stack.what(), amount);
            if (!maySetAmountOnThisSide(requested)) {
                return;
            }
            setStackAmount(requested);
            writeClientAction(ACTION_SET_AMOUNT, buffer -> GenericStack.writeBuffer(requested, buffer));
            dialog.close();
        };

        panel.addWidget(createVanillaButton(26, height - 24, 54, 20, "ldlib.gui.tips.confirm", submit));
        panel.addWidget(createVanillaButton(width - 80, height - 24, 54, 20, "ldlib.gui.tips.cancel", dialog::close));
    }

    private static ButtonWidget createVanillaButton(int x, int y, int width, int height, String text, Runnable onClick) {
        var textTexture = new TextTexture(text).setWidth(width).setDropShadow(false);
        var button = new ButtonWidget(x, y, width, height,
                new GuiTextureGroup(ResourceBorderTexture.VANILLA_BUTTON_NORMAL, textTexture),
                clickData -> onClick.run());
        button.setHoverTexture(new GuiTextureGroup(ResourceBorderTexture.VANILLA_BUTTON_SELECTED,
                new TextTexture(text).setWidth(width).setDropShadow(false)));
        button.setClickedTexture(new GuiTextureGroup(ResourceBorderTexture.VANILLA_BUTTON_PRESSED,
                new TextTexture(text).setWidth(width).setDropShadow(false)));
        return button;
    }

    private static boolean isValidAmountText(GenericStack stack, @Nullable String input) {
        if (input == null || input.isBlank()) {
            return true;
        }
        return parseAmount(stack, input) != null;
    }

    @Nullable
    private static Long parseAmount(GenericStack stack, @Nullable String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            var amount = Long.parseLong(input.trim());
            var maxAmount = getMaxAmount(stack);
            return amount >= 0 && amount <= maxAmount ? amount : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long getMaxAmount(GenericStack stack) {
        return stack.what() instanceof AEFluidKey
                || (MBD2.isAppliedMekanisticsLoaded() && AppliedMekanisticsCompat.isChemicalKey(stack.what()))
                ? Long.MAX_VALUE : Integer.MAX_VALUE;
    }

    /**
     * Notifies listeners that this widget's logical stack changed.
     * <p>
     * The callback runs synchronously on the caller's thread, so callers should invoke this from the same UI/server
     * context that owns the surrounding widget state.
     */
    protected void notifyChangeListener() {
        if (changeListener != null) {
            changeListener.run();
        }
    }
}
