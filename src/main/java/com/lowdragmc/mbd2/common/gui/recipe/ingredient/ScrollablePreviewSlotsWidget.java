package com.lowdragmc.mbd2.common.gui.recipe.ingredient;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurableWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.mbd2.api.capability.recipe.IO;
import com.lowdragmc.mbd2.api.capability.recipe.RecipeCapability;
import com.lowdragmc.mbd2.api.recipe.content.Content;
import com.lowdragmc.mbd2.common.capability.recipe.FluidRecipeCapability;
import com.lowdragmc.mbd2.common.capability.recipe.ItemRecipeCapability;
import com.lowdragmc.mbd2.utils.WidgetUtils;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Scrollable XEI preview container for item and fluid recipe contents.
 *
 * <p>The widget creates capability-specific XEI template widgets, binds them to recipe
 * {@link Content}, and lays them out in a fixed-size grid. It is intended for recipe UI
 * templates that need more slots than fit in a static row. Slot ids follow the
 * {@code @scrollable_preview_slot_<capability>_<side>_<index>} convention so other binding
 * code can find generated widgets predictably.</p>
 *
 * <p>Runtime binding preserves the current vertical scroll offset and snaps scrolling to
 * whole slot rows. The widget is UI-thread state and is not thread-safe.</p>
 */
@Configurable(name = "ldlib.gui.editor.register.widget.container.scrollable_preview_slots", collapse = false)
@LDLRegister(name = "scrollable_preview_slots", group = "widget.container")
public class ScrollablePreviewSlotsWidget extends DraggableScrollableWidgetGroup implements IConfigurableWidget {

    @Configurable(name = "ldlib.gui.editor.name.slotSize")
    protected int slotSize = Integer.MAX_VALUE;
    @Configurable(name = "ldlib.gui.editor.name.slotGap")
    protected int slotGap = 0;
    @Configurable(name = "ldlib.gui.editor.name.columns")
    protected int columns = Integer.MAX_VALUE;
    protected Supplier<List<PreviewSlot>> previewSlotSupplier;
    protected IngredientIO previewSlotIngredientIO = IngredientIO.RENDER_ONLY;
    protected List<PreviewSlot> lastPreviewSlots = Collections.emptyList();

    /**
     * Creates a default 72x36 preview-slot container for editor registration.
     */
    public ScrollablePreviewSlotsWidget() {
        this(0, 0, 72, 36);
    }

    /**
     * Creates a preview-slot container.
     *
     * @param x      left position relative to the parent widget
     * @param y      top position relative to the parent widget
     * @param width  widget width in pixels
     * @param height widget height in pixels
     */
    public ScrollablePreviewSlotsWidget(int x, int y, int width, int height) {
        super(x, y, width, height);
        setXScrollBarHeight(0);
        setYScrollBarWidth(4);
    }

    /**
     * Sets each generated slot's square size.
     *
     * @param slotSize size in pixels; values below 1 are clamped to 1
     * @return this widget for chaining
     */
    public ScrollablePreviewSlotsWidget setSlotSize(int slotSize) {
        this.slotSize = Math.max(1, slotSize);
        return this;
    }

    /**
     * Sets the gap between generated slots.
     *
     * @param slotGap gap in pixels; negative values are clamped to 0
     * @return this widget for chaining
     */
    public ScrollablePreviewSlotsWidget setSlotGap(int slotGap) {
        this.slotGap = Math.max(0, slotGap);
        return this;
    }

    /**
     * Sets the number of columns in the generated grid.
     *
     * @param columns column count; values below 1 are clamped to 1
     * @return this widget for chaining
     */
    public ScrollablePreviewSlotsWidget setColumns(int columns) {
        this.columns = Math.max(1, columns);
        return this;
    }

    /**
     * Rebuilds slots from all supported capabilities in the provided recipe content map.
     *
     * @param values recipe content map keyed by capability
     * @param io     recipe side used for ingredient role and generated ids
     */
    public void bindXEIContents(Map<RecipeCapability<?>, List<Content>> values, IO io) {
        int scrollYOffset = getScrollYOffset();
        clearAllWidgets();
        List<PreviewSlot> previewSlots = collectPreviewSlots(values, io);
        for (int i = 0; i < previewSlots.size(); i++) {
            PreviewSlot slot = previewSlots.get(i);
            Widget widget = slot.capability.createXEITemplate();
            widget.setSelfPosition(getSlotPosition(i));
            bindXEIWidget(slot, widget, ingredientIO(io));
            widget.setId(getSlotId(slot.capability, io, i));
            addWidget(widget);
        }
        computeMax();
        setScrollYOffset(scrollYOffset);
    }

