package com.lowdragmc.mbd2.common.machine;

import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.client.renderer.IRenderer;
import com.lowdragmc.lowdraglib.client.renderer.impl.IModelRenderer;
import com.lowdragmc.lowdraglib.client.renderer.impl.UIResourceRenderer;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.syncdata.IEnhancedManaged;
import com.lowdragmc.lowdraglib.syncdata.IManaged;
import com.lowdragmc.lowdraglib.syncdata.annotation.*;
import com.lowdragmc.lowdraglib.syncdata.field.FieldManagedStorage;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.syncdata.managed.IRef;
import com.lowdragmc.lowdraglib.syncdata.managed.MultiManagedStorage;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.blockentity.IMachineBlockEntity;
import com.lowdragmc.mbd2.api.capability.recipe.*;
import com.lowdragmc.mbd2.api.machine.IMachine;
import com.lowdragmc.mbd2.api.recipe.MBDRecipe;
import com.lowdragmc.mbd2.api.recipe.MBDRecipeType;
import com.lowdragmc.mbd2.api.recipe.IRecipeSearchResultListener;
import com.lowdragmc.mbd2.api.recipe.RecipeConsumption;
import com.lowdragmc.mbd2.api.recipe.RecipeLogic;
import com.lowdragmc.mbd2.api.recipe.content.ContentModifier;
import com.lowdragmc.mbd2.client.MachineSound;
import com.lowdragmc.mbd2.client.renderer.MBDClientRenderers;
import com.lowdragmc.mbd2.common.gui.factory.MachineUIFactory;
import com.lowdragmc.mbd2.common.machine.definition.MBDMachineDefinition;
import com.lowdragmc.mbd2.common.machine.definition.config.ConfigMachineSettings;
import com.lowdragmc.mbd2.common.machine.definition.config.MachineState;
import com.lowdragmc.mbd2.common.machine.definition.config.event.*;
import com.lowdragmc.mbd2.common.trait.ICapabilityProviderTrait;
import com.lowdragmc.mbd2.common.trait.ITrait;
import com.lowdragmc.mbd2.common.trait.TraitDefinition;
import com.lowdragmc.mbd2.common.capability.recipe.RecipeCapabilitiesProxyCompat;
import com.lowdragmc.mbd2.common.trait.redstone.RedstoneSignalCapabilityTrait;
import com.lowdragmc.mbd2.common.trait.recipethread.RecipeThreadTrait;
import com.lowdragmc.mbd2.integration.geckolib.GeckolibRenderer;
import com.lowdragmc.mbd2.integration.kubejs.events.MBDServerEvents;
import com.lowdragmc.mbd2.integration.photon.MachineFX;
import com.lowdragmc.photon.client.fx.FXHelper;
import dev.latvian.mods.rhino.util.HideFromJS;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default block-backed machine runtime for an {@link MBDMachineDefinition}.
 * <p>
 * A machine owns the persisted/synced state that is not part of the block
 * state itself: custom names/data, recipe logic, dynamic model overrides,
 * redstone output, additional traits, capability routing, sounds, and optional
 * integration state. It is bound to an {@link IMachineBlockEntity} holder and
 * attaches its managed storage to the holder during construction.
 * <p>
 * Most methods are expected to run on the logical server or the logical client
 * main thread, matching normal Minecraft block entity lifecycles. The class is
 * not generally thread-safe; the only concurrent structure is the static set
 * used to de-duplicate trait load warnings. Server-side mutators often mark the
 * holder dirty, send block updates, post Forge/KubeJS/graph events, or invoke
 * LowDragLib RPCs to tracking clients.
 */
@Getter
public class MBDMachine implements IMachine, IEnhancedManaged, ICapabilityProvider, IUIHolder, IRecipeSearchResultListener {
    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(MBDMachine.class);
    private static final int GAME_DELAY_SYNC_INTERVAL = 20;
    private static final int GAME_DELAY_AVERAGE_WINDOW_SECONDS = 10;
    private static final Set<String> REPORTED_TRAIT_LOAD_FAILURES = ConcurrentHashMap.newKeySet();

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void onChanged() {
        this.markDirty();
    }

    private final FieldManagedStorage syncStorage = new FieldManagedStorage(this);
    private final MBDMachineDefinition definition;
    private final IMachineBlockEntity machineHolder;

    @Getter
    @Setter
    @DropSaved
    @Persisted
    @DescSynced
    private Component customName = null;
    @DropSaved
    @Persisted
    @DescSynced
    @UpdateListener(methodName = "updateCustomData")
    @Setter
    private CompoundTag customData = new CompoundTag();
    @DropSaved
    @Persisted
    @DescSynced
    private final RecipeLogic recipeLogic;
    private final Table<IO, RecipeCapability<?>, List<IRecipeHandler<?>>> recipeCapabilitiesProxy;
    @Nonnull
    @DropSaved
    @Persisted
    @DescSynced
    @UpdateListener(methodName = "updateState")
    private String machineState;
    @Getter
    private final List<ITrait> additionalTraits = new ArrayList<>();
    @Getter
    private Map<IRenderer, Object> animatableMachine = new HashMap<>(); // it's used for Geckolib
    @Getter
    private Map<String, Object> photonFXs = new HashMap<>(); // it's used for Photon
    @DropSaved
    @Persisted
    @DescSynced
    @Getter
    private int dynamicMachineLevel = -1;
    @DropSaved
    @Persisted
    @DescSynced
    @Getter
    private int dynamicMaxParallel = -1;
    @DescSynced
    @UpdateListener(methodName = "updateDynamicRenderer")
    private String dynamicBlockModel = "";
    @DescSynced
    @UpdateListener(methodName = "updateDynamicRenderer")
    private String dynamicFrontModel = "";
    @Nullable
    @OnlyIn(Dist.CLIENT)
    private transient IRenderer dynamicBlockRenderer;
    @Nullable
    @OnlyIn(Dist.CLIENT)
    private transient IRenderer dynamicFrontRenderer;
    @Nullable
    @OnlyIn(Dist.CLIENT)
    private transient String lastDynamicBlockModel;
    @Nullable
    @OnlyIn(Dist.CLIENT)
    private transient String lastDynamicFrontModel;
    @DescSynced
    @Getter(AccessLevel.NONE)
    private long gameDelayMicroseconds;
    @DescSynced
    @Getter(AccessLevel.NONE)
    private long tenSecondAverageGameDelayMicroseconds;
    private final long[] gameDelaySecondAverageBuckets = new long[GAME_DELAY_AVERAGE_WINDOW_SECONDS];
    private int gameDelaySecondAverageBucketIndex;
    @DescSynced
    private int gameDelaySecondAverageBucketCount;
    private long currentSecondGameDelayMicroseconds;
    private int currentSecondGameDelaySamples;
    private long currentGameDelaySecondKey = Long.MIN_VALUE;
    private long lastGameDelaySampleTick = Long.MIN_VALUE;
    private long lastGameDelaySampleMicroseconds;
    // redstone signal
    @Getter
    @DropSaved
    @Persisted
    @DescSynced
    private byte[] outputSignal = new byte[6];
    @Getter
    @DropSaved
    @Persisted
    @DescSynced
    private byte[] outputDirectSignal = new byte[6];
    @Getter
    @DropSaved
    @Persisted
    @DescSynced
    private byte analogOutputSignal = 0;
    @Nullable
    @OnlyIn(Dist.CLIENT)
    private MachineSound currentSound;

