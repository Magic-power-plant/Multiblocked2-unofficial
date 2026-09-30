package com.lowdragmc.mbd2.api.pattern;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.texture.*;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.utils.*;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.blockentity.IMachineBlockEntity;
import com.lowdragmc.mbd2.api.machine.IMultiController;
import com.lowdragmc.mbd2.api.pattern.predicates.SimplePredicate;
import com.lowdragmc.mbd2.api.pattern.preview.PartHighlightIndex;
import com.lowdragmc.mbd2.api.pattern.preview.PartHighlightSource;
import com.lowdragmc.mbd2.client.xei.MultiblockPreviewLayoutMetrics;
import com.lowdragmc.mbd2.client.xei.PartHighlightOverviewWidget;
import com.lowdragmc.mbd2.client.xei.PartHighlightOverlayRenderer;
import com.lowdragmc.mbd2.client.xei.PatternPartHighlightSession;
import com.lowdragmc.mbd2.common.block.MBDMachineBlock;
import com.lowdragmc.mbd2.common.blockentity.MachineBlockEntity;
import com.lowdragmc.mbd2.common.machine.definition.MultiblockMachineDefinition;
import com.lowdragmc.mbd2.common.machine.definition.config.toggle.ToggleCatalyst;
import com.lowdragmc.mbd2.config.ConfigHolder;
import com.lowdragmc.mbd2.utils.ControllerBlockInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.emi.emi.screen.RecipeScreen;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.shedaniel.rei.impl.client.gui.screen.AbstractDisplayViewingScreen;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Client-side widget that renders multiblock pattern previews and candidate
 * lists for recipe viewers and editor-style UI.
 *
 * <p>The business goal is to show players every configured multiblock shape,
 * layer slices, required parts, catalyst requirements, and predicate-specific
 * alternatives in one compact interactive widget. This class is client-only and
 * mutates a shared {@link TrackedDummyWorld}; callers must use it from the
 * Minecraft client/render UI thread.</p>
 */
@OnlyIn(Dist.CLIENT)
public class PatternPreviewWidget extends WidgetGroup {
    private boolean isLoaded;
    private static TrackedDummyWorld LEVEL;
    private static Level previewLevel;
    private static final Set<PatternPreviewWidget> OPEN_PREVIEWS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static BlockPos LAST_POS = new BlockPos(0, 50, 0);
    private final SceneWidget sceneWidget;
    public final MultiblockMachineDefinition controllerDefinition;
    public final ImageWidget descriptionWidget;
    public MBPattern[] patterns;
    private long previewRevision;
    private int index;
    public int layer;
    private final XEIIngredientScrollableWidgetGroup predicatesGroup;
    private final XEIIngredientScrollableWidgetGroup candidatesGroup;
    private final PatternPartHighlightSession partSession = new PatternPartHighlightSession();
    private final PartHighlightOverviewWidget partOverview;
    private final ImageWidget positionStatus;
    private final java.lang.ref.WeakReference<Level> boundLevel;
    private ButtonWidget pageButton;

