package com.lowdragmc.mbd2.client.renderer;

import com.lowdragmc.lowdraglib.client.renderer.ISerializableRenderer;
import com.lowdragmc.lowdraglib.client.renderer.IRenderer;
import com.lowdragmc.lowdraglib.client.renderer.impl.UIResourceRenderer;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;

/** Serializable block entity renderer whose callback can be supplied by Java or client scripts. */
@LDLRegisterClient(name = "kubejs", group = "renderer")
@Getter
@Setter
@OnlyIn(Dist.CLIENT)
public class KubeJSRenderer implements ISerializableRenderer {
    /** Kept public for compatibility with upstream renderer integrations. Mutate on the client thread. */
    public static final HashMap<ResourceLocation, RenderSupplier> renderFunctions = new HashMap<>();

    @Configurable(name = "custom_renderer.renderer_name", tips = "custom_renderer.renderer_name.tooltip")
    public ResourceLocation rendererName = ResourceLocation.fromNamespaceAndPath("mbd2", "beacon");

    @Configurable(name = "custom_renderer.data", tips = "custom_renderer.data.tooltip")
    public CompoundTag data = new CompoundTag();

    @Configurable(name = "custom_renderer.render_off_screen", tips = "custom_renderer.render_off_screen.tooltip")
    public boolean renderOffScreen = false;

    @Configurable(name = "custom_renderer.max_render_distance")
    @NumberRange(range = {1, Integer.MAX_VALUE})
    public int maxRenderDistance = 64;

    public KubeJSRenderer() {
        data.putInt("color", -1);
    }

    @Override
    public boolean hasTESR(BlockEntity blockEntity) {
        return true;
    }

    @Override
    public void render(BlockEntity blockEntity, float partialTick, PoseStack stack, MultiBufferSource buffer,
                       int combinedLight, int combinedOverlay) {
        var renderer = renderFunctions.get(rendererName);
        if (renderer == null) {
            return;
        }
        stack.pushPose();
        try {
            renderer.render(blockEntity, partialTick, stack, buffer, combinedLight, combinedOverlay,
                    data == null ? new CompoundTag() : data);
        } finally {
            stack.popPose();
        }
    }

    @Override
    public boolean isGlobalRenderer(BlockEntity blockEntity) {
        return renderOffScreen;
    }

    @Override
    public int getViewDistance() {
        return Math.max(1, maxRenderDistance);
    }

    /** Applies a custom callback's distance limit inside the machine state's overall render radius. */
    static int limitRenderDistance(IRenderer renderer, int machineRadius) {
        // Resource wrappers are user-authored; bound traversal in case resources reference each other.
        for (int depth = 0; depth < 16; depth++) {
            if (renderer instanceof KubeJSRenderer custom) {
                return Math.min(machineRadius, custom.getViewDistance());
            }
            if (renderer instanceof UIResourceRenderer resource) {
                renderer = resource.getRenderer();
            } else if (renderer instanceof MachineStateRenderer state) {
                renderer = state.blockRenderer();
            } else {
                break;
            }
        }
        return machineRadius;
    }

    @FunctionalInterface
    public interface RenderSupplier {
        void render(BlockEntity blockEntity, float partialTicks, PoseStack stack, MultiBufferSource buffer,
                    int combinedLight, int combinedOverlay, CompoundTag data);
    }
}
