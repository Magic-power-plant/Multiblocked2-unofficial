package com.lowdragmc.mbd2.client.renderer;

import com.lowdragmc.lowdraglib.client.model.forge.LDLRendererModel;
import com.lowdragmc.lowdraglib.client.renderer.IRenderer;
import com.lowdragmc.mbd2.api.machine.IMachine;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.machine.MBDMultiblockMachine;
import com.lowdragmc.mbd2.common.machine.MBDPartMachine;
import com.mojang.blaze3d.vertex.PoseStack;
import lombok.AllArgsConstructor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.lowdragmc.lowdraglib.client.model.forge.LDLRendererModel.RendererBakedModel.*;

/**
 * Client renderer bridge for block-backed MBD machines.
 *
 * <p>The business goal is to let a registered machine block render with the runtime machine renderer when a machine is
 * present, while still falling back to a default renderer for item/model baking contexts without a machine holder.
 * Dynamic block-entity rendering also includes machine trait renderers and proxy trait renderers exposed through part
 * machines. Rendering is client-only and reads machine state without mutating gameplay state.</p>
 */
@OnlyIn(Dist.CLIENT)
@AllArgsConstructor
public class MBDBlockRenderer implements IRenderer {

    protected final BooleanSupplier useAO;
    protected final Supplier<IRenderer> defaultRenderer;

    /**
     * Returns the current ambient-occlusion policy.
     *
     * @return value supplied by {@code useAO}
     */
    @Override
    public boolean useAO() {
        return useAO.getAsBoolean();
    }

    /**
     * Resolves a machine from world and block position.
     *
     * @param level block/tint getter from the render context; may be {@code null} during item/model baking
     * @param pos   block position; may be {@code null} during item/model baking
     * @return attached MBD machine when available
     */
    public Optional<MBDMachine> getMachine(@Nullable BlockAndTintGetter level, @Nullable BlockPos pos) {
        if (level == null || pos == null)
            return Optional.empty();
        return IMachine.ofMachine(level, pos).filter(MBDMachine.class::isInstance).map(MBDMachine.class::cast);
    }

    /**
     * Resolves a machine from a block entity.
     *
     * @param blockEntity block entity from a dynamic render query
     * @return attached MBD machine when available
     */
    public Optional<MBDMachine> getMachine(@Nullable BlockEntity blockEntity) {
        if (blockEntity == null)
            return Optional.empty();
        return IMachine.ofMachine(blockEntity).filter(MBDMachine.class::isInstance).map(MBDMachine.class::cast);
    }

    /**
     * Item rendering is intentionally not handled by the block renderer wrapper.
     *
     * @param stack           stack being rendered
     * @param transformType   vanilla display transform
     * @param leftHand        whether the render is for the left hand
     * @param poseStack       pose stack for transforms
     * @param buffer          render buffer source
     * @param combinedLight   packed light value
     * @param combinedOverlay packed overlay value
     * @param model           baked model being rendered
     * @throws UnsupportedOperationException always; item rendering should use {@link MBDItemRenderer}
     */
    @Override
    public void renderItem(ItemStack stack, ItemDisplayContext transformType, boolean leftHand, PoseStack poseStack, MultiBufferSource buffer, int combinedLight, int combinedOverlay, BakedModel model) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Renders baked model quads for the machine at a block position.
     *
     * <p>If a machine is found and rendering is not disabled, the machine's current real renderer is used with the
     * machine's front-facing direction. A disabled machine without a dynamic renderer override returns no quads. When no
     * machine is present, the configured default renderer handles the request.</p>
     *
     * @param level optional render world
     * @param pos   optional block position
     * @param state optional block state
     * @param side  side being rendered, or {@code null} for general quads
     * @param rand  random source for model baking
     * @return baked quads for the current render context
     */
    @Override
    public List<BakedQuad> renderModel(@Nullable BlockAndTintGetter level, @Nullable BlockPos pos, @Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return getMachine(level, pos)
                .map(machine -> {
                    if (machine.isDisableRendering() && !machine.hasDynamicRendererOverride()) {
                        FusionModelDataHelper.debugOnce("mbd-block-renderer-disabled-" + machine.getDefinition(),
                                "MBDBlockRenderer skipped disabled machine={}, side={}, pos={}",
                                machine.getDefinition(), side, pos);
                        return Collections.<BakedQuad>emptyList();
                    }
                    var renderer = machine.getRealRenderer(machine.getFrontFacing().orElse(Direction.NORTH));
                    FusionModelDataHelper.debugOnce("mbd-block-renderer-" + machine.getDefinition() + "-" + renderer.getClass().getName(),
                            "MBDBlockRenderer rendering machine={}, renderer={}, side={}, pos={}",
                            machine.getDefinition(), renderer.getClass().getName(), side, pos);
                    return renderer.renderModel(level, pos, state, side, rand);
                })
                .orElseGet(() -> defaultRenderer.get().renderModel(level, pos, state, side, rand));
    }

