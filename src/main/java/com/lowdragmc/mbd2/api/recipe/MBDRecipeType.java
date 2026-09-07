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
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
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

    /**
     * Determines how many times one target/source proxy relationship is
     * converted during a recipe-manager load.
     *
     * <p>The policy is evaluated once for each receiving MBD recipe type and
     * configured source recipe type pair. A value below one is normalized to
     * one, so a configured proxy source is never silently skipped. KubeJS uses
     * this capability to derive the count from
     * {@code onTransferProxyRecipe("mbd_recipe_type", ...)} or
     * {@code onTransferProxyRecipe("mbd_recipe_type@traversal", ...)}
     * listeners.</p>
     */
    @FunctionalInterface
    public interface ProxyRecipeTraversalPolicy {
        ProxyRecipeTraversalPolicy SINGLE_PASS = (targetRecipeType, sourceRecipeType) -> 1;

        /**
         * Returns the requested number of conversion traversals for one target
         * MBD recipe type and proxy source recipe type pair.
         *
         * @param targetRecipeType MBD recipe type receiving converted recipes
         * @param sourceRecipeType configured proxy source type
         * @return requested traversal count; values below one become one
         */
        int getTraversalCount(MBDRecipeType targetRecipeType, RecipeType<?> sourceRecipeType);
    }

    @Configurable(name = "recipe_type.registry_name", tips = {
            "recipe_type.registry_name.tooltip",
            "config.require_restart"
    }, forceUpdate = false)
    @Getter
    private ResourceLocation registryName;
    @Setter
    @Configurable(name = "recipe_type.display_name", tips = "recipe_type.display_name.tooltip")
    @Persisted
    private String displayName = "";
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
     * Loads builtin and proxy recipes for one or more recipe types.
     *
     * <p>Proxy sources can themselves be MBD recipe types.  A single pass is
     * order-dependent in that case: a type visited before its source type has
     * been expanded cannot see the generated recipes.  This overload expands
     * the complete set to a fixed point, while retaining source snapshots so
     * recipe maps can safely be modified during the pass.</p>
     *
     * @param rawRecipes recipe-manager data keyed by recipe type and recipe id
     * @param recipeTypes MBD recipe types to initialize and expand
     */
    public static void onRecipeManagerLoaded(
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> rawRecipes,
            Iterable<? extends MBDRecipeType> recipeTypes) {
        onRecipeManagerLoaded(rawRecipes, recipeTypes, ProxyRecipeTraversalPolicy.SINGLE_PASS);
    }

    /**
     * Loads builtin and proxy recipes with a source-specific traversal policy.
     *
     * <p>Each traversal receives the same source recipe and is distinguished
     * by the traversal number passed to {@link #toMBDrecipe(RecipeType,
     * ResourceLocation, Recipe, int)}. Traversal counts are scoped to the
     * receiving MBD type and source type, keeping independent proxy passes
     * available while the surrounding fixed-point expansion continues to load
     * chains of MBD recipe types.</p>
     *
     * @param rawRecipes recipe-manager data keyed by recipe type and recipe id
     * @param recipeTypes MBD recipe types to initialize and expand
     * @param traversalPolicy determines the number of passes for each target/source pair
     */
    public static void onRecipeManagerLoaded(
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> rawRecipes,
            Iterable<? extends MBDRecipeType> recipeTypes,
            ProxyRecipeTraversalPolicy traversalPolicy) {
        Objects.requireNonNull(rawRecipes, "rawRecipes");
        Objects.requireNonNull(traversalPolicy, "traversalPolicy");
        var targets = snapshotRecipeTypes(recipeTypes);
        if (targets.isEmpty()) return;

        var context = new ProxyLoadContext(traversalPolicy);
        for (var target : targets) {
            target.proxyRecipes.clear();
            var targetRecipes = rawRecipes.computeIfAbsent(target, type -> new HashMap<>());
            target.builtinRecipes.values().forEach(recipe -> putRuntimeRecipe(targetRecipes, recipe));
            for (var sourceType : target.uniqueProxyRecipeTypes()) {
                target.proxyRecipes.put(sourceType, new ArrayList<>());
            }
        }

        // Existing MBD recipes may have been inserted by another loader before
        // this pass.  Their owner is enough to seed cycle detection.
        context.seedMaps(rawRecipes.values());
        expandProxyRecipes(rawRecipes, targets, context);
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
        onRecipeManagerLoaded(rawRecipes, List.of(this));
    }

    /**
     * Loads builtin and proxy recipes into KubeJS' flat recipe map.
     *
     * <p>The KubeJS recipe event runs after scripts have created and modified
     * recipes, so the map is expanded repeatedly just like the normal recipe
     * manager table.  This also makes chained MBD proxy types independent of
     * registry iteration order.</p>
     *
     * @param recipesByName mutable flat map keyed by recipe id
     * @param recipeTypes MBD recipe types to initialize and expand
     */
    public static void onRecipeManagerLoadedKjs(
            Map<ResourceLocation, Recipe<?>> recipesByName,
            Iterable<? extends MBDRecipeType> recipeTypes) {
        onRecipeManagerLoadedKjs(recipesByName, recipeTypes, ProxyRecipeTraversalPolicy.SINGLE_PASS);
    }

    /**
     * Loads builtin and proxy recipes into KubeJS' flat recipe map with a
     * source-specific traversal policy.
     *
     * @param recipesByName mutable flat map keyed by recipe id
     * @param recipeTypes MBD recipe types to initialize and expand
     * @param traversalPolicy determines the number of passes for each target/source pair
     */
    public static void onRecipeManagerLoadedKjs(
            Map<ResourceLocation, Recipe<?>> recipesByName,
            Iterable<? extends MBDRecipeType> recipeTypes,
            ProxyRecipeTraversalPolicy traversalPolicy) {
        Objects.requireNonNull(recipesByName, "recipesByName");
        Objects.requireNonNull(traversalPolicy, "traversalPolicy");
        var targets = snapshotRecipeTypes(recipeTypes);
        if (targets.isEmpty()) return;

        var context = new ProxyLoadContext(traversalPolicy);
        for (var target : targets) {
            target.proxyRecipes.clear();
            target.builtinRecipes.values().forEach(recipe -> putRuntimeRecipe(recipesByName, recipe));
            for (var sourceType : target.uniqueProxyRecipeTypes()) {
                target.proxyRecipes.put(sourceType, new ArrayList<>());
            }
        }

        context.seedRecipes(recipesByName.values());
        expandProxyRecipesKjs(recipesByName, targets, context);
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
        onRecipeManagerLoadedKjs(recipesByName, List.of(this));
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
        var recipeTypeTag = tag.getCompound("recipe_type");
        this.registryName = new ResourceLocation(recipeTypeTag.getString("registryName"));
        // Keep the display fallback available before deferred resource/UI loading. XEI categories can be created
        // during client setup, before the post-load task runs.
        if (recipeTypeTag.contains("displayName")) {
            this.displayName = recipeTypeTag.getString("displayName");
        }
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
     * Resolves the localized name used by recipe viewers for this recipe type.
     *
     * <p>The registry-name translation remains authoritative when it exists. The editor-provided display name is
     * used as the fallback for projects that do not provide a translation, matching machine display-name behavior.</p>
     *
     * @return localized recipe-type name, or the configured display name when its translation is missing
     */
    public Component getDisplayName() {
        var translationKey = registryName.toLanguageKey();
        return getDisplayName(Component.translatable(translationKey), translationKey);
    }

    /**
     * Resolves a localized recipe-type name with an explicit translation component and fallback.
     *
     * <p>This overload mirrors the machine definition naming API and is useful to integrations that already have a
     * pre-built localized component.</p>
     *
     * @param localizedName normal localized component to return when no display name is configured
     * @param translationKey translation key represented by {@code localizedName}
     * @return localized component, using the configured display name only when the translation is unavailable
     */
    public Component getDisplayName(Component localizedName, String translationKey) {
        if (displayName != null && !displayName.isBlank()) {
            return Component.translatableWithFallback(translationKey, displayName);
        }
        return localizedName;
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
        return toMBDrecipe(recipeType, id, recipe, 1);
    }

    /**
     * Converts an external recipe during a numbered proxy traversal.
     *
     * <p>Traversal one remains the compatibility entry point for existing
     * {@link #toMBDrecipe(RecipeType, ResourceLocation, Recipe)} overrides.
     * Later traversals start from the same external source recipe so KubeJS
     * listeners can independently create or replace proxy recipes.</p>
     *
     * @param recipeType original recipe type
     * @param id original recipe id
     * @param recipe original recipe instance
     * @param traversal one-based traversal number
     * @return converted MBD recipe, or {@code null} when conversion produced no
     * content or an event listener canceled it
     */
    @Nullable
    public MBDRecipe toMBDrecipe(RecipeType<?> recipeType, ResourceLocation id, Recipe<?> recipe, int traversal) {
        if (traversal < 1) {
            throw new IllegalArgumentException("Proxy recipe traversal must be at least 1");
        }
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
            var event = new TransferProxyRecipeEvent(this, proxyTypeId, recipeType, id, recipe, traversal, result);
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

    /**
     * Takes a stable, identity-deduplicated snapshot of the recipe types that
     * participate in one load. Registry views are live and may be backed by a
     * mutable map, so retaining the caller's iterable would make the expansion
     * order and termination dependent on external mutations.
     */
    private static List<MBDRecipeType> snapshotRecipeTypes(Iterable<? extends MBDRecipeType> recipeTypes) {
        Objects.requireNonNull(recipeTypes, "recipeTypes");
        var snapshot = new ArrayList<MBDRecipeType>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<MBDRecipeType, Boolean>());
        for (var recipeType : recipeTypes) {
            if (recipeType != null && seen.add(recipeType)) {
                snapshot.add(recipeType);
            }
        }
        return snapshot;
    }

    /**
     * Returns the configured proxy sources in declaration order, ignoring null
     * entries and duplicate values.
     */
    private List<RecipeType<?>> uniqueProxyRecipeTypes() {
        var snapshot = new LinkedHashSet<RecipeType<?>>();
        for (var proxyType : proxyRecipeTypes) {
            if (proxyType != null) {
                snapshot.add(proxyType);
            }
        }
        return new ArrayList<>(snapshot);
    }

    /**
     * Repeatedly expands normal recipe-manager tables until every currently
     * reachable proxy source has been visited. Each source map is copied before
     * it is scanned because a target can also be one of its own proxy sources.
     */
    private static void expandProxyRecipes(
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> rawRecipes,
            List<MBDRecipeType> targets,
            ProxyLoadContext context) {
        boolean converted;
        do {
            converted = false;
            for (var target : targets) {
                var targetRecipes = rawRecipes.computeIfAbsent(target, type -> new HashMap<>());
                for (var sourceType : target.uniqueProxyRecipeTypes()) {
                    var sourceRecipes = rawRecipes.get(sourceType);
                    if (sourceRecipes == null || sourceRecipes.isEmpty()) {
                        continue;
                    }
                    for (var entry : new ArrayList<>(sourceRecipes.entrySet())) {
                        var sourceId = entry.getKey();
                        var sourceRecipe = entry.getValue();
                        if (sourceId == null || sourceRecipe == null || context.containsTarget(sourceRecipe, target)) {
                            continue;
                        }

                        var traversalCount = context.getTraversalCount(target, sourceType);
                        for (int traversal = 1; ; traversal++) {
                            if (context.markAttempt(target, sourceType, sourceId, sourceRecipe, traversal)) {
                                // Keep existing three-argument overrides active for the first pass.
                                var convertedRecipe = traversal == 1
                                        ? target.toMBDrecipe(sourceType, sourceId, sourceRecipe)
                                        : target.toMBDrecipe(sourceType, sourceId, sourceRecipe, traversal);
                                if (convertedRecipe != null) {
                                    context.record(sourceRecipe, convertedRecipe, target);
                                    putRuntimeRecipe(targetRecipes, convertedRecipe);
                                    target.addProxyRecipe(sourceType, convertedRecipe);
                                    converted = true;
                                }
                            }
                            if (traversal == traversalCount) {
                                break;
                            }
                        }
                    }
                }
            }
        } while (converted);
    }

    /**
     * KubeJS exposes one flat id-to-recipe map instead of the recipe-manager
     * type buckets. The same fixed-point expansion is applied while filtering
     * each snapshot by its recipe type.
     */
    private static void expandProxyRecipesKjs(
            Map<ResourceLocation, Recipe<?>> recipesByName,
            List<MBDRecipeType> targets,
            ProxyLoadContext context) {
        boolean converted;
        do {
            converted = false;
            var sourceSnapshot = new ArrayList<>(recipesByName.entrySet());
            for (var target : targets) {
                for (var sourceType : target.uniqueProxyRecipeTypes()) {
                    for (var entry : sourceSnapshot) {
                        var sourceId = entry.getKey();
                        var sourceRecipe = entry.getValue();
                        var recipeSourceType = sourceRecipe == null ? null : sourceRecipe.getType();
                        if (sourceId == null || sourceRecipe == null
                                || !sourceType.equals(recipeSourceType)
                                || context.containsTarget(sourceRecipe, target)) {
                            continue;
                        }

                        var traversalCount = context.getTraversalCount(target, recipeSourceType);
                        for (int traversal = 1; ; traversal++) {
                            if (context.markAttempt(target, recipeSourceType, sourceId, sourceRecipe, traversal)) {
                                // Keep existing three-argument overrides active for the first pass.
                                var convertedRecipe = traversal == 1
                                        ? target.toMBDrecipe(recipeSourceType, sourceId, sourceRecipe)
                                        : target.toMBDrecipe(recipeSourceType, sourceId, sourceRecipe, traversal);
                                if (convertedRecipe != null) {
                                    context.record(sourceRecipe, convertedRecipe, target);
                                    putRuntimeRecipe(recipesByName, convertedRecipe);
                                    target.addProxyRecipe(recipeSourceType, convertedRecipe);
                                    converted = true;
                                }
                            }
                            if (traversal == traversalCount) {
                                break;
                            }
                        }
                    }
                }
            }
        } while (converted);
    }

    private void addProxyRecipe(RecipeType<?> sourceType, MBDRecipe recipe) {
        var recipes = proxyRecipes.computeIfAbsent(sourceType, type -> new ArrayList<>());
        // The runtime recipe map is keyed by id, so retain only its active value
        // when two traversal passes intentionally return the same recipe id.
        recipes.removeIf(existing -> Objects.equals(existing.getId(), recipe.getId()));
        recipes.add(recipe);
    }

    /**
     * Per-load state used to make expansion deterministic and cycle-safe.
     * Recipe and recipe-type relationships are identity based: two distinct
     * runtime objects can legally expose the same id while still representing
     * different conversion attempts.
     */
    private static final class ProxyLoadContext {
        private final ProxyRecipeTraversalPolicy traversalPolicy;
        private final IdentityHashMap<MBDRecipeType, IdentityHashMap<RecipeType<?>, Integer>> traversalCounts = new IdentityHashMap<>();
        private final Set<ProxyConversionKey> attempted = new HashSet<>();
        private final IdentityHashMap<Recipe<?>, Set<MBDRecipeType>> ancestry = new IdentityHashMap<>();

        private ProxyLoadContext(ProxyRecipeTraversalPolicy traversalPolicy) {
            this.traversalPolicy = traversalPolicy;
        }

        private void seedMaps(Iterable<? extends Map<ResourceLocation, Recipe<?>>> recipeMaps) {
            for (var recipeMap : recipeMaps) {
                if (recipeMap != null) {
                    seedRecipes(recipeMap.values());
                }
            }
        }

        private void seedRecipes(Iterable<? extends Recipe<?>> recipes) {
            for (var recipe : recipes) {
                if (recipe instanceof MBDRecipe mbdRecipe && mbdRecipe.recipeType != null) {
                    owners(recipe).add(mbdRecipe.recipeType);
                }
            }
        }

        private int getTraversalCount(MBDRecipeType target, RecipeType<?> sourceType) {
            var targetCounts = traversalCounts.computeIfAbsent(target, ignored -> new IdentityHashMap<>());
            return targetCounts.computeIfAbsent(sourceType,
                    type -> Math.max(1, traversalPolicy.getTraversalCount(target, type)));
        }

        private boolean markAttempt(MBDRecipeType target, RecipeType<?> sourceType,
                                    ResourceLocation sourceId, Recipe<?> sourceRecipe, int traversal) {
            return attempted.add(new ProxyConversionKey(target, sourceType, sourceId, sourceRecipe, traversal));
        }

        private boolean containsTarget(Recipe<?> sourceRecipe, MBDRecipeType target) {
            var owners = ancestry.get(sourceRecipe);
            return owners != null && owners.contains(target);
        }

        private void record(Recipe<?> sourceRecipe, MBDRecipe convertedRecipe, MBDRecipeType target) {
            var owners = owners(convertedRecipe);
            var sourceOwners = ancestry.get(sourceRecipe);
            if (sourceOwners != null) {
                owners.addAll(sourceOwners);
            }
            owners.add(target);
            if (convertedRecipe.recipeType != null) {
                owners.add(convertedRecipe.recipeType);
            }
        }

        private Set<MBDRecipeType> owners(Recipe<?> recipe) {
            return ancestry.computeIfAbsent(recipe,
                    ignored -> Collections.newSetFromMap(new IdentityHashMap<MBDRecipeType, Boolean>()));
        }
    }

    private static final class ProxyConversionKey {
        private final MBDRecipeType target;
        private final RecipeType<?> sourceType;
        private final ResourceLocation sourceId;
        private final Recipe<?> sourceRecipe;
        private final int traversal;

        private ProxyConversionKey(MBDRecipeType target, RecipeType<?> sourceType,
                                   ResourceLocation sourceId, Recipe<?> sourceRecipe, int traversal) {
            this.target = target;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.sourceRecipe = sourceRecipe;
            this.traversal = traversal;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ProxyConversionKey key)) return false;
            return target == key.target
                    && sourceType == key.sourceType
                    && sourceRecipe == key.sourceRecipe
                    && Objects.equals(sourceId, key.sourceId)
                    && traversal == key.traversal;
        }

        @Override
        public int hashCode() {
            var result = System.identityHashCode(target);
            result = 31 * result + System.identityHashCode(sourceType);
            result = 31 * result + Objects.hashCode(sourceId);
            result = 31 * result + System.identityHashCode(sourceRecipe);
            result = 31 * result + traversal;
            return result;
        }
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
