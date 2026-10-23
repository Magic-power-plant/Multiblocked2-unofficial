package com.lowdragmc.mbd2.common.event;

import com.lowdragmc.mbd2.client.renderer.CustomRendererRegistry;
import com.lowdragmc.mbd2.client.renderer.KubeJSRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.Event;

/**
 * Client-only registration event on the Forge event bus, posted after mod loading.
 * Constructing an event never resets existing registrations. Scripts may override Java registrations.
 */
@OnlyIn(Dist.CLIENT)
public class CustomRendererEvent extends Event {
    public void addRenderer(ResourceLocation name, KubeJSRenderer.RenderSupplier renderer) {
        CustomRendererRegistry.register(name, renderer);
    }
}
