package com.lowdragmc.mbd2.common.gui.editor.recipe;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.runtime.ConfiguratorParser;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.texture.WidgetTexture;
import com.lowdragmc.lowdraglib.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.mbd2.api.capability.recipe.IO;
import com.lowdragmc.mbd2.api.capability.recipe.RecipeCapability;
import com.lowdragmc.mbd2.api.recipe.content.Content;
import com.lowdragmc.mbd2.api.recipe.content.ContentWidget;
import com.lowdragmc.mbd2.api.registry.MBDRegistries;
import com.lowdragmc.mbd2.common.gui.editor.MachineEditor;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.Tuple;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

/**
 * Scrollable editor for recipe input or output content stacks.
 *
 * <p>The container displays mutable {@link Content} entries grouped by
 * {@link RecipeCapability}. Selecting a row opens both generic content fields and the
 * capability-specific content configurator. Context-menu add, copy, and remove operations
 * update the supplied content map directly and rebuild the owning recipe-row preview. Value
 * edits keep the surrounding widget tree intact and notify {@code onContentChanged} so its
 * existing compact preview can refresh in place.</p>
 *
 * <p>This widget is client editor UI. It is not thread-safe and assumes all mutations occur
 * on the render/UI thread.</p>
 */
public class ContentContainer extends WidgetGroup {
    private final Map<RecipeCapability<?>, List<Content>> contents;
    private final IO io;
    private final DraggableScrollableWidgetGroup container;
    private final Runnable onContentsStructureChanged;
    @Setter
    private Runnable onContentChanged = () -> { };
    private final Map<Content, ContentWidget<?>> contentWidgets = new IdentityHashMap<>();
    @Getter
    @Nullable
    private Tuple<RecipeCapability, Content> selected;
    @Setter
    @Nullable
    private Runnable onSelected;

    /**
     * Creates a content editor that can add either input or output defaults.
     *
     * @param x               left position relative to the parent widget
     * @param y               top position relative to the parent widget
     * @param width           widget width in pixels
     * @param height          widget height in pixels
     * @param contents        mutable capability-to-content map to edit
     * @param onContentUpdate callback invoked after structural content changes
     */
    public ContentContainer(int x, int y, int width, int height, Map<RecipeCapability<?>, List<Content>> contents, Runnable onContentUpdate) {
        this(x, y, width, height, contents, onContentUpdate, IO.BOTH);
    }

    /**
     * Creates a content editor for a specific recipe side.
     *
     * @param x               left position relative to the parent widget
     * @param y               top position relative to the parent widget
     * @param width           widget width in pixels
     * @param height          widget height in pixels
     * @param contents        mutable capability-to-content map to edit
     * @param onContentUpdate callback invoked after structural changes to {@code contents}
     * @param io              side used when creating capability default content and configurators
     */
    public ContentContainer(int x, int y, int width, int height, Map<RecipeCapability<?>, List<Content>> contents, Runnable onContentUpdate, IO io) {
        super(x, y, width, height);
        this.contents = contents;
        this.io = io;
        this.onContentsStructureChanged = onContentUpdate;
        this.container = new DraggableScrollableWidgetGroup(0, 15, width, height - 15);
        this.container.setYScrollBarWidth(4).setYBarStyle(null, ColorPattern.T_WHITE.rectTexture().setRadius(2).transform(-0.5f, 0));
        this.container.setBackground(ColorPattern.T_WHITE.borderTexture(1));
        addWidget(container);
        createTitle();
        reloadContents();
    }

    /**
     * Clears the selected row without modifying the backing content map.
     */
    public void clearSelected() {
        container.setSelected(null);
    }

