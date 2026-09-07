package com.lowdragmc.mbd2.api.recipe;

import com.google.common.collect.Queues;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.*;
import com.lowdragmc.lowdraglib.gui.editor.data.resource.TexturesResource;
import com.lowdragmc.lowdraglib.gui.editor.runtime.PersistedParser;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ProgressTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.UIResourceTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.capability.recipe.IO;
import com.lowdragmc.mbd2.api.capability.recipe.IRecipeCapabilityHolder;
import com.lowdragmc.mbd2.api.capability.recipe.RecipeCapability;
import com.lowdragmc.mbd2.api.recipe.content.Content;
import com.lowdragmc.mbd2.api.recipe.event.FuelRecipeUIEvent;
import com.lowdragmc.mbd2.api.recipe.event.RecipeUIEvent;
import com.lowdragmc.mbd2.api.recipe.event.TransferProxyRecipeEvent;
import com.lowdragmc.mbd2.common.gui.recipe.ingredient.ScrollablePreviewSlotsWidget;
import com.lowdragmc.mbd2.common.machine.definition.config.event.MachineUIEvent;
import com.lowdragmc.mbd2.integration.kubejs.recipe.MBDRecipeSchema;
import com.lowdragmc.mbd2.utils.FormattingUtil;
import com.lowdragmc.mbd2.utils.WidgetUtils;
import dev.latvian.mods.rhino.util.HideFromJS;
import dev.latvian.mods.rhino.util.RemapPrefixForJS;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.ItemLike;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Defines one MBD recipe family, including recipe lookup, proxy conversion,
 * builtin recipes, editor serialization, and XEI recipe UI creation.
 *
 * <p>The business goal is to let a machine definition own a cohesive recipe
 * namespace while still adapting recipes from vanilla or modded
 * {@link RecipeType}s. Runtime mutation is expected during registry/project
 * loading, recipe-manager reload, and editor interaction; recipe lookup and UI
 * construction should run on the thread that owns the current Minecraft or
 * editor context.</p>
 */
@Accessors(chain = true)
@RemapPrefixForJS("kjs$")
public class MBDRecipeType implements RecipeType<MBDRecipe>, ITagSerializable<CompoundTag>, IConfigurable {
    public static final MBDRecipeType DUMMY = new MBDRecipeType(MBD2.id("dummy"));

    /**
     * Factory for viewer widgets representing one recipe.
     */
    public interface UICreator {
        UICreator DEFAULT = recipe -> new WidgetGroup();

        /**
         * Creates a root widget for the supplied recipe.
         *
         * <p>Side effects depend on the implementation; project-loaded creators
         * usually deserialize widget trees and bind recipe placeholders.</p>
         *
         * @param recipe recipe whose contents and conditions should be shown
         * @return mutable widget root for recipe viewers
         */
        WidgetGroup create(MBDRecipe recipe);
    }

    @Configurable(name = "recipe_type.registry_name", tips = {
            "recipe_type.registry_name.tooltip",
            "config.require_restart"
    }, forceUpdate = false)
    @Getter
    private ResourceLocation registryName;
    @Setter
    @Getter
    private MBDRecipeBuilder recipeBuilder;
    @Setter
    @Getter
    @Configurable(name = "recipe_type.icon", tips = "recipe_type.icon.tooltip")
    private IGuiTexture icon = new ResourceTexture();
    @Setter
    @Getter
    @Configurable(name = "recipe_type.fuel_icon", tips = "recipe_type.fuel_icon.tooltip")
    private IGuiTexture fuelIcon = new ProgressTexture();
    @Setter
    @Getter
    @Configurable(name = "recipe_type.require_fuel", tips = "recipe_type.require_fuel.tooltip")
    protected boolean requireFuelForWorking;
    @Setter
    @Getter
    @Configurable(name = "recipe_type.is_xei_visible", tips = "recipe_type.is_xei_visible.tooltip")
    protected boolean isXEIVisible = true;
    @Setter
    @Getter
    @Configurable(name = "recipe_type.is_proxy_recipe_xei_visible", tips = "recipe_type.is_proxy_recipe_xei_visible.tooltip")
    protected boolean isProxyRecipeXEIVisible = false;
    private final List<RecipeType<?>> proxyRecipeTypes = new ArrayList<>();
    @Getter
    protected final Map<ResourceLocation, MBDRecipe> builtinRecipes = new LinkedHashMap<>();
    @Setter
    @Getter
    protected UICreator uiCreator = UICreator.DEFAULT;
    @Setter
    @Getter
    protected Size uiSize = new Size(176, 166);
    @Setter
    @Getter
    protected UICreator fuelUICreator = UICreator.DEFAULT;
    @Setter
    @Getter
    protected Size fuelUISize = new Size(176, 166);

