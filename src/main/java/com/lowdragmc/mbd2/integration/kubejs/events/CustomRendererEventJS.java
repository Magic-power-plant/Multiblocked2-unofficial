package com.lowdragmc.mbd2.integration.kubejs.events;

import com.lowdragmc.mbd2.client.renderer.KubeJSRenderer;
import com.lowdragmc.mbd2.common.event.CustomRendererEvent;
import dev.latvian.mods.kubejs.event.EventJS;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Objects;

/** Registration payload for MBDMachineEvents.onCustomRendererFrame in client_scripts. */
@OnlyIn(Dist.CLIENT)
public class CustomRendererEventJS extends EventJS {
    private final CustomRendererEvent event;

    public CustomRendererEventJS() {
        this(new CustomRendererEvent());
    }

    public CustomRendererEventJS(CustomRendererEvent event) {
        this.event = Objects.requireNonNull(event, "event");
    }

    public void addRenderer(ResourceLocation name, KubeJSRenderer.RenderSupplier renderer) {
        event.addRenderer(name, renderer);
    }
}
