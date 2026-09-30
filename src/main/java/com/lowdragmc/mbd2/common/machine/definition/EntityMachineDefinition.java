package com.lowdragmc.mbd2.common.machine.definition;

import com.lowdragmc.lowdraglib.client.renderer.IRenderer;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.IRendererResource;
import com.lowdragmc.mbd2.client.renderer.MBDItemRenderer;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.blockentity.IMachineBlockEntity;
import com.lowdragmc.mbd2.api.entity.IMachineEntity;
import com.lowdragmc.mbd2.client.renderer.EntityMachineRenderer;
import com.lowdragmc.mbd2.client.renderer.MBDBlockRenderer;
import com.lowdragmc.mbd2.common.entity.MBDEntityMachine;
import com.lowdragmc.mbd2.common.entity.RegisteredMBDLivingMachineEntity;
import com.lowdragmc.mbd2.common.entity.RegisteredMBDMachineEntity;
import com.lowdragmc.mbd2.common.item.EntityMachineItem;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.machine.definition.config.*;
import com.lowdragmc.mbd2.common.trait.TraitDefinition;
import com.lowdragmc.mbd2.utils.UIResourceRendererContext;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Deque;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Machine definition whose runtime is represented by a Minecraft entity rather
 * than a placed block entity.
 * <p>
 * Entity machine definitions still inherit most machine-definition behavior
 * from {@link MBDMachineDefinition}, including recipe logic, traits, states,
 * items, renderers, and editor serialization. At Forge registration time they
 * register an {@link EntityType} plus an item that can place/spawn the entity
 * machine. Entity-specific AI/event graphs are stored in
 * {@link ConfigEntityAISettings}.
 * <p>
 * Thread safety: definition registration and renderer initialization follow
 * Forge's normal mod-loading/client setup threads. Runtime entity access should
 * stay on the logical level thread that owns the entity.
 */
@LDLRegister(name = "entity_machine", group = "machine_definition")
public class EntityMachineDefinition extends MBDMachineDefinition {
    /**
     * Entity implementation family used for registration.
     */
    public enum EntityKind {
        /**
         * Basic non-living entity machine.
         */
        ENTITY,
        /**
         * LivingEntity-backed machine with registered living attributes.
         */
        LIVING
    }

    @Persisted
    @Configurable(name = "config.entity_machine.entity_kind", tips = "config.require_restart")
    private EntityKind entityKind = EntityKind.ENTITY;
    @Persisted
    @Configurable(name = "config.entity_machine.width", tips = "config.require_restart")
    @NumberRange(range = {0.01, Float.MAX_VALUE})
    private float entityWidth = 0.98f;
    @Persisted
    @Configurable(name = "config.entity_machine.height", tips = "config.require_restart")
    @NumberRange(range = {0.01, Float.MAX_VALUE})
    private float entityHeight = 0.98f;
    @Persisted
    @Configurable(name = "config.entity_machine.client_tracking_range", tips = "config.require_restart")
    @NumberRange(range = {1, Integer.MAX_VALUE})
    private int clientTrackingRange = 8;
    @Persisted
    @Configurable(name = "config.entity_machine.update_interval", tips = "config.require_restart")
    @NumberRange(range = {1, Integer.MAX_VALUE})
    private int updateInterval = 3;
    @Persisted(subPersisted = true)
    @Configurable(name = "config.entity_machine.model", subConfigurable = true, tips = "config.entity_machine.model.tooltip", collapse = false)
    private final EntityMachineModelSettings entityModelSettings;
    @Persisted(subPersisted = true)
    private final ConfigEntityAISettings entityAISettings = new ConfigEntityAISettings();

    private Predicate<TraitDefinition> entityTraitFilter = MBDEntityMachine::isDefaultEntitySafeTrait;
    private EntityType<?> entityType;
    private Item entityItem;
    @OnlyIn(Dist.CLIENT)
    private IRenderer entityBlockRenderer;
    @OnlyIn(Dist.CLIENT)
    private IRenderer entityItemRenderer;