    // run-time
    @Nullable
    @Setter
    @Getter
    private File projectFile;
    @Getter
    @Deprecated
    protected final Map<RecipeType<?>, List<MBDRecipe>> proxyRecipes = new HashMap<>();

    /**
     * Creates a recipe type with optional proxy recipe sources.
     *
     * <p>Preconditions: {@code registryName} should be stable and unique in the
     * recipe-type registry. Side effects: creates the default
     * {@link MBDRecipeBuilder} template and stores proxy type references.</p>
     *
     * @param registryName registry id used for recipes, fuel recipes, and UI
     * routing
     * @param proxyRecipes vanilla or modded recipe types that may be adapted into
     * this MBD type when the recipe manager loads
     */
    public MBDRecipeType(ResourceLocation registryName, RecipeType<?>... proxyRecipes) {
        this.registryName = registryName;
        recipeBuilder = new MBDRecipeBuilder(registryName, this);
        proxyRecipeTypes.addAll(Arrays.asList(proxyRecipes));
    }

    /**
     * Injects builtin recipes and rebuilds the proxy recipe cache after a recipe
     * manager reload.
     *
     * <p>Business goal: make editor-defined recipes and adapted proxy recipes
     * visible to normal recipe lookup. Preconditions: {@code rawRecipes} is the
     * mutable recipe-manager map for the current reload and should contain maps
     * for configured proxy types. Side effects: mutates {@code rawRecipes},
     * clears and repopulates {@link #proxyRecipes}, and posts proxy-transfer
     * events through {@link #toMBDrecipe(RecipeType, ResourceLocation, Recipe)}.</p>
     *
     * @param rawRecipes recipe manager data keyed first by recipe type and then
     * by recipe id
     */
    public void onRecipeManagerLoaded(Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> rawRecipes) {
        // append builtin recipes
        var recipeTypeMap = rawRecipes.computeIfAbsent(this, type -> new HashMap<>());
        builtinRecipes.values().forEach(recipe -> putRuntimeRecipe(recipeTypeMap, recipe));

        // load proxy recipes
        proxyRecipes.clear();
        for (var type : new LinkedHashSet<>(proxyRecipeTypes)) {
            var sourceRecipeMap = rawRecipes.get(type);
            if (sourceRecipeMap == null || sourceRecipeMap.isEmpty()) {
                proxyRecipes.put(type, Collections.emptyList());
                continue;
            }
            var recipes = new ArrayList<MBDRecipe>();
            for (var recipe : new ArrayList<>(sourceRecipeMap.entrySet())) {
                var mbdRecipe = toMBDrecipe(type, recipe.getKey(), recipe.getValue());
                if (mbdRecipe != null) {
                    recipes.add(mbdRecipe);
                    putRuntimeRecipe(recipeTypeMap, mbdRecipe);
                }
            }
            proxyRecipes.put(type, recipes);
        }
    }

