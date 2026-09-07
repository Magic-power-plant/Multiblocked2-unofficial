package com.lowdragmc.mbd2.api.block;

import com.lowdragmc.lowdraglib.client.renderer.IBlockRendererProvider;
import com.lowdragmc.lowdraglib.client.renderer.IRenderer;
import com.lowdragmc.mbd2.api.blockentity.ProxyPartBlockEntity;
import com.lowdragmc.mbd2.api.machine.IMachine;
import com.lowdragmc.mbd2.client.renderer.ProxyPartRenderer;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Proxy block used to replace non-MBD blocks while a multiblock is formed.
 *
 * <p>The proxy stores the original block state/data in {@link ProxyPartBlockEntity}, delegates drops and breaking
 * speed to that original state, and restores it when the proxy is removed. This lets formed multiblocks hide or
 * custom-render structural blocks without permanently changing the world.</p>
 */
public class ProxyPartBlock extends Block implements EntityBlock, IBlockRendererProvider {
    public static final ProxyPartBlock BLOCK = new ProxyPartBlock();

    /**
     * Creates the singleton proxy block.
     */
    public ProxyPartBlock() {
        super(Properties.of().dynamicShape().noOcclusion());
    }

    /**
     * Uses a block entity renderer for proxy appearance.
     *
     * @param pState proxy state
     * @return animated entity-block render shape
     */
    @Override
    public RenderShape getRenderShape(BlockState pState) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    /**
     * Creates the proxy block entity that stores original block data.
     *
     * @param pos   block position
     * @param state proxy state
     * @return new proxy block entity
     */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProxyPartBlockEntity(pos, state);
    }

    /**
     * Returns drops from the captured original block state when available.
     *
     * @param state   proxy block state
     * @param builder loot context builder
     * @return original block drops or proxy fallback drops
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        // drop the original block's drops
        var context = builder.withParameter(LootContextParams.BLOCK_STATE, state).create(LootContextParamSets.BLOCK);
        if (context.getParamOrNull(LootContextParams.BLOCK_ENTITY) instanceof ProxyPartBlockEntity blockEntity &&
                blockEntity.getOriginalState() != null) {
            return blockEntity.getOriginalState().getDrops(builder);
        }
        return super.getDrops(state, builder);
    }

    /**
     * Restores the original block when the proxy is replaced by a different block.
     *
     * @param pState    current proxy state
     * @param pLevel    level containing the proxy
     * @param pPos      proxy position
     * @param pNewState replacement state
     * @param pIsMoving whether removal is caused by piston movement
     */
    @Override
    public void onRemove(BlockState pState, Level pLevel, BlockPos pPos, BlockState pNewState, boolean pIsMoving) {
        if (pState.hasBlockEntity()) {
            if (!pState.is(pNewState.getBlock())) { // new block
                ProxyPartBlockEntity blockEntity = pLevel.getBlockEntity(pPos) instanceof ProxyPartBlockEntity proxyPartBlockEntity ?
                        proxyPartBlockEntity : null;
                pLevel.updateNeighbourForOutputSignal(pPos, this);
                pLevel.removeBlockEntity(pPos);
                if (!pLevel.isClientSide && blockEntity != null && blockEntity.getOriginalState() != null &&
                        !pNewState.is(blockEntity.getOriginalState().getBlock())) {
                    blockEntity.restoreOriginalBlock();
                }
            }
        }
    }

    /**
     * Delegates block breaking speed to the captured original state.
     *
     * @param pState  proxy state
     * @param pPlayer player breaking the block
     * @param pLevel  level reader
     * @param pPos    proxy position
     * @return original destroy progress, or {@code 0} when no original state is available
     */
    @Override
    public float getDestroyProgress(BlockState pState, Player pPlayer, BlockGetter pLevel, BlockPos pPos) {
        if (pLevel.getBlockEntity(pPos) instanceof ProxyPartBlockEntity blockEntity && blockEntity.getOriginalState() != null) {
            return blockEntity.getOriginalState().getDestroyProgress(pPlayer, pLevel, pPos);
        }
        return 0;
    }

    /**
     * Keeps the proxy's interaction outline consistent with the captured block.
     * A proxy without captured data is non-selectable instead of becoming a full
     * cube that can obscure an oversized controller shape.
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (level.getBlockEntity(pos) instanceof ProxyPartBlockEntity blockEntity && blockEntity.getOriginalState() != null) {
            return blockEntity.getOriginalState().getShape(level, pos, context);
        }
        return Shapes.empty();
    }

    /**
     * Delegates physical collision to the captured block while the proxy is
     * active, preserving normal gameplay for hidden structural blocks.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (level.getBlockEntity(pos) instanceof ProxyPartBlockEntity blockEntity && blockEntity.getOriginalState() != null) {
            return blockEntity.getOriginalState().getCollisionShape(level, pos, context);
        }
        return Shapes.empty();
    }

    /**
     * Delegates interaction ray tracing to the original block state.
     */
    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ProxyPartBlockEntity blockEntity && blockEntity.getOriginalState() != null) {
            return blockEntity.getOriginalState().getInteractionShape(level, pos);
        }
        return Shapes.empty();
    }

    @Override
    public boolean isCollisionShapeFullBlock(BlockState state, BlockGetter level, BlockPos pos) {
        // The proxy's collision can still be solid for entity physics, but it
        // must never advertise a full occlusion cube to adjacent renderers.
        return false;
    }

    /**
     * Makes block inspection tools identify a proxy with the controller it
     * visually represents instead of the internal proxy block registration.
     */
    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        if (level.getBlockEntity(pos) instanceof ProxyPartBlockEntity blockEntity) {
            if (blockEntity.getControllerPos() != null) {
                var controllerStack = IMachine.ofMachine(level, blockEntity.getControllerPos())
                        .filter(MBDMachine.class::isInstance)
                        .map(MBDMachine.class::cast)
                        .map(machine -> machine.getDefinition().asStack())
                        .orElse(ItemStack.EMPTY);
                if (!controllerStack.isEmpty()) {
                    return controllerStack;
                }
            }
            if (blockEntity.getOriginalState() != null) {
                return blockEntity.getOriginalState().getBlock().getCloneItemStack(level, pos, blockEntity.getOriginalState());
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Replaces a world block with a proxy and captures the original block entity data.
     *
     * <p>Preconditions: call on the logical server while the multiblock is forming. The original block state and full
     * block entity metadata are stored on the new proxy block entity.</p>
     *
     * @param controllerPos controller position that owns the proxy
     * @param level         target level
     * @param pos           block position to proxy
     */
    public static void replaceOriginalBlock(BlockPos controllerPos, Level level, BlockPos pos) {
        var originalState = level.getBlockState(pos);
        var originalBlockEntity = level.getBlockEntity(pos);
        var originalData = Optional.ofNullable(originalBlockEntity).map(BlockEntity::saveWithFullMetadata).orElse(null);
        level.setBlockAndUpdate(pos, BLOCK.defaultBlockState());
        if (level.getBlockEntity(pos) instanceof ProxyPartBlockEntity blockEntity) {
            blockEntity.setOriginalData(originalState, originalData, controllerPos);
        }
    }

    /**
     * Returns the renderer used for proxy block appearance.
     *
     * @param state proxy state
     * @return shared proxy part renderer
     */
    @Nullable
    @Override
    public IRenderer getRenderer(BlockState state) {
        return ProxyPartRenderer.INSTANCE;
    }
}