    /**
     * Creates and attaches a machine instance to its block entity holder.
     *
     * @param machineHolder block entity wrapper that owns storage, position, and
     *                      level access; its root storage must be a
     *                      {@link MultiManagedStorage}
     * @param definition    static definition that drives states, traits, recipe
     *                      logic, and UI behavior
     * @param args          optional subclass-specific creation arguments
     * @throws RuntimeException if the holder does not expose a
     *                          {@link MultiManagedStorage}
     */
    public MBDMachine(IMachineBlockEntity machineHolder, MBDMachineDefinition definition, Object... args) {
        this.machineHolder = machineHolder;
        this.definition = definition;
        // bind sync storage
        if (machineHolder.getRootStorage() instanceof MultiManagedStorage multiManagedStorage) {
            multiManagedStorage.attach(getSyncStorage());
        } else {
            throw new RuntimeException("Root storage of MBDMachine's holder must be MultiManagedStorage");
        }
        recipeCapabilitiesProxy = Tables.newCustomTable(new EnumMap<>(IO.class), HashMap::new);
        machineState = definition.stateMachine().getRootState().name();
        // trait initialization
        recipeLogic = createRecipeLogic(args);
        // additional traits initialization
        loadAdditionalTraits();
    }

    @Override
    public void onChunkUnloaded() {
        IMachine.super.onChunkUnloaded();
        resetGameDelay();
        for (ITrait additionalTrait : additionalTraits) {
            additionalTrait.onChunkUnloaded();
        }
    }

    @Override
    public void onUnload() {
        IMachine.super.onUnload();
        resetGameDelay();
        for (ITrait additionalTrait : additionalTraits) {
            additionalTrait.onMachineUnLoad();
        }
    }

