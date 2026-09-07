package com.lowdragmc.mbd2.common.machine.definition;

import com.google.common.collect.Queues;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.client.renderer.IRenderer;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurableWidget;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.IRendererResource;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.TexturesResource;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.jei.JEIPlugin;
import com.lowdragmc.lowdraglib.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.blockentity.IMachineBlockEntity;
import com.lowdragmc.mbd2.api.recipe.MBDRecipeType;
import com.lowdragmc.mbd2.client.renderer.MBDBESRenderer;
import com.lowdragmc.mbd2.client.renderer.MBDBlockRenderer;
import com.lowdragmc.mbd2.client.renderer.MBDItemRenderer;
import com.lowdragmc.mbd2.common.block.MBDMachineBlock;
import com.lowdragmc.mbd2.common.blockentity.MachineBlockEntity;
import com.lowdragmc.mbd2.common.item.MBDMachineItem;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.machine.MBDPartMachine;
import com.lowdragmc.mbd2.common.machine.definition.config.*;
import com.lowdragmc.mbd2.common.trait.ITrait;
import com.lowdragmc.mbd2.common.trait.IUIProviderTrait;
import com.lowdragmc.mbd2.integration.emi.MBDRecipeTypeEmiCategory;
import com.lowdragmc.mbd2.integration.jei.MBDRecipeTypeCategory;
import com.lowdragmc.mbd2.integration.rei.MBDRecipeTypeDisplayCategory;
import com.lowdragmc.mbd2.utils.UIResourceRendererContext;
import com.lowdragmc.mbd2.utils.WidgetUtils;
import dev.emi.emi.api.EmiApi;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Machine definition.
 * <br>
 * This is used to define a mbd machine's {@link MBDMachine#getDefinition()} behaviours.
 */
@Getter
@Accessors(fluent = true)
@LDLRegister(name = "single_machine", group = "machine_definition")
public class MBDMachineDefinition implements IConfigurable, IPersistedSerializable {
    /**
     * Factory for per-definition machine settings.
     *
     * <p>The supplier is stored during definition construction and invoked from
     * {@link #loadFactory()} after registry setup has completed. Implementations should
     * return a fresh mutable {@link ConfigMachineSettings} instance for this definition.</p>
     */
    @FunctionalInterface
    public interface ConfigMachineSettingsFactory extends Supplier<ConfigMachineSettings> {
    }

    /**
     * Factory for optional proxy-part settings.
     *
     * <p>The supplier is only used when {@link #allowPartSettings()} is {@code true}; it is
     * invoked from {@link #loadFactory()} and should return a fresh mutable
     * {@link ConfigPartSettings} instance for this definition.</p>
     */
    @FunctionalInterface
    public interface ConfigPartSettingsFactory extends Supplier<ConfigPartSettings> {
    }

    /**
     * Definition context used while Forge constructs the block instance for this definition.
     * <p>
     * The value is thread-local because Forge registration can call the block constructor through registry helpers. It
     * must be set immediately before block creation and cleared in the same registration callback.
     */
    static final ThreadLocal<MBDMachineDefinition> STATE = new ThreadLocal<>();

    /**
     * Returns the definition currently being used to construct a machine block.
     * <p>
     * Preconditions: call only from code running inside a registration block guarded by {@link #set(MBDMachineDefinition)}
     * and {@link #clear()}. Outside that window this returns {@code null}. Thread safety is provided only by
     * {@link ThreadLocal}; definitions themselves are not synchronized.
     *
     * @return current block-construction definition for this thread, or {@code null} when no definition is being
     * registered
     */
    public static MBDMachineDefinition get() {
        return STATE.get();
    }

    /**
     * Sets the definition context for the current registration thread.
     * <p>
     * Side effect: subsequent block-construction code on this thread can obtain {@code state} through {@link #get()}.
     * Always pair this with {@link #clear()} in the same logical registration operation.
     *
     * @param state definition whose block is about to be created; must not be {@code null}
     */
    public static void set(MBDMachineDefinition state) {
        STATE.set(state);
    }

