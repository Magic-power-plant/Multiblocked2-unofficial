package com.lowdragmc.mbd2.client;

import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.registry.MBDRegistries;
import com.lowdragmc.mbd2.common.CommonProxy;
import com.lowdragmc.mbd2.client.renderer.CustomRendererRegistry;
import com.lowdragmc.mbd2.integration.create.machine.KineticInstanceRenderer;
import net.createmod.catnip.render.SuperByteBufferCache;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

/**
 * Client-side bootstrap that extends common registration with renderer,
 * model, and item-property setup.
 *
 * <p>The business goal is to make project-defined machines render correctly on
 * the client while reusing the common registry loading path. Methods run on
 * Forge client/mod event threads and mutate client-only renderer/model
 * registries; they must not be called on a dedicated server.</p>
 */
@OnlyIn(Dist.CLIENT)
public class ClientProxy extends CommonProxy {
    /**
     * Registers common bootstrap state and initializes Create renderer caches
     * when Create integration is present.
     *
     * <p>Preconditions: called only on the client distribution during mod
     * construction. Side effects: all {@link CommonProxy#CommonProxy()} effects,
     * plus a Create super-buffer cache compartment when Create is loaded.</p>
     */
    public ClientProxy() {
        super();
        if (MBD2.isCreateLoaded()) {
            SuperByteBufferCache.getInstance().registerCompartment(KineticInstanceRenderer.DIRECTIONAL_PARTIAL);
        }
    }

    /**
     * Registers entity/block renderers for the fake machine and all loaded
     * machine definitions.
     *
     * <p>Preconditions: machine definitions must be loaded before Forge fires
     * the renderer registration event. Side effects: delegates renderer
     * registration to each definition.</p>
     *
     * @param e Forge client renderer registration event
     */
    @SubscribeEvent
    public void registerRenderers(RegisterRenderers e) {
        MBDRegistries.FAKE_MACHINE().initRenderer(e);
        MBDRegistries.MACHINE_DEFINITIONS.forEach(definition -> definition.initRenderer(e));
    }

    /**
     * Registers client item model property hooks.
     *
     * <p>Business goal: make the gadget item model reflect its current mode via
     * the {@code mbd2:mode} property. Side effects: enqueues client work that
     * registers an item property reader.</p>
     *
     * @param e Forge client setup event
     */
    @SubscribeEvent
    public void clientSetup(final FMLClientSetupEvent e) {
        e.enqueueWork(() -> ItemProperties.register(MBDRegistries.GADGETS_ITEM(), MBD2.id("mode"),
                (itemStack, clientWorld, entity, seed) -> itemStack.getDamageValue()));
    }

    /** Registers custom callbacks after all client scripts have loaded, including when KubeJS is absent. */
    @SubscribeEvent
    public void registerCustomRenderers(FMLLoadCompleteEvent event) {
        event.enqueueWork(CustomRendererRegistry::reload);
    }

    /**
     * Registers workspace and project-referenced model resources with Forge's
     * model loader.
     *
     * <p>Preconditions: called during the additional model registration stage.
     * Side effects: scans workspace/project model references and accepts them
     * into the model event.</p>
     *
     * @param event additional model registration event
     */
    @SubscribeEvent
    public void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        WorkspaceResourceHelper.findWorkspaceModelFiles().forEach(event::register);
        WorkspaceResourceHelper.findProjectReferencedModels().forEach(event::register);
    }
}
