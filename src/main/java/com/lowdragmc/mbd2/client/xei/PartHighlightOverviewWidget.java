package com.lowdragmc.mbd2.client.xei;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.mbd2.api.pattern.preview.PartHighlightIndex;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** A non-slot viewport: overview items must never become recipe-viewer ingredients. */
public final class PartHighlightOverviewWidget extends Widget {
    private static final int ROW = 18, VISIBLE = 5;
    private final PatternPartHighlightSession session;
    private final net.minecraft.resources.ResourceLocation controllerId;
    private int first, hovered = -1;

    public PartHighlightOverviewWidget(PatternPartHighlightSession session, net.minecraft.resources.ResourceLocation controllerId) {
        super(MultiblockPreviewLayoutMetrics.PART_X, MultiblockPreviewLayoutMetrics.PART_Y,
                MultiblockPreviewLayoutMetrics.PART_WIDTH, MultiblockPreviewLayoutMetrics.PART_HEIGHT);
        this.session = session;
        this.controllerId = controllerId;
        setBackground(ColorPattern.T_GRAY.rectTexture());
    }

    public void reset() { first = 0; hovered = -1; session.clearHover(); }

    private boolean inViewport(double mouseX, double mouseY) {
        if (!isVisible() || !isActive() || !isMouseOverElement(mouseX, mouseY)) return false;
        for (Widget ancestor = getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (!ancestor.isVisible()) return false;
            if (ancestor instanceof com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup
                    && !ancestor.isMouseOverElement(mouseX, mouseY)) return false;
        }
        return true;
    }

    public void updateHover(int mouseX, int mouseY) {
        int next = -1;
        if (inViewport(mouseX, mouseY) && mouseX < getPosition().x + ROW) {
            int row = (mouseY - getPosition().y) / ROW;
            int candidate = first + row;
            if (row >= 0 && row < VISIBLE && candidate < session.index().entries().size()) next = candidate;
        }
        hovered = next;
        if (next < 0) session.clearHover();
        else session.updateHover(session.index().entries().get(next).key());
    }

    @Override
    public void drawInBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        int x = getPosition().x, y = getPosition().y;
        var entries = session.index().entries();
        if (entries.isEmpty()) {
            graphics.renderItem(new ItemStack(session.index().status() == PartHighlightIndex.Status.UNAVAILABLE
                    ? Items.BARRIER : Items.GRAY_DYE), x + 1, y + 1);
        }
        for (int row = 0; row < VISIBLE && first + row < entries.size(); row++) {
            if (first + row == hovered) graphics.fill(x, y + row * ROW, x + ROW, y + (row + 1) * ROW, 0x70FFFFFF);
            graphics.renderItem(entries.get(first + row).displayStack(), x + 1, y + row * ROW + 1);
        }
        if (entries.size() > VISIBLE) {
            int thumb = Math.max(8, getSizeHeight() * VISIBLE / entries.size());
            int travel = getSizeHeight() - thumb;
            int maxFirst = entries.size() - VISIBLE;
            graphics.fill(x + ROW + 1, y + first * travel / maxFirst, x + ROW + 3,
                    y + first * travel / maxFirst + thumb, 0xCCFFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public void drawInForeground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (gui == null || gui.getModularUIGui() == null || !inViewport(mouseX, mouseY)) return;
        if (hovered >= session.index().entries().size()) reset();
        if (hovered < 0) {
            var key = session.index().status() == PartHighlightIndex.Status.UNAVAILABLE
                    ? "pattern_preview.parts_unavailable" : "pattern_preview.parts_empty";
            if (session.index().entries().isEmpty())
                gui.getModularUIGui().setHoverTooltip(List.of(Component.translatable(key)), net.minecraft.world.item.ItemStack.EMPTY, null, null);
            return;
        }
        var entry = session.index().entries().get(hovered);
        var stack = entry.displayStack();
        int visible = session.highlightedPositions().size();
        var lines = PartHighlightTooltipComposer.compose(entry, visible,
                new com.lowdragmc.mbd2.api.pattern.preview.PartTooltipProvider.Context(controllerId, session.source()));
        gui.getModularUIGui().setHoverTooltip(lines, stack, null, stack.getTooltipImage().orElse(null));
    }

    @Override
    public boolean mouseWheelMove(double mouseX, double mouseY, double wheelDelta) {
        if (!inViewport(mouseX, mouseY) || session.index().entries().size() <= VISIBLE || wheelDelta == 0) return false;
        first = Math.max(0, Math.min(Math.max(0, session.index().entries().size() - VISIBLE), first - (int) Math.signum(wheelDelta)));
        updateHover((int) mouseX, (int) mouseY);
        return true;
    }
}