    /**
     * Clears the current thread's block-construction definition context.
     * <p>
     * Side effect: removes the thread-local value so later registrations or runtime code cannot observe a stale
     * definition.
     */
    public static void clear() {
        STATE.remove();
    }

    @Configurable(tips = {"config.definition.id.tooltip", "config.require_restart"}, forceUpdate = false)
    private ResourceLocation id;
    @Persisted
    @Configurable(name = "config.definition.display_name", tips = "config.definition.display_name.tooltip")
    private String displayName = "";
    protected final StateMachine<?> stateMachine;
    @Configurable(name = "config.definition.block_properties", subConfigurable = true, tips = "config.definition.block_properties.tooltip", collapse = false)
    protected final ConfigBlockProperties blockProperties;
    @Configurable(name = "config.definition.item_properties", subConfigurable = true, tips = "config.definition.item_properties.tooltip", collapse = false)
    protected final ConfigItemProperties itemProperties;
    @Configurable(name = "config.definition.machine_settings", subConfigurable = true, tips = "config.definition.machine_settings.tooltip", collapse = false)
    protected ConfigMachineSettings machineSettings;
    @Configurable(name = "config.definition.recipe_logic_settings", subConfigurable = true, tips = {
            "config.definition.recipe_logic_settings.tooltip.0",
            "config.definition.recipe_logic_settings.tooltip.1"
    }, collapse = false)
    protected ConfigRecipeLogicSettings recipeLogicSettings;
    @Nullable
    @Configurable(name = "config.definition.part_settings", subConfigurable = true, tips = {
            "config.definition.part_settings.tooltip.0",
            "config.definition.part_settings.tooltip.1",
            "config.definition.part_settings.tooltip.2",
    })
    protected ConfigPartSettings partSettings;
    @Persisted(subPersisted = true)
    protected final ConfigMachineEvents machineEvents;

    // runtime
    protected ConfigMachineSettingsFactory machineSettingsFactory;
    @Nullable
    protected ConfigPartSettingsFactory partSettingsFactory;

    @Nullable
    private File projectFile;
    private Block block;
    private Item item;
    private BlockEntityType<?> blockEntityType;
    private IRenderer blockRenderer;
    private IRenderer itemRenderer;
    private Function<MBDMachine, WidgetGroup> uiCreator;

    /**
     * Creates a machine definition from builder or project data.
     * <p>
     * The constructor wires static configuration and stores factories that are loaded later by {@link #loadFactory()}.
     * It does not register blocks, items, block entities, or client renderers. Missing optional arguments are replaced
     * with default settings objects.
     *
     * @param id                     registry id for the machine definition; when {@code null}, a placeholder id is used until project data
     *                               overwrites it
     * @param rootState              root visual/shape state for the state machine; when {@code null}, {@link #createDefaultRootState()}
     *                               is used
     * @param blockProperties        block behavior settings; when {@code null}, default block properties are built
     * @param itemProperties         item settings; when {@code null}, default item properties are built
     * @param machineSettingsFactory factory that creates runtime machine settings during {@link #loadFactory()}; when
     *                               {@code null}, default machine settings are built
     * @param recipeLogicSettings    recipe-processing settings; when {@code null}, default recipe logic settings are built
     * @param partSettingsFactory    optional proxy-part settings factory; ignored when {@link #allowPartSettings()} returns
     *                               {@code false}
     */
    protected MBDMachineDefinition(ResourceLocation id,
                                   @Nullable MachineState rootState,
                                   @Nullable ConfigBlockProperties blockProperties,
                                   @Nullable ConfigItemProperties itemProperties,
                                   @Nullable ConfigMachineSettingsFactory machineSettingsFactory,
                                   @Nullable ConfigRecipeLogicSettings recipeLogicSettings,
                                   @Nullable ConfigPartSettingsFactory partSettingsFactory) {
        this.id = id == null ? MBD2.id("undefined") : id;
        this.stateMachine = new StateMachine<>(rootState == null ? createDefaultRootState() : rootState);
        this.blockProperties = blockProperties == null ? ConfigBlockProperties.builder().build() : blockProperties;
        this.itemProperties = itemProperties == null ? ConfigItemProperties.builder().build() : itemProperties;
        this.machineSettingsFactory = machineSettingsFactory == null ? () -> ConfigMachineSettings.builder().build() : machineSettingsFactory;
        this.recipeLogicSettings = recipeLogicSettings == null ? ConfigRecipeLogicSettings.builder().build() : recipeLogicSettings;
        this.partSettingsFactory = allowPartSettings() ? (partSettingsFactory == null ? () -> ConfigPartSettings.builder().build() : partSettingsFactory) : null;
        this.machineEvents = createMachineEvents();
    }

