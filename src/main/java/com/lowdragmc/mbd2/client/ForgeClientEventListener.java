package com.lowdragmc.mbd2.client;

import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.client.renderer.MultiblockInWorldPreviewRenderer;
import com.lowdragmc.mbd2.api.registry.MultiblockXEIRegistry;
import com.lowdragmc.mbd2.api.pattern.PatternPreviewWidget;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Forge-bus client event hooks for commands, preview rendering, and preview
 * lifetime ticking.
 *
 * <p>The business goal is to expose client-only tools and render the in-world
 * multiblock preview at a stage where it remains visible through transparent
 * blocks. Handlers run on the logical client/render thread and mutate only
 * client-side command or preview state.</p>
 */
@Mod.EventBusSubscriber(modid = MBD2.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
@OnlyIn(Dist.CLIENT)
public class ForgeClientEventListener {

    @SubscribeEvent
    public static void onScreenClosing(net.minecraftforge.client.event.ScreenEvent.Closing event) {
        PatternPreviewWidget.invalidatePreviewSessions();
    }

    /**
     * Drops server-provided XEI pages when leaving a world or server.
     *
     * @param event client network logout event
     */
    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MultiblockXEIRegistry.clearDataPackEntries();
        PatternPreviewWidget.clearPreviewWorld();
    }
    /**
     * Registers client-only MBD commands.
     *
     * <p>Preconditions: called by Forge while client command dispatchers are
     * being built. Side effects: adds every command returned by
     * {@link ClientCommands#createClientCommands()} to the active dispatcher.</p>
     *
     * @param event client command registration event
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        List<LiteralArgumentBuilder<CommandSourceStack>> commands = ClientCommands.createClientCommands();
        commands.forEach(dispatcher::register);
    }

    /**
     * Renders active multiblock previews after block entities have rendered.
     *
     * <p>Preconditions: called during level rendering. Only
     * {@link RenderLevelStageEvent.Stage#AFTER_BLOCK_ENTITIES} is handled. Side
     * effects: draws preview geometry through
     * {@link MultiblockInWorldPreviewRenderer}; it does not change world state.</p>
     *
     * @param event render stage event containing pose stack, camera, and partial tick
     */
    @SubscribeEvent
    public static void onRenderLevelStageEvent(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            // to render the preview after block entities, before the translucent. so it can be seen through the
            // transparent blocks.
            MultiblockInWorldPreviewRenderer.renderInWorldPreview(event.getPoseStack(), event.getCamera(), event.getPartialTick());
        }
    }

    /**
     * Advances client preview state once per client tick event.
     *
     * <p>Side effects: delegates countdown/update work to
     * {@link MultiblockInWorldPreviewRenderer#onClientTick()}.</p>
     *
     * @param event client tick event supplied by Forge
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        MultiblockInWorldPreviewRenderer.onClientTick();
    }
}