    /**
     * Rebuilds slots for one exact capability name.
     *
     * @param values         recipe content map keyed by capability
     * @param io             recipe side used for ingredient role and generated ids
     * @param capabilityName exact capability name to display
     */
    public void bindXEIContents(Map<RecipeCapability<?>, List<Content>> values, IO io, String capabilityName) {
        bindXEIContents(values, io, Pattern.compile(Pattern.quote(capabilityName)));
    }

    /**
     * Rebuilds slots for capabilities whose names match a pattern.
     *
     * @param values         recipe content map keyed by capability
     * @param io             recipe side used for ingredient role and generated ids
     * @param capabilityName pattern matched against {@link RecipeCapability#name}
     */
    public void bindXEIContents(Map<RecipeCapability<?>, List<Content>> values, IO io, Pattern capabilityName) {
        int scrollYOffset = getScrollYOffset();
        clearAllWidgets();
        List<PreviewSlot> previewSlots = collectPreviewSlots(values, io);
        for (int i = 0, visibleIndex = 0; i < previewSlots.size(); i++) {
            PreviewSlot slot = previewSlots.get(i);
            if (!capabilityName.matcher(slot.capability.name).matches()) {
                continue;
            }
            Widget widget = slot.capability.createXEITemplate();
            widget.setSelfPosition(getSlotPosition(visibleIndex));
            bindXEIWidget(slot, widget, ingredientIO(io));
            widget.setId(getSlotId(slot.capability, io, visibleIndex));
            addWidget(widget);
            visibleIndex++;
        }
        computeMax();
        setScrollYOffset(scrollYOffset);
    }

    /**
     * Rebuilds slots from precomputed preview slot descriptors.
     *
     * @param previewSlots ordered slot descriptors to render
     * @param ingredientIO XEI ingredient role to bind to every generated widget
     */
    public void bindXEIContents(List<PreviewSlot> previewSlots, IngredientIO ingredientIO) {
        int scrollYOffset = getScrollYOffset();
        lastPreviewSlots = List.copyOf(previewSlots);
        clearAllWidgets();
        for (int i = 0; i < previewSlots.size(); i++) {
            PreviewSlot slot = previewSlots.get(i);
            Widget widget = slot.capability.createXEITemplate();
            widget.setSelfPosition(getSlotPosition(i));
            bindXEIWidget(slot, widget, ingredientIO);
            widget.setId(getSlotId(slot.capability, ingredientIO, i));
            addWidget(widget);
        }
        computeMax();
        setScrollYOffset(scrollYOffset);
    }

    /**
     * Configures live slot data for client-side preview updates.
     *
     * <p>On each screen update the supplier is compared against the last rendered slot list.
     * The widget only rebuilds when the list changes.</p>
     *
     * @param previewSlotSupplier supplier for ordered preview slots; {@code null} disables
     *                            dynamic updates
     * @param ingredientIO        XEI ingredient role to bind to supplied slots; {@code null} maps
     *                            to {@link IngredientIO#RENDER_ONLY}
     * @return this widget for chaining
     */
    public ScrollablePreviewSlotsWidget setPreviewSlotSupplier(Supplier<List<PreviewSlot>> previewSlotSupplier, IngredientIO ingredientIO) {
        this.previewSlotSupplier = previewSlotSupplier;
        this.previewSlotIngredientIO = ingredientIO == null ? IngredientIO.RENDER_ONLY : ingredientIO;
        this.lastPreviewSlots = Collections.emptyList();
        return this;
    }

    @Override
    public void updateScreen() {
        if (previewSlotSupplier != null) {
            List<PreviewSlot> previewSlots = Objects.requireNonNullElseGet(previewSlotSupplier.get(), Collections::emptyList);
            if (!previewSlots.equals(lastPreviewSlots)) {
                bindXEIContents(previewSlots, previewSlotIngredientIO);
            }
        }
        super.updateScreen();
    }

    @Override
    public void setScrollYOffset(int scrollYOffset) {
        int pitch = getSlotPitch();
        super.setScrollYOffset(Math.max(0, Math.round(scrollYOffset / (float) pitch) * pitch));
    }