    /**
     * Returns the particle texture for the machine currently being baked.
     *
     * <p>LowDragLib stores model bake context in thread-local model data; when that context points at an MBD machine,
     * this method uses the machine renderer's particle texture. Otherwise it falls back to the default renderer.</p>
     *
     * @return particle sprite
     */
    @NotNull
    @Override
    public TextureAtlasSprite getParticleTexture() {
        var modelData = LDLRendererModel.RendererBakedModel.CURRENT_MODEL_DATA.get();
        if (modelData != null) {
            var world = modelData.get(WORLD);
            var pos = modelData.get(POS);
            return getMachine(world, pos)
                    .map(machine -> machine.getRealRenderer().getParticleTexture())
                    .orElseGet(() -> defaultRenderer.get().getParticleTexture());
        }
        return defaultRenderer.get().getParticleTexture();
    }

    /**
     * Checks whether the machine or any of its trait renderers needs dynamic block-entity rendering.
     *
     * @param blockEntity block entity being queried
     * @return {@code true} when the machine renderer, definition trait renderers, or proxied part trait renderers need
     * TESR-style rendering
     */
    @Override
    public boolean hasTESR(BlockEntity blockEntity) {
        return getMachine(blockEntity).map(machine ->
                machine.getRealRenderer().hasTESR(blockEntity) ||
                        machine.getDefinition().machineSettings().traitDefinitions().stream()
                                .map(definition -> definition.getBESRenderer(machine))
                                .filter(Objects::nonNull)
                                .anyMatch(renderer -> renderer.hasTESR(blockEntity)) ||
                        getProxiedTraitRenderers(machine).stream()
                                .anyMatch(renderer -> renderer.hasTESR(blockEntity))
        ).orElseGet(() -> defaultRenderer.get().hasTESR(blockEntity));
    }

    /**
     * Checks whether the machine should render globally outside normal frustum bounds.
     *
     * @param blockEntity block entity being queried
     * @return machine state/renderer/global trait policy, or default renderer policy when no machine is attached
     */
    @Override
    public boolean isGlobalRenderer(BlockEntity blockEntity) {
        return getMachine(blockEntity).map(machine ->
                machine.getMachineState().isGlobalVisible() ||
                        machine.getRealRenderer().isGlobalRenderer(blockEntity) ||
                        machine.getDefinition().machineSettings().traitDefinitions().stream()
                                .map(definition -> definition.getBESRenderer(machine))
                                .filter(Objects::nonNull)
                                .anyMatch(renderer -> renderer.isGlobalRenderer(blockEntity)) ||
                        getProxiedTraitRenderers(machine).stream()
                                .anyMatch(renderer -> renderer.isGlobalRenderer(blockEntity))
        ).orElseGet(() -> defaultRenderer.get().isGlobalRenderer(blockEntity));
    }

    /**
     * Applies machine-state render distance and disabled-rendering checks.
     *
     * @param blockEntity block entity being queried
     * @param cameraPos   camera position
     * @return {@code true} when the machine should be rendered from the current camera position
     */
    @Override
    public boolean shouldRender(BlockEntity blockEntity, Vec3 cameraPos) {
        return getMachine(blockEntity)
                .map(machine -> !machine.isDisableRendering() && Vec3.atCenterOf(blockEntity.getBlockPos()).closerThan(cameraPos,
                        KubeJSRenderer.limitRenderDistance(machine.getRealRenderer(), machine.getMachineState().renderingRadius())))
                .orElseGet(() -> defaultRenderer.get().shouldRender(blockEntity, cameraPos));
    }