    /**
     * Creates a preview widget for one multiblock controller definition.
     *
     * <p>Side effects: initializes scene widgets, loads preview patterns into
     * the shared dummy world, and prepares candidate/catalyst controls.</p>
     *
     * @param controllerDefinition controller definition whose preview shapes are
     *                             displayed
     */
    protected PatternPreviewWidget(MultiblockMachineDefinition controllerDefinition) {
        super(0, 0, MultiblockPreviewLayoutMetrics.WIDTH, MultiblockPreviewLayoutMetrics.HEIGHT);
        setClientSideWidget();

        // predicates
        addWidget(predicatesGroup = new XEIIngredientScrollableWidgetGroup(4, 9, 22, 90, 1, 5,
                IngredientIO.INPUT, true));
        predicatesGroup.setYScrollBarWidth(4)
                .setYBarStyle(null, ColorPattern.T_WHITE.rectTexture().setRadius(2).transform(-0.5f, 0));
        addWidget(positionStatus = new ImageWidget(5, 10, 16, 16,
                new ResourceTexture("mbd2:textures/gui/information.png")));
        positionStatus.setHoverTooltips("pattern_preview.select_position");

        // prepare scene
        addWidget(new ImageWidget(26, 7, 106, 106, ResourceBorderTexture.BORDERED_BACKGROUND_INVERSE));
        addWidget(sceneWidget = new SceneWidget(26 + 3, 7 + 3, 106 - 6, 106 - 6, LEVEL)
                .setOnSelected(this::onPosSelected)
                .setAfterWorldRender(scene -> PartHighlightOverlayRenderer.render(partSession.highlightedPositions()))
                .setRenderFacing(false));
        addWidget(partOverview = new PartHighlightOverviewWidget(partSession, controllerDefinition.id()));
        if (ConfigHolder.useVBO) {
            if (!RenderSystem.isOnRenderThread()) {
                RenderSystem.recordRenderCall(sceneWidget::useCacheBuffer);
            } else {
                sceneWidget.useCacheBuffer();
            }
        }

        // load patterns
        this.controllerDefinition = controllerDefinition;
        this.boundLevel = new java.lang.ref.WeakReference<>(previewLevel);
        this.layer = -1;
        reloadPatterns();

        // id
        addWidget(new ImageWidget(26 + 3, 7 + 3, 106 - 6, 15,
                new TextTexture(controllerDefinition.getDescriptionId(), -1)
                        .setType(TextTexture.TextType.ROLL)
                        .setWidth(106 - 6)
                        .setDropShadow(true)));

        // buttons
        var buttonTexture = new GuiTextureGroup(
                new ColorRectTexture(ColorUtils.color(255, 221, 221, 221)),
                new ColorBorderTexture(-1, ColorUtils.color(255, 73, 73, 73))
        );
        pageButton = new ButtonWidget(MultiblockPreviewLayoutMetrics.CONTROLS_X, 11, 18, 18, new GuiTextureGroup(
                buttonTexture,
                new ItemStackTexture(Items.PAPER),
                new TextTexture("0", ColorPattern.BLACK.color).setSupplier(() -> Integer.toString(index)).scale(0.8f)
        ), cd -> setPage((index + 1 >= patterns.length) ? 0 : index + 1));
        pageButton.setHoverBorderTexture(-1, -1)
                .setHoverTooltips("pattern_preview.page");
        var layerButton = new ButtonWidget(MultiblockPreviewLayoutMetrics.CONTROLS_X, 34, 18, 18, new GuiTextureGroup(
                buttonTexture,
                new ResourceTexture("mbd2:textures/gui/multiblock_info_page_layer.png"),
                new TextTexture("", ColorPattern.BLACK.color).setSupplier(() -> layer == -1 ? "" : Integer.toString(layer)).scale(0.8f)
        ), cd -> updateLayer());
        layerButton.setHoverBorderTexture(-1, -1)
                .setHoverTooltips("pattern_preview.layer");
        var formedButton = new SwitchWidget(MultiblockPreviewLayoutMetrics.CONTROLS_X, 57, 18, 18, (cd, pressed) -> onFormedSwitch(pressed))
                .setSupplier(() -> patterns.length > 0 && patterns[index].controllerBase.isFormed())
                .setTexture(new GuiTextureGroup(buttonTexture, new ResourceTexture("mbd2:textures/gui/multiblock_info_page_unformed.png")),
                        new GuiTextureGroup(buttonTexture, new ResourceTexture("mbd2:textures/gui/multiblock_info_page.png")))
                .setHoverBorderTexture(-1, -1);
        formedButton.setHoverTooltips("pattern_preview.formed");
        updatePageButtonVisibility();
        addWidget(pageButton);
        addWidget(layerButton);
        addWidget(formedButton);

        // catalyst
        if (controllerDefinition.multiblockSettings().catalyst().isEnable()) {
            var catalyst = controllerDefinition.multiblockSettings().catalyst();
            List<ItemStack> catalystItems = new ArrayList<>();
            for (var stack : catalyst.getFilterItems()) {
                catalystItems.add(stack.copy());
            }
            for (var filterTag : catalyst.getFilterTags()) {
                BuiltInRegistries.ITEM.getTag(ItemTags.create(filterTag)).ifPresent(values -> {
                    for (var stack : values) {
                        catalystItems.add(stack.value().getDefaultInstance());
                    }
                });
            }
            if (!catalystItems.isEmpty()) {
                addWidget(new SlotWidget(new CycleItemStackHandler(List.of(catalystItems)), 0,
                        MultiblockPreviewLayoutMetrics.CONTROLS_X, 80, false, false)
                        .setIngredientIO(IngredientIO.CATALYST)
                        .setOnAddedTooltips((widget, tooltips) -> {
                            tooltips.add(Component.translatable("config.multiblock_settings.catalyst.tooltip"));
                            tooltips.add(Component.translatable(catalyst.getCatalystType().getTranslateKey()));
                            if (catalyst.getCatalystType() == ToggleCatalyst.CatalystType.CONSUME_ITEM) {
                                tooltips.add(Component.translatable("config.multiblock_settings.catalyst.consume_type.consume_item.amount")
                                        .append(Component.literal(" " + catalyst.getConsumeItemAmount())));
                            } else {
                                tooltips.add(Component.translatable("config.multiblock_settings.catalyst.consume_type.consume_durability.amount")
                                        .append(Component.literal(" " + catalyst.getConsumeDurabilityValue())));
                            }
                        })
                        .setBackground(buttonTexture));
            }
        }

        // description
        addWidget(descriptionWidget = new ImageWidget(sceneWidget.getPositionX() + sceneWidget.getSizeWidth() - 20,
                sceneWidget.getPositionY() + sceneWidget.getSizeHeight() - 20, 16, 16,
                IGuiTexture.EMPTY));

        // candidates
        addWidget(candidatesGroup = new XEIIngredientScrollableWidgetGroup(6, 117, 174, 36,
                MultiblockPreviewLayoutMetrics.MATERIAL_COLUMNS, MultiblockPreviewLayoutMetrics.MATERIAL_ROWS, IngredientIO.INPUT));
        candidatesGroup.setYScrollBarWidth(4)
                .setYBarStyle(null, ColorPattern.T_WHITE.rectTexture().setRadius(2).transform(-0.5f, 0));

        // set initial page
        setPage(getPreferredPageIndex());
        OPEN_PREVIEWS.add(this);
    }

    private void reloadPatterns() {
        partSession.invalidate();
        previewRevision = controllerDefinition.previewRevision();

        HashSet<ItemStackKey> drops = new HashSet<>();
        drops.add(new ItemStackKey(this.controllerDefinition.asStack()));
        patterns = Arrays.stream(controllerDefinition.getPatternShapeInfos(null))
                .map(it -> initializePattern(it, drops))
                .filter(Objects::nonNull)
                .toArray(MBPattern[]::new);
        if (patterns.length == 0) {
            MBD2.LOGGER.warn("No multiblock preview patterns generated for {}", controllerDefinition.id());
        }
    }