    /**
     * Indicates whether this definition can create proxy part settings.
     * <p>
     * Single-block machines allow part settings so they can act as proxy parts for multiblocks. Definitions that are
     * themselves controllers, such as multiblocks, should return {@code false}.
     *
     * @return {@code true} when {@link ConfigPartSettings} should be created and loaded for this definition
     */
    public boolean allowPartSettings() {
        return true;
    }

    /**
     * Creates the machine event configuration container for this definition.
     * <p>
     * Subclasses can extend the registered event groups before project data is loaded. The returned object becomes the
     * persisted {@link #machineEvents} instance for this definition.
     *
     * @return fresh event configuration with the base machine event group registered
     */
    public ConfigMachineEvents createMachineEvents() {
        return new ConfigMachineEvents().registerEventGroup("MachineEvent");
    }

    /**
     * Creates the default root state used when no explicit root state is supplied.
     * <p>
     * The default state is intentionally minimal and is suitable for editor/project bootstrap before a real renderer and
     * shape are configured.
     *
     * @return root machine state for a new definition state machine
     */
    public MachineState createDefaultRootState() {
        return StateMachine.createDefault(MachineState::builder);
    }

    /**
     * Loads settings produced by definition factories.
     * <p>
     * Preconditions: call after machine definition, trait, and recipe capability registries have been populated so
     * factory-created settings can resolve registered references. Side effects: assigns {@link #machineSettings} and,
     * when part settings are supported, {@link #partSettings}. This should run on the mod-loading thread before runtime
     * machine instances are created.
     */
    public void loadFactory() {
        machineSettings = machineSettingsFactory.get();
        if (partSettingsFactory != null) partSettings = partSettingsFactory.get();
    }

    /**
     * Starts a builder for a single-block machine definition.
     *
     * @return mutable builder with no fields set
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a placeholder single-block machine definition with default settings.
     * <p>
     * This is used by editor/bootstrap paths that need a structurally valid definition before project-specific data is
     * loaded. The returned definition is not registered automatically.
     *
     * @return default unregistered machine definition using the {@code mbd2:dummy} id
     */
    public static MBDMachineDefinition createDefault() {
        return new MBDMachineDefinition(
                MBD2.id("dummy"),
                StateMachine.createDefault(MachineState::builder),
                ConfigBlockProperties.builder().build(),
                ConfigItemProperties.builder().build(),
                () -> ConfigMachineSettings.builder().build(),
                ConfigRecipeLogicSettings.builder().build(),
                () -> ConfigPartSettings.builder().build());
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = IPersistedSerializable.super.serializeNBT();
        tag.put("stateMachine", stateMachine.serializeNBT());
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        IPersistedSerializable.super.deserializeNBT(tag);
        stateMachine.deserializeNBT(tag.getCompound("stateMachine"));
        if (!tag.contains("recipeLogicSettings")) {
            // compatible with old project
            recipeLogicSettings.deserializeNBT(tag.getCompound("machineSettings"));
        }
    }