    /**
     * KubeJS-oriented recipe-manager hook for injecting builtin and proxy
     * recipes into a flat recipe map.
     *
     * <p>Preconditions: {@code recipesByName} is mutable and represents one
     * recipe-manager reload. Side effects: mutates the map by adding builtin and
     * adapted proxy recipes, and rebuilds {@link #proxyRecipes}.</p>
     *
     * @param recipesByName flat recipe map keyed by recipe id
     */
    public void onRecipeManagerLoadedKjs(Map<ResourceLocation, Recipe<?>> recipesByName) {
        var sourceRecipes = new ArrayList<>(recipesByName.entrySet());
        builtinRecipes.values().forEach(recipe -> putRuntimeRecipe(recipesByName, recipe));
        // load proxy recipes
        proxyRecipes.clear();
        var proxyRecipeTypes = new LinkedHashSet<>(this.proxyRecipeTypes);

        if (proxyRecipeTypes.isEmpty()) return;
        for (var entry : sourceRecipes) {
            var key = entry.getKey();
            var recipe = entry.getValue();
            if (proxyRecipeTypes.contains(recipe.getType())) {
                var mbdRecipe = toMBDrecipe(recipe.getType(), key, recipe);
                if (mbdRecipe != null) {
                    proxyRecipes.computeIfAbsent(recipe.getType(), type -> new ArrayList<>()).add(mbdRecipe);
                }
            }
        }
        for (List<MBDRecipe> recipes : proxyRecipes.values()) {
            for (MBDRecipe recipe : recipes) {
                putRuntimeRecipe(recipesByName, recipe);
            }
        }
    }

    /**
     * Creates the editor default recipe type.
     *
     * @return new recipe type registered under {@code mbd2:recipe_type}
     */
    public static MBDRecipeType createDefault() {
        return new MBDRecipeType(MBD2.id("recipe_type"));
    }

    /**
     * Loads a packaged project recipe type from NBT and defers resource-dependent
     * UI initialization.
     *
     * <p>Business goal: turn an exported editor project into a runtime recipe
     * type without touching registries before textures, blocks, items, and other
     * resources are ready. Side effects: records {@code file}, immediately
     * updates the registry name, and appends a runnable that deserializes recipe
     * type settings and UI definitions when executed.</p>
     *
     * @param file source project file, or {@code null} for in-memory product data
     * @param tag project NBT containing {@code recipe_type}, resources, and UI
     * payloads
     * @param postTask queue that will run after mod loading completes
     * @return this recipe type for chaining
     */
    public MBDRecipeType loadProductiveTag(@Nullable File file, CompoundTag tag, Deque<Runnable> postTask) {
        this.projectFile = file;
        this.registryName = new ResourceLocation(tag.getCompound("recipe_type").getString("registryName"));
        postTask.add(() -> {
            var texturesResource = new TexturesResource();
            texturesResource.deserializeNBT(tag.getCompound("resources").getCompound(TexturesResource.RESOURCE_NAME));
            UIResourceTexture.setCurrentResource(texturesResource, false);
            deserializeNBT(tag.getCompound("recipe_type"));
            UIResourceTexture.clearCurrentResource();
            var uiTag = tag.getCompound("ui");
            var size = uiTag.getCompound("size");
            setUiSize(new Size(size.getInt("width"), size.getInt("height")));
            setUiCreator(recipe -> {
                var recipeUI = new WidgetGroup();
                recipeUI.setClientSideWidget();
                IConfigurableWidget.deserializeNBT(recipeUI, uiTag, texturesResource, false);
                bindXEIRecipeUI(recipeUI, recipe);
                recipeUI.setSelfPosition(0, 0);
                recipeUI.setBackground(IGuiTexture.EMPTY);
                return recipeUI;
            });
            if (requireFuelForWorking && tag.contains("fuelUI")) {
                var fuelUITag = tag.getCompound("fuelUI");
                var fuelSize= fuelUITag.getCompound("size");
                setFuelUISize(new Size(fuelSize.getInt("width"), fuelSize.getInt("height")));
                setFuelUICreator(recipe -> {
                    var recipeUI = new WidgetGroup();
                    recipeUI.setClientSideWidget();
                    IConfigurableWidget.deserializeNBT(recipeUI, fuelUITag, texturesResource, false);
                    bindXEIRecipeUI(recipeUI, recipe);
                    recipeUI.setSelfPosition(0, 0);
                    recipeUI.setBackground(IGuiTexture.EMPTY);
                    return recipeUI;
                });
            }

        });
        return this;
    }

    /**
     * Returns whether this type came from an external project file.
     *
     * @return {@code true} after {@link #loadProductiveTag(File, CompoundTag, Deque)}
     * was called with a non-null file
     */
    public boolean isCreatedFromProjectFile() {
        return projectFile != null;
    }