    private void ensurePatternsFresh() {
        if (boundLevel.get() != Minecraft.getInstance().level || boundLevel.get() != previewLevel) {
            partSession.invalidate();
            patterns = new MBPattern[0];
            partOverview.reset();
            clearScene();
            predicatesGroup.setStacks(Collections.emptyList());
            candidatesGroup.setStacks(Collections.emptyList());
            positionStatus.setVisible(false);
            return;
        }
        if (previewRevision == controllerDefinition.previewRevision()) return;
        reloadPatterns();
        updatePageButtonVisibility();
        if (patterns.length > 0) {
            setPage(getPreferredPageIndex());
        } else {
            index = 0;
            layer = -1;
            clearScene();
            predicatesGroup.setStacks(Collections.emptyList());
            candidatesGroup.setStacks(Collections.emptyList());
            partOverview.reset();
            positionStatus.setVisible(false);
            descriptionWidget.setImage(IGuiTexture.EMPTY);
            descriptionWidget.setHoverTooltips(Collections.emptyList());
        }
    }

    private void updatePageButtonVisibility() {
        if (pageButton != null) {
            pageButton.setVisible(patterns.length > 1);
        }
    }

    private int getPreferredPageIndex() {
        return Math.max(0, Math.min(controllerDefinition.getSelectedPatternShapeInfoIndex(null), patterns.length - 1));
    }

    private void updateLayer() {
        if (patterns.length == 0) return;
        MBPattern pattern = patterns[index];
        if (layer + 1 >= -1 && layer + 1 <= pattern.maxY - pattern.minY) {
            layer += 1;
            if (pattern.controllerBase.isFormed()) {
                onFormedSwitch(false);
            }
        } else {
            layer = -1;
            if (!pattern.controllerBase.isFormed()) {
                onFormedSwitch(true);
            }
        }
        setupScene(pattern);
    }

    private void setupScene(MBPattern pattern) {
        Stream<BlockPos> stream = pattern.blockMap.keySet().stream()
                .filter(pos -> layer == -1 || layer + pattern.minY == pos.getY());
        Set<BlockPos> rendered;
        if (pattern.controllerBase.isFormed()) {
            LongSet set = pattern.controllerBase.getMultiblockState().getMatchContext().getOrDefault("renderMask",
                    LongSets.EMPTY_SET);
            Set<BlockPos> modelDisabled = set.stream().map(BlockPos::of).collect(Collectors.toSet());
            rendered = stream.filter(pos -> !modelDisabled.contains(pos)).collect(Collectors.toSet());
        } else {
            rendered = stream.collect(Collectors.toSet());
        }
        partSession.updateVisiblePositions(rendered);
        if (Minecraft.getInstance().isSameThread()) {
            ensureSceneInitialized();
            sceneWidget.setRenderedCore(rendered, null);
        }
    }

    private void ensureSceneInitialized() {
        // EMI inspects a widget on its reload worker; LDLib deliberately skips
        // SceneWidget.createScene there. Initialize only when the UI is used.
        if (sceneWidget.getCore() == null && Minecraft.getInstance().isSameThread()
                && boundLevel.get() == Minecraft.getInstance().level && boundLevel.get() == previewLevel
                && LEVEL != null) {
            sceneWidget.createScene(LEVEL);
            sceneWidget.setRenderedCore(partSession.visiblePositions(), null);
        }
    }

    private void clearScene() {
        // Metadata-only EMI widgets have no renderer/core, including at logout.
        if (Minecraft.getInstance().isSameThread() && sceneWidget.getCore() != null) {
            sceneWidget.setRenderedCore(Collections.emptyList(), null);
        }
    }

    private static List<PatternPreviewWidget> previewSnapshot() {
        synchronized (OPEN_PREVIEWS) {
            return new ArrayList<>(OPEN_PREVIEWS);
        }
    }

    /**
     * Creates a pattern preview widget backed by the shared dummy world.
     *
     * <p>Preconditions: a client level must already be loaded. Side effects:
     * creates the shared dummy world on first use.</p>
     *
     * @param controllerDefinition controller definition to preview
     * @return new preview widget
     * @throws IllegalStateException when called before a client level exists
     */
    public static PatternPreviewWidget getPatternWidget(MultiblockMachineDefinition controllerDefinition) {
        Level currentLevel = Minecraft.getInstance().level;
        if (LEVEL == null || currentLevel != previewLevel) {
            if (currentLevel == null) {
                MBD2.LOGGER.error("Try to init pattern previews before level load");
                throw new IllegalStateException();
            }
            clearPreviewWorld();
            LEVEL = new TrackedDummyWorld();
            previewLevel = currentLevel;
            LAST_POS = new BlockPos(0, 50, 0);
        }
        return new PatternPreviewWidget(controllerDefinition);
    }

    /** Invalidates the shared dummy world after a client disconnect. */
    public static void clearPreviewWorld() {
        invalidatePreviewSessions();
        for (var preview : previewSnapshot()) {
            preview.patterns = new MBPattern[0];
            preview.clearScene();
            preview.predicatesGroup.setStacks(Collections.emptyList());
            preview.candidatesGroup.setStacks(Collections.emptyList());
            preview.positionStatus.setVisible(false);
        }
        OPEN_PREVIEWS.clear();
        LEVEL = null;
        previewLevel = null;
        LAST_POS = new BlockPos(0, 50, 0);
    }

