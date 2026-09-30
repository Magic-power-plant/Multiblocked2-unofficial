package com.lowdragmc.mbd2.common.machine.definition;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.blockentity.IMachineBlockEntity;
import com.lowdragmc.mbd2.api.machine.IMultiPart;
import com.lowdragmc.mbd2.api.pattern.BlockPattern;
import com.lowdragmc.mbd2.api.pattern.CombinedBlockPattern;
import com.lowdragmc.mbd2.api.pattern.MultiblockShapeInfo;
import com.lowdragmc.mbd2.common.gui.editor.MultiblockMachineProject;
import com.lowdragmc.mbd2.common.gui.editor.PredicateResource;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.machine.MBDMultiblockMachine;
import com.lowdragmc.mbd2.common.machine.definition.config.*;
import com.lowdragmc.mbd2.common.trait.IUIProviderTrait;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.Util;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.lowdragmc.mbd2.common.gui.editor.MultiblockMachineProject.createBlockPattern;

/**
 * Multiblock machine definition.
 * <br>
 * This is used to define a mbd machine's {@link MBDMultiblockMachine#getDefinition()} behaviours.
 */
@Getter
@Accessors(fluent = true)
@LDLRegister(name = "multiblock", group = "machine_definition")
public class MultiblockMachineDefinition extends MBDMachineDefinition {
    public static final Map<Block, Set<MultiblockMachineDefinition>> CATALYST_CANDIDATES = Collections.synchronizedMap(new HashMap<>());

    /**
     * Factory for per-definition multiblock settings.
     *
     * <p>The supplier is stored during definition construction and invoked from
     * {@link #loadFactory()} after the base machine settings are initialized. It should
     * return a fresh mutable {@link ConfigMultiblockSettings} instance for this definition.</p>
     */
    @FunctionalInterface
    public interface ConfigMultiblockSettingsFactory extends Supplier<ConfigMultiblockSettings> {
    }

    @Configurable(name = "config.definition.multiblock_settings", subConfigurable = true, tips = "config.definition.multiblock_settings.tooltip", collapse = false)
    protected ConfigMultiblockSettings multiblockSettings;

    // runtime
    protected ConfigMultiblockSettingsFactory multiblockSettingsFactory;

    private Function<MBDMultiblockMachine, BlockPattern> blockPatternFactory;
    private Function<MBDMultiblockMachine, BlockPattern[]> blockPatternsFactory;
    private Function<MultiblockMachineDefinition, MultiblockShapeInfo[]> shapeInfoFactory;
    private int selectedPatternIndex;
    private long previewRevision;

    /**
     * Creates a multiblock machine definition.
     * <p>
     * The constructor delegates common machine definition state to {@link MBDMachineDefinition} and stores a factory for
     * multiblock-specific settings. It does not load block patterns, shape info, catalyst candidates, or Forge registry
     * objects.
     *
     * @param id                        registry id for the controller definition
     * @param rootState                 root state used for controller block rendering and shape, or {@code null} for the default state
     * @param blockProperties           block settings for the controller block, or {@code null} for defaults
     * @param itemProperties            item settings for the controller item, or {@code null} for defaults
     * @param machineSettingsFactory    factory for common machine settings loaded by {@link #loadFactory()}
     * @param recipeLogicSettings       recipe-processing settings, or {@code null} for defaults
     * @param multiblockSettingsFactory factory for multiblock settings loaded by {@link #loadFactory()}, or {@code null}
     *                                  for defaults
     */
    public MultiblockMachineDefinition(ResourceLocation id,
                                       @Nullable MachineState rootState,
                                       @Nullable ConfigBlockProperties blockProperties,
                                       @Nullable ConfigItemProperties itemProperties,
                                       @Nullable ConfigMachineSettingsFactory machineSettingsFactory,
                                       @Nullable ConfigRecipeLogicSettings recipeLogicSettings,
                                       @Nullable ConfigMultiblockSettingsFactory multiblockSettingsFactory) {
        this(id, rootState, blockProperties, itemProperties, machineSettingsFactory, recipeLogicSettings, null, multiblockSettingsFactory);
    }