    /**
     * Loads definition data from a persisted project tag for production/runtime use.
     * <p>
     * Block properties, item properties, and the state machine are loaded immediately because they are needed during
     * block and item registration. Settings that depend on registries, resources, traits, or UI templates are deferred
     * into {@code postTask}; callers must run those tasks after mod registries and project resources are available.
     * <p>
     * Side effects: updates this definition id and persisted configuration, stores {@code file} for future reloads, and
     * may install a UI factory that binds project widgets to runtime machine data. This method is not thread-safe and is
     * intended for startup/editor reload flows.
     *
     * @param file       project file used as the reload source, or {@code null} for in-memory project data
     * @param projectTag root project NBT containing {@code definition}, {@code resources}, and optional {@code ui} data
     * @param postTask   queue that receives deferred loading work; callers must execute it after registry/resource loading
     * @return this definition after immediate data has been loaded
     */
    public MBDMachineDefinition loadProductiveTag(@Nullable File file, CompoundTag projectTag, Deque<Runnable> postTask) {
        this.projectFile = file;
        var rendererResource = new IRendererResource();
        rendererResource.deserializeNBT(projectTag.getCompound("resources").getCompound(IRendererResource.RESOURCE_NAME));
        var definitionTag = projectTag.getCompound("definition");
        try (var ignored = UIResourceRendererContext.push(rendererResource, false)) {
            var parsedId = ResourceLocation.tryParse(definitionTag.getString("id"));
            id = parsedId == null ? MBD2.id("undefined") : parsedId;
            // The editor stores the configured fallback name in the definition
            // tag. Keep this on the definition before runtime/UI instances are
            // created; otherwise productive loads silently revert to the empty
            // default even though the project file contains a name.
            if (definitionTag.contains("displayName")) {
                displayName = definitionTag.getString("displayName");
            }
            blockProperties.deserializeNBT(definitionTag.getCompound("blockProperties"));
            itemProperties.deserializeNBT(definitionTag.getCompound("itemProperties"));
            stateMachine.deserializeNBT(definitionTag.getCompound("stateMachine"));
        }
        postTask.add(() -> {
            machineSettings.deserializeNBT(definitionTag.getCompound("machineSettings"));
            if (definitionTag.contains("recipeLogicSettings")) {
                recipeLogicSettings.deserializeNBT(definitionTag.getCompound("recipeLogicSettings"));
            } else {
                // compatible with old project
                var tag = definitionTag.getCompound("machineSettings");
                recipeLogicSettings.deserializeNBT(tag);
                recipeLogicSettings.setEnable(tag.getBoolean("hasRecipeLogic"));
            }
            if (partSettings != null) {
                partSettings.deserializeNBT(definitionTag.getCompound("partSettings"));
            }
            machineEvents.deserializeNBT(definitionTag.getCompound("machineEvents"));
            if (machineSettings().hasUI()) {
                var texturesResource = new TexturesResource();
                texturesResource.deserializeNBT(projectTag.getCompound("resources").getCompound(TexturesResource.RESOURCE_NAME));
                var uiTag = projectTag.getCompound("ui");
                uiCreator = machine -> {
                    var machineUI = new WidgetGroup();
                    IConfigurableWidget.deserializeNBT(machineUI, uiTag, texturesResource, false);
                    bindMachineUI(machine, machineUI);
                    return machineUI;
                };
            }
        });
        return this;
    }

    /**
     * Indicates whether this definition was loaded from a project file.
     *
     * @return {@code true} when {@link #loadProductiveTag(File, CompoundTag, Deque)} was given a non-null file
     */
    public boolean isCreatedFromProjectFile() {
        return projectFile != null;
    }

