package com.lowdragmc.mbd2.common.item;

import com.lowdragmc.lowdraglib.client.renderer.IItemRendererProvider;
import com.lowdragmc.lowdraglib.client.renderer.IRenderer;
import com.lowdragmc.mbd2.common.machine.definition.EntityMachineDefinition;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Spawn item for an {@link EntityMachineDefinition}.
 *
 * <p>The business goal is to let entity-backed machines be placed from an item stack like a spawn egg while preserving
 * machine placement hooks and the definition's custom item renderer. Placement is handled on the logical server; client
 * calls report success so vanilla prediction can play the hand animation.</p>
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class EntityMachineItem extends Item implements IItemRendererProvider {
    private final EntityMachineDefinition definition;

    /**
     * Creates an item bound to one entity machine definition.
     *
     * @param definition entity machine definition used for spawning and rendering
     * @param properties vanilla item properties such as stack size and creative-tab behavior
     */
    public EntityMachineItem(EntityMachineDefinition definition, Properties properties) {
        super(properties);
        this.definition = definition;
    }

    /**
     * Returns the definition spawned by this item.
     *
     * @return entity machine definition
     */
    public EntityMachineDefinition getDefinition() {
        return definition;
    }

    /**
     * Returns the localized spawn-item name with the definition's configured display name as its fallback.
     *
     * @param stack entity machine item stack being named
     * @return translated item name, or the configured display name when its translation key is absent
     */
    @Override
    public Component getName(ItemStack stack) {
        return definition.getDisplayName(super.getName(stack), getDescriptionId(stack));
    }

    /**
     * Spawns the definition's entity machine on the clicked side of a block.
     *
     * <p>Side effects on the logical server: creates the entity, runs the machine placement hook with the placing
     * player and stack, emits a vanilla entity-place game event, and consumes one item unless the player has creative
     * instabuild. If entity creation fails the interaction fails without consuming the stack.</p>
     *
     * @param context vanilla item-use context
     * @return {@link InteractionResult#CONSUME} after successful server placement, {@link InteractionResult#FAIL} when
     * spawn failed, or client-side success for prediction
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();
        BlockPos spawnPos = context.getClickedPos().relative(context.getClickedFace());
        Entity entity = definition.getOrCreateEntityType().spawn(serverLevel, stack, context.getPlayer(), spawnPos,
                MobSpawnType.SPAWN_EGG, true, false);
        if (entity == null) {
            return InteractionResult.FAIL;
        }
        var machine = definition.getMachine(entity);
        if (machine != null) {
            machine.onMachinePlaced(context.getPlayer(), stack);
        }
        level.gameEvent(context.getPlayer(), GameEvent.ENTITY_PLACE, entity.position());
        if (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Returns the item renderer configured by the entity machine definition.
     *
     * @param stack stack being rendered
     * @return custom renderer, or {@code null} to use vanilla item rendering
     */
    @Nullable
    @Override
    public IRenderer getRenderer(ItemStack stack) {
        return definition.itemRenderer();
    }
}