    /**
     * Creates an entity machine definition.
     *
     * @param id                     registry id for the entity type and item
     * @param rootState              root state tree; default state tree is used by the base
     *                               definition when {@code null}
     * @param blockProperties        block/item renderer backing properties inherited
     *                               from regular machine definitions
     * @param itemProperties         item form configuration
     * @param machineSettingsFactory factory for general machine settings
     * @param recipeLogicSettings    recipe logic configuration
     */
    public EntityMachineDefinition(ResourceLocation id,
                                   @Nullable MachineState rootState,
                                   @Nullable ConfigBlockProperties blockProperties,
                                   @Nullable ConfigItemProperties itemProperties,
                                   @Nullable ConfigMachineSettingsFactory machineSettingsFactory,
                                   @Nullable ConfigRecipeLogicSettings recipeLogicSettings) {
        super(id, rootState, blockProperties, itemProperties, machineSettingsFactory, recipeLogicSettings, null);
        var defaultRenderer = stateMachine().getRootState().renderer().getValue();
        entityModelSettings = new EntityMachineModelSettings(defaultRenderer == null ? IRenderer.EMPTY : defaultRenderer);
    }

    /**
     * Creates the editor/default entity machine definition.
     *
     * @return default definition with dummy id and empty renderer
     */
    public static EntityMachineDefinition createDefault() {
        return new EntityMachineDefinition(
                MBD2.id("dummy"),
                StateMachine.createSingleDefault(MachineState::builder, IRenderer.EMPTY),
                ConfigBlockProperties.builder().rotationState(com.lowdragmc.mbd2.api.block.RotationState.NONE).build(),
                ConfigItemProperties.builder().build(),
                () -> ConfigMachineSettings.builder().build(),
                ConfigRecipeLogicSettings.builder().build());
    }

    /**
     * Creates a fluent builder for entity machine definitions.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean allowPartSettings() {
        return false;
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        super.buildConfigurator(father);
        father.getConfigurators().stream()
                .filter(configurator -> "config.definition.block_properties".equals(configurator.getName()))
                .findFirst()
                .ifPresent(father::removeConfigurator);
    }

    @Override
    public MBDMachineDefinition loadProductiveTag(@Nullable File file, net.minecraft.nbt.CompoundTag projectTag, Deque<Runnable> postTask) {
        super.loadProductiveTag(file, projectTag, postTask);
        var definitionTag = projectTag.getCompound("definition");
        if (definitionTag.contains("entityKind")) {
            try {
                entityKind = EntityKind.valueOf(definitionTag.getString("entityKind"));
            } catch (IllegalArgumentException ignored) {
                entityKind = EntityKind.ENTITY;
            }
        }
        if (definitionTag.contains("entityWidth")) {
            entityWidth = definitionTag.getFloat("entityWidth");
        }
        if (definitionTag.contains("entityHeight")) {
            entityHeight = definitionTag.getFloat("entityHeight");
        }
        if (definitionTag.contains("clientTrackingRange")) {
            clientTrackingRange = definitionTag.getInt("clientTrackingRange");
        }
        if (definitionTag.contains("updateInterval")) {
            updateInterval = definitionTag.getInt("updateInterval");
        }
        if (definitionTag.contains("entityModelSettings")) {
            var rendererResource = new IRendererResource();
            rendererResource.deserializeNBT(projectTag.getCompound("resources").getCompound(IRendererResource.RESOURCE_NAME));
            try (var ignored = UIResourceRendererContext.push(rendererResource, false)) {
                entityModelSettings.deserializeNBT(definitionTag.getCompound("entityModelSettings"));
            }
        } else if (entityModelSettings.getRenderer() == IRenderer.EMPTY) {
            var stateRenderer = stateMachine().getRootState().renderer();
            if (stateRenderer.isEnable() && stateRenderer.getValue() != null) {
                entityModelSettings.renderer().setEnable(true);
                entityModelSettings.renderer().setValue(stateRenderer.getValue());
            }
        }
        if (definitionTag.contains("entityAISettings")) {
            entityAISettings.deserializeNBT(definitionTag.getCompound("entityAISettings"));
        }
        return this;
    }

    @Override
    public void onRegistry(RegisterEvent event) {
        event.register(ForgeRegistries.ENTITY_TYPES.getRegistryKey(), helper -> helper.register(id(), entityType = createEntityType()));
        event.register(ForgeRegistries.ITEMS.getRegistryKey(), helper -> helper.register(id(), entityItem = createItem()));
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initRenderer(EntityRenderersEvent.RegisterRenderers event) {
        entityBlockRenderer = createBlockRenderer();
        entityItemRenderer = createItemRenderer();
        if (entityType != null) {
            event.registerEntityRenderer((EntityType<Entity>) entityType, EntityMachineRenderer::new);
        }
    }

    /**
     * Registers living attributes when this definition uses
     * {@link EntityKind#LIVING}.
     *
     * @param event Forge attribute creation event
     */
    public void registerEntityAttributes(EntityAttributeCreationEvent event) {
        if (entityKind == EntityKind.LIVING && entityType != null) {
            event.put((EntityType<? extends LivingEntity>) entityType, LivingEntity.createLivingAttributes().build());
        }
    }