    /**
     * Creates fixed column labels for metadata fields.
     */
    private void createTitle() {
        var width = container.getSizeWidth() - 5;
        var dur = 5;
        var x = 0;
        var showOutputRange = showOutputRangeColumns();
        var metadataColumns = showOutputRange ? 6 : 4;
        var textFieldWidth = Math.max(50, (width - 20 - dur - 20 - dur) / metadataColumns - dur);
        appendTitleWidget(x, 20, "C");
        x += (20 + dur);
        if (x + 20 > width) return;
        appendTitleWidget(x, 20, "tick");
        x += (20 + dur);
        if (x + textFieldWidth > width) return;
        appendTitleWidget(x, textFieldWidth, "editor.machine.recipe_type.content.chance");
        x += (textFieldWidth + dur);
        if (x + textFieldWidth > width) return;
        appendTitleWidget(x, textFieldWidth, "editor.machine.recipe_type.content.tier_chance_boost");
        x += (textFieldWidth + dur);
        if (x + textFieldWidth > width) return;
        if (showOutputRange) {
            appendTitleWidget(x, textFieldWidth, "editor.machine.recipe_type.content.min_output");
            x += (textFieldWidth + dur);
            if (x + textFieldWidth > width) return;
            appendTitleWidget(x, textFieldWidth, "editor.machine.recipe_type.content.max_output");
            x += (textFieldWidth + dur);
            if (x + textFieldWidth > width) return;
        }
        appendTitleWidget(x, textFieldWidth, "editor.machine.recipe_type.content.slot_name");
        x += (textFieldWidth + dur);
        if (x + textFieldWidth > width) return;
        appendTitleWidget(x, textFieldWidth, "editor.machine.recipe_type.content.ui_name");
    }

    /**
     * Adds one title label to the metadata header.
     *
     * @param x     left offset in pixels
     * @param width label width in pixels
     * @param label translation key or literal text to display
     */
    private void appendTitleWidget(int x, int width, String label) {
        addWidget(new ImageWidget(x, 0, width, 15, new TextTexture(label).setWidth(width)));
    }

    /**
     * Rebuilds row widgets from the current content map.
     */
    private void reloadContents() {
        container.clearAllWidgets();
        contentWidgets.clear();
        for (var entry : contents.entrySet()) {
            var cap = (RecipeCapability) entry.getKey();
            var contentList = entry.getValue();
            for (var content : contentList) {
                var contentLine = createContentLine(cap, content);
                contentLine.setSelfPosition(0, container.getAllWidgetSize() * 20);
                contentLine.setSelectedTexture(new GuiTextureGroup(ColorPattern.T_GRAY.rectTexture()));
                contentLine.setOnSelected(group -> {
                    selected = new Tuple<>(cap, content);
                    if (onSelected != null) {
                        onSelected.run();
                    }
                    if (Editor.INSTANCE != null) {
                        Editor.INSTANCE.getConfigPanel().openConfigurator(MachineEditor.SECOND, new IConfigurable() {
                            @Override
                            public void buildConfigurator(ConfiguratorGroup father) {
                                var contentGroup = new ConfiguratorGroup("editor.machine.basic_settings");
                                ConfiguratorParser.createConfigurators(contentGroup, new HashMap<>(), content.getClass(), content);
                                father.addConfigurators(contentGroup);
                                cap.createContentConfigurator(father, () -> cap.of(content.content),
                                        updatedContent -> updateContent(content, updatedContent), io);
                            }
                        });

                    }
                });
                contentLine.setOnUnSelected(group -> {
                    selected = null;
                    if (Editor.INSTANCE != null) {
                        Editor.INSTANCE.getConfigPanel().clearAllConfigurators(MachineEditor.SECOND);
                    }
                });
                container.addWidget(contentLine);
            }
        }
    }