    /**
     * Reloads editable definition data from the remembered project file.
     * <p>
     * Preconditions: this definition must have been loaded from a project file. Side effects mirror
     * {@link #loadProductiveTag(File, CompoundTag, Deque)} and immediately run deferred tasks. Registry-bound objects
     * such as the already registered block, item, and block entity type are not recreated, so changes to those properties
     * require a full restart/re-registration.
     */
    public void reloadFromProjectFile() {
        if (projectFile != null) {
            try {
                var tag = NbtIo.read(projectFile);
                if (tag != null) {
                    Deque<Runnable> postTask = Queues.newArrayDeque();
                    loadProductiveTag(projectFile, tag, postTask);
                    postTask.forEach(Runnable::run);
                }
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Binds project-authored UI widgets to a runtime machine instance.
     * <p>
     * The method attaches suppliers/callbacks for standard ids such as {@code ui:machine_name}, progress/fuel bars, XEI
     * lookup buttons, trait UI widgets, and proxy-controller trait views. Side effects mutate the supplied widget tree.
     * Call on the UI/client setup path after the machine's traits and recipe logic are available.
     *
     * @param machine machine whose runtime state is displayed by the UI
     * @param ui      root widget group loaded from the project template
     */
    protected void bindMachineUI(MBDMachine machine, WidgetGroup ui) {
        WidgetUtils.widgetByIdForEach(ui, "^ui:machine_name$", TextTextureWidget.class,
                nameWidget -> nameWidget.setText(() -> {
                    if (machine.getCustomName() == null) return machine.getDefinition().getMachineName(machine);
                    return machine.getCustomName();
                }));
        WidgetUtils.widgetByIdForEach(ui, "^ui:progress_bar$", ProgressWidget.class,
                progressWidget -> progressWidget.setProgressSupplier(() -> machine.getRecipeLogic().getProgressPercent()));
        WidgetUtils.widgetByIdForEach(ui, "^ui:fuel_bar$", ProgressWidget.class,
                progressWidget -> progressWidget.setProgressSupplier(() -> machine.getRecipeLogic().getFuelProgressPercent()));
        WidgetUtils.widgetByIdForEach(ui, "^ui:xei_lookup$", ButtonWidget.class,
                buttonWidget -> buttonWidget.setOnPressCallback(cd -> {
                    if (cd.isRemote && (LDLib.isReiLoaded() || LDLib.isJeiLoaded() || LDLib.isEmiLoaded()) && Editor.INSTANCE == null) {
                        var recipeTypes = machine.getRecipeTypes().stream()
                                .filter(recipeType -> recipeType != MBDRecipeType.DUMMY && recipeType.isXEIVisible())
                                .toList();
                        if (!recipeTypes.isEmpty()) {
                            if (LDLib.isReiLoaded()) {
                                var builder = ViewSearchBuilder.builder();
                                recipeTypes.forEach(recipeType -> builder.addCategory(MBDRecipeTypeDisplayCategory.CATEGORIES.apply(recipeType)));
                                builder.open();
                            } else if (LDLib.isJeiLoaded()) {
                                JEIPlugin.jeiRuntime.getRecipesGui().showTypes(recipeTypes.stream()
                                        .map(MBDRecipeTypeCategory.TYPES)
                                        .<RecipeType<?>>map(recipeType -> recipeType)
                                        .toList());
                            } else if (LDLib.isEmiLoaded()) {
                                recipeTypes.forEach(recipeType -> EmiApi.displayRecipeCategory(MBDRecipeTypeEmiCategory.CATEGORIES.apply(recipeType)));
                            }
                        }
                    }
                }));
        bindLoadedTraitUIs(machine.getAdditionalTraits(), ui);
        // proxy controller ui
        if (partSettings != null && partSettings.isEnable() && machine instanceof MBDPartMachine partMachine) {
            var prefix = "controller:";
            var midTag = "@ui:";
            for (Widget widget : ui.getWidgetsById("controller:.*?@ui:")) {
                var id = widget.getId();
                if (id.startsWith(prefix)) {
                    int atIndex = id.indexOf(midTag);
                    if (atIndex != -1) {
                        var traitName = id.substring(prefix.length(), atIndex);
                        var uiName = "ui:" + id.substring(atIndex + midTag.length());
                        for (var controller : partMachine.getControllers()) {
                            if (controller instanceof MBDMachine mbdMachine) {
                                var trait = mbdMachine.getTraitByName(traitName);
                                if (trait != null && trait.getDefinition() instanceof IUIProviderTrait provider && uiName.startsWith(provider.uiPrefixName())) {
                                    widget.setId(uiName);
                                    provider.initTraitUI(trait, ui);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Binds the UI providers owned by the machine's live trait instances.
     *
     * <p>Definition reloads replace the configured definition objects while existing machines retain their already
     * constructed traits. Binding from the live traits keeps an openable UI attached to those traits until the machine
     * itself is rebuilt.</p>
     *
     * @param traits live traits attached to the machine
     * @param ui     instantiated widget tree to bind
     */
    static void bindLoadedTraitUIs(Iterable<? extends ITrait> traits, WidgetGroup ui) {
        for (ITrait trait : traits) {
            if (trait.getDefinition() instanceof IUIProviderTrait provider) {
                provider.initTraitUI(trait, ui);
            }
        }
    }

    /**
     * Resolves the display name shown by machine UI templates.
     * <p>
     * Subclasses may override this to use entity names, controller-specific names, or other runtime data. The default
     * name is taken from the registered block.
     *
     * @param machine runtime machine requesting its display name
     * @return localized component used when the machine has no custom name
     */
    protected Component getMachineName(MBDMachine machine) {
        return getDisplayName(block().getName(), block().getDescriptionId());
    }

    /**
     * Resolves this definition's name from its normal localized component.
     *
     * <p>Machine UI templates and machine item stacks both use this method, so a configured display name is consistently
     * used as the fallback wherever Minecraft requests the machine's name.</p>
     *
     * @param localizedName normal machine name component
     * @param translationKey translation key used to resolve the component
     * @return localized name, or the configured fallback
     */
    public Component getDisplayName(Component localizedName, String translationKey) {
        return resolveDisplayNameFallback(localizedName, translationKey, displayName);
    }

    /**
     * Resolves a configured display name without requiring a machine definition instance.
     *
     * <p>{@link Component#translatableWithFallback(String, String, Object...)} performs the missing-translation
     * check where translation data is actually available. Comparing {@link Component#getString()} with the key is
     * unreliable because component rendering can preserve or transform an unresolved translation key.</p>
     *
     * @param localizedName normal machine name component
     * @param translationKey translation key used to resolve the component
     * @param configuredName editor-provided fallback name
     * @return localized name, or the configured fallback when the translation key is absent
     */
    static Component resolveDisplayNameFallback(Component localizedName, String translationKey, String configuredName) {
        if (configuredName != null && !configuredName.isBlank()) {
            return Component.translatableWithFallback(translationKey, configuredName);
        }
        return localizedName;
    }

    /**
     * Registers the Forge block, item, and block entity type for this definition.
     * <p>
     * Side effects: writes {@link #block}, {@link #item}, and {@link #blockEntityType}. This must run during the Forge
     * registration event for the matching registries. The block registration temporarily sets the thread-local definition
     * context used by block construction.
     *
     * @param event Forge registration event dispatching block, item, and block entity type registry helpers
     */
    public void onRegistry(RegisterEvent event) {
        event.register(ForgeRegistries.BLOCKS.getRegistryKey(), helper -> {
            MBDMachineDefinition.set(this);
            helper.register(id, block = createBlock());
            MBDMachineDefinition.clear();
        });
        event.register(ForgeRegistries.ITEMS.getRegistryKey(), helper -> helper.register(id, item = createItem(block)));
        event.register(ForgeRegistries.BLOCK_ENTITY_TYPES.getRegistryKey(), helper ->
                helper.register(id, blockEntityType = BlockEntityType.Builder.of(this::createBlockEntity, block).build(null)));
    }

    /**
     * Creates the Forge block instance for this machine definition.
     * <p>
     * Preconditions: call during block registration before runtime machines exist. Side effects are limited to the new
     * block object; the caller stores it in {@link #block}.
     *
     * @return unregistered machine block configured with this definition's block properties and state machine
     */
    public Block createBlock() {
        return new MBDMachineBlock(blockProperties.apply(stateMachine, BlockBehaviour.Properties.of()), this);
    }

    /**
     * Creates the Forge item instance that represents this machine block.
     *
     * @param block block instance produced by {@link #createBlock()}; must be an {@link MBDMachineBlock}
     * @return unregistered block item configured with this definition's item properties
     * @throws ClassCastException when {@code block} is not an {@link MBDMachineBlock}
     */
    public Item createItem(Block block) {
        return new MBDMachineItem((MBDMachineBlock) block, itemProperties.apply(new Item.Properties()));
    }

    /**
     * Creates a block entity for a placed machine block.
     * <p>
     * The returned entity uses {@link #createMachine(IMachineBlockEntity)} as its runtime machine factory.
     *
     * @param pos   world position of the block entity
     * @param state placed block state
     * @return block entity bound to this definition's registered block entity type
     */
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new MachineBlockEntity(blockEntityType(), pos, state, this::createMachine);
    }

    /**
     * Creates the runtime machine object stored by a machine block entity.
     * <p>
     * Definitions with part settings create {@link MBDPartMachine}; other definitions create {@link MBDMachine}. Disabled
     * part settings are filtered by {@link MBDPartMachine#isPartEnabled()} so existing block entities can become valid
     * parts after project reloads without recreating the block entity.
     *
     * @param blockEntity owning block entity wrapper
     * @return runtime machine instance for the owner
     */
    public MBDMachine createMachine(IMachineBlockEntity blockEntity) {
        return partSettings != null ? new MBDPartMachine(blockEntity, this) : new MBDMachine(blockEntity, this);
    }

    /**
     * Initializes client-side renderers for the registered block entity and block render layers.
     * <p>
     * Preconditions: {@link #onRegistry(RegisterEvent)} must have assigned the block and block entity type. Side effects:
     * creates cached block/item renderers, registers the block entity renderer provider, and configures render-layer
     * predicates for the block.
     *
     * @param event Forge client renderer registration event
     */
    @OnlyIn(Dist.CLIENT)
    public void initRenderer(EntityRenderersEvent.RegisterRenderers event) {
        blockRenderer = createBlockRenderer();
        itemRenderer = createItemRenderer();
        event.registerBlockEntityRenderer(blockEntityType, createBESRR());
        setBlockRenderLayer(renderType -> {
            if (renderType == RenderType.translucent()) {
                return blockProperties.renderTypes().translucent();
            } else if (renderType == RenderType.cutout()) {
                return blockProperties.renderTypes().cutout();
            } else if (renderType == RenderType.cutoutMipped()) {
                return blockProperties.renderTypes().cutoutMipped();
            } else if (renderType == RenderType.solid()) {
                return blockProperties.renderTypes().solid();
            }
            return false;
        });
    }

    @SuppressWarnings("removal")
    private void setBlockRenderLayer(java.util.function.Predicate<RenderType> renderTypePredicate) {
        ItemBlockRenderTypes.setRenderLayer(block(), renderTypePredicate);
    }

    /**
     * Creates the renderer used for in-world block model rendering.
     * <p>
     * The renderer reads ambient-occlusion and root-state renderer suppliers lazily so editor/project reloads can update
     * those values without recreating the renderer.
     *
     * @return client block renderer for this definition
     */
    @OnlyIn(Dist.CLIENT)
    public IRenderer createBlockRenderer() {
        return new MBDBlockRenderer(blockProperties::useAO, () -> stateMachine.getRootState().getRealRenderer());
    }

    /**
     * Creates the renderer used when this machine appears as an item.
     * <p>
     * The item renderer uses explicit item renderer settings when enabled, otherwise it falls back to the root machine
     * state renderer.
     *
     * @return client item renderer for machine stacks
     */
    @OnlyIn(Dist.CLIENT)
    public IRenderer createItemRenderer() {
        return new MBDItemRenderer(itemProperties::useBlockLight, itemProperties::isGui3d, () -> itemProperties.renderer().isEnable() ? itemProperties.renderer().getValue() : stateMachine.getRootState().getRealRenderer());
    }

    /**
     * Creates the block entity renderer provider for machine block entities.
     *
     * @return provider that reuses or creates the shared MBD block entity renderer for a render context
     */
    @OnlyIn(Dist.CLIENT)
    public BlockEntityRendererProvider<BlockEntity> createBESRR() {
        return MBDBESRenderer::getOrCreate;
    }

    /**
     * Finds a named machine state in this definition's state machine.
     *
     * @param name state name configured in the state machine; matching is delegated to {@link StateMachine#getState(String)}
     * @return matching state, or {@code null} when no state with that name exists
     */
    public MachineState getState(String name) {
        return stateMachine.getState(name);
    }

    /**
     * Returns the translation key for this machine's block/item display name.
     *
     * @return description id from the registered block
     */
    public String getDescriptionId() {
        return block().getDescriptionId();
    }

    /**
     * Creates a one-count item stack for this machine.
     * <p>
     * If the item has not been registered yet, a barrier stack is returned as a visible placeholder instead of throwing.
     *
     * @return machine item stack or a barrier placeholder before item registration
     */
    public ItemStack asStack() {
        return item() == null ? new ItemStack(Items.BARRIER) : new ItemStack(item());
    }

    /**
     * Creates an item stack for this machine with an explicit count.
     * <p>
     * Preconditions: the machine item must have been registered. Unlike {@link #asStack()}, this method does not provide
     * a placeholder for unregistered items.
     *
     * @param count stack size to request; normal Minecraft item stack limits still apply to downstream consumers
     * @return machine item stack with the requested count
     */
    public ItemStack asStack(int count) {
        return new ItemStack(item(), count);
    }

    /**
     * Appends static item tooltip lines configured by the machine definition.
     * <p>
     * Side effect: mutates {@code tooltip}. This method does not inspect the stack's NBT and is safe to call from normal
     * client tooltip construction.
     *
     * @param stack   stack being inspected; currently used only to match the standard item tooltip signature
     * @param tooltip mutable tooltip list that receives configured item tooltip components
     */
    public void appendHoverText(ItemStack stack, List<Component> tooltip) {
        tooltip.addAll(itemProperties().itemTooltips());
    }

    @Setter
    @Accessors(chain = true, fluent = true)
    public static class Builder {
        protected ResourceLocation id;
        protected MachineState rootState;
        protected ConfigBlockProperties blockProperties;
        protected ConfigItemProperties itemProperties;
        protected ConfigMachineSettingsFactory machineSettings;
        protected ConfigRecipeLogicSettings recipeLogicSettings;
        @Nullable
        protected ConfigPartSettingsFactory partSettings;

        /**
         * Creates an empty machine definition builder.
         * <p>
         * Builder instances are mutable and not thread-safe.
         */
        protected Builder() {
        }

        /**
         * Configures the recipe logic to use a fixed maximum parallel value.
         * <p>
         * Side effect: creates recipe logic settings when needed and mutates their recipe modifiers. Values less than
         * one are accepted here and interpreted later by {@link ConfigRecipeLogicSettings} / recipe logic.
         *
         * @param maxParallel maximum number of recipe operations this machine may run in parallel
         * @return this builder for chaining
         */
        public Builder setMaxParallel(int maxParallel) {
            getOrCreateRecipeLogicSettings().recipeModifiers().setMaxParallel(maxParallel);
            return this;
        }

        /**
         * Fluent alias for {@link #setMaxParallel(int)}.
         *
         * @param maxParallel maximum number of recipe operations this machine may run in parallel
         * @return this builder for chaining
         */
        public Builder maxParallel(int maxParallel) {
            return setMaxParallel(maxParallel);
        }

        /**
         * Returns mutable recipe logic settings, creating default settings if the builder has none yet.
         * <p>
         * Side effect: may assign {@link #recipeLogicSettings}.
         *
         * @return recipe logic settings owned by this builder
         */
        protected ConfigRecipeLogicSettings getOrCreateRecipeLogicSettings() {
            if (recipeLogicSettings == null) {
                recipeLogicSettings = ConfigRecipeLogicSettings.builder().build();
            }
            return recipeLogicSettings;
        }

        /**
         * Builds a single-block machine definition from the current builder state.
         * <p>
         * Missing optional fields are resolved by the definition constructor. Calling this does not register the
         * definition, create Forge objects, or load deferred factory settings.
         *
         * @return new machine definition instance
         */
        public MBDMachineDefinition build() {
            return new MBDMachineDefinition(id, rootState, blockProperties, itemProperties, machineSettings, recipeLogicSettings, partSettings);
        }
    }
}