    /**
     * Creates the entity type configured by this definition.
     * <p>
     * Width, height, tracking range, and update interval are clamped to valid
     * positive values before building the type.
     *
     * @return new entity type instance
     */
    public EntityType<?> createEntityType() {
        var width = Math.max(0.01f, entityWidth);
        var height = Math.max(0.01f, entityHeight);
        var trackingRange = Math.max(1, clientTrackingRange);
        var interval = Math.max(1, updateInterval);
        if (entityKind == EntityKind.LIVING) {
            return EntityType.Builder.<RegisteredMBDLivingMachineEntity>of(
                            (type, level) -> new RegisteredMBDLivingMachineEntity(type, level, this), MobCategory.MISC)
                    .sized(width, height)
                    .clientTrackingRange(trackingRange)
                    .updateInterval(interval)
                    .build(id().toString());
        }
        return EntityType.Builder.<RegisteredMBDMachineEntity>of(
                        (type, level) -> new RegisteredMBDMachineEntity(type, level, this), MobCategory.MISC)
                .sized(width, height)
                .clientTrackingRange(trackingRange)
                .updateInterval(interval)
                .build(id().toString());
    }

    /**
     * Creates an unregistered preview entity for editor/client rendering.
     *
     * @param level level used by the preview entity
     * @return preview entity matching {@link #entityKind}
     */
    public Entity createPreviewEntity(Level level) {
        if (entityKind == EntityKind.LIVING) {
            return new RegisteredMBDLivingMachineEntity(EntityType.ARMOR_STAND, level, this);
        }
        return new RegisteredMBDMachineEntity(EntityType.MARKER, level, this);
    }