    /**
     * Reloads this type from its stored project file when available.
     *
     * <p>Side effects: reads NBT from disk, reloads recipe type state, and runs
     * deferred post-load tasks immediately. IO exceptions and missing tags are
     * ignored to preserve the current in-memory type.</p>
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
            } catch (IOException ignored) {}
        }
    }

    @Override
    public String toString() {
        return registryName.toString();
    }

    /**
     * Builds the synthetic recipe-type id used for fuel recipes.
     *
     * @return id in the same namespace as {@link #registryName} with
     * {@code .fuel} appended to the path
     */
    public ResourceLocation getFuelRegistryName() {
        return new ResourceLocation(registryName.getNamespace(), registryName.getPath() + ".fuel");
    }

    /**
     * Searches fuel recipes that can currently run against a holder.
     *
     * <p>Preconditions: {@code recipeManager} must be the active manager for the
     * server/editor context and {@code holder} must expose recipe capability
     * proxies. Side effects: matching is simulated by recipe capability handlers;
     * no recipe IO should be committed by this method.</p>
     *
     * @param recipeManager manager containing recipes for this type
     * @param holder machine or other capability holder used for matching
     * @return matching fuel recipes sorted by ascending priority, or an empty
     * list when fuel is not required or no proxies are available
     */
    public List<MBDRecipe> searchFuelRecipe(RecipeManager recipeManager, IRecipeCapabilityHolder holder) {
        if (!holder.hasProxies() || !isRequireFuelForWorking()) return Collections.emptyList();
        List<MBDRecipe> matches = new ArrayList<>();
        for (MBDRecipe recipe : recipeManager.getAllRecipesFor(this)) {
            if (recipe.isFuel && recipe.matchRecipe(holder).isSuccess() && recipe.matchTickRecipe(holder).isSuccess()) {
                matches.add(recipe);
            }
        }
        matches.sort(Comparator.comparingInt(r -> r.priority));
        return matches;
    }

    /**
     * Searches non-fuel recipes that can currently run against a holder.
     *
     * <p>Business goal: provide the candidate list consumed by
     * {@link RecipeLogic}. Matching runs on the caller's thread so a large
     * number of machine searches does not contend for the shared ForkJoin pool.
     * Side effects should be limited to simulated matching.</p>
     *
     * @param recipeManager manager containing recipes for this type
     * @param holder machine or other capability holder used for matching
     * @return matching non-fuel recipes sorted by ascending priority; empty when
     * the holder has no recipe proxies
     */
    public List<MBDRecipe> searchRecipe(RecipeManager recipeManager, IRecipeCapabilityHolder holder) {
        return searchRecipe(recipeManager, holder, recipe -> true);
    }

    /**
     * Searches non-fuel recipes after applying a cheap candidate prefilter.
     *
     * <p>The prefilter runs before capability simulation and is intended for schedulers that already know the recipe
     * ids a logical lane may execute. It must depend only on immutable recipe metadata and thread-safe snapshots when
     * asynchronous recipe searching is enabled.</p>
     *
     * @param recipeManager  manager containing recipes for this type
     * @param holder         machine or other capability holder used for matching
     * @param candidateFilter predicate evaluated before expensive recipe matching
     * @return matching non-fuel recipes sorted by ascending priority
     */
    public List<MBDRecipe> searchRecipe(RecipeManager recipeManager,
                                        IRecipeCapabilityHolder holder,
                                        Predicate<? super MBDRecipe> candidateFilter) {
        if (!holder.hasProxies()) return Collections.emptyList();
        Objects.requireNonNull(candidateFilter, "candidateFilter");
        List<MBDRecipe> matches = new ArrayList<>();
        for (MBDRecipe recipe : recipeManager.getAllRecipesFor(this)) {
            if (!recipe.isFuel
                    && candidateFilter.test(recipe)
                    && recipe.matchRecipe(holder).isSuccess()
                    && recipe.matchTickRecipe(holder).isSuccess()) {
                matches.add(recipe);
            }
        }
        matches.sort(Comparator.comparingInt(r -> r.priority));
        return matches;
    }