    public MultiblockMachineDefinition(ResourceLocation id,
                                       @Nullable MachineState rootState,
                                       @Nullable ConfigBlockProperties blockProperties,
                                       @Nullable ConfigItemProperties itemProperties,
                                       @Nullable ConfigMachineSettingsFactory machineSettingsFactory,
                                       @Nullable ConfigRecipeLogicSettings recipeLogicSettings,
                                       @Nullable ConfigPartSettingsFactory partSettingsFactory,
                                       @Nullable ConfigMultiblockSettingsFactory multiblockSettingsFactory) {
        super(id, rootState, blockProperties, itemProperties, machineSettingsFactory, recipeLogicSettings, partSettingsFactory);
        this.multiblockSettingsFactory = multiblockSettingsFactory == null ? () -> ConfigMultiblockSettings.builder().build() : multiblockSettingsFactory;
    }

    /**
     * Enables proxy-part settings on multiblock controller definitions.
     * <p>
     * A multiblock controller can also be matched as a part by an outer multiblock when the project enables part
     * settings for this definition.
     *
     * @return always {@code true}
     */
    @Override
    public boolean allowPartSettings() {
        return true;
    }

    /**
     * Creates a placeholder multiblock definition with default settings.
     * <p>
     * The returned definition is structurally valid but has no project pattern data and is not registered automatically.
     *
     * @return default unregistered multiblock definition using the {@code mbd2:dummy} id
     */
    public static MultiblockMachineDefinition createDefault() {
        return new MultiblockMachineDefinition(
                MBD2.id("dummy"),
                StateMachine.createDefault(MachineState::builder),
                ConfigBlockProperties.builder().build(),
                ConfigItemProperties.builder().build(),
                () -> ConfigMachineSettings.builder().build(),
                ConfigRecipeLogicSettings.builder().build(),
                () -> ConfigMultiblockSettings.builder().build());
    }

    /**
     * Starts a builder for a multiblock machine definition.
     *
     * @return mutable builder with no fields set
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ConfigMachineEvents createMachineEvents() {
        return super.createMachineEvents().registerEventGroup("MachineEvent.Multiblock");
    }

    /**
     * Loads common and multiblock-specific settings from their factories.
     * <p>
     * Preconditions match {@link MBDMachineDefinition#loadFactory()}: call after relevant registries have been populated
     * and before runtime controller machines are created. Side effects: assigns {@link #machineSettings} through the
     * superclass and assigns {@link #multiblockSettings}.
     */
    @Override
    public void loadFactory() {
        super.loadFactory();
        multiblockSettings = multiblockSettingsFactory.get();
    }

    /**
     * Creates the runtime controller machine for a multiblock block entity.
     *
     * @param blockEntity owning controller block entity
     * @return new multiblock controller runtime instance bound to this definition
     */
    @Override
    public MBDMultiblockMachine createMachine(IMachineBlockEntity blockEntity) {
        return new MBDMultiblockMachine(blockEntity, this);
    }

