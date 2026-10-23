package com.lowdragmc.mbd2.client.renderer;

import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.common.event.CustomRendererEvent;
import com.lowdragmc.mbd2.integration.kubejs.events.CustomRendererEventJS;
import com.lowdragmc.mbd2.integration.kubejs.events.MBDClientEvents;
import dev.latvian.mods.kubejs.script.ScriptType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;

import java.util.Objects;
import java.util.function.Consumer;

/** Owns client renderer registration, with defaults followed by Java and then script overrides. */
@OnlyIn(Dist.CLIENT)
public final class CustomRendererRegistry {
    private CustomRendererRegistry() {
    }

    /** Called after mod loading, when client scripts and Forge listeners are available. */
    public static void reload() {
        reload(event -> MinecraftForge.EVENT_BUS.post(event), event -> {
            if (MBD2.isKubeJSLoaded()) {
                KubeJSWrapper.post(event);
            }
        });
    }

    static void reload(Consumer<CustomRendererEvent> javaRegistrations, Consumer<CustomRendererEvent> scriptRegistrations) {
        KubeJSRenderer.renderFunctions.clear();
        register(ResourceLocation.fromNamespaceAndPath("mbd2", "beacon"), (be, partialTick, stack, buffer, light, overlay, data) -> {
            if (be == null || be.getLevel() == null) {
                return;
            }
            var texture = data.contains("texture") ? ResourceLocation.tryParse(data.getString("texture")) : null;
            int color = data.contains("color") ? data.getInt("color") : -1;
            BeaconRenderer.renderBeaconBeam(stack, buffer,
                    texture == null ? BeaconRenderer.BEAM_LOCATION : texture,
                    partialTick, 1, be.getLevel().getGameTime(),
                    data.contains("y_offset") ? data.getInt("y_offset") : 0,
                    data.contains("height") ? data.getInt("height") : 1000,
                    new float[]{((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f},
                    floatValue(data, "radius", 0.2f), floatValue(data, "glow_radius", 0.25f));
        });
        var event = new CustomRendererEvent();
        javaRegistrations.accept(event);
        scriptRegistrations.accept(event);
    }

    public static void register(ResourceLocation name, KubeJSRenderer.RenderSupplier renderer) {
        KubeJSRenderer.renderFunctions.put(Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(renderer, "renderer"));
    }

    private static float floatValue(CompoundTag data, String key, float fallback) {
        return data.contains(key) ? data.getFloat(key) : fallback;
    }

    /** Loaded only when the optional KubeJS integration is present. */
    private static final class KubeJSWrapper {
        private static void post(CustomRendererEvent event) {
            MBDClientEvents.CUSTOM_RENDER.post(ScriptType.CLIENT, new CustomRendererEventJS(event));
        }
    }
}