    public static void invalidatePreviewSessions() {
        for (var preview : previewSnapshot()) {
            preview.partSession.invalidate();
            preview.partOverview.reset();
            preview.isLoaded = false;
        }
    }

    /**
     * Selects the currently displayed preview pattern.
     *
     * <p>Side effects: resets layer filtering, updates the scene, part
     * candidates, and description tooltip. Out-of-range indexes are ignored.</p>
     *
     * @param index zero-based preview pattern index
     */
    public void setPage(int index) {
        if (boundLevel.get() != previewLevel || boundLevel.get() != Minecraft.getInstance().level
                || index >= patterns.length || index < 0) return;
        this.index = index;
        this.layer = -1;
        MBPattern pattern = patterns[index];
        partSession.bind(pattern);
        partOverview.reset();
        predicatesGroup.setStacks(Collections.emptyList());
        predicatesGroup.setScrollYOffset(0);
        positionStatus.setVisible(true);
        positionStatus.setHoverTooltips("pattern_preview.select_position");
        candidatesGroup.setScrollYOffset(0);
        setupScene(pattern);
        setupPatternCandidates(pattern);
        setupDescription(pattern);
    }

    /**
     * Returns the full required part list for the currently selected preview shape.
     * <p>
     * Recipe viewers use this for integrations that need every multiblock part, not just the visible scroller slots.
     *
     * @return copied candidate stack groups, one group per logical required part
     */
    public List<List<ItemStack>> getCurrentPatternParts() {
        ensurePatternsFresh();
        if (patterns.length == 0 || index >= patterns.length || index < 0) {
            return Collections.emptyList();
        }
        return patterns[index].parts.stream()
                .map(group -> group.stream().map(ItemStack::copy).toList())
                .toList();
    }

    private void setupPatternCandidates(MBPattern pattern) {
        candidatesGroup.setStacks(pattern.parts);
    }

    private void setupPredicateCandidates(List<List<ItemStack>> candidateStacks, List<SimplePredicate> simplePredicates,
                                          TraceabilityPredicate traceabilityPredicate) {
        int scrollYOffset = predicatesGroup.getScrollYOffset();
        var tooltipProviders = new ArrayList<Function<ItemStack, List<Component>>>();
        for (var simplePredicate : simplePredicates) {
            tooltipProviders.add(stack -> simplePredicate.getCandidateToolTips(traceabilityPredicate, stack));
        }
        predicatesGroup.setStacks(candidateStacks, tooltipProviders);
        predicatesGroup.computeMax();
        int maxScrollYOffset = predicatesGroup.getMaxScrollYOffset();
        predicatesGroup.setScrollYOffset(Math.min(scrollYOffset, maxScrollYOffset));
    }

    private void setupDescription(MBPattern pattern) {
        var description = pattern.description;
        if (description.isEmpty()) {
            descriptionWidget.setImage(IGuiTexture.EMPTY);
            descriptionWidget.setHoverTooltips(Collections.emptyList());
        } else {
            descriptionWidget.setImage(new ResourceTexture("mbd2:textures/gui/information.png"));
            descriptionWidget.setHoverTooltips(description.stream().map(Component::translatable).collect(Collectors.toList()));
        }
    }

    private void onFormedSwitch(boolean isFormed) {
        if (patterns.length == 0) return;
        MBPattern pattern = patterns[index];
        IMultiController controllerBase = pattern.controllerBase;
        if (isFormed) {
            this.layer = -1;
            boolean matched = loadControllerFormed(controllerBase);
            Long2ObjectMap<TraceabilityPredicate> cache = controllerBase.getMultiblockState().getMatchContext().get("predicates");
            pattern.predicateMap.clear();
            pattern.highlightIndex = null;
            pattern.hasMatchedPredicates = matched && cache != null;
            if (pattern.hasMatchedPredicates && cache != null) {
                cache.long2ObjectEntrySet().forEach(e -> pattern.predicateMap.put(BlockPos.of(e.getLongKey()), e.getValue()));
            }
            partSession.bind(pattern);
            partOverview.reset();
            predicatesGroup.setStacks(Collections.emptyList());
            predicatesGroup.setScrollYOffset(0);
            positionStatus.setVisible(true);
            positionStatus.setHoverTooltips("pattern_preview.select_position");
        } else {
            controllerBase.onStructureInvalid();
        }
        setupScene(pattern);
    }

    private void onPosSelected(BlockPos pos, Direction facing) {
        if (index >= patterns.length || index < 0) return;
        TraceabilityPredicate predicate = patterns[index].predicateMap.get(pos);
        if (predicate == null) {
            predicatesGroup.setStacks(Collections.emptyList());
            positionStatus.setVisible(true);
            positionStatus.setHoverTooltips("pattern_preview.position_empty");
            return;
        }
        var allPredicates = new ArrayList<SimplePredicate>();
        allPredicates.addAll(predicate.common);
        allPredicates.addAll(predicate.limited);
        allPredicates.removeIf(p -> p == null || p.candidates == null); // why it happens?
        var candidateStacks = new ArrayList<List<ItemStack>>();
        var simplePredicates = new ArrayList<SimplePredicate>();
        for (var simplePredicate : allPredicates) {
            List<ItemStack> itemStacks = simplePredicate.getCandidates();
            if (!itemStacks.isEmpty()) {
                candidateStacks.add(itemStacks);
                simplePredicates.add(simplePredicate);
            }
        }
        setupPredicateCandidates(candidateStacks, simplePredicates, predicate);
        positionStatus.setVisible(candidateStacks.isEmpty());
        positionStatus.setHoverTooltips("pattern_preview.position_empty");
    }