    /**
     * Loads multiblock-specific project data in addition to the base machine definition data.
     * <p>
     * Base properties are loaded immediately by the superclass. This method appends deferred work that loads multiblock
     * settings, registers catalyst candidates, expands external pattern references, creates block pattern factories, and
     * builds preview shape-info suppliers after resources and registries are available.
     * <p>
     * Side effects: updates {@link #multiblockSettings}, {@link #selectedPatternIndex}, pattern factories, shape info
     * factories, preview revision, and the global {@link #CATALYST_CANDIDATES} map.
     *
     * @param file       project file used to resolve external pattern JSON references, or {@code null} for in-memory data
     * @param projectTag root project tag containing definition, resources, and pattern data
     * @param postTask   queue that receives deferred loading work to execute after registry/resource setup
     * @return this definition after immediate data has been loaded
     */
    @Override
    public MultiblockMachineDefinition loadProductiveTag(@Nullable File file, CompoundTag projectTag, Deque<Runnable> postTask) {
        super.loadProductiveTag(file, projectTag, postTask);
        postTask.add(() -> {
            // load multiblock settings
            multiblockSettings.deserializeNBT(projectTag.getCompound("definition").getCompound("multiblockSettings"));
            // setup catalyst candidates
            if (multiblockSettings.catalyst().isEnable() && multiblockSettings.catalyst().getCandidates().isEnable()) {
                for (var block : multiblockSettings.catalyst().getCandidates().getValue()) {
                    CATALYST_CANDIDATES.computeIfAbsent(block, b -> new HashSet<>()).add(this);
                }
            }
            // setup block patterns
            if (file != null) {
                try {
                    MultiblockMachineProject.expandPatternReferences(file, projectTag);
                } catch (IOException e) {
                    MBD2.LOGGER.error("Failed to load multiblock pattern json for {}", file, e);
                }
            }
            var predicateResource = new PredicateResource();
            predicateResource.deserializeNBT(projectTag.getCompound("resources").getCompound(PredicateResource.RESOURCE_NAME));
            var patternEntries = loadPatternEntries(projectTag);
            selectedPatternIndex = Math.max(0, Math.min(projectTag.getInt("selected_pattern"), patternEntries.size() - 1));
            var loadedPatterns = new ArrayList<LoadedPattern>();
            for (var patternTag : patternEntries) {
                loadedPatterns.add(loadPattern(patternTag, predicateResource));
            }
            var blockPatterns = loadedPatterns.stream().map(LoadedPattern::pattern).toArray(BlockPattern[]::new);
            blockPatternsFactory(controller -> blockPatterns);
            // setup shape info
            shapeInfoFactory(Util.memoize(definition -> {
                var shapeInfos = new ArrayList<MultiblockShapeInfo>();
                for (var pattern : loadedPatterns) {
                    appendPatternShapeInfos(shapeInfos, pattern.pattern());
                }
                return shapeInfos.toArray(new MultiblockShapeInfo[0]);
            }));
        });
        return this;
    }