    /**
     * Resolves and matches exact recipe ids for an assigned logical recipe lane.
     *
     * <p>Subclasses that expose recipes outside the vanilla recipe-manager index may override this method while
     * retaining the same non-fuel and simulation-only contract.</p>
     *
     * @param recipeManager manager containing recipes for this type
     * @param holder        machine or other capability holder used for matching
     * @param candidateIds  exact recipe ids to resolve
     * @return matching recipes from this type sorted by ascending priority
     */
    public List<MBDRecipe> searchRecipesById(RecipeManager recipeManager,
                                             IRecipeCapabilityHolder holder,
                                             Set<ResourceLocation> candidateIds) {
        if (!holder.hasProxies() || candidateIds == null || candidateIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<MBDRecipe> matches = new ArrayList<>(candidateIds.size());
        for (ResourceLocation candidateId : candidateIds) {
            if (candidateId == null) continue;
            Recipe<?> candidate = recipeManager.byKey(candidateId).orElse(null);
            if (!(candidate instanceof MBDRecipe recipe)
                    || recipe.isFuel
                    || recipe.getType() != this) {
                continue;
            }
            if (recipe.matchRecipe(holder).isSuccess() && recipe.matchTickRecipe(holder).isSuccess()) {
                matches.add(recipe);
            }
        }
        matches.sort(Comparator.comparingInt(recipe -> recipe.priority));
        return matches;
    }

    //////////////////////////////////////
    //*****     Recipe Builder    ******//
    //////////////////////////////////////

    /**
     * Mutates the shared builder template before recipes are created from it.
     *
     * <p>Side effects: exposes {@link #recipeBuilder} to the supplied callback;
     * changes affect future builder copies created by this type.</p>
     *
     * @param onPrepare callback that configures the builder template
     * @return this recipe type for chaining
     */
    public MBDRecipeType prepareBuilder(Consumer<MBDRecipeBuilder> onPrepare) {
        onPrepare.accept(recipeBuilder);
        return this;
    }

    /**
     * Creates a recipe builder copy for a concrete id.
     *
     * <p>Business goal: keep type-level builder defaults while producing
     * independent builders for individual recipes. Side effects: none on this
     * type unless the returned builder is later saved as builtin.</p>
     *
     * @param id base recipe id
     * @param append optional id suffix fragments; each fragment is converted to
     * lower snake case and appended with underscores
     * @return mutable builder copy for the computed id
     */
    public MBDRecipeBuilder recipeBuilder(ResourceLocation id, Object... append) {
        if (append.length > 0) {
            return recipeBuilder.copy(new ResourceLocation(id.getNamespace(),
                    id.getPath() + Arrays.stream(append).map(Object::toString).map(FormattingUtil::toLowerCaseUnder).reduce("", (a, b) -> a + "_" + b)));
        }
        return recipeBuilder.copy(id);
    }

    /**
     * Creates a recipe builder under this mod namespace.
     *
     * @param id path relative to the MBD namespace helper
     * @param append optional id suffix fragments
     * @return mutable builder copy for the computed id
     */
    public MBDRecipeBuilder recipeBuilder(String id, Object... append) {
        return recipeBuilder(MBD2.id(id), append);
    }

    /**
     * Creates a recipe builder from a lazily supplied item.
     *
     * @param item supplier that must return a non-null item-like object
     * @param append optional id suffix fragments
     * @return mutable builder copy using the item's generated id
     */
    public MBDRecipeBuilder recipeBuilder(Supplier<? extends ItemLike> item, Object... append) {
        return recipeBuilder(item.get(), append);
    }

    /**
     * Creates a recipe builder from an item-like object's description id.
     *
     * @param itemLike item-like source used to derive the base id
     * @param append optional id suffix fragments
     * @return mutable builder copy for the computed id
     */
    @HideFromJS
    public MBDRecipeBuilder recipeBuilder(ItemLike itemLike, Object... append) {
        return recipeBuilder(new ResourceLocation(itemLike.asItem().getDescriptionId()), append);
    }

    /**
     * Creates the KubeJS-facing recipe builder schema.
     *
     * @return KubeJS wrapper object when KubeJS is loaded
     * @throws UnsupportedOperationException when KubeJS integration is absent
     */
    public Object kjs$recipeBuilder() {
        if (LDLib.isKubejsLoaded()) {
            return new MBDRecipeSchema.MBDRecipeJS(this);
        }
        throw new UnsupportedOperationException("KubeJS is not loaded");
    }

    /**
     * Copies another builder into this type's recipe namespace.
     *
     * @param builder builder whose content, duration, data, and conditions should
     * be reused
     * @return mutable builder copy with this type assigned and save callback
     * cleared
     */
    public MBDRecipeBuilder copyFrom(MBDRecipeBuilder builder) {
        return recipeBuilder.copyFrom(builder);
    }

    /**
     * Registers a callback invoked when this type's builders save datagen
     * recipes.
     *
     * <p>Side effects: mutates the shared builder template, so the callback
     * applies to future builder copies.</p>
     *
     * @param onBuild callback that receives the builder and datagen consumer
     * @return this recipe type for chaining
     */
    public MBDRecipeType onRecipeBuild(BiConsumer<MBDRecipeBuilder, Consumer<FinishedRecipe>> onBuild) {
        recipeBuilder.onSave(onBuild);
        return this;
    }

    /**
     * Converts an external recipe into this type's recipe model.
     *
     * <p>Business goal: allow vanilla or modded recipe types to appear in MBD
     * machines and recipe viewers. Preconditions: {@code recipeType},
     * {@code id}, and {@code recipe} must describe the same external recipe.
     * Side effects: logs transfer failures, builds a temporary recipe builder,
     * and posts {@link TransferProxyRecipeEvent}; listeners may replace or cancel
     * the converted recipe.</p>
     *
     * @param recipeType original recipe type
     * @param id original recipe id
     * @param recipe original recipe instance
     * @return converted MBD recipe, or {@code null} when conversion produced no
     * content or an event listener canceled it
     */
    @Nullable
    public MBDRecipe toMBDrecipe(RecipeType<?> recipeType, ResourceLocation id, Recipe<?> recipe) {
        MBDRecipe result = null;
        if (recipe instanceof MBDRecipe mbdRecipe) {
            if (mbdRecipe.recipeType == this || recipe.getType() == this) {
                return null;
            }
            var copied = mbdRecipe.copy(getProxyRecipeId(id));
            copied.recipeType = this;
            result = copied;
        } else {
            var newID = getProxyRecipeId(id);
            var builder = recipeBuilder(newID).recipeType(this);
            try {
                for (var ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    builder.inputItems(ingredient);
                }
            } catch (RuntimeException e) {
                MBD2.LOGGER.warn("Failed to transfer item inputs while adapting proxy recipe {}", id, e);
            }
            try {
                var resultItem = recipe.getResultItem(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
                if (!resultItem.isEmpty()) {
                    builder.outputItems(resultItem);
                }
            } catch (RuntimeException e) {
                MBD2.LOGGER.warn("Failed to transfer item outputs while adapting proxy recipe {}", id, e);
            }
            if (hasRecipeContent(builder.input) || hasRecipeContent(builder.output)) {
                if (recipe instanceof SmeltingRecipe smeltingRecipe) {
                    builder.duration(smeltingRecipe.getCookingTime());
                }
                builder.isXEIHidden(!isProxyRecipeXEIVisible);
                result = builder.buildRawRecipe();
            }
        }
        var proxyTypeId = ForgeRegistries.RECIPE_TYPES.getKey(recipeType);
        if (proxyTypeId != null) {
            var event = new TransferProxyRecipeEvent(this, proxyTypeId, recipeType, id, recipe, result);
            MinecraftForge.EVENT_BUS.post(event.postCustomEvent());
            if (event.isCanceled()) {
                return null;
            }
            return event.mbdRecipe;
        }
        return result;
    }

    private ResourceLocation getProxyRecipeId(ResourceLocation id) {
        return new ResourceLocation(registryName.getNamespace(), registryName.getPath() + "/" + id.getPath());
    }

    private static void putRuntimeRecipe(Map<ResourceLocation, Recipe<?>> recipeMap, MBDRecipe recipe) {
        recipeMap.put(recipe.getId(), recipe);
    }

    private static boolean hasRecipeContent(Map<RecipeCapability<?>, List<Content>> contents) {
        return contents.values().stream().anyMatch(content -> content != null && !content.isEmpty());
    }

    /**
     * Adds editor configurators for recipe-type-specific settings.
     *
     * <p>Side effects: appends controls to {@code father}; those controls mutate
     * {@link #proxyRecipeTypes} and persisted configurable fields.</p>
     *
     * @param father parent configurator group receiving child controls
     */
    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IConfigurable.super.buildConfigurator(father);
        var proxyGroup = new ArrayConfiguratorGroup<>("recipe_type.proxy_recipes", true,
                () -> proxyRecipeTypes,
                (getter, setter) -> new SearchComponentConfigurator<>("editor.machine.recipe_type", getter, setter,
                        RecipeType.SMELTING, true, (word, find) -> {
                    for (var recipeType : ForgeRegistries.RECIPE_TYPES) {
                        if (Thread.currentThread().isInterrupted()) return;
                        var id = ForgeRegistries.RECIPE_TYPES.getKey(recipeType);
                        if (id != null && id.toString().contains(word.toLowerCase())) {
                            find.accept(recipeType);
                        }
                    }}, recipeType -> Optional.ofNullable(ForgeRegistries.RECIPE_TYPES.getKey(recipeType)).map(Object::toString).orElse("missing")), false);
        proxyGroup.setAddDefault(() -> RecipeType.SMELTING);
        proxyGroup.setOnAdd(proxyRecipeTypes::add);
        proxyGroup.setOnRemove(proxyRecipeTypes::remove);
        proxyGroup.setOnUpdate(list -> {
            proxyRecipeTypes.clear();
            proxyRecipeTypes.addAll(list);
        });
        proxyGroup.setTips("recipe_type.proxy_recipes.tooltip");
        father.addConfigurators(proxyGroup);
    }

    //////////////////////////////////////
    //********    Serialize    *********//
    //////////////////////////////////////
    /**
     * Serializes configurable recipe-type state, proxy type ids, and builtin
     * recipes.
     *
     * @return NBT payload suitable for editor/project persistence
     */
    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        PersistedParser.serializeNBT(tag, this.getClass(), this);
        // proxyRecipeTypes
        var proxyTag = new ListTag();
        for (var type : proxyRecipeTypes) {
            var location = ForgeRegistries.RECIPE_TYPES.getKey(type);
            if (location != null) proxyTag.add(StringTag.valueOf(location.toString()));
        }
        tag.put("proxyRecipeTypes", proxyTag);
        // builtin recipes
        var recipesTag = new CompoundTag();
        for (var entry : builtinRecipes.entrySet()) {
            recipesTag.put(entry.getKey().toString(), MBDRecipeSerializer.SERIALIZER.toNBT(entry.getValue()));
        }
        tag.put("builtinRecipes", recipesTag);
        return tag;
    }