    /**
     * Allocates a separated dummy-world region for a preview pattern.
     *
     * <p>Side effects: advances the static last-position cursor by {@code range}
     * blocks on X and Z so previews do not overlap.</p>
     *
     * @param range spacing between preview regions; should be large enough for
     *              the rendered multiblock
     * @return origin for the next preview region
     */
    public static BlockPos locateNextRegion(int range) {
        BlockPos pos = LAST_POS;
        LAST_POS = LAST_POS.offset(range, 0, range);
        return pos;
    }

    @Override
    public void updateScreen() {
        ensurePatternsFresh();
        super.updateScreen();
        if (boundLevel.get() != previewLevel || boundLevel.get() != Minecraft.getInstance().level) return;
        // I can only think of this way
        if (!isLoaded && LDLib.isEmiLoaded() && Minecraft.getInstance().screen instanceof RecipeScreen) {
            setPage(getPreferredPageIndex());
            isLoaded = true;
        } else if (!isLoaded && LDLib.isReiLoaded() && Minecraft.getInstance().screen instanceof AbstractDisplayViewingScreen) {
            setPage(getPreferredPageIndex());
            isLoaded = true;
        } else if (!isLoaded && LDLib.isJeiLoaded() && Minecraft.getInstance().screen instanceof RecipesGui) {
            setPage(getPreferredPageIndex());
            isLoaded = true;
        }
    }