    /**
     * Creates one selectable content row.
     *
     * @param cap     capability that owns and renders the content payload
     * @param content mutable recipe content metadata and value
     * @return selectable row with preview widget and editable metadata fields
     */
    private SelectableWidgetGroup createContentLine(RecipeCapability<?> cap, Content content) {
        var width = container.getSizeWidth() - 5;
        var contentLine = new SelectableWidgetGroup(0, 0, width, 20);
        var contentWidget = new ContentWidget<>(0, 0, cap, content, io);
        contentWidgets.put(content, contentWidget);
        var dur = 5;
        var x = 0;
        var showOutputRange = showOutputRangeColumns();
        var metadataColumns = showOutputRange ? 6 : 4;
        var textFieldWidth = Math.max(50, (width - 20 - dur - 20 - dur) / metadataColumns - dur);
        contentLine.addWidget(contentWidget);
        x += (20 + dur);
        if (x + 20 > width) return contentLine;
        contentLine.addWidget(new SwitchWidget(x + 1, 1, 18, 18,
                (cd, pressed) -> content.perTick = pressed)
                .setSupplier(() -> content.perTick)
                .setTexture(
                        new GuiTextureGroup(ColorPattern.WHITE.borderTexture(-2)).scale(0.6f),
                        new GuiTextureGroup(ColorPattern.WHITE.borderTexture(-2), ColorPattern.WHITE.rectTexture().scale(0.5f)).scale(0.6f)
                ));
        x += (20 + dur);
        if (x + textFieldWidth > width) return contentLine;
        createFloatField(contentLine, x, textFieldWidth, content.chance, f -> content.chance = f, 0, 1);
        x += (textFieldWidth + dur);
        if (x + textFieldWidth > width) return contentLine;
        createFloatField(contentLine, x, textFieldWidth, content.tierChanceBoost, f -> content.tierChanceBoost = f, 0, 1);
        x += (textFieldWidth + dur);
        if (x + textFieldWidth > width) return contentLine;
        if (showOutputRange) {
            boolean rangeEnabled = cap.supportsOutputRange();
            long rangeMaximum = Long.MAX_VALUE;
            boolean rangeValid = rangeEnabled && cap.supportsOutputRange(content.minOutput, content.maxOutput)
                    && cap.supportsOutputMultiplier(content.content, content.maxOutput);
            long displayedMin = rangeEnabled && (content.minOutput == Content.OUTPUT_RANGE_DISABLED || rangeValid)
                    ? content.minOutput : Content.OUTPUT_RANGE_DISABLED;
            long displayedMax = rangeEnabled && (content.maxOutput == Content.OUTPUT_RANGE_DISABLED || rangeValid)
                    ? content.maxOutput : Content.OUTPUT_RANGE_DISABLED;
            createLongField(contentLine, x, textFieldWidth,
                    rangeEnabled ? displayedMin : Content.OUTPUT_RANGE_DISABLED,
                    rangeEnabled ? value -> content.minOutput = value : value -> { },
                    -1, rangeMaximum, rangeEnabled);
            x += (textFieldWidth + dur);
            if (x + textFieldWidth > width) return contentLine;
            createLongField(contentLine, x, textFieldWidth,
                    rangeEnabled ? displayedMax : Content.OUTPUT_RANGE_DISABLED,
                    rangeEnabled ? value -> content.maxOutput = value : value -> { },
                    -1, rangeMaximum, rangeEnabled);
            x += (textFieldWidth + dur);
            if (x + textFieldWidth > width) return contentLine;
        }
        createStringField(contentLine, x, textFieldWidth, content.slotName, s -> content.slotName = s);
        x += (textFieldWidth + dur);
        if (x + textFieldWidth > width) return contentLine;
        createStringField(contentLine, x, textFieldWidth, content.uiName, s -> content.uiName = s);
        return contentLine;
    }

    /**
     * Applies a capability configurator update and immediately refreshes every
     * editor preview that represents the owning recipe content.
     */
    private void updateContent(Content content, Object updatedContent) {
        content.updateContent(updatedContent);
        var contentWidget = contentWidgets.get(content);
        if (contentWidget != null) {
            contentWidget.refreshContent();
        }
        onContentChanged.run();
    }

    /**
     * Adds a bounded float text field to a content row.
     */
    private static void createFloatField(WidgetGroup contentLine, int x, int textFieldWidth, float initial, Consumer<Float> setter, float min, float max) {
        var textField = new TextFieldWidget(x + 3, 5, textFieldWidth - 3, 10, null,
                s -> setter.accept(Float.parseFloat(s)))
                .setCurrentString(String.valueOf(initial))
                .setNumbersOnly(min, max);
        textField.setBordered(false);
        textField.setWheelDur(0.1f);
        contentLine.addWidget(new ImageWidget(x, 5, textFieldWidth, 10, ColorPattern.T_GRAY.rectTexture().setRadius(5)));
        contentLine.addWidget(textField);
    }