    /**
     * Restores configurable recipe-type state, proxy type ids, and builtin
     * recipes from NBT.
     *
     * <p>Preconditions: recipe type ids in the tag should already be registered.
     * Side effects: clears and repopulates proxy type and builtin recipe
     * collections, and assigns this type to deserialized builtin recipes.</p>
     *
     * @param tag payload produced by {@link #serializeNBT()} or a compatible
     * project file
     */
    @Override
    public void deserializeNBT(CompoundTag tag) {
        PersistedParser.deserializeNBT(tag, new HashMap<>(), this.getClass(), this);
        // proxyRecipeTypes
        proxyRecipeTypes.clear();
        var proxyTag = tag.getList("proxyRecipeTypes", 8);
        for (Tag type : proxyTag) {
            var location = new ResourceLocation(type.getAsString());
            var recipeType = ForgeRegistries.RECIPE_TYPES.getValue(location);
            if (recipeType != null) proxyRecipeTypes.add(recipeType);
        }
        // builtin recipes
        builtinRecipes.clear();
        var recipesTag = tag.getCompound("builtinRecipes");
        for (var key : recipesTag.getAllKeys()) {
            var recipe = MBDRecipeSerializer.SERIALIZER.fromNBT(new ResourceLocation(key), recipesTag.getCompound(key));
            recipe.recipeType = this;
            builtinRecipes.put(new ResourceLocation(key), recipe);
        }
    }