    @Override
    public List<Widget> getContainedWidgets(boolean includeHidden) {
        if (includeHidden) {
            return super.getContainedWidgets(true);
        }
        return widgets.stream()
                .filter(Widget::isVisible)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /**
     * Calculates the top-left position for a generated slot.
     *
     * @param index zero-based visible slot index
     * @return slot position relative to this widget
     */
    public Position getSlotPosition(int index) {
        int pitch = getSlotPitch();
        return new Position((index % columns) * pitch, (index / columns) * pitch);
    }

    /**
     * Returns the slot-to-slot distance in pixels.
     */
    public int getSlotPitch() {
        return slotSize + slotGap;
    }

    /**
     * Binds all scrollable preview widgets inside a recipe UI template.
     *
     * <p>Widgets with id {@code @scrollable_preview_<side>} receive all supported contents
     * for that side. Widgets with id {@code @scrollable_preview_<side>_<capability>} receive
     * only matching capability contents.</p>
     *
     * @param ui     recipe UI root to search
     * @param values recipe content map keyed by capability
     * @param io     recipe side to bind
     */
    public static void bindXEIRecipeUI(com.lowdragmc.lowdraglib.gui.widget.WidgetGroup ui, Map<RecipeCapability<?>, List<Content>> values, IO io) {
        WidgetUtils.widgetByIdForEach(ui, "^@scrollable_preview_%s$".formatted(io.name), ScrollablePreviewSlotsWidget.class,
                previewSlots -> previewSlots.bindXEIContents(values, io));
        WidgetUtils.widgetByIdForEach(ui, "^@scrollable_preview_%s_[a-zA-Z0-9_.:-]+$".formatted(io.name), ScrollablePreviewSlotsWidget.class,
                previewSlots -> {
                    String id = previewSlots.getId();
                    String prefix = "@scrollable_preview_%s_".formatted(io.name);
                    previewSlots.bindXEIContents(values, io, Pattern.quote(id.substring(prefix.length())));
                });
    }

    /**
     * Collects supported item/fluid contents into ordered slot descriptors.
     *
     * @param values recipe content map keyed by capability
     * @param io     recipe side represented by the collected slots
     * @return immutable-empty or mutable ordered list of preview slots
     */
    public static List<PreviewSlot> collectPreviewSlots(Map<RecipeCapability<?>, List<Content>> values, IO io) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<PreviewSlot> previewSlots = new ArrayList<>();
        values.forEach((capability, contents) -> {
            if (capability instanceof ItemRecipeCapability || capability instanceof FluidRecipeCapability) {
                for (Content content : contents) {
                    previewSlots.add(new PreviewSlot(capability, content, io));
                }
            }
        });
        return previewSlots;
    }

    private static void bindXEIWidget(PreviewSlot slot, Widget widget, IngredientIO ingredientIO) {
        slot.capability.bindXEIWidget(widget, slot.content, ingredientIO);
        var tooltips = new ArrayList<Component>();
        slot.content.appendTooltip(tooltips,
                slot.capability.supportsOutputRange(slot.content.minOutput, slot.content.maxOutput)
                        && slot.capability.supportsOutputMultiplier(slot.content.content, slot.content.maxOutput), slot.io);
        if (!tooltips.isEmpty()) {
            widget.appendHoverTooltips(tooltips);
        }
    }

    private static String getSlotId(RecipeCapability<?> capability, IO io, int index) {
        return "@scrollable_preview_slot_%s_%s_%d".formatted(capability.name, io.name, index);
    }

    private static String getSlotId(RecipeCapability<?> capability, IngredientIO ingredientIO, int index) {
        return "@scrollable_preview_slot_%s_%s_%d".formatted(capability.name, ingredientIO.name().toLowerCase(), index);
    }

    private static IngredientIO ingredientIO(IO io) {
        return switch (io) {
            case IN -> IngredientIO.INPUT;
            case OUT -> IngredientIO.OUTPUT;
            case BOTH -> IngredientIO.BOTH;
            default -> IngredientIO.RENDER_ONLY;
        };
    }

    /**
     * One content entry to render in a scrollable preview slot.
     *
     * @param capability capability that creates and binds the preview widget
     * @param content    recipe content value and metadata to display
     * @param io         recipe side associated with the content
     */
    public record PreviewSlot(RecipeCapability<?> capability, Content content, IO io) {
    }
}