    /**
     * Creates the item form used to place/spawn this entity machine.
     *
     * @return new item instance configured with {@link #itemProperties()}
     */
    public Item createItem() {
        return new EntityMachineItem(this, itemProperties().apply(new Item.Properties()));
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public IRenderer createBlockRenderer() {
        return new MBDBlockRenderer(blockProperties::useAO, this::entityRenderer);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public IRenderer createItemRenderer() {
        return new MBDItemRenderer(itemProperties()::useBlockLight, itemProperties()::isGui3d,
                () -> entityModelSettings.getItemRenderer(entityModelSettings.getRenderer()));
    }

    @Override
    public Item item() {
        return entityItem;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public IRenderer blockRenderer() {
        return entityBlockRenderer;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public IRenderer itemRenderer() {
        return entityItemRenderer;
    }

    /**
     * Returns the entity type assigned during registration.
     *
     * @return registered entity type, or {@code null} before Forge registration or lazy creation completes
     */
    public EntityType<?> entityType() {
        return entityType;
    }

    /**
     * Returns the registered entity type, creating a standalone type when
     * registration has not populated it yet.
     *
     * @return entity type for this definition
     */
    public EntityType<?> getOrCreateEntityType() {
        if (entityType == null) {
            entityType = createEntityType();
        }
        return entityType;
    }

    /**
     * Returns the base behavior category used when constructing the entity type.
     *
     * @return entity kind controlling vanilla base entity behavior
     */
    public EntityKind entityKind() {
        return entityKind;
    }

    /**
     * Returns the configured entity hitbox width.
     *
     * @return width in blocks; expected to be positive
     */
    public float entityWidth() {
        return entityWidth;
    }

    /**
     * Returns the configured entity hitbox height.
     *
     * @return height in blocks; expected to be positive
     */
    public float entityHeight() {
        return entityHeight;
    }

    /**
     * Returns the mutable model and renderer settings for this entity machine.
     *
     * @return entity model settings owned by this definition
     */
    public EntityMachineModelSettings entityModelSettings() {
        return entityModelSettings;
    }

    /**
     * Returns the mutable AI settings used by spawned entity machines.
     *
     * @return entity AI settings owned by this definition
     */
    public ConfigEntityAISettings entityAISettings() {
        return entityAISettings;
    }

    /**
     * Returns the renderer used by the entity machine in world.
     *
     * @return renderer resolved from entity model settings or root state
     */
    @OnlyIn(Dist.CLIENT)
    public IRenderer entityRenderer() {
        return getEntityStateBlockRenderer(stateMachine().getRootState());
    }

    /**
     * Returns the renderer for a specific entity state.
     *
     * @param state       machine state being rendered
     * @param frontFacing front direction; currently ignored by entity renderers
     * @return renderer for the state/entity
     */
    @OnlyIn(Dist.CLIENT)
    public IRenderer getEntityStateRenderer(MachineState state, Direction frontFacing) {
        return getEntityStateBlockRenderer(state);
    }

    /**
     * Resolves the block-style renderer used for an entity state.
     * <p>
     * Non-root state renderers override the entity renderer; otherwise the
     * entity model renderer is used before falling back to the state's renderer.
     *
     * @param state state to render
     * @return renderer for the state
     */
    @OnlyIn(Dist.CLIENT)
    public IRenderer getEntityStateBlockRenderer(MachineState state) {
        var stateRenderer = findExplicitEntityStateRenderer(state);
        if (stateRenderer != null) {
            return stateRenderer;
        }
        var entityRenderer = entityModelSettings.getRenderer();
        if (entityRenderer != IRenderer.EMPTY) {
            return entityRenderer;
        }
        return state.getRenderer();
    }

    @OnlyIn(Dist.CLIENT)
    @Nullable
    private IRenderer findExplicitEntityStateRenderer(@Nullable MachineState state) {
        var current = state;
        while (current != null) {
            if (!current.isRoot()) {
                var renderer = current.renderer();
                if (renderer.isEnable() && renderer.getValue() != null) {
                    return renderer.getValue();
                }
            }
            current = current.parent();
        }
        return null;
    }

    @Override
    public MBDEntityMachine createMachine(IMachineBlockEntity blockEntity) {
        return new MBDEntityMachine(blockEntity, this);
    }

    /**
     * Returns this definition's machine runtime for an entity.
     *
     * @param entity entity to inspect
     * @return machine runtime when the entity is an MBD entity machine using
     * this definition; otherwise {@code null}
     */
    @Nullable
    public MBDEntityMachine getMachine(Entity entity) {
        if (entity instanceof IMachineEntity machineEntity && machineEntity.getMetaMachine() instanceof MBDEntityMachine machine) {
            return machine.getDefinition() == this ? machine : null;
        }
        return null;
    }

    @Override
    public String getDescriptionId() {
        return id().toLanguageKey("entity");
    }

    @Override
    public ItemStack asStack(int count) {
        return new ItemStack(item(), count);
    }

    @Override
    protected Component getMachineName(MBDMachine machine) {
        String translationKey = id().toLanguageKey("machine");
        return getDisplayName(Component.translatable(translationKey), translationKey);
    }

    /**
     * Checks whether a trait definition is safe/supported for entity machines.
     *
     * @param traitDefinition trait definition to test
     * @return {@code true} when the current entity trait filter accepts it
     */
    public boolean isTraitSupportedOnEntity(TraitDefinition traitDefinition) {
        return entityTraitFilter.test(traitDefinition);
    }

    /**
     * Sets the filter that decides which trait definitions are allowed on this
     * entity machine definition.
     *
     * @param entityTraitFilter predicate to use; {@code null} rejects all traits
     * @return this definition
     */
    public EntityMachineDefinition entityTraitFilter(Predicate<TraitDefinition> entityTraitFilter) {
        this.entityTraitFilter = entityTraitFilter == null ? trait -> false : entityTraitFilter;
        return this;
    }

    /**
     * Reads the entity's front-facing direction.
     *
     * @param entity entity machine instance
     * @return current direction when available
     */
    public Optional<Direction> getEntityFrontFacing(Entity entity) {
        return Optional.ofNullable(entity.getDirection());
    }

    /**
     * Updates the entity yaw/head yaw to match a front-facing direction.
     *
     * @param entity entity machine instance
     * @param facing desired front direction
     */
    public void setEntityFrontFacing(Entity entity, Direction facing) {
        entity.setYRot(facing.toYRot());
        entity.setYHeadRot(facing.toYRot());
    }

    /**
     * Fluent builder for entity machine definitions.
     */
    @Setter
    @Accessors(chain = true, fluent = true)
    public static class Builder extends MBDMachineDefinition.Builder {
        @Override public Builder xeiCatalystGroup(String group) { super.xeiCatalystGroup(group); return this; }
        @Override public Builder xeiCatalystPriority(int priority) { super.xeiCatalystPriority(priority); return this; }
        @Override public Builder xeiPartTooltip(String text) { super.xeiPartTooltip(text); return this; }
        @Override public Builder xeiPartTooltipKey(String key) { super.xeiPartTooltipKey(key); return this; }
        @Override public Builder xeiPartTooltips(java.util.List<net.minecraft.network.chat.Component> lines) { super.xeiPartTooltips(lines); return this; }
        private Predicate<TraitDefinition> entityTraitFilter = MBDEntityMachine::isDefaultEntitySafeTrait;
        private EntityKind entityKind = EntityKind.ENTITY;
        private float entityWidth = 0.98f;
        private float entityHeight = 0.98f;
        private int clientTrackingRange = 8;
        private int updateInterval = 3;

        /**
         * Creates a builder with entity-safe defaults.
         * <p>
         * Defaults build a standard entity-sized machine with default entity-safe traits, tracking range {@code 8}, and
         * update interval {@code 3}. Use {@link EntityMachineDefinition#builder()} to obtain instances.
         */
        protected Builder() {
        }

        /**
         * Sets the filter that decides which trait definitions are allowed on
         * entity machine definitions built by this builder.
         *
         * @param entityTraitFilter predicate to use; {@code null} rejects all
         *                          traits
         * @return this builder
         */
        public Builder entityTraitFilter(Predicate<TraitDefinition> entityTraitFilter) {
            this.entityTraitFilter = entityTraitFilter == null ? trait -> false : entityTraitFilter;
            return this;
        }

        @Override
        public Builder setMaxParallel(int maxParallel) {
            super.setMaxParallel(maxParallel);
            return this;
        }

        @Override
        public Builder maxParallel(int maxParallel) {
            super.maxParallel(maxParallel);
            return this;
        }

        /**
         * Builds the entity machine definition.
         *
         * @return new entity machine definition
         */
        public EntityMachineDefinition build() {
            if (hasXeiPartTooltips()) throw new IllegalStateException("Entity machine " + id + " does not support part settings");
            var definition = new EntityMachineDefinition(id, rootState, blockProperties, itemProperties, machineSettings, recipeLogicSettings)
                    .entityTraitFilter(entityTraitFilter);
            definition.entityKind = entityKind == null ? EntityKind.ENTITY : entityKind;
            definition.entityWidth = entityWidth;
            definition.entityHeight = entityHeight;
            definition.clientTrackingRange = clientTrackingRange;
            definition.updateInterval = updateInterval;
            return definition;
        }
    }
}
