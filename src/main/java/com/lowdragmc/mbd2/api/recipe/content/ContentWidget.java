package com.lowdragmc.mbd2.api.recipe.content;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.mbd2.api.capability.recipe.IO;
import com.lowdragmc.mbd2.api.capability.recipe.RecipeCapability;
import lombok.Getter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor widget that renders one recipe {@link Content} value through its
 * {@link RecipeCapability}.
 *
 * <p>The business goal is to provide a compact live preview for recipe content
 * while preserving common overlays and tooltips. This widget is client-side UI
 * state; it mutates child widgets when content changes but does not mutate recipe
 * logic or world state.</p>
 *
 * @param <T> capability content type
 */
@Getter
public class ContentWidget<T> extends WidgetGroup {
    private final RecipeCapability<T> cap;
    private final Content content;
    @Nullable
    private final IO io;
    private Tag lastContentTag;
    private long lastPreviewVersion;

    /**
     * Creates a 20x20 content preview widget.
     *
     * <p>Side effects: creates and adds the capability-specific preview widget at
     * offset {@code (1, 1)} and stores an NBT snapshot used for change detection.</p>
     *
     * @param x       widget x coordinate
     * @param y       widget y coordinate
     * @param cap     capability used to convert and preview the content
     * @param content content wrapper to display
     */
    public ContentWidget(int x, int y, RecipeCapability<T> cap, Content content) {
        this(x, y, cap, content, null);
    }

    /**
     * Creates a 20x20 content preview widget for a recipe side.
     *
     * @param x       widget x coordinate
     * @param y       widget y coordinate
     * @param cap     capability used to convert and preview the content
     * @param content content wrapper to display
     * @param io      recipe side represented by this preview, or {@code null}
     *                for a direction-neutral preview
     */
    public ContentWidget(int x, int y, RecipeCapability<T> cap, Content content, @Nullable IO io) {
        super(x, y, 20, 20);
        this.cap = cap;
        this.content = content;
        this.io = io;
        this.lastContentTag = createContentTag();
        this.lastPreviewVersion = content.getPreviewVersion();
        var contentWidget = createPreviewWidget();
        contentWidget.setSelfPosition(1, 1);
        addWidget(contentWidget);
    }

    /**
     * Rebuilds the preview widget after the editor changes the content value.
     *
     * <p>This is intentionally unconditional. Some ingredient configurators
     * mutate a nested value in place, and their serialized representation can
     * be cached or omit display-only state. Recreating the small preview keeps
     * its texture synchronized with the editor's authoritative content.</p>
     */
    @OnlyIn(Dist.CLIENT)
    public void refreshContent() {
        lastContentTag = createContentTag();
        lastPreviewVersion = content.getPreviewVersion();
        clearAllWidgets();
        var contentWidget = createPreviewWidget();
        contentWidget.setSelfPosition(1, 1);
        addWidget(contentWidget);
    }

    /**
     * Refreshes the preview widget when the serialized content value changes.
     *
     * <p>Client-side only. Side effects: clears and recreates child widgets when
     * the capability serializer reports a different NBT value.</p>
     */
    @Override
    @OnlyIn(Dist.CLIENT)
    public void updateScreen() {
        super.updateScreen();
        var currentTag = createContentTag();
        if (content.getPreviewVersion() != lastPreviewVersion || !currentTag.equals(lastContentTag)) {
            refreshContent();
        }
    }

    /**
     * Serializes a detached content snapshot for polling-based refreshes.
     */
    private Tag createContentTag() {
        return cap.serializer.toNBT(cap.of(content.getContent())).copy();
    }

    /**
     * Creates the capability-specific child preview for the current content.
     */
    private Widget createPreviewWidget() {
        return cap.createPreviewWidget(cap.of(content.getContent()));
    }

    /**
     * Draws the capability preview and common content overlays.
     *
     * <p>Client-side only. Side effects: draws chance and per-tick overlays over
     * the 18x18 inner slot.</p>
     */
    @Override
    @OnlyIn(Dist.CLIENT)
    public void drawInBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        content.createOverlay(cap.supportsOutputRange(content.minOutput, content.maxOutput)
                        && cap.supportsOutputMultiplier(content.content, content.maxOutput))
                .draw(graphics, mouseX, mouseY, getPositionX() + 1, getPositionY() + 1, 18, 18);
    }

    /**
     * Appends metadata tooltips when the cursor is over the content slot.
     *
     * <p>Client-side only. Side effects: merges existing ModularUI tooltips with
     * content, slot-name, and UI-name tooltips.</p>
     */
    @Override
    @OnlyIn(Dist.CLIENT)
    public void drawInForeground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        if (isMouseOver(getPositionX() + 1, getPositionY() + 1, 18, 18, mouseX, mouseY)) {
            List<Component> tooltips = new ArrayList<>();
            if (gui.getModularUIGui().tooltipTexts != null) {
                tooltips.addAll(gui.getModularUIGui().tooltipTexts);
            }
            content.appendTooltip(tooltips, cap.supportsOutputRange(content.minOutput, content.maxOutput)
                    && cap.supportsOutputMultiplier(content.content, content.maxOutput), io);
            if (!tooltips.isEmpty()) {
                gui.getModularUIGui().tooltipTexts = tooltips;
            }
        }
    }
}
