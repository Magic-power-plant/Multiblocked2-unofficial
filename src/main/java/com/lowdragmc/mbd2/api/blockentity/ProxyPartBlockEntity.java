package com.lowdragmc.mbd2.api.blockentity;

import com.lowdragmc.mbd2.api.machine.IMachine;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;

/**
 * Block entity for a proxy block that temporarily stands in for a non-MBD multiblock part.
 *
 * <p>The business goal is to hide or custom-render original blocks while a multiblock is formed, without losing the
 * original block state or block entity NBT. When the structure invalidates, {@link #restoreOriginalBlock()} writes the
 * captured block and data back into the world. World mutations are server-side operations; client sync only mirrors
 * the captured state for rendering.</p>
 */
public class ProxyPartBlockEntity extends BlockEntity implements Nameable {
    @Getter
    @Setter
    private boolean isAsyncSyncing = false;

    public static RegistryObject<BlockEntityType<ProxyPartBlockEntity>> TYPE;

    /**
     * Returns the registered block entity type for proxy parts.
     *
     * @return proxy part block entity type
     */
    public static BlockEntityType<?> TYPE() {
        return TYPE.get();
    }

    @Nullable
    @Getter
    private BlockState originalState;
    @Nullable
    @Getter
    private CompoundTag originalData;
    @Nullable
    @Getter
    private BlockPos controllerPos;

    /**
     * Creates a proxy block entity at a world position.
     *
     * @param pPos        block position
     * @param pBlockState proxy block state
     */
    public ProxyPartBlockEntity(BlockPos pPos, BlockState pBlockState) {
        super(TYPE(), pPos, pBlockState);
    }

    /**
     * Uses the owning controller name for proxy blocks so rendered multiblock
     * parts do not expose the internal proxy registry key.
     */
    @Override
    public Component getName() {
        if (level != null && controllerPos != null) {
            var controller = level.getBlockEntity(controllerPos);
            if (controller instanceof Nameable nameable) {
                return nameable.getDisplayName();
            }
        }
        if (originalState != null) {
            return originalState.getBlock().getName();
        }
        return getBlockState().getBlock().getName();
    }

    /**
     * Mirrors the controller's render footprint at this proxy position. This
     * is required because the proxy renderer draws the controller model from
     * each part block entity, while vanilla otherwise culls it to one block.
     */
    @Override
    public AABB getRenderBoundingBox() {
        if (level != null && controllerPos != null) {
            var controllerBox = IMachine.ofMachine(level, controllerPos)
                    .filter(MBDMachine.class::isInstance)
                    .map(MBDMachine.class::cast)
                    .map(MBDMachine::getRenderBoundingBox)
                    .orElse(null);
            if (controllerBox != null) {
                return controllerBox.move(
                        getBlockPos().getX() - controllerPos.getX(),
                        getBlockPos().getY() - controllerPos.getY(),
                        getBlockPos().getZ() - controllerPos.getZ());
            }
        }
        if (level != null && originalState != null) {
            var shape = originalState.getShape(level, getBlockPos(), CollisionContext.empty());
            if (!shape.isEmpty()) {
                return shape.bounds().move(getBlockPos());
            }
        }
        return super.getRenderBoundingBox();
    }

    /**
     * Updates only the controller reference and syncs clients when changed.
     *
     * @param controllerPos controller that owns this proxy block
     */
    public void setControllerData(BlockPos controllerPos) {
        if (this.controllerPos != controllerPos) {
            this.controllerPos = controllerPos;
            sync();
        }
    }

    /**
     * Captures the original block state, optional block entity NBT, and owning controller.
     *
     * <p>Side effects: updates this proxy's persisted fields and sends a sync packet when any reference changes.</p>
     *
     * @param originalState block state to restore later
     * @param originalData  serialized block entity data to restore later, or {@code null}
     * @param controllerPos owning controller position
     */
    public void setOriginalData(BlockState originalState, CompoundTag originalData, BlockPos controllerPos) {
        if (this.originalState != originalState || this.originalData != originalData || this.controllerPos != controllerPos) {
            this.originalState = originalState;
            this.originalData = originalData;
            this.controllerPos = controllerPos;
            sync();
        }
    }

    /**
     * Places the captured original block back into the world and restores its block entity data.
     *
     * <p>Preconditions: call on the server side while {@link #level} is non-null. If no original state was captured,
     * this method does nothing.</p>
     */
    public void restoreOriginalBlock() {
        if (originalState != null) {
            level.setBlockAndUpdate(getBlockPos(), originalState);
            if (originalData != null) {
                var blockEntity = level.getBlockEntity(worldPosition);
                if (blockEntity != null) {
                    blockEntity.load(originalData);
                }
            }
        }
    }

    /**
     * Saves captured original block data into chunk NBT.
     *
     * @param tag destination tag
     */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (originalState != null) {
            tag.put("originalState", NbtUtils.writeBlockState(originalState));
        }

        if (originalData != null) {
            tag.put("originalData", originalData);
        }

        if (controllerPos != null) {
            tag.put("controllerPos", NbtUtils.writeBlockPos(controllerPos));
        }
    }

    /**
     * Loads captured original block data from chunk NBT.
     *
     * @param tag source tag
     */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        if (tag.contains("originalState")) {
            originalState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("originalState"));
        }

        if (tag.contains("originalData")) {
            originalData = tag.getCompound("originalData");
        }

        if (tag.contains("controllerPos")) {
            controllerPos = NbtUtils.readBlockPos(tag.getCompound("controllerPos"));
        }

    }

    /**
     * Builds the client update tag for proxy rendering.
     *
     * @return tag containing any captured original state/data and controller position
     */
    @Override
    public CompoundTag getUpdateTag() {
        var tag = new CompoundTag();

        if (originalState != null) {
            tag.put("originalState", NbtUtils.writeBlockState(originalState));
        }

        if (originalData != null) {
            tag.put("originalData", originalData);
        }

        if (controllerPos != null) {
            tag.put("controllerPos", NbtUtils.writeBlockPos(controllerPos));
        }

        return tag;
    }

    /**
     * Creates a vanilla block entity update packet for client sync.
     *
     * @return update packet
     */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * Sends a block update packet for this proxy.
     *
     * <p>Side effects are server-side only. Flag {@code 11} refreshes clients and neighbor state.</p>
     */
    public void sync() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 11);
        }
    }

}