    /**
     * on machine valid in the chunk.
     */
    @Override
    public void onLoad() {
        IMachine.super.onLoad();
        resetGameDelay();
        for (ITrait additionalTrait : additionalTraits) {
            additionalTrait.onMachineLoad();
        }
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(0, () -> MinecraftForge.EVENT_BUS.post(new MachineOnLoadEvent(this).postCustomEvent())));
        }
    }

    /**
     * Detach the {@link com.lowdragmc.lowdraglib.syncdata.IManagedStorage} of all traits.
     * <br>
     * Have to call this method while changing the machine instance. e.g. {@link com.lowdragmc.mbd2.common.blockentity.MachineBlockEntity#setMachine(IMachine)}
     */
    public void detach() {
        if (machineHolder.getRootStorage() instanceof MultiManagedStorage multiManagedStorage) {
            multiManagedStorage.detach(getSyncStorage());
            for (ITrait trait : additionalTraits) {
                if (trait instanceof IManaged managed) {
                    multiManagedStorage.detach(managed.getSyncStorage());
                }
            }
        }
    }

    /**
     * Creates the recipe logic instance owned by this machine.
     * <p>
     * Subclasses may override this to provide threaded or specialized recipe
     * logic. The returned logic is persisted and description-synced by the
     * machine.
     *
     * @param args constructor arguments passed to the machine
     * @return recipe logic instance for this machine
     */
    protected RecipeLogic createRecipeLogic(Object... args) {
        return new RecipeLogic(this);
    }

    /**
     * Whether disable all rendering.
     */
    public boolean isDisableRendering() {
        return false;
    }

    /**
     * Update the machine state from the {@link MBDMachineDefinition#stateMachine()} by the given state name. if no such state found, it will do nothing.
     *
     * @param newState
     */
    public void setMachineState(String newState) {
        if (machineState.equals(newState)) return;
        if (definition.stateMachine().hasState(newState)) {
            var event = new MachineStateChangedEvent(this, machineState, newState).postCustomEvent();
            MinecraftForge.EVENT_BUS.post(event);
            if (!event.isCanceled()) {
                var oldState = machineState;
                machineState = newState;
                notifyBlockUpdate();
                updateState(newState, oldState);
            }
        }
    }

    /**
     * Sets a server-synced dynamic block model override.
     * <p>
     * The override is persisted through description sync only, invalidates the
     * client renderer cache, marks the block for update, and sends an RPC to
     * tracking clients when called on the server.
     *
     * @param modelPath resource location of the model to render; must be a
     *                  valid model location
     */
    @HideFromJS
    public void setMachineBlockModel(ResourceLocation modelPath) {
        applyMachineBlockModel(modelPath);
    }

    /**
     * Sets a server-synced dynamic block model override from a string id.
     * Invalid resource locations are logged and ignored.
     *
     * @param modelPath resource location string such as {@code modid:path}
     */
    public void setMachineBlockModel(String modelPath) {
        var location = ResourceLocation.tryParse(modelPath);
        if (location == null) {
            MBD2.LOGGER.warn("[MBD2/Fusion] Ignored invalid machine block model path '{}' for machine={} at {}",
                    modelPath, definition, getPos());
            return;
        }
        applyMachineBlockModel(location);
    }

    private void applyMachineBlockModel(ResourceLocation modelPath) {
        dynamicBlockModel = modelPath.toString();
        MBD2.LOGGER.trace("[MBD2/Fusion] setMachineBlockModel machine={}, pos={}, model={}, remote={}",
                definition, getPos(), dynamicBlockModel, isRemote());
        notifyDynamicRendererChanged();
        updateDynamicRenderer(dynamicBlockModel, "");
    }

    /**
     * Sets a server-synced dynamic front model override.
     * <p>
     * The override follows the same sync and render-cache invalidation rules as
     * {@link #setMachineBlockModel(ResourceLocation)}.
     *
     * @param modelPath resource location of the front model to render
     */
    @HideFromJS
    public void setMachineFrontModel(ResourceLocation modelPath) {
        applyMachineFrontModel(modelPath);
    }

    /**
     * Sets a server-synced dynamic front model override from a string id.
     * Invalid resource locations are logged and ignored.
     *
     * @param modelPath resource location string such as {@code modid:path}
     */
    public void setMachineFrontModel(String modelPath) {
        var location = ResourceLocation.tryParse(modelPath);
        if (location == null) {
            MBD2.LOGGER.warn("[MBD2/Fusion] Ignored invalid machine front model path '{}' for machine={} at {}",
                    modelPath, definition, getPos());
            return;
        }
        applyMachineFrontModel(location);
    }

    private void applyMachineFrontModel(ResourceLocation modelPath) {
        dynamicFrontModel = modelPath.toString();
        MBD2.LOGGER.trace("[MBD2/Fusion] setMachineFrontModel machine={}, pos={}, model={}, remote={}",
                definition, getPos(), dynamicFrontModel, isRemote());
        notifyDynamicRendererChanged();
        updateDynamicRenderer(dynamicFrontModel, "");
    }

    /**
     * Clears the dynamic block model override and resynchronizes renderer state
     * to tracking clients.
     */
    public void clearMachineBlockModel() {
        dynamicBlockModel = "";
        notifyDynamicRendererChanged();
        updateDynamicRenderer(dynamicBlockModel, "");
    }

    /**
     * Clears the dynamic front model override and resynchronizes renderer state
     * to tracking clients.
     */
    public void clearMachineFrontModel() {
        dynamicFrontModel = "";
        notifyDynamicRendererChanged();
        updateDynamicRenderer(dynamicFrontModel, "");
    }

    private void notifyDynamicRendererChanged() {
        markDirty();
        notifyBlockUpdate();
        var level = getLevel();
        if (level != null) {
            var pos = getPos();
            var state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
        }
        syncDynamicRenderer();
    }

    private void syncDynamicRenderer() {
        if (!isRemote()) {
            rpcToTracking("setDynamicRenderer", dynamicBlockModel, dynamicFrontModel);
        }
    }

    /**
     * RPC entry point used to apply dynamic renderer ids on clients.
     *
     * @param blockModel block model id, or empty/null to clear it
     * @param frontModel front model id, or empty/null to clear it
     */
    @RPCMethod
    public void setDynamicRenderer(String blockModel, String frontModel) {
        dynamicBlockModel = blockModel == null ? "" : blockModel;
        dynamicFrontModel = frontModel == null ? "" : frontModel;
        MBD2.LOGGER.trace("[MBD2/Fusion] synced dynamic renderer machine={}, pos={}, blockModel={}, frontModel={}, remote={}",
                definition, getPos(), dynamicBlockModel, dynamicFrontModel, isRemote());
        updateDynamicRenderer(dynamicBlockModel, dynamicFrontModel);
    }

    /**
     * Managed-field listener for dynamic renderer changes.
     * <p>
     * On the logical client this clears cached renderer instances and requests a
     * render update. Server calls only update synced field values.
     *
     * @param newValue value reported by the changed managed field
     * @param oldValue previous value reported by the changed managed field
     */
    public void updateDynamicRenderer(String newValue, String oldValue) {
        if (isRemote()) {
            clearDynamicRendererCache();
            scheduleRenderUpdate();
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void clearDynamicRendererCache() {
        dynamicBlockRenderer = null;
        dynamicFrontRenderer = null;
        lastDynamicBlockModel = null;
        lastDynamicFrontModel = null;
    }

    /**
     * Managed-field listener for custom machine data changes.
     *
     * @param newValue new custom NBT payload
     * @param oldValue previous custom NBT payload
     */
    public void updateCustomData(CompoundTag newValue, CompoundTag oldValue) {
        MinecraftForge.EVENT_BUS.post(new MachineCustomDataUpdateEvent(this, newValue, oldValue).postCustomEvent());
    }

    /**
     * Managed-field listener for machine state changes.
     * <p>
     * Updates lighting when state light levels differ, and on the client starts
     * the state sound and schedules a render refresh.
     *
     * @param newValue new state name
     * @param oldValue previous state name
     */
    public void updateState(String newValue, String oldValue) {
        var hasLightChanged = definition.stateMachine().getState(newValue).getLightLevel() != definition.stateMachine().getState(oldValue).getLightLevel();
        // notify the light engine to update the light value
        if (hasLightChanged) {
            // TODO it doesnt save the light value to the chunk?
            var profilerfiller = getLevel().getProfiler();
            var level = getLevel();
            var pos = getPos();
            int j = pos.getX() & 15;
            int k = pos.getY() & 15;
            int l = pos.getZ() & 15;
            profilerfiller.push("updateSkyLightSources");
            var levelChunk = level.getChunkAt(getPos());
            levelChunk.getSkyLightSources().update(level, j, pos.getY(), l);
            profilerfiller.popPush("queueCheckLight");
            level.getChunkSource().getLightEngine().checkBlock(pos);
            profilerfiller.pop();
        }
        // update sound and renderer
        if (isRemote()) {
            playStateSound(newValue);
            scheduleRenderUpdate();
        }
    }

    /**
     * Load additional traits from the {@link ConfigMachineSettings#traitDefinitions()}.
     * <br>
     * It will attach the {@link com.lowdragmc.lowdraglib.syncdata.IManagedStorage} of all traits for sync/persisted data management.
     * <br>
     * You don't have to call this method manually, it will be called automatically when the machine is created.
     */
    public void loadAdditionalTraits() {
        if (machineHolder.getRootStorage() instanceof MultiManagedStorage multiManagedStorage) {
            for (ITrait trait : additionalTraits) {
                if (trait instanceof IManaged managed) {
                    multiManagedStorage.detach(managed.getSyncStorage());
                }
            }
            additionalTraits.clear();
            definition.machineSettings().traitDefinitions().stream()
                    .filter(this::canLoadTrait)
                    .sorted((a, b) -> b.getPriority() - a.getPriority()).forEach(traitDefinition -> {
                        ITrait trait;
                        try {
                            trait = traitDefinition.createTrait(this);
                        } catch (RuntimeException | LinkageError error) {
                            reportTraitLoadFailure(traitDefinition, error);
                            return;
                        }
                        additionalTraits.add(trait);
                        if (trait instanceof IManaged managed) {
                            for (IRef ref : managed.getSyncStorage().getPersistedFields()) {
                                ref.setPersistedPrefixName("trait." + traitDefinition.getName());
                            }
                            multiManagedStorage.attach(managed.getSyncStorage());
                        }
                    });
            initCapabilitiesProxy();
        }
    }

    /**
     * Checks whether a configured trait definition should be instantiated for
     * this machine.
     *
     * @param traitDefinition configured trait definition
     * @return {@code true} to create and attach the trait
     */
    protected boolean canLoadTrait(TraitDefinition traitDefinition) {
        return true;
    }

    private void reportTraitLoadFailure(TraitDefinition traitDefinition, Throwable error) {
        var traitKey = traitDefinition.getClass().getName() + ":" + traitDefinition.getName();
        if (REPORTED_TRAIT_LOAD_FAILURES.add(traitKey)) {
            MBD2.LOGGER.warn("[mbd2] Failed to create trait '{}' ({}) for machine {} at {}; skipping this trait to keep the block entity loadable. This is often caused by an incompatible optional integration or stale runtime transformer patch. Original error: {}",
                    traitDefinition.getName(), traitDefinition.getClass().getName(), definition.id(), getPos(), error.toString(), error);
        } else {
            MBD2.LOGGER.warn("[mbd2] Skipped failed trait '{}' for machine {} at {}. Original error: {}",
                    traitDefinition.getName(), definition.id(), getPos(), error.toString());
        }
    }

    /**
     * Initialize the capabilities proxy for recipe logic. see {@link IRecipeCapabilityHolder#getRecipeCapabilitiesProxy()}
     */
    public void initCapabilitiesProxy() {
        recipeCapabilitiesProxy.clear();
        for (var trait : additionalTraits) {
            for (var recipeHandlerTrait : trait.getRecipeHandlerTraits()) {
                if (!recipeCapabilitiesProxy.contains(recipeHandlerTrait.getHandlerIO(), recipeHandlerTrait.getRecipeCapability())) {
                    recipeCapabilitiesProxy.put(recipeHandlerTrait.getHandlerIO(), recipeHandlerTrait.getRecipeCapability(), new ArrayList<>());
                }
                recipeCapabilitiesProxy.get(recipeHandlerTrait.getHandlerIO(), recipeHandlerTrait.getRecipeCapability()).add(recipeHandlerTrait);
            }
        }
        RecipeCapabilitiesProxyCompat.apply(recipeCapabilitiesProxy);
    }

    /**
     * Get the Trait Instance by the given trait definition.
     */
    /**
     * Looks up a loaded trait by exact definition identity.
     *
     * @param traitDefinition definition instance to match
     * @return loaded trait, or {@code null} when the definition is not attached
     */
    @Nullable
    public ITrait getTraitByDefinition(TraitDefinition traitDefinition) {
        for (var trait : additionalTraits) {
            if (traitDefinition == trait.getDefinition()) {
                return trait;
            }
        }
        return null;
    }

    /**
     * Looks up a loaded trait by definition name.
     *
     * @param name trait definition name
     * @return loaded trait, or {@code null} if none matches
     */
    @Nullable
    public ITrait getTraitByName(String name) {
        for (var trait : additionalTraits) {
            if (trait.getDefinition().getName().equals(name)) {
                return trait;
            }
        }
        return null;
    }

    /**
     * Looks up and casts a loaded trait by definition name.
     *
     * @param clazz expected trait runtime type
     * @param name  trait definition name
     * @param <T>   expected trait type
     * @return loaded trait cast to {@code clazz}, or {@code null} if no matching
     * trait exists
     */
    public <T> T getTraitByName(Class<T> clazz, String name) {
        for (var trait : additionalTraits) {
            if (trait.getDefinition().getName().equals(name) && clazz.isInstance(trait)) {
                return (T) trait;
            }
        }
        return null;
    }

    /**
     * Get the block entity holder.
     */
    @Override
    public BlockEntity getHolder() {
        return machineHolder.getSelf();
    }

    /**
     * Get the random offset.
     */
    @Override
    public long getOffset() {
        return machineHolder.getOffset();
    }

    /**
     * Get the front facing of the machine.
     */
    @Override
    public Optional<Direction> getFrontFacing() {
        return getDefinition().blockProperties().rotationState().property.flatMap(property -> getBlockState().getOptionalValue(property));
    }

    /**
     * Is the facing valid for setup.
     */
    @Override
    public boolean isFacingValid(Direction facing) {
        return getDefinition().blockProperties().rotationState().test(facing);
    }

    /**
     * Set the front facing of the machine.
     */
    @Override
    public void setFrontFacing(Direction facing) {
        var blockState = getBlockState();
        var property = getDefinition().blockProperties().rotationState().property;
        if (property.isPresent() && blockState.hasProperty(property.get()) && isFacingValid(facing)) {
            getLevel().setBlockAndUpdate(getPos(), blockState.setValue(property.get(), facing));
        }
    }

    /**
     * Get the recipe type. which is defined in the {@link com.lowdragmc.mbd2.common.machine.definition.config.ConfigRecipeLogicSettings#getRecipeType()}.
     */
    @NotNull
    @Override
    public MBDRecipeType getRecipeType() {
        return definition.recipeLogicSettings().getRecipeType();
    }

    @NotNull
    @Override
    public List<MBDRecipeType> getRecipeTypes() {
        return definition.recipeLogicSettings().getRecipeTypes();
    }

    /**
     * Called when recipe logic status changed.
     * <br>
     * By default, We will update the machine state to match the recipe logic status.
     */
    @Override
    public void notifyRecipeStatusChanged(RecipeLogic.Status oldStatus, RecipeLogic.Status newStatus) {
        switch (newStatus) {
            case WORKING -> setMachineState("working");
            case IDLE -> setMachineState(definition.stateMachine().getRootState().name());
            case WAITING -> setMachineState("waiting");
            case SUSPEND -> setMachineState("suspend");
        }
        MinecraftForge.EVENT_BUS.post(new MachineRecipeStatusChangedEvent(this, oldStatus, newStatus).postCustomEvent());
    }

    /**
     * Forwards completed recipe-search snapshots to the optional recipe-thread scheduler.
     *
     * <p>{@link RecipeLogic} invokes this callback on the logical server thread even when candidate discovery ran on
     * the background executor. Machines without a recipe-thread trait pay only the trait lookup cost.</p>
     */
    @Override
    public void onRecipeSearchResults(RecipeLogic source, List<MBDRecipe> candidates, boolean exhaustive) {
        RecipeThreadTrait trait = RecipeThreadTrait.get(this);
        if (trait != null) {
            trait.onRecipeSearchResults(source, candidates, exhaustive);
        }
    }

    /**
     * Get the machine level. it will be used for recipe condition {@link com.lowdragmc.mbd2.common.recipe.MachineLevelCondition} an so on.
     */
    @Override
    public int getMachineLevel() {
        return dynamicMachineLevel < 0 ? getDefinition().machineSettings().machineLevel() : dynamicMachineLevel;
    }

    /**
     * Set the machine level dynamically.
     */
    public void setMachineLevel(int level) {
        dynamicMachineLevel = level;
    }

    /**
     * Set the max recipe parallel dynamically.
     */
    public void setMaxParallel(int maxParallel) {
        dynamicMaxParallel = Math.max(1, maxParallel);
        RecipeThreadTrait trait = RecipeThreadTrait.get(this);
        if (trait != null) {
            for (RecipeLogic logic : trait.getRecipeLogics()) {
                logic.markLastRecipeDirty();
            }
        } else {
            recipeLogic.markLastRecipeDirty();
        }
        markDirty();
    }

    /**
     * Clear the dynamic max recipe parallel and use definition settings again.
     */
    public void clearMaxParallel() {
        dynamicMaxParallel = -1;
        recipeLogic.markLastRecipeDirty();
        markDirty();
    }

    /**
     * re-render the chunk.
     */
    @Override
    public void scheduleRenderUpdate() {
        IMachine.super.scheduleRenderUpdate();
    }

    /**
     * Resolves the current persisted state name to the configured machine state.
     * <p>
     * The state name is description-synced and changed through {@link #setMachineState(String)} or multiblock formed
     * state transitions. Callers should ensure the definition still contains the state name; project reloads that remove
     * a state can make this return {@code null}.
     *
     * @return configured state for the current state name, or {@code null} if the definition no longer has that state
     */
    public MachineState getMachineState() {
        return definition.getState(machineState);
    }

    /**
     * Builds the client renderer for the current state and facing.
     * <p>
     * This is client-only and should be called from render setup/render paths. It uses north as the fallback front when
     * the machine has no front-facing direction. Dynamic block/front renderer overrides take precedence over definition
     * renderers.
     *
     * @return renderer composed for the current state and effective front direction
     */
    @OnlyIn(Dist.CLIENT)
    public IRenderer getRealRenderer() {
        return getRealRenderer(getFrontFacing().orElse(Direction.NORTH));
    }

    /**
     * Builds the client renderer for the current state with an explicit front direction.
     * <p>
     * The returned renderer combines the block renderer and optional front renderer. If the front renderer is
     * {@link IRenderer#EMPTY}, only the block renderer is used. Dynamic model overrides are resolved lazily and cached
     * until their synced model path changes.
     *
     * @param frontFacing direction used to orient the front overlay renderer
     * @return renderer that should be used for this machine state and facing
     */
    @OnlyIn(Dist.CLIENT)
    public IRenderer getRealRenderer(Direction frontFacing) {
        var state = getMachineState();
        var blockRenderer = getDynamicBlockRenderer();
        var frontRenderer = getDynamicFrontRenderer();
        var realBlockRenderer = blockRenderer == null ? state.getRenderer() : blockRenderer;
        var realFrontRenderer = frontRenderer == null ? state.getFrontRenderer() : frontRenderer;
        if (realFrontRenderer == IRenderer.EMPTY) {
            return realBlockRenderer;
        }
        return MBDClientRenderers.createMachineStateRenderer(realBlockRenderer, realFrontRenderer, frontFacing);
    }

    /**
     * Resolves the dynamic block renderer override, if one is currently configured.
     * <p>
     * Client-only. Side effect: when {@code dynamicBlockModel} changed since the last call, recreates and caches the
     * renderer. Blank or invalid model ids clear the cached override and return {@code null}.
     *
     * @return dynamic block renderer, or {@code null} to use the definition state renderer
     */
    @OnlyIn(Dist.CLIENT)
    @Nullable
    protected IRenderer getDynamicBlockRenderer() {
        if (Objects.equals(lastDynamicBlockModel, dynamicBlockModel)) {
            return dynamicBlockRenderer;
        }
        lastDynamicBlockModel = dynamicBlockModel;
        dynamicBlockRenderer = createDynamicModelRenderer(dynamicBlockModel);
        return dynamicBlockRenderer;
    }

    /**
     * Resolves the dynamic front renderer override, if one is currently configured.
     * <p>
     * Client-only. Side effect: when {@code dynamicFrontModel} changed since the last call, recreates and caches the
     * renderer. Blank or invalid model ids clear the cached override and return {@code null}.
     *
     * @return dynamic front renderer, or {@code null} to use the definition state front renderer
     */
    @OnlyIn(Dist.CLIENT)
    @Nullable
    protected IRenderer getDynamicFrontRenderer() {
        if (Objects.equals(lastDynamicFrontModel, dynamicFrontModel)) {
            return dynamicFrontRenderer;
        }
        lastDynamicFrontModel = dynamicFrontModel;
        dynamicFrontRenderer = createDynamicModelRenderer(dynamicFrontModel);
        return dynamicFrontRenderer;
    }

    @OnlyIn(Dist.CLIENT)
    @Nullable
    private IRenderer createDynamicModelRenderer(String modelPath) {
        if (modelPath == null || modelPath.isBlank()) {
            return null;
        }
        var modelLocation = ResourceLocation.tryParse(modelPath);
        return modelLocation == null ? null : MBDClientRenderers.createFusionModelRenderer(modelLocation);
    }

    /**
     * Returns the model id that should be used by render systems that need a block-model location.
     * <p>
     * Client-only. Dynamic block model overrides take precedence. If the active renderer is a nested
     * {@link UIResourceRenderer}, it is unwrapped until a model renderer is found or no model-backed renderer remains.
     *
     * @return model location for the dynamic or state renderer, or {@link Optional#empty()} when rendering is not model
     * backed
     */
    @OnlyIn(Dist.CLIENT)
    public Optional<ResourceLocation> getBlockModelLocationForRendering() {
        var dynamicModel = ResourceLocation.tryParse(dynamicBlockModel);
        if (dynamicModel != null) {
            return Optional.of(dynamicModel);
        }
        return getModelLocation(getMachineState().getRenderer());
    }

    /**
     * Checks whether a dynamic block or front renderer path has been supplied.
     *
     * @return {@code true} when either dynamic renderer override string is non-blank
     */
    public boolean hasDynamicRendererOverride() {
        return dynamicBlockModel != null && !dynamicBlockModel.isBlank() ||
                dynamicFrontModel != null && !dynamicFrontModel.isBlank();
    }

    /**
     * Extracts a model location from a renderer tree.
     * <p>
     * Client-only. This method only inspects renderer metadata; it does not create models or trigger resource reloads.
     *
     * @param renderer renderer to inspect; may be a direct model renderer or a UI resource wrapper
     * @return model location when the renderer ultimately delegates to an {@link IModelRenderer}
     */
    @OnlyIn(Dist.CLIENT)
    protected Optional<ResourceLocation> getModelLocation(IRenderer renderer) {
        if (renderer instanceof IModelRenderer modelRenderer) {
            return Optional.ofNullable(modelRenderer.getModelLocation());
        }
        if (renderer instanceof UIResourceRenderer resourceRenderer) {
            return getModelLocation(resourceRenderer.getRenderer());
        }
        return Optional.empty();
    }

    /**
     * Returns the synced state name currently selected for this machine.
     *
     * @return state name used to resolve {@link #getMachineState()}
     */
    public String getMachineStateName() {
        return machineState;
    }

    @Override
    @NotNull
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        List<T> results = new ArrayList<>();
        for (var trait : additionalTraits) {
            for (var capabilityProviderTrait : trait.getCapabilityProviderTraits()) {
                if (capabilityProviderTrait.getCapability() == cap) {
                    var io = capabilityProviderTrait.getCapabilityIO(side);
                    if (io != IO.NONE) {
                        results.add((T) capabilityProviderTrait.getCapContent(io));
                    }
                }
            }
        }
        if (results.isEmpty()) {
            return LazyOptional.empty();
        } else {
            if (results.size() == 1) {
                return LazyOptional.of(() -> results.get(0));
            } else {
                for (var trait : additionalTraits) {
                    for (var capabilityProviderTrait : trait.getCapabilityProviderTraits()) {
                        if (capabilityProviderTrait.getCapability() == cap) {
                            return LazyOptional.of(() -> (T) ((ICapabilityProviderTrait) capabilityProviderTrait).mergeContents(results));
                        }
                    }
                }
            }
        }
        return cap.orEmpty(cap, LazyOptional.of(() -> results.get(0)));
    }

    //////////////////////////////////////
    //********       MISC      *********//
    //////////////////////////////////////

    /**
     * RPC, use this method to send custom data to player (client).
     */
    protected void rpcToPlayer(ServerPlayer player, String methodName, Object... args) {
        machineHolder.rpcToPlayer(this, player, methodName, args);
    }

    /**
     * RPC, use this method to send custom data to all players (client) tracking this machine.
     */
    protected void rpcToTracking(String methodName, Object... args) {
        machineHolder.rpcToTracking(this, methodName, args);
    }

    /**
     * Server tick. will be called on server side per tick.
     */
    public void serverTick() {
        long start = System.nanoTime();
        try {
            var event = new MachineTickEvent(this).postCustomEvent();
            MinecraftForge.EVENT_BUS.post(event);
            if (!event.isCanceled()) {
                postFixedTickEvent();
                internalServerTick();
            }
        } finally {
            updateGameDelay(System.nanoTime() - start);
        }
    }

    /**
     * Records one server-tick execution time sample for UI/debug display.
     * <p>
     * Side effects: updates the current tick sample, per-second accumulation, ten-second rolling average buckets, and the
     * description-synced delay value every {@value #GAME_DELAY_SYNC_INTERVAL} offset ticks. Negative elapsed values are
     * clamped to zero. Call from the logical server tick path after machine work completes.
     *
     * @param elapsedNanos elapsed wall-clock time in nanoseconds for one machine tick
     */
    protected void updateGameDelay(long elapsedNanos) {
        var offsetTimer = getOffsetTimer();
        var sampleMicroseconds = Math.max(0, elapsedNanos / 1_000L);
        var secondKey = Math.floorDiv(offsetTimer, GAME_DELAY_SYNC_INTERVAL);
        if (currentGameDelaySecondKey == Long.MIN_VALUE) {
            currentGameDelaySecondKey = secondKey;
        } else if (currentGameDelaySecondKey != secondKey) {
            updateTenSecondAverageGameDelay();
            currentGameDelaySecondKey = secondKey;
        }
        var level = getLevel();
        var gameTime = level == null ? offsetTimer : level.getGameTime();
        if (lastGameDelaySampleTick == gameTime) {
            currentSecondGameDelayMicroseconds += sampleMicroseconds - lastGameDelaySampleMicroseconds;
        } else {
            currentSecondGameDelayMicroseconds += sampleMicroseconds;
            currentSecondGameDelaySamples++;
            lastGameDelaySampleTick = gameTime;
        }
        lastGameDelaySampleMicroseconds = sampleMicroseconds;

        if (offsetTimer % GAME_DELAY_SYNC_INTERVAL != 0) return;
        gameDelayMicroseconds = sampleMicroseconds;
    }

    /**
     * Rolls the current one-second delay accumulation into the ten-second average.
     * <p>
     * Side effects: writes the next average bucket, advances the circular bucket index, updates the published
     * ten-second average, and clears the current-second counters. If no samples were collected, no state changes occur.
     */
    protected void updateTenSecondAverageGameDelay() {
        if (currentSecondGameDelaySamples <= 0) return;
        gameDelaySecondAverageBuckets[gameDelaySecondAverageBucketIndex] = currentSecondGameDelayMicroseconds / currentSecondGameDelaySamples;
        gameDelaySecondAverageBucketIndex = (gameDelaySecondAverageBucketIndex + 1) % GAME_DELAY_AVERAGE_WINDOW_SECONDS;
        if (gameDelaySecondAverageBucketCount < GAME_DELAY_AVERAGE_WINDOW_SECONDS) {
            gameDelaySecondAverageBucketCount++;
        }
        long total = 0;
        for (int i = 0; i < gameDelaySecondAverageBucketCount; i++) {
            total += gameDelaySecondAverageBuckets[i];
        }
        tenSecondAverageGameDelayMicroseconds = total / gameDelaySecondAverageBucketCount;
        currentSecondGameDelayMicroseconds = 0;
        currentSecondGameDelaySamples = 0;
    }

    /**
     * Clears all tick-delay measurements.
     * <p>
     * Side effects: resets synced delay values, rolling average buckets, current-second accumulation, and duplicate-tick
     * detection. Called when the machine loads/unloads so stale timings from a previous chunk lifecycle are not reported.
     */
    protected void resetGameDelay() {
        gameDelayMicroseconds = 0;
        tenSecondAverageGameDelayMicroseconds = 0;
        Arrays.fill(gameDelaySecondAverageBuckets, 0);
        gameDelaySecondAverageBucketIndex = 0;
        gameDelaySecondAverageBucketCount = 0;
        currentSecondGameDelayMicroseconds = 0;
        currentSecondGameDelaySamples = 0;
        currentGameDelaySecondKey = Long.MIN_VALUE;
        lastGameDelaySampleTick = Long.MIN_VALUE;
        lastGameDelaySampleMicroseconds = 0;
    }

    @Override
    public long getGameDelayMicroseconds() {
        return gameDelayMicroseconds;
    }

    @Override
    public long getTenSecondAverageGameDelayMicroseconds() {
        return tenSecondAverageGameDelayMicroseconds;
    }

    /**
     * Posts configured fixed-tick events for this machine.
     * <p>
     * The interval is clamped to at least one tick. Side effects: may post Forge custom events and KubeJS machine
     * fixed-tick hooks on the logical server tick path. This method does not run recipe logic or trait ticks.
     */
    protected void postFixedTickEvent() {
        var timer = getOffsetTimer();
        var interval = Math.max(1, definition.machineEvents().getFixedTickInterval());
        if (timer % interval == 0) {
            MinecraftForge.EVENT_BUS.post(new MachineFixedTickEvent(this, interval, timer).postCustomEvent());
        }
        if (MBD2.isKubeJSLoaded()) {
            MBDServerEvents.postMachineFixedTickEvery(this, timer);
        }
    }

    /**
     * Runs the core server-tick work after external tick events have had a chance to cancel.
     * <p>
     * Side effects: advances recipe logic when {@link #runRecipeLogic()} permits it and then ticks every additional
     * trait. Call only from the logical server tick path.
     */
    protected void internalServerTick() {
        if (runRecipeLogic()) {
            recipeLogic.serverTick();
        }
        for (ITrait trait : additionalTraits) {
            trait.serverTick();
        }
    }

    /**
     * Shall we run the recipe logic during the server tick?
     * <br>
     * if the machine has no recipe logic or using the {@link MBDRecipeType#DUMMY}, it will return false.
     */
    public boolean runRecipeLogic() {
        return getDefinition().recipeLogicSettings().isEnable() && IMachine.super.runRecipeLogic();
    }

    @Override
    public @Nullable MBDRecipe modifyFuelRecipe(MBDRecipe recipe) {
        var event = new MachineFuelRecipeModifyEvent(this, recipe);
        MinecraftForge.EVENT_BUS.post(event.postCustomEvent());
        if (event.isCanceled()) return null;
        return event.getRecipe();
    }

    @Override
    public void onFuelBurningFinish(@Nullable MBDRecipe recipe) {
        MinecraftForge.EVENT_BUS.post(new MachineFuelBurningFinishEvent(this, recipe));
    }

    @Nullable
    @Override
    public MBDRecipe doModifyRecipe(@NotNull MBDRecipe recipe) {
        var before = new MachineRecipeModifyEvent.Before(this, recipe);
        MinecraftForge.EVENT_BUS.post(before.postCustomEvent());
        recipe = before.getRecipe();
        if (before.isCanceled() || recipe == null) {
            return recipe;
        }
        recipe = IMachine.super.doModifyRecipe(recipe);
        var after = new MachineRecipeModifyEvent.After(this, recipe);
        MinecraftForge.EVENT_BUS.post(after.postCustomEvent());
        return after.getRecipe();
    }

    /**
     * Override it to modify recipe on the fly e.g. applying overclock, change chance, etc
     *
     * @param recipe recipe from detected from MBDRecipe
     * @return modified recipe.
     * null -- this recipe is unavailable
     */
    @Nullable
    @Override
    public MBDRecipe getModifiedRecipe(@Nonnull MBDRecipe recipe) {
        return getDefinition().recipeLogicSettings().recipeModifiers().applyModifiers(getCurrentRecipeLogic(), recipe);
    }

    @Override
    public ContentModifier getMaxParallel(@Nonnull MBDRecipe recipe) {
        return getDefinition().recipeLogicSettings().recipeModifiers().getMaxParallel(getCurrentRecipeLogic(), recipe)
                .merge(getDynamicMaxParallelModifier());
    }

    /**
     * Resolves the recipe logic that is currently executing for this machine.
     * <p>
     * In threaded recipe mode this delegates to {@link RecipeThreadTrait} so graph callbacks and recipe modifiers see
     * the active thread context. Without a thread trait it resolves to the base machine recipe logic.
     *
     * @return active recipe logic for the current execution context
     */
    @Nonnull
    public RecipeLogic getCurrentRecipeLogic() {
        return RecipeThreadTrait.getCurrentRecipeLogic(this);
    }

    /**
     * Resolves a recipe logic instance by thread id.
     * <p>
     * Thread id {@code 0} maps to the base recipe logic when the machine has no {@link RecipeThreadTrait}. Other ids
     * return {@code null} unless a recipe-thread trait owns them.
     *
     * @param threadId zero-based recipe thread id
     * @return recipe logic for the requested thread, or {@code null} when no such thread exists
     */
    @Nullable
    public RecipeLogic getRecipeLogic(int threadId) {
        RecipeThreadTrait trait = RecipeThreadTrait.get(this);
        return trait == null ? (threadId == 0 ? getRecipeLogic() : null) : trait.getRecipeLogic(threadId);
    }

    /**
     * Converts the dynamic max-parallel override into a recipe content modifier.
     *
     * @return multiplier based on {@link #dynamicMaxParallel}, or {@link ContentModifier#IDENTITY} when no override is
     * active
     */
    protected ContentModifier getDynamicMaxParallelModifier() {
        return dynamicMaxParallel > 0 ? ContentModifier.multiplier(dynamicMaxParallel) : ContentModifier.IDENTITY;
    }

    /**
     * Always try {@link #doModifyRecipe(MBDRecipe)} before setting up recipe.
     *
     * @return true - will map {@link RecipeLogic#getLastOriginRecipe()} to the latest recipe for next round when finishing.
     * false - keep using the {@link RecipeLogic#getLastRecipe()}, which is already modified.
     */
    @Override
    public boolean alwaysTryModifyRecipe() {
        return !getDefinition().recipeLogicSettings().recipeModifiers().recipeModifiers.isEmpty() || getDefinition().recipeLogicSettings().alwaysModifyRecipe();
    }

    /**
     * Always re-search recipe when the recipe is finished.
     *
     * @return true - will re-search recipe when the last recipe is finished.
     */
    @Override
    public boolean alwaysReSearchRecipe() {
        return getDefinition().recipeLogicSettings().alwaysSearchRecipe();
    }

    /**
     * if the recipe handling is waiting, damping value is the decreased ticks of the current progress.
     *
     * @return damping value in tick.
     */
    @Override
    public int getRecipeDampingValue() {
        return getDefinition().recipeLogicSettings().recipeDampingValue();
    }

    @Override
    public boolean consumeInputsAfterWorking(MBDRecipe recipe) {
        if (getDefinition().recipeLogicSettings().consumeInputsAfterWorking()) {
            var event = new MachineAfterRecipeWorkingEvent(this, recipe).postCustomEvent();
            MinecraftForge.EVENT_BUS.post(event);
            return !event.isCanceled();
        }
        return false;
    }

    @Override
    public boolean beforeWorking(MBDRecipe recipe) {
        var event = new MachineBeforeRecipeWorkingEvent(this, recipe);
        MinecraftForge.EVENT_BUS.post(event.postCustomEvent());
        if (event.isCanceled()) {
            return true;
        }
        return IMachine.super.beforeWorking(recipe);
    }

    @Override
    public boolean onWorking() {
        RecipeLogic logic = getCurrentRecipeLogic();
        var event = new MachineOnRecipeWorkingEvent(this, logic.getLastRecipe(), logic.getProgress());
        MinecraftForge.EVENT_BUS.post(event.postCustomEvent());
        if (event.isCanceled()) {
            return true;
        }
        return IMachine.super.onWorking();
    }

    @Override
    public void onWaiting() {
        MinecraftForge.EVENT_BUS.post(new MachineOnRecipeWaitingEvent(this, getCurrentRecipeLogic().getLastRecipe()).postCustomEvent());
        IMachine.super.onWaiting();
    }

    @Override
    public void afterWorking() {
        MinecraftForge.EVENT_BUS.post(new MachineAfterRecipeWorkingEvent(this, getCurrentRecipeLogic().getLastRecipe()).postCustomEvent());
        IMachine.super.afterWorking();
    }

    @Override
    public void onConsumeInputsAfterWorking() {
        MinecraftForge.EVENT_BUS.post(new MachineOnConsumeInputsAfterWorkingEvent(this, getCurrentRecipeLogic().getLastRecipe()).postCustomEvent());
    }

    @Override
    public void onConsumeInputsAfterWorking(RecipeConsumption consumedInputs) {
        MinecraftForge.EVENT_BUS.post(new MachineOnConsumeInputsAfterWorkingEvent(this, getCurrentRecipeLogic().getLastRecipe(), consumedInputs).postCustomEvent());
    }

    @Override
    public void onRecipeInputsConsumed(MBDRecipe recipe, RecipeConsumption consumedInputs, boolean afterWorking) {
        MinecraftForge.EVENT_BUS.post(new MachineRecipeInputsConsumedEvent(this, recipe, consumedInputs, afterWorking).postCustomEvent());
        IMachine.super.onRecipeInputsConsumed(recipe, consumedInputs, afterWorking);
    }

    @Override
    public void onRecipeFinish() {
        MinecraftForge.EVENT_BUS.post(new MachineOnRecipeFinishEvent(this, getCurrentRecipeLogic().getLastRecipe()).postCustomEvent());
        IMachine.super.onRecipeFinish();
    }

    /**
     * Client tick. will be called on client side per tick.
     */
    @OnlyIn(Dist.CLIENT)
    public void clientTick() {
        MinecraftForge.EVENT_BUS.post(new MachineClientTickEvent(this).postCustomEvent());
        for (ITrait trait : additionalTraits) {
            trait.clientTick();
        }
        if (currentSound != null && currentSound.loop && currentSound.loopWithShuffle &&
                !Minecraft.getInstance().getSoundManager().isActive(currentSound)) {
            if (currentSound.predicate.getAsBoolean()) {
                currentSound.play();
            } else {
                currentSound = null;
            }
        }
    }

    /**
     * Called periodically clientside on blocks near the player to show effects (like furnace fire particles).
     */
    public void animateTick(RandomSource random) {
    }

    /**
     * Called when neighbors changed.
     */
    public void onNeighborChanged(Block block, BlockPos fromPos, boolean isMoving) {
        MinecraftForge.EVENT_BUS.post(new MachineNeighborChangedEvent(this, block, fromPos).postCustomEvent());
        for (ITrait trait : additionalTraits) {
            trait.onNeighborChanged(block, fromPos, isMoving);
        }
    }

    /**
     * Called when machine placed by (if exist) an entity with item.
     * it won't be called when machine added by {@link Level#setBlock(BlockPos, BlockState, int, int)}
     */
    public void onMachinePlaced(LivingEntity player, ItemStack stack) {
        if (stack.hasCustomHoverName()) {
            setCustomName(stack.getHoverName());
        }
        MinecraftForge.EVENT_BUS.post(new MachinePlacedEvent(this, player, stack).postCustomEvent());
    }

    /**
     * Returns the {@link BlockState} that this state reports to look like on the given side for querying by other mods.
     */
    public BlockState getAppearance(BlockState state, Direction side, BlockState queryState, BlockPos queryPos) {
        return state;
    }

    /**
     * Get the shape of this block, as well as collision boxes, it's used for interaction and selection.
     */
    public VoxelShape getShape(CollisionContext pContext) {
        return getDefinition().getState(machineState).getShape(getFrontFacing().orElse(Direction.NORTH));
    }

    /**
     * Set output signal.
     */
    public void setOutputSignal(int signal, Direction side) {
        if (!isRemote()) {
            var sig = (byte) Mth.clamp(signal, 0, 15);
            if (outputSignal[side.ordinal()] != sig) {
                outputSignal[side.ordinal()] = sig;
                updateSignal();
            }
        }
    }

    /**
     * Set output direct signal.
     */
    public void setOutputDirectSignal(int signal, Direction side) {
        if (!isRemote()) {
            var sig = (byte) Mth.clamp(signal, 0, 15);
            if (outputDirectSignal[side.ordinal()] != sig) {
                outputDirectSignal[side.ordinal()] = sig;
                updateSignal();
            }
        }
    }

    /**
     * Set output analog signal.
     */
    public void setAnalogOutputSignal(int signal) {
        if (!isRemote()) {
            var sig = (byte) Mth.clamp(signal, 0, 15);
            if (analogOutputSignal != sig) {
                analogOutputSignal = sig;
                updateSignal();
            }
        }
    }

    /**
     * Whether the machine can connect to the redstone from given side
     */
    public boolean canConnectRedstone(Direction direction) {
        if (getOutputSignal(direction) > 0) return true;
        for (ITrait trait : additionalTraits) {
            if (trait instanceof RedstoneSignalCapabilityTrait redstoneTrait && redstoneTrait.canConnectRedstone(direction)) {
                return true;
            }
        }
        return getDefinition().machineSettings().signalConnection().getConnection(getFrontFacing().orElse(Direction.NORTH), direction);
    }

    /**
     * Get the output signal for the given side.
     */
    public int getOutputSignal(Direction direction) {
        return outputSignal[direction.ordinal()];
    }

    /**
     * Get the direct signal for the given side.
     */
    public int getOutputDirectSignal(Direction direction) {
        return outputDirectSignal[direction.ordinal()];
    }

    /**
     * Call to update output signal.
     * also see {@link #getOutputSignal(Direction)} and
     * {@link #getOutputDirectSignal(Direction)}
     */
    public void updateSignal() {
        if (!getLevel().isClientSide) {
            notifyBlockUpdate();
        }
    }

    /**
     * On machine removed.
     */
    public void onMachineRemoved() {
        for (ITrait additionalTrait : additionalTraits) {
            additionalTrait.onMachineRemoved();
        }
        MinecraftForge.EVENT_BUS.post(new MachineRemovedEvent(this).postCustomEvent());
    }

    /**
     * Get the drop item when the machine is broken.
     */
    public ItemStack getDropItem() {
        var item = getDefinition().asStack();
        if (customName != null) {
            item.setHoverName(customName);
        }
        if (shouldKeepDropItemNbt()) {
            CompoundTag blockEntityTag = new CompoundTag();
            machineHolder.saveManagedPersistentData(blockEntityTag, true);
            if (!blockEntityTag.isEmpty()) {
                item.getOrCreateTag().put(BlockItem.BLOCK_ENTITY_TAG, blockEntityTag);
            }
        }
        return item;
    }

    /**
     * Whether the broken machine item should keep machine persisted NBT.
     *
     * <p>Only block-backed machines with a machine item drop can use this path.
     * Entity machines override {@link #getDropItem()} with an empty stack.</p>
     */
    public boolean shouldKeepDropItemNbt() {
        return getDefinition().machineSettings().dropMachineItem() &&
                getDefinition().machineSettings().keepDropItemNbt();
    }

    /**
     * On machine broken and drops items.
     */
    public void onDrops(Entity entity, List<ItemStack> drops) {
        if (getDefinition().machineSettings().dropMachineItem()) {
            var drop = getDropItem();
            if (!drop.isEmpty()) {
                drops.add(drop);
            }
        }
        for (ITrait trait : getAdditionalTraits()) {
            trait.onMachineDrop(entity, drops);
        }
        MinecraftForge.EVENT_BUS.post(new MachineDropsEvent(this, entity, drops).postCustomEvent());
    }

    /**
     * On hand is using on the machine.
     */
    public InteractionResult onUse(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        var event = new MachineRightClickEvent(this, player, hand, hit);
        event.setInteractionResult(InteractionResult.PASS);
        MinecraftForge.EVENT_BUS.post(event.postCustomEvent());
        return event.getInteractionResult();
    }

    /**
     * Should open UI.
     */
    public boolean shouldOpenUI(InteractionHand hand, BlockHitResult hit) {
        return getDefinition().machineSettings().hasUI();
    }

    /**
     * Try to open UI.
     */
    public InteractionResult openUI(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            var event = new MachineOpenUIEvent(this, player);
            MinecraftForge.EVENT_BUS.post(event.postCustomEvent());
            if (event.isCanceled()) {
                return InteractionResult.PASS;
            }
            MachineUIFactory.INSTANCE.openUI(this, serverPlayer);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    /**
     * Create Modular UI.
     */
    public ModularUI createUI(Player entityPlayer) {
        var ui = getDefinition().uiCreator().apply(this);
        var event = new MachineUIEvent(this, ui, entityPlayer);
        MinecraftForge.EVENT_BUS.post(event.postKubeJSEvent());
        ui = event.getRoot();
        if (ui == null) {
            return null;
        }
        return new ModularUI(ui, this, entityPlayer);
    }

    @Override
    public boolean isInvalid() {
        return isInValid();
    }

    @Override
    public boolean isRemote() {
        var level = getLevel();
        return level == null ? LDLib.isRemote() : level.isClientSide;
    }

    @Override
    public void markAsDirty() {
        this.markDirty();
    }

    /**
     * It's used to define a visible box for BlockEntityRenderer in the world.
     *
     * @return null, use the default bounding box based on the shape.
     */
    @Nullable
    public AABB getRenderBoundingBox() {
        var aabb = getMachineState().getRenderingBox(getFrontFacing().orElse(Direction.NORTH));
        if (aabb != null) {
            // offset the box to the block position
            aabb = aabb.move(getPos());
            return aabb;
        }
        // A state may use its collision/selection shape as the visual model
        // footprint. The vanilla block-entity fallback only covers one block,
        // which culls models extending outside the controller block.
        var shape = getShape(CollisionContext.empty());
        return shape.isEmpty() ? null : shape.bounds().move(getPos());
    }

    /**
     * Returns the currently playing machine state sound.
     * <p>
     * Client-only. The value is set by {@link #playStateSound(String)} and may be {@code null} when the active state has
     * no sound or no sound has been started.
     *
     * @return current sound handle, or {@code null}
     */
    @Nullable
    @OnlyIn(Dist.CLIENT)
    public MachineSound getCurrentSound() {
        return currentSound;
    }

    /**
     * Play the sound by the given state.
     */
    @OnlyIn(Dist.CLIENT)
    public void playStateSound(String state) {
        if (getDefinition().stateMachine().hasState(state)) {
            currentSound = definition.stateMachine().getState(state).createMachineSound(getPos(), () -> IMachine
                    .ofMachine(getLevel(), getPos())
                    .map(m -> m == this && ((MBDMachine) m).machineState.equals(state))
                    .orElse(false));
            if (currentSound != null) {
                currentSound.play();
            }
        }
    }

    /**
     * Triggers a Geckolib animation on the default controller.
     * <p>
     * Safe to call on either logical side. Server-side calls are forwarded to tracking clients by
     * {@link #triggerGeckolibAnim(String, String, float)}.
     *
     * @param animName triggerable animation name configured on the current Geckolib renderer
     * @param speed    playback speed; values below zero are clamped to zero on the client
     */
    public void triggerGeckolibAnim(String animName, float speed) {
        triggerGeckolibAnim("", animName, speed);
    }

    /**
     * Trigger the geckolib animation by name.
     * <br>
     * It's safe to call this method on both side.
     */
    @RPCMethod
    public void triggerGeckolibAnim(String controllerName, String animName, float speed) {
        if (MBD2.isGeckolibLoaded()) {
            if (isRemote()) {
                if (controllerName.isEmpty()) {
                    controllerName = "base_controller";
                }
                if (getMachineState().getRenderer() instanceof GeckolibRenderer renderer) {
                    var controller = renderer.getAnimatableFromMachine(this).getAnimatableInstanceCache()
                            .getManagerForId(0)
                            .getAnimationControllers()
                            .get(controllerName);
                    if (controller != null) {
                        controller.setAnimationSpeed(Math.max(speed, 0));
                        if (controller.tryTriggerAnimation(animName)) {
                            controller.forceAnimationReset();
                        }
                    }
                }
            } else {
                rpcToTracking("triggerGeckolibAnim", controllerName, animName, speed);
            }
        }
    }

    /**
     * Emit the photon fx.
     */
    @RPCMethod
    public void emitPhotonFx(String identifier, ResourceLocation fxLocation, Vector3f offset, Vector3f rotation, int delay, boolean forcedDeath, boolean replaceExisting) {
        if (MBD2.isPhotonLoaded()) {
            if (isRemote()) {
                var fx = FXHelper.getFX(fxLocation);
                if (fx != null) {
                    var machineFX = new MachineFX(fx, identifier, this);
                    machineFX.setOffset(offset.x, offset.y, offset.z);
                    machineFX.setRotation(rotation.x, rotation.y, rotation.z);
                    machineFX.setDelay(delay);
                    machineFX.setForcedDeath(forcedDeath);
                    machineFX.setReplaceExisting(replaceExisting);
                    machineFX.start();
                }
            } else {
                rpcToTracking("emitPhotonFx", identifier, fxLocation, offset, rotation, delay, forcedDeath, replaceExisting);
            }
        }
    }

    /**
     * Kill the photon fx.
     */
    @RPCMethod
    public void killPhotonFx(String identifier, boolean forcedDeath) {
        if (MBD2.isPhotonLoaded()) {
            if (isRemote()) {
                if (photonFXs.get(identifier) instanceof MachineFX machineFX) {
                    machineFX.kill(forcedDeath);
                    photonFXs.remove(identifier);
                }
            } else {
                rpcToTracking("killPhotonFx", identifier, forcedDeath);
            }
        }
    }
}