    /**
     * Binds base machine UI widgets and multiblock part-proxy UI widgets.
     * <p>
     * Project UI widget ids using {@code part:<trait>@ui:<widget>} are rebound to matching traits found on currently
     * connected parts. Side effects mutate widget ids and attach trait UI suppliers/callbacks to the supplied UI tree.
     *
     * @param machine controller machine whose part set is used for proxy binding
     * @param ui      root widget group loaded from the project template
     */
    @Override
    protected void bindMachineUI(MBDMachine machine, WidgetGroup ui) {
        super.bindMachineUI(machine, ui);
        // proxy part ui
        if (machine instanceof MBDMultiblockMachine multiblock) {
            var prefix = "part:";
            var midTag = "@ui:";
            for (Widget widget : ui.getWidgetsById("part:.*?@ui:")) {
                var id = widget.getId();
                if (id.startsWith(prefix)) {
                    int atIndex = id.indexOf(midTag);
                    if (atIndex != -1) {
                        var traitName = id.substring(prefix.length(), atIndex);
                        var uiName = "ui:" + id.substring(atIndex + midTag.length());
                        for (IMultiPart part : multiblock.getParts()) {
                            if (part instanceof MBDMachine mbdMachine) {
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
     * Returns the pattern object used for structure matching.
     * <p>
     * When multiple patterns are available they are wrapped in a {@link CombinedBlockPattern}; when no pattern factory is
     * configured this returns {@code null}. The returned object is derived from the current pattern factories and may be
     * controller-specific.
     *
     * @param controller controller requesting the pattern; may influence dynamic pattern factories
     * @return single pattern, combined pattern, or {@code null} when no patterns are configured
     */
    public BlockPattern getPattern(MBDMultiblockMachine controller) {
        var patterns = getPatterns(controller);
        if (patterns.length == 0) return null;
        if (patterns.length == 1) return patterns[0];
        return new CombinedBlockPattern(patterns);
    }

    /**
     * Resolves all concrete block patterns for a controller.
     * <p>
     * Combined patterns are flattened so each returned entry is a concrete structure variant. The returned array is never
     * {@code null}; no configured factory or a factory returning {@code null} produces an empty array.
     *
     * @param controller controller requesting patterns; may be used by dynamic factories
     * @return flattened pattern array in factory order
     */
    public BlockPattern[] getPatterns(MBDMultiblockMachine controller) {
        if (blockPatternsFactory != null) {
            var patterns = blockPatternsFactory.apply(controller);
            if (patterns != null) {
                return flattenPatterns(patterns);
            }
        }
        if (blockPatternFactory != null) {
            var pattern = blockPatternFactory.apply(controller);
            return flattenPatterns(pattern);
        }
        return new BlockPattern[0];
    }

    /**
     * Returns configured repeatable aisles for one pattern without exposing its mutable backing arrays.
     *
     * @param controller controller context used by dynamic pattern factories, or {@code null}
     * @param patternIndex zero-based pattern index
     * @return immutable repeat-layer metadata, or an empty list for an invalid index
     */
    public List<BlockPattern.AisleRepeat> getPatternRepeatableAisles(@Nullable MBDMultiblockMachine controller,
                                                                    int patternIndex) {
        var patterns = getPatterns(controller);
        return patternIndex >= 0 && patternIndex < patterns.length
                ? patterns[patternIndex].getRepeatableAisles()
                : List.of();
    }

    /**
     * Returns every aisle repetition range for one concrete pattern.
     *
     * @param controller controller context used by dynamic pattern factories, or {@code null}
     * @param patternIndex zero-based concrete pattern index
     * @return defensive {@code [aisle][min,max]} array, or an empty array for an invalid index
     */
    public int[][] getPatternAisleRepetitionRanges(@Nullable MBDMultiblockMachine controller, int patternIndex) {
        var patterns = getPatterns(controller);
        return patternIndex >= 0 && patternIndex < patterns.length
                ? patterns[patternIndex].getAisleRepetitionRanges()
                : new int[0][2];
    }

    /**
     * Builds preview shape infos for every configured pattern and every repeated-aisle preview variant.
     * <p>
     * This method recomputes shape infos from current patterns rather than using the memoized project preview factory.
     * It is suitable for dynamic controller-aware previews.
     *
     * @param controller controller context, or {@code null} when the preview is definition-only
     * @return all preview shape infos in pattern order
     */
    public MultiblockShapeInfo[] getPatternShapeInfos(@Nullable MBDMultiblockMachine controller) {
        var shapeInfos = new ArrayList<MultiblockShapeInfo>();
        for (var pattern : getPatterns(controller)) {
            appendPatternShapeInfos(shapeInfos, pattern);
        }
        return shapeInfos.toArray(new MultiblockShapeInfo[0]);
    }

    /**
     * Builds preview shape infos for one pattern index.
     *
     * @param controller   controller context, or {@code null} for definition-only previews
     * @param patternIndex zero-based index into {@link #getPatterns(MBDMultiblockMachine)}
     * @return shape infos for the selected pattern, or an empty array when {@code patternIndex} is outside the available
     * pattern range
     */
    public MultiblockShapeInfo[] getPatternShapeInfos(@Nullable MBDMultiblockMachine controller, int patternIndex) {
        var patterns = getPatterns(controller);
        if (patternIndex < 0 || patternIndex >= patterns.length) return new MultiblockShapeInfo[0];
        var shapeInfos = new ArrayList<MultiblockShapeInfo>();
        appendPatternShapeInfos(shapeInfos, patterns[patternIndex]);
        return shapeInfos.toArray(new MultiblockShapeInfo[0]);
    }

    /**
     * Calculates the global shape-info offset for a pattern.
     * <p>
     * The result is the number of preview entries contributed by all patterns before {@code patternIndex}. Negative or
     * zero pattern indexes return {@code 0}; indexes larger than the available pattern count are clamped by iteration.
     *
     * @param controller   controller context used to resolve dynamic patterns
     * @param patternIndex zero-based pattern index whose first shape-info offset is requested
     * @return non-negative offset into the flattened shape-info list
     */
    public int getFirstPatternShapeInfoIndex(@Nullable MBDMultiblockMachine controller, int patternIndex) {
        if (patternIndex <= 0) return 0;
        var patterns = getPatterns(controller);
        int index = 0;
        for (int i = 0; i < patternIndex && i < patterns.length; i++) {
            index += getShapeInfoCount(patterns[i]);
        }
        return index;
    }

    /**
     * Returns the flattened shape-info offset for the project-selected pattern.
     *
     * @param controller controller context used to resolve dynamic patterns
     * @return non-negative offset into the flattened shape-info list for {@link #selectedPatternIndex}
     */
    public int getSelectedPatternShapeInfoIndex(@Nullable MBDMultiblockMachine controller) {
        return getFirstPatternShapeInfoIndex(controller, selectedPatternIndex);
    }

    /**
     * Installs a factory that produces one pattern for each controller.
     * <p>
     * Side effects: stores both single-pattern and array-pattern views and increments {@link #previewRevision} so preview
     * consumers can invalidate cached shape info.
     *
     * @param blockPatternFactory factory returning a pattern for a controller, or {@code null} for no pattern
     */
    public void blockPatternFactory(Function<MBDMultiblockMachine, BlockPattern> blockPatternFactory) {
        this.blockPatternFactory = blockPatternFactory;
        this.blockPatternsFactory = controller -> {
            var pattern = blockPatternFactory.apply(controller);
            return pattern == null ? new BlockPattern[0] : new BlockPattern[]{pattern};
        };
        previewRevision++;
    }

    /**
     * Installs a factory that produces all pattern variants for each controller.
     * <p>
     * Side effects: stores both array-pattern and single/combined-pattern views and increments {@link #previewRevision}.
     * A {@code null} or empty factory result is treated as no pattern.
     *
     * @param blockPatternsFactory factory returning pattern variants for a controller
     */
    public void blockPatternsFactory(Function<MBDMultiblockMachine, BlockPattern[]> blockPatternsFactory) {
        this.blockPatternsFactory = blockPatternsFactory;
        this.blockPatternFactory = controller -> {
            var patterns = blockPatternsFactory.apply(controller);
            if (patterns == null || patterns.length == 0) return null;
            if (patterns.length == 1) return patterns[0];
            return new CombinedBlockPattern(patterns);
        };
        previewRevision++;
    }

    /**
     * Installs a definition-level preview shape-info factory.
     * <p>
     * Side effect: replaces the current factory and increments {@link #previewRevision}. The supplied function should be
     * side-effect free because preview callers may invoke it repeatedly or through memoized wrappers.
     *
     * @param shapeInfoFactory factory returning preview shape infos for this definition
     */
    public void shapeInfoFactory(Function<MultiblockMachineDefinition, MultiblockShapeInfo[]> shapeInfoFactory) {
        this.shapeInfoFactory = shapeInfoFactory;
        previewRevision++;
    }

    /**
     * Orders matched multiblock parts before they are stored on the controller.
     * <p>
     * The base implementation preserves discovery order. Subclasses can override this to impose deterministic ordering
     * by role, distance, side, or other business rules. Side effect: implementations may reorder {@code parts} in place.
     *
     * @param parts mutable list of parts matched for a formed structure
     */
    public void sortParts(List<IMultiPart> parts) {
    }

    private List<CompoundTag> loadPatternEntries(CompoundTag projectTag) {
        var entries = new ArrayList<CompoundTag>();
        if (projectTag.contains("patterns", Tag.TAG_LIST)) {
            ListTag list = projectTag.getList("patterns", Tag.TAG_COMPOUND);
            entries.addAll(list.stream().map(CompoundTag.class::cast).toList());
        }
        if (entries.isEmpty()) {
            entries.add(projectTag);
        }
        return entries;
    }

    private BlockPattern[] flattenPatterns(BlockPattern... patterns) {
        var result = new ArrayList<BlockPattern>();
        for (var pattern : patterns) {
            if (pattern instanceof CombinedBlockPattern combinedPattern) {
                Collections.addAll(result, combinedPattern.getPatterns());
            } else if (pattern != null) {
                result.add(pattern);
            }
        }
        return result.toArray(BlockPattern[]::new);
    }

    private LoadedPattern loadPattern(CompoundTag patternTag, PredicateResource predicateResource) {
        var placeholders = MultiblockMachineProject.deserializeBlockPlaceholders(patternTag.getCompound("placeholders"), predicateResource);
        var layerAxisName = patternTag.getString("layer_axis");
        var layerAxis = layerAxisName.isBlank() ? Direction.Axis.Y : Direction.Axis.valueOf(layerAxisName);
        var aisleLength = switch (layerAxis) {
            case X -> placeholders.length;
            case Y -> placeholders[0].length;
            case Z -> placeholders[0][0].length;
        };
        var aisleRepetitions = new int[aisleLength][2];
        var repetitions = MultiblockMachineProject.getIntArrayCompat(patternTag, "aisle_repetitions");
        if (repetitions.length != 0 && repetitions.length != aisleLength * 2) {
            throw new IllegalArgumentException("Aisle repetition count does not match the " + layerAxis + " axis length");
        }
        for (int i = 0; i < aisleLength; i++) {
            aisleRepetitions[i][0] = i * 2 < repetitions.length ? repetitions[i * 2] : 1;
            aisleRepetitions[i][1] = i * 2 + 1 < repetitions.length ? repetitions[i * 2 + 1] : aisleRepetitions[i][0];
            BlockPattern.validateAisleRepetitionRange(aisleRepetitions[i][0], aisleRepetitions[i][1]);
        }
        return new LoadedPattern(patternTag, createBlockPattern(placeholders, layerAxis, aisleRepetitions, this), aisleRepetitions);
    }

    private void appendPatternShapeInfos(List<MultiblockShapeInfo> shapeInfos, BlockPattern pattern) {
        for (int[] repetitions : pattern.getAisleRepetitionCombinations()) {
            shapeInfos.add(new MultiblockShapeInfo(pattern.getPreview(repetitions)));
        }
    }

    private int getShapeInfoCount(BlockPattern pattern) {
        return Math.toIntExact(pattern.getRepetitionCombinationCount());
    }

    private record LoadedPattern(CompoundTag tag, BlockPattern pattern, int[][] aisleRepetitions) {
    }

    @Setter
    @Accessors(chain = true, fluent = true)
    public static class Builder extends MBDMachineDefinition.Builder {
        protected ConfigMultiblockSettingsFactory multiblockSettings;

        @Override public Builder xeiCatalystGroup(String group) { super.xeiCatalystGroup(group); return this; }
        @Override public Builder xeiCatalystPriority(int priority) { super.xeiCatalystPriority(priority); return this; }
        @Override public Builder xeiPartTooltip(String text) { super.xeiPartTooltip(text); return this; }
        @Override public Builder xeiPartTooltipKey(String key) { super.xeiPartTooltipKey(key); return this; }
        @Override public Builder xeiPartTooltips(java.util.List<net.minecraft.network.chat.Component> lines) { super.xeiPartTooltips(lines); return this; }

        /**
         * Creates an empty multiblock definition builder.
         * <p>
         * Builder instances are mutable and not thread-safe.
         */
        protected Builder() {
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
         * Builds a multiblock definition from the current builder state.
         * <p>
         * Missing optional fields are resolved by the definition constructor. Calling this does not register the
         * definition, load project patterns, or create Forge registry objects.
         *
         * @return new multiblock definition instance
         */
        public MultiblockMachineDefinition build() {
            return new MultiblockMachineDefinition(id, rootState, blockProperties, itemProperties, machineSettings, recipeLogicSettings, configuredPartSettings(), multiblockSettings);
        }
    }
}