    /**
     * Renders dynamic machine and trait content.
     *
     * <p>Rendering order is dynamic baked model override, the machine's real renderer, definition trait renderers, and
     * proxy trait renderers for part machines. If no MBD machine is attached, the default renderer handles the block
     * entity.</p>
     *
     * @param blockEntity     block entity being rendered
     * @param partialTicks    partial tick interpolation value
     * @param stack           pose stack
     * @param buffer          render buffer source
     * @param combinedLight   packed light value
     * @param combinedOverlay packed overlay value
     */
    @Override
    public void render(BlockEntity blockEntity, float partialTicks, PoseStack stack, MultiBufferSource buffer, int combinedLight, int combinedOverlay) {
        getMachine(blockEntity).ifPresentOrElse(machine -> {
            if (machine.hasDynamicRendererOverride()) {
                renderDynamicModel(machine, blockEntity, stack, buffer, combinedLight, combinedOverlay);
            }
            machine.getRealRenderer().render(blockEntity, partialTicks, stack, buffer, combinedLight, combinedOverlay);
            for (var traitDefinition : machine.getDefinition().machineSettings().traitDefinitions()) {
                var renderer = traitDefinition.getBESRenderer(machine);
                if (renderer != null && renderer.hasTESR(blockEntity)) {
                    renderer.render(blockEntity, partialTicks, stack, buffer, combinedLight, combinedOverlay);
                }
            }
            for (var renderer : getProxiedTraitRenderers(machine)) {
                if (renderer.hasTESR(blockEntity)) {
                    renderer.render(blockEntity, partialTicks, stack, buffer, combinedLight, combinedOverlay);
                }
            }
        }, () -> defaultRenderer.get().render(blockEntity, partialTicks, stack, buffer, combinedLight, combinedOverlay));
    }

    /**
     * Collects trait renderers from controller traits proxied through a part machine.
     *
     * @param machine candidate machine
     * @return renderers that should render on the part while representing proxied controller capabilities
     */
    private List<IRenderer> getProxiedTraitRenderers(MBDMachine machine) {
        if (!(machine instanceof MBDPartMachine partMachine) || partMachine.getDefinition().partSettings() == null) {
            return Collections.emptyList();
        }
        var renderers = new java.util.ArrayList<IRenderer>();
        for (var controller : partMachine.getControllers()) {
            if (controller instanceof MBDMultiblockMachine proxyController) {
                for (var proxyControllerCapability : partMachine.getDefinition().partSettings().proxyControllerCapabilities()) {
                    for (var traitDefinition : proxyController.getDefinition().machineSettings().traitDefinitions()) {
                        if (proxyControllerCapability.matchesTraitName(traitDefinition.getName())) {
                            var renderer = traitDefinition.getBESRenderer(partMachine);
                            if (renderer != null && renderer != IRenderer.EMPTY) {
                                renderers.add(renderer);
                            }
                        }
                    }
                }
            }
        }
        return renderers;
    }

    /**
     * Emits dynamic baked model quads during block-entity rendering.
     *
     * <p>This path is used when a machine has a dynamic renderer override, so model quads are drawn manually into the
     * cutout buffer in addition to normal block-entity renderer hooks.</p>
     *
     * @param machine         machine being rendered
     * @param blockEntity     backing block entity
     * @param stack           pose stack
     * @param buffer          render buffer source
     * @param combinedLight   packed light value
     * @param combinedOverlay packed overlay value
     */
    private void renderDynamicModel(MBDMachine machine, BlockEntity blockEntity, PoseStack stack, MultiBufferSource buffer, int combinedLight, int combinedOverlay) {
        var level = blockEntity.getLevel();
        if (level == null) {
            return;
        }
        var pos = blockEntity.getBlockPos();
        var state = blockEntity.getBlockState();
        var renderer = machine.getRealRenderer(machine.getFrontFacing().orElse(Direction.NORTH));
        FusionModelDataHelper.debugOnce("mbd-block-ber-" + machine.getDefinition() + "-" + renderer.getClass().getName(),
                "MBDBlockRenderer BE rendering dynamic machine={}, renderer={}, pos={}",
                machine.getDefinition(), renderer.getClass().getName(), pos);
        var random = RandomSource.create(machine.getOffset());
        var consumer = buffer.getBuffer(RenderType.cutout());
        var pose = stack.last();
        for (var side : Direction.values()) {
            for (var quad : renderer.renderModel(level, pos, state, side, random)) {
                consumer.putBulkData(pose, quad, 1, 1, 1, combinedLight, combinedOverlay);
            }
        }
        for (var quad : renderer.renderModel(level, pos, state, null, random)) {
            consumer.putBulkData(pose, quad, 1, 1, 1, combinedLight, combinedOverlay);
        }
    }
}