    //////////////////////////////////////
    //***********     UI    ************//
    //////////////////////////////////////

    /**
     * Binds standard recipe placeholders inside a recipe viewer widget tree.
     *
     * <p>Business goal: let project-authored UI templates display live recipe
     * duration, conditions, inputs, and outputs. Side effects: mutates matching
     * child widgets by setting labels, progress tooltips, content, ingredient
     * bindings, and hover tooltips.</p>
     *
     * @param ui root widget containing placeholder widget ids such as
     * {@code @progress_bar}
     * @param recipe recipe whose data should be shown
     */
    public void bindXEIRecipeUI(WidgetGroup ui, MBDRecipe recipe) {
        WidgetUtils.widgetByIdForEach(ui, "^@progress_bar$", ProgressWidget.class,
                progress -> progress.setHoverTooltips(Component.translatable("recipe.duration.value", recipe.duration)));
        WidgetUtils.widgetByIdForEach(ui, "^@duration$", LabelWidget.class,
                label -> label.setComponent(Component.translatable("recipe.duration.value", recipe.duration)));
        WidgetUtils.widgetByIdForEach(ui, "^@condition$", TextBoxWidget.class,
                textBoxWidget -> textBoxWidget.setContent(recipe.conditions.stream().map(RecipeCondition::getTooltips).map(Component::getString).toList()));
        bindCapIOUI(ui, recipe.inputs, IO.IN);
        bindCapIOUI(ui, recipe.outputs, IO.OUT);
    }