    /**
     * Adds a bounded long text field to a content row.
     *
     * <p>{@code -1} is accepted by the output range fields as the disabled
     * sentinel, while non-negative values represent an output multiplier.</p>
     */
    private static void createLongField(WidgetGroup contentLine, int x, int textFieldWidth, long initial,
                                        Consumer<Long> setter, long min, long max, boolean active) {
        var textField = new TextFieldWidget(x + 3, 5, textFieldWidth - 3, 10, null,
                s -> {
                    try {
                        setter.accept(Long.parseLong(s));
                    } catch (NumberFormatException ignored) {
                        // Keep the last valid value while the user edits the field.
                    }
                })
                .setCurrentString(Long.toString(initial))
                .setMaxStringLength(20);
        textField.setBordered(false);
        textField.setActive(active);
        textField.setWheelDur(1);
        textField.setNumbersOnly(min, max);
        contentLine.addWidget(new ImageWidget(x, 5, textFieldWidth, 10, ColorPattern.T_GRAY.rectTexture().setRadius(5)));
        contentLine.addWidget(textField);
    }

    /** Returns whether this editor is the output table with stable range columns. */
    private boolean showOutputRangeColumns() {
        // Keep the output table layout stable while capabilities are added or
        // removed through the context menu. Unsupported capabilities render
        // disabled range fields in these columns.
        return io == IO.OUT;
    }

    /**
     * Adds a free-form string text field to a content row.
     */
    private static void createStringField(WidgetGroup contentLine, int x, int textFieldWidth, @Nullable String initial, Consumer<String> setter) {
        var textField = new TextFieldWidget(x + 3, 5, textFieldWidth - 3, 10, null, setter)
                .setCurrentString(initial == null ? "" : initial);
        textField.setBordered(false);
        contentLine.addWidget(new ImageWidget(x, 5, textFieldWidth, 10, ColorPattern.T_GRAY.rectTexture().setRadius(5)));
        contentLine.addWidget(textField);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (container.isMouseOverElement(mouseX, mouseY) && button == 1 && Editor.INSTANCE != null) {
            var menu = TreeBuilder.Menu.start()
                    .branch(Icons.ADD_FILE, "editor.machine.recipe_type.add_content", m -> {
                        for (RecipeCapability cap : MBDRegistries.RECIPE_CAPABILITIES.values()) {
                            m.leaf(new WidgetTexture(cap.createPreviewWidget(cap.createDefaultContent(io)).setClientSideWidget()), "recipe.capability.%s.name".formatted(cap.name), () -> {
                                var content = cap.createDefaultContent(io);
                                contents.computeIfAbsent(cap, c -> new ArrayList<>()).add(
                                        new Content(content, false, 1, 0));
                                reloadContents();
                                onContentsStructureChanged.run();
                            });
                        }
                    });
            if (selected != null) {
                menu.crossLine();
                menu.leaf(Icons.COPY, "ldlib.gui.editor.menu.copy", () -> {
                    var copied = selected.getB().deepCopy(selected.getA(), null);
                    contents.computeIfAbsent(selected.getA(), cap -> new ArrayList<>()).add(copied);
                    reloadContents();
                    onContentsStructureChanged.run();
                });
                menu.leaf(Icons.REMOVE_FILE, "editor.machine.recipe_type.remove_content", () -> {
                    var contentList = contents.getOrDefault(selected.getA(), Collections.emptyList());
                    contentList.remove(selected.getB());
                    if (contentList.isEmpty()) {
                        contents.remove(selected.getA());
                    }
                    selected = null;
                    Editor.INSTANCE.getConfigPanel().clearAllConfigurators(MachineEditor.SECOND);
                    reloadContents();
                    onContentsStructureChanged.run();
                });
            }
            Editor.INSTANCE.openMenu(mouseX, mouseY, menu);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

}
