package com.lowdragmc.mbd2.client.xei;

import com.lowdragmc.lowdraglib.client.utils.RenderBufferUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** One depth-tested batch; restores every GL state changed by the overlay. */
public final class PartHighlightOverlayRenderer {
    private PartHighlightOverlayRenderer() {}

    public static void render(java.util.Set<net.minecraft.core.BlockPos> positions) {
        if (positions.isEmpty()) return;
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        var shader = RenderSystem.getShader();
        try {
            RenderSystem.enableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            var tessellator = Tesselator.getInstance();
            var buffer = tessellator.getBuilder();
            var pose = new PoseStack();
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            for (var pos : positions) {
                RenderBufferUtils.drawCubeFace(pose, buffer,
                        pos.getX() - 0.001f, pos.getY() - 0.001f, pos.getZ() - 0.001f,
                        pos.getX() + 1.001f, pos.getY() + 1.001f, pos.getZ() + 1.001f,
                        0.1f, 0.8f, 0.25f, 0.42f, false);
            }
            tessellator.end();
        } finally {
            if (!blend) RenderSystem.disableBlend();
            if (!depth) RenderSystem.disableDepthTest();
            if (!cull) RenderSystem.disableCull();
            RenderSystem.blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            RenderSystem.depthMask(depthMask);
            RenderSystem.setShader(() -> shader);
        }
    }

}