    @Override
    public void drawInBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        ensurePatternsFresh();
        ensureSceneInitialized();
        if (partSession.state() == com.lowdragmc.mbd2.api.pattern.preview.AbstractPartHighlightSession.State.UNBOUND
                && patterns.length > 0) setPage(getPreferredPageIndex());
        partOverview.updateHover(mouseX, mouseY);
        RenderSystem.enableBlend();
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
    }

    private MBPattern initializePattern(MultiblockShapeInfo shapeInfo, HashSet<ItemStackKey> blockDrops) {
        Map<BlockPos, BlockInfo> blockMap = new HashMap<>();
        IMultiController controllerBase = null;
        BlockPos multiPos = locateNextRegion(500);

        BlockInfo[][][] blocks = shapeInfo.getBlocks();
        for (int x = 0; x < blocks.length; x++) {
            BlockInfo[][] aisle = blocks[x];
            for (int y = 0; y < aisle.length; y++) {
                BlockInfo[] column = aisle[y];
                for (int z = 0; z < column.length; z++) {
                    if (column[z] instanceof ControllerBlockInfo controllerBlockInfo) {
                        var state = controllerDefinition.block().defaultBlockState();
                        if (controllerDefinition.blockProperties().rotationState().property.isPresent()) {
                            state = state.setValue(controllerDefinition.blockProperties().rotationState().property.get(), controllerBlockInfo.getFacing());
                        }
                        column[z] = new BlockInfo(state, blockEntity -> {
                            if (blockEntity instanceof MachineBlockEntity machineBlockEntity) {
                                var controllerMachine = controllerDefinition.createMachine(machineBlockEntity);
                                machineBlockEntity.setMachine(controllerMachine);
                                controllerMachine.loadAdditionalTraits();
                            }
                        });
                    }
                    BlockInfo blockInfo = column[z];
                    BlockState blockState = blockInfo.getBlockState();
                    BlockPos pos = multiPos.offset(x, y, z);
                    if (blockInfo.getBlockEntity(pos) instanceof IMachineBlockEntity holder &&
                            holder.getMetaMachine() instanceof IMultiController controller) {
                        holder.getSelf().setLevel(LEVEL);
                        controllerBase = controller;
                    }
                    blockMap.put(pos, blockInfo);
                }
            }
        }

        LEVEL.addBlocks(blockMap);
        if (controllerBase != null) {
            LEVEL.setInnerBlockEntity(controllerBase.getHolder());
        }

        Map<ItemStackKey, PartInfo> parts = gatherBlockDrops(blockMap);
        blockDrops.addAll(parts.keySet());

        Map<BlockPos, TraceabilityPredicate> predicateMap = new HashMap<>();
        boolean matched = false;
        if (controllerBase != null) {
            matched = loadControllerFormed(controllerBase);
            Long2ObjectMap<TraceabilityPredicate> cachedPredicates = controllerBase.getMultiblockState()
                    .getMatchContext().get("predicates");
            if (matched && cachedPredicates != null) {
                predicateMap = new HashMap<>(cachedPredicates.size());
                for (var entry : cachedPredicates.long2ObjectEntrySet()) {
                    predicateMap.put(BlockPos.of(entry.getLongKey()), entry.getValue());
                }
            }
            matched &= cachedPredicates != null;
        }
        return controllerBase == null ? null : new MBPattern(blockMap, parts.values().stream().sorted((one, two) -> {
            if (one.isController) return -1;
            if (two.isController) return +1;
            if (one.isTile && !two.isTile) return -1;
            if (two.isTile && !one.isTile) return +1;
            if (one.blockId != two.blockId) return two.blockId - one.blockId;
            return two.amount - one.amount;
        }).map(PartInfo::getItemStack).filter(list -> !list.isEmpty()).collect(Collectors.toList()), predicateMap,
                controllerBase, shapeInfo.getDescription(), matched);
    }

    private boolean loadControllerFormed(IMultiController controllerBase) {
        BlockPattern pattern = controllerBase.getPattern();
        if (pattern != null && pattern.checkPatternAt(controllerBase.getMultiblockState(), true)) {
            controllerBase.onStructureFormed();
        } else {
            controllerBase.onStructureInvalid();
            MBD2.LOGGER.warn("Pattern formed checking failed: {}", controllerBase.getBlockState().getBlock().getDescriptionId());
            return false;
        }
        if (!controllerBase.isFormed()) {
            MBD2.LOGGER.warn("Pattern formed checking failed: {}", controllerBase.getBlockState().getBlock().getDescriptionId());
        }
        return controllerBase.isFormed();
    }

    private Map<ItemStackKey, PartInfo> gatherBlockDrops(Map<BlockPos, BlockInfo> blocks) {
        Map<ItemStackKey, PartInfo> partsMap = new Object2ObjectOpenHashMap<>();
        for (Map.Entry<BlockPos, BlockInfo> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState blockState = ((Level) PatternPreviewWidget.LEVEL).getBlockState(pos);
            ItemStack itemStack = blockState.getBlock().getCloneItemStack(PatternPreviewWidget.LEVEL, pos, blockState);

            if (itemStack.isEmpty() && !blockState.getFluidState().isEmpty()) {
                Fluid fluid = blockState.getFluidState().getType();
                itemStack = fluid.getBucket().getDefaultInstance();
            }

            ItemStackKey itemStackKey = new ItemStackKey(itemStack);
            partsMap.computeIfAbsent(itemStackKey, key -> new PartInfo(key, entry.getValue())).amount++;
        }
        return partsMap;
    }

    private static class PartInfo {

        final ItemStackKey itemStackKey;
        boolean isController = false;
        boolean isTile = false;
        final int blockId;
        int amount = 0;

        PartInfo(final ItemStackKey itemStackKey, final BlockInfo blockInfo) {
            this.itemStackKey = itemStackKey;
            this.blockId = Block.getId(blockInfo.getBlockState());
            this.isTile = blockInfo.hasBlockEntity();

            if (blockInfo.getBlockState().getBlock() instanceof MBDMachineBlock block) {
                if (block.getDefinition() instanceof MultiblockMachineDefinition)
                    this.isController = true;
            }
        }

        /**
         * Builds the display stacks for this part requirement.
         * <p>
         * Each candidate from the stack key is copied and assigned the aggregated required amount. Empty candidates are
         * filtered out so recipe viewers receive only usable part entries.
         *
         * @return copied candidate stacks with counts set to the required part amount
         */
        public List<ItemStack> getItemStack() {
            return Arrays.stream(itemStackKey.getItemStack())
                    .map(itemStack -> {
                        var item = itemStack.copy();
                        item.setCount(amount);
                        return item;
                    }).filter(item -> !((ItemStack) item).isEmpty()).toList();
        }
    }

    /**
     * Baked preview data for one displayed multiblock shape.
     *
     * <p>The business goal is to keep the scene block map, required part stacks,
     * predicate map, temporary controller instance, vertical bounds, and
     * description together after a shape has been loaded into the dummy world.
     * Instances are immutable by convention after construction, except that the
     * referenced controller and dummy-world block entities may mutate as preview
     * formation toggles.</p>
     */
    public static class MBPattern implements PartHighlightSource {

        @NotNull
        final List<List<ItemStack>> parts;
        @NotNull
        final Map<BlockPos, TraceabilityPredicate> predicateMap;
        @NotNull
        final Map<BlockPos, BlockInfo> blockMap;
        @NotNull
        final IMultiController controllerBase;
        final int maxY, minY;
        final List<String> description;
        private boolean hasMatchedPredicates;
        private PartHighlightIndex highlightIndex;

        @Override
        public PartHighlightIndex partHighlightIndex() {
            if (highlightIndex == null) {
                highlightIndex = PartHighlightIndex.build(hasMatchedPredicates ? new HashMap<>(predicateMap) : null,
                        blockMap.keySet(), controllerBase.getPos());
            }
            return highlightIndex;
        }

        /**
         * Creates baked preview data and computes vertical bounds.
         *
         * @param blockMap       absolute dummy-world positions to rendered block
         *                       info
         * @param parts          grouped item stacks required by the pattern
         * @param predicateMap   pattern predicates keyed by dummy-world position
         * @param controllerBase temporary controller backing formed preview
         * @param description    translation keys shown in the description tooltip
         */
        public MBPattern(@NotNull Map<BlockPos, BlockInfo> blockMap, @NotNull List<List<ItemStack>> parts,
                         @NotNull Map<BlockPos, TraceabilityPredicate> predicateMap,
                         @NotNull IMultiController controllerBase,
                         @NotNull List<String> description) {
            this(blockMap, parts, predicateMap, controllerBase, description, !predicateMap.isEmpty());
        }

        private MBPattern(@NotNull Map<BlockPos, BlockInfo> blockMap, @NotNull List<List<ItemStack>> parts,
                          @NotNull Map<BlockPos, TraceabilityPredicate> predicateMap,
                          @NotNull IMultiController controllerBase,
                          @NotNull List<String> description, boolean hasMatchedPredicates) {
            this.parts = parts;
            this.blockMap = blockMap;
            this.predicateMap = predicateMap;
            this.controllerBase = controllerBase;
            this.description = description;
            this.hasMatchedPredicates = hasMatchedPredicates;
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (BlockPos pos : blockMap.keySet()) {
                min = Math.min(min, pos.getY());
                max = Math.max(max, pos.getY());
            }
            minY = min;
            maxY = max;
        }
    }

    private class XEIIngredientScrollableWidgetGroup extends DraggableScrollableWidgetGroup {

        private static final int SLOT_SIZE = 18;
        private final int columns;
        private final int visibleSlots;
        private final List<List<ItemStack>> displayStacks;
        private final CycleItemStackHandler displayHandler;
        private final List<Widget> xeiSlots;
        private final boolean exposeXEISlots;
        private List<List<ItemStack>> stacks = Collections.emptyList();
        private List<Function<ItemStack, List<Component>>> tooltipProviders = Collections.emptyList();

        /**
         * Creates a default input ingredient scroller for recipe viewer displays.
         * <p>
         * Uses a seven-column by two-row viewport and exposes proxy slots to XEI integrations. Side effects: creates and
         * adds the visible slot widgets immediately.
         *
         * @param x      left edge in parent-widget coordinates
         * @param y      top edge in parent-widget coordinates
         * @param width  viewport width in pixels
         * @param height viewport height in pixels
         */
        public XEIIngredientScrollableWidgetGroup(int x, int y, int width, int height) {
            this(x, y, width, height, 7, 2, IngredientIO.INPUT);
        }

        /**
         * Creates an ingredient scroller with proxy slots visible to recipe viewers.
         *
         * @param x            left edge in parent-widget coordinates
         * @param y            top edge in parent-widget coordinates
         * @param width        viewport width in pixels
         * @param height       viewport height in pixels
         * @param columns      number of slot columns; values below {@code 1} are clamped
         * @param rows         number of visible slot rows; values below {@code 1} are clamped
         * @param ingredientIO ingredient role reported to XEI integrations
         */
        public XEIIngredientScrollableWidgetGroup(int x, int y, int width, int height, int columns, int rows, IngredientIO ingredientIO) {
            this(x, y, width, height, columns, rows, ingredientIO, true);
        }

        /**
         * Creates a fixed-size scrolling grid for candidate item stacks.
         * <p>
         * The widget keeps a small visible slot pool and rewrites it as the scroll offset changes. When
         * {@code exposeXEISlots} is {@code true}, matching non-rendered proxy slots are also kept for recipe-viewer
         * ingredient discovery. Call from the client UI thread.
         *
         * @param x              left edge in parent-widget coordinates
         * @param y              top edge in parent-widget coordinates
         * @param width          viewport width in pixels
         * @param height         viewport height in pixels
         * @param columns        number of slot columns; values below {@code 1} are clamped
         * @param rows           number of visible slot rows; values below {@code 1} are clamped
         * @param ingredientIO   ingredient role reported by each slot
         * @param exposeXEISlots whether recipe viewers should see proxy ingredient slots
         */
        public XEIIngredientScrollableWidgetGroup(int x, int y, int width, int height, int columns, int rows,
                                                  IngredientIO ingredientIO, boolean exposeXEISlots) {
            super(x, y, width, height);
            this.columns = Math.max(1, columns);
            this.visibleSlots = this.columns * Math.max(1, rows);
            this.exposeXEISlots = exposeXEISlots;
            this.displayStacks = new ArrayList<>(visibleSlots);
            for (int i = 0; i < visibleSlots; i++) {
                displayStacks.add(Collections.emptyList());
            }
            this.displayHandler = new CycleItemStackHandler(displayStacks);
            this.xeiSlots = new ArrayList<>(visibleSlots);
            for (int i = 0; i < visibleSlots; i++) {
                int displaySlotIndex = i;
                addWidget(new PredicateSlotWidget(displayHandler, i,
                        (i % this.columns) * SLOT_SIZE, (i / this.columns) * SLOT_SIZE,
                        stack -> getToolTips(displaySlotIndex, stack))
                        .setIngredientIO(ingredientIO));
                if (exposeXEISlots) {
                    var proxySlot = new PredicateSlotWidget(displayHandler, i,
                            x + (i % this.columns) * SLOT_SIZE, y + (i / this.columns) * SLOT_SIZE,
                            stack -> getToolTips(displaySlotIndex, stack))
                            .markAsScrollerProxy(this);
                    proxySlot.setIngredientIO(ingredientIO)
                            .setDrawHoverOverlay(false)
                            .setDrawHoverTips(false)
                            .setBackgroundTexture(null);
                    xeiSlots.add(proxySlot);
                }
            }
        }

        /**
         * Replaces the displayed stack groups without extra tooltips.
         * <p>
         * Null input is treated as an empty list. Side effect: clamps the current row-snapped scroll offset and refreshes
         * the visible slot pool.
         *
         * @param stacks candidate stack groups, one group per logical scroller slot
         */
        public void setStacks(List<List<ItemStack>> stacks) {
            setStacks(stacks, Collections.emptyList());
        }

        /**
         * Replaces the displayed stack groups and optional tooltip providers.
         * <p>
         * The lists are retained rather than copied, so callers should not mutate them while the widget is rendering.
         * Tooltip providers are indexed by logical stack group; missing providers simply produce no extra tooltip lines.
         * Side effect: clamps the current row-snapped scroll offset and refreshes the visible slot pool.
         *
         * @param stacks           candidate stack groups, one group per logical scroller slot
         * @param tooltipProviders tooltip callbacks aligned with {@code stacks}
         */
        public void setStacks(List<List<ItemStack>> stacks, List<Function<ItemStack, List<Component>>> tooltipProviders) {
            this.stacks = stacks == null ? Collections.emptyList() : stacks;
            this.tooltipProviders = tooltipProviders == null ? Collections.emptyList() : tooltipProviders;
            this.scrollYOffset = Math.min(this.scrollYOffset, getMaxScrollYOffset());
            updateDisplayStacks();
        }

        @Override
        public void setScrollYOffset(int scrollYOffset) {
            int snapped = Math.max(0, Math.round(scrollYOffset / (float) SLOT_SIZE) * SLOT_SIZE);
            this.scrollYOffset = Math.min(snapped, getMaxScrollYOffset());
            updateDisplayStacks();
        }

        @Override
        public List<Widget> getContainedWidgets(boolean includeHidden) {
            ensurePatternsFresh();
            return exposeXEISlots ? xeiSlots : Collections.emptyList();
        }

        @Override
        public void computeMax() {
            this.scrollYOffset = Math.min(this.scrollYOffset, getMaxScrollYOffset());
            updateDisplayStacks();
        }

        @Override
        public int getWidgetBottomHeight() {
            return getTotalRows() * SLOT_SIZE;
        }

        @Override
        protected int getMaxHeight() {
            return Math.max(getSize().height, getWidgetBottomHeight() + xBarHeight);
        }

        private int getVisibleRows() {
            return Math.max(1, (visibleSlots + columns - 1) / columns);
        }

        private int getTotalRows() {
            return Math.max(getVisibleRows(), (stacks.size() + columns - 1) / columns);
        }

        private int getMaxScrollYOffset() {
            return Math.max(0, (getTotalRows() - getVisibleRows()) * SLOT_SIZE);
        }

        private void updateDisplayStacks() {
            int firstSlot = (getScrollYOffset() / SLOT_SIZE) * columns;
            for (int i = 0; i < visibleSlots; i++) {
                int stackIndex = firstSlot + i;
                displayStacks.set(i, stackIndex >= 0 && stackIndex < stacks.size() ?
                        stacks.get(stackIndex) : Collections.emptyList());
            }
            displayHandler.updateStacks(displayStacks);
        }

        private List<Component> getToolTips(int proxySlotIndex, ItemStack stack) {
            int stackIndex = (getScrollYOffset() / SLOT_SIZE) * columns + proxySlotIndex;
            if (stackIndex >= 0 && stackIndex < tooltipProviders.size()) {
                return tooltipProviders.get(stackIndex).apply(getTooltipStack(stackIndex, stack));
            }
            return Collections.emptyList();
        }

        private ItemStack getTooltipStack(int stackIndex, ItemStack stack) {
            if (stack != null && !stack.isEmpty()) {
                return stack;
            }
            if (stackIndex >= 0 && stackIndex < stacks.size()) {
                return stacks.get(stackIndex).stream()
                        .filter(candidate -> candidate != null && !candidate.isEmpty())
                        .findFirst()
                        .orElse(ItemStack.EMPTY);
            }
            return ItemStack.EMPTY;
        }
    }

    private static class PredicateSlotWidget extends SlotWidget {

        private final Function<ItemStack, List<Component>> tooltipProvider;
        private ItemStack lastTooltipStack = ItemStack.EMPTY;

        PredicateSlotWidget(CycleItemStackHandler itemHandler, int slotIndex, int xPosition, int yPosition,
                            Function<ItemStack, List<Component>> tooltipProvider) {
            super(itemHandler, slotIndex, xPosition, yPosition, false, false);
            this.tooltipProvider = tooltipProvider;
        }

        private PredicateSlotWidget markAsScrollerProxy(WidgetGroup parent) {
            setParent(parent);
            return this;
        }

        @Override
        public List<Component> getTooltipTexts() {
            var tooltips = new ArrayList<>(super.getTooltipTexts());
            tooltips.addAll(tooltipProvider.apply(getTooltipStack()));
            return tooltips;
        }

        @Override
        public List<Component> getFullTooltipTexts() {
            var tooltips = super.getFullTooltipTexts();
            return tooltips.isEmpty() ? getTooltipTexts() : tooltips;
        }

        @Override
        public List<Object> getXEIIngredients() {
            captureTooltipStack();
            return super.getXEIIngredients();
        }

        @Override
        public Object getXEIIngredientOverMouse(double mouseX, double mouseY) {
            captureTooltipStack();
            return super.getXEIIngredientOverMouse(mouseX, mouseY);
        }

        @Override
        public Object getXEICurrentIngredient() {
            captureTooltipStack();
            return super.getXEICurrentIngredient();
        }

        private ItemStack getTooltipStack() {
            var stack = getItem();
            if (stack != null && !stack.isEmpty()) {
                lastTooltipStack = stack.copy();
                return stack;
            }
            return lastTooltipStack;
        }

        private void captureTooltipStack() {
            getTooltipStack();
        }
    }
}