    /**
     * Binds capability-specific ingredient widgets in a viewer UI.
     *
     * <p>Side effects: delegates to capability binders and appends content
     * tooltips to widgets whose ids match capability, IO side, and content
     * index or explicit UI name.</p>
     *
     * @param ui root widget to search
     * @param values capability content grouped by capability
     * @param io content side represented by the widgets
     */
    private static void bindCapIOUI(WidgetGroup ui, Map<RecipeCapability<?>, List<Content>> values, IO io) {
        ScrollablePreviewSlotsWidget.bindXEIRecipeUI(ui, values, io);
        values.forEach((cap, contents) -> {
            for (int i = 0; i < contents.size(); i++) {
                var content = contents.get(i);
                var id = content.uiName.isEmpty() ? "^@%s_%s_%d$".formatted(cap.name, io.name, i) : Pattern.quote(content.uiName);
                for (var widget : WidgetUtils.getWidgetsById(ui, id)) {
                    cap.bindXEIWidget(widget, content, switch (io) {
                        case IN -> IngredientIO.INPUT;
                        case OUT -> IngredientIO.OUTPUT;
                        case BOTH -> IngredientIO.BOTH;
                        default -> IngredientIO.RENDER_ONLY;
                    });
                    var tooltips = new ArrayList<Component>();
                    content.appendTooltip(tooltips, cap.supportsOutputRange(content.minOutput, content.maxOutput)
                            && cap.supportsOutputMultiplier(content.content, content.maxOutput), io);
                    if (!tooltips.isEmpty()) {
                        widget.appendHoverTooltips(tooltips);
                    }
                }
            }
        });
    }

    /**
     * Creates the main recipe viewer UI and exposes it to integration events.
     *
     * <p>Side effects: runs the configured UI creator, posts
     * {@link RecipeUIEvent}, and marks the returned root as client-side.</p>
     *
     * @param recipe recipe to display
     * @return client-side root widget after event listeners have had a chance to
     * replace or mutate it
     */
    public WidgetGroup createRecipeUI(MBDRecipe recipe) {
        var ui = uiCreator.create(recipe);
        var event = new RecipeUIEvent(this, recipe, ui);
        MinecraftForge.EVENT_BUS.post(event.postKubeJSEvent());
        return event.getRoot().setClientSideWidget();
    }

    /**
     * Creates the fuel recipe viewer UI and exposes it to integration events.
     *
     * <p>Side effects: runs the configured fuel UI creator, posts
     * {@link FuelRecipeUIEvent}, and marks the returned root as client-side.</p>
     *
     * @param recipe fuel recipe to display
     * @return client-side root widget after event listeners have had a chance to
     * replace or mutate it
     */
    public WidgetGroup createFuelUI(MBDRecipe recipe) {
        var ui = fuelUICreator.create(recipe);
        var event = new FuelRecipeUIEvent(this, recipe, ui);
        MinecraftForge.EVENT_BUS.post(event.postKubeJSEvent());
        return event.getRoot().setClientSideWidget();
    }
}
