package com.lowdragmc.mbd2.common.machine.definition.config;

import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.NumberRange;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ArrayConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IToggleConfigurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.SearchComponentConfigurator;
import com.lowdragmc.mbd2.common.gui.editor.ValidatedStringConfigurator;
import com.lowdragmc.lowdraglib.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.recipe.MBDRecipeType;
import com.lowdragmc.mbd2.api.registry.MBDRegistries;
import com.lowdragmc.mbd2.utils.FileUtils;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Persisted recipe-logic settings for a machine definition.
 * <p>
 * This config selects the recipe type, enables or disables recipe processing,
 * controls search/modify behavior, and stores definition-level recipe
 * modifiers. The runtime logic reads these values from
 * {@link com.lowdragmc.mbd2.common.machine.MBDMachine#runRecipeLogic()} and
 * related recipe lifecycle callbacks.
 */
@Getter
@Builder
@Accessors(fluent = true)
public class ConfigRecipeLogicSettings implements IToggleConfigurable, IPersistedSerializable {
    @Builder.Default
    @Setter
    @Persisted
    @Accessors(fluent = false)
    private boolean enable = true;
    @Builder.Default
    @Persisted
    private ResourceLocation recipeType = MBDRecipeType.DUMMY.getRegistryName();
    @Builder.Default
    @Persisted
    private ResourceLocation[] recipeTypes = new ResourceLocation[]{MBDRecipeType.DUMMY.getRegistryName()};
    @Builder.Default
    @Getter
    protected final RecipeModifier.RecipeModifiers recipeModifiers = new RecipeModifier.RecipeModifiers();
    @Getter
    @Builder.Default
    @Configurable(name = "config.recipe_logic_settings.recipe_damping_value", tips = "config.recipe_logic_settings.recipe_damping_value.tooltip")
    @NumberRange(range = {0, Integer.MAX_VALUE})
    protected int recipeDampingValue = 2;
    @Getter
    @Builder.Default
    @Configurable(name = "config.recipe_logic_settings.consume_inputs_after_working", tips = {
            "config.recipe_logic_settings.consume_inputs_after_working.tooltip.0",
            "config.recipe_logic_settings.consume_inputs_after_working.tooltip.1"
    })
    protected boolean consumeInputsAfterWorking = false;
    @Getter
    @Builder.Default
    @Configurable(name = "config.recipe_logic_settings.always_search_recipe", tips = {
            "config.recipe_logic_settings.always_search_recipe.tooltip.0",
            "config.recipe_logic_settings.always_search_recipe.tooltip.1"
    })
    protected boolean alwaysSearchRecipe = false;
    @Getter
    @Builder.Default
    @Configurable(name = "config.recipe_logic_settings.always_modify_recipe", tips = {
            "config.recipe_logic_settings.always_modify_recipe.tooltip.0",
            "config.recipe_logic_settings.always_modify_recipe.tooltip.1"
    })
    protected boolean alwaysModifyRecipe = false;
    @Builder.Default
    @Persisted
    private String xeiCatalystGroup = "";
    @Builder.Default
    @Persisted
    @Configurable(name = "config.recipe_logic_settings.xei_catalyst_priority", tips = "config.recipe_logic_settings.xei_catalyst_priority.tooltip")
    @NumberRange(range = {Integer.MIN_VALUE, Integer.MAX_VALUE})
    private int xeiCatalystPriority = 0;

    public void setXeiCatalystGroup(String group) {
        String normalized = group == null ? "" : group.trim();
        if (!isValidXeiCatalystGroup(normalized)) {
            throw new IllegalArgumentException("Invalid XEI catalyst group: " + group);
        }
        xeiCatalystGroup = normalized;
    }

    public static boolean isValidXeiCatalystGroup(String group) {
        if (group == null || group.isEmpty()) return true;
        int separator = group.indexOf(':');
        return separator > 0 && separator < group.length() - 1
                && separator == group.lastIndexOf(':') && ResourceLocation.tryParse(group) != null;
    }

    public void setXeiCatalystPriority(int priority) {
        xeiCatalystPriority = priority;
    }

    /**
     * Resolves the primary configured recipe type from the MBD registry.
     *
     * @return first configured recipe type, or {@link MBDRecipeType#DUMMY} when no id is configured or registered
     */
    public MBDRecipeType getRecipeType() {
        syncRecipeTypes();
        return Arrays.stream(recipeTypes)
                .filter(Objects::nonNull)
                .map(id -> MBDRegistries.RECIPE_TYPES.getOrDefault(id, MBDRecipeType.DUMMY))
                .filter(type -> type != MBDRecipeType.DUMMY)
                .findFirst()
                .orElse(MBDRecipeType.DUMMY);
    }

    /**
     * Resolves all configured recipe types from the MBD registry.
     *
     * @return configured recipe types in UI order, excluding missing and dummy entries
     */
    public List<MBDRecipeType> getRecipeTypes() {
        syncRecipeTypes();
        return Arrays.stream(recipeTypes)
                .filter(Objects::nonNull)
                .map(id -> MBDRegistries.RECIPE_TYPES.getOrDefault(id, MBDRecipeType.DUMMY))
                .filter(type -> type != MBDRecipeType.DUMMY)
                .distinct()
                .toList();
    }

    public boolean isRecipeTypeAllowed(MBDRecipeType type) {
        return type != null && getRecipeTypes().contains(type);
    }

    @Override
    public CompoundTag serializeNBT() {
        syncRecipeTypes();
        var tag = IPersistedSerializable.super.serializeNBT();
        tag.put("recipeModifiers", recipeModifiers.serializeNBT());
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        xeiCatalystGroup = "";
        xeiCatalystPriority = 0;
        IPersistedSerializable.super.deserializeNBT(tag);
        migrateLegacyRecipeType(tag);
        syncRecipeTypes();
        recipeModifiers.deserializeNBT(tag.getList("recipeModifiers", Tag.TAG_COMPOUND));
    }

    private void migrateLegacyRecipeType(CompoundTag tag) {
        if (tag.contains("recipeTypes")) return;
        if (recipeType == null || recipeType.equals(MBDRecipeType.DUMMY.getRegistryName())) return;
        recipeTypes = new ResourceLocation[]{recipeType};
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IToggleConfigurable.super.buildConfigurator(father);
        // add recipe type configurator
        var candidates = new HashSet<ResourceLocation>();
        candidates.add(MBDRecipeType.DUMMY.getRegistryName());
        // add all loaded recipe types
        candidates.addAll(MBDRegistries.RECIPE_TYPES.keys());
        // add from files
        var path = new File(MBD2.getLocation(), "recipe_type");
        FileUtils.loadNBTFiles(path, ".rt", (file, tag) -> {
            var recipeType = tag.getCompound("recipe_type").getString("registryName");
            if (!recipeType.isEmpty() && ResourceLocation.isValidResourceLocation(recipeType)) {
                candidates.add(ResourceLocation.tryParse(recipeType));
            }
        });

        syncRecipeTypes();
        var recipeTypeGroup = new ArrayConfiguratorGroup<>("editor.machine.recipe_type", false,
                () -> Arrays.stream(recipeTypes).filter(Objects::nonNull).toList(),
                (getter, setter) -> new SearchComponentConfigurator<>("", getter, setter,
                        MBDRecipeType.DUMMY.getRegistryName(), true, (word, find) -> {
                    var lowerCase = word.toLowerCase();
                    for (var candidate : candidates) {
                        if (Thread.currentThread().isInterrupted()) return;
                        if (candidate.toString().contains(lowerCase)) {
                            find.accept(candidate);
                        }
                    }
                }, ResourceLocation::toString), false);
        recipeTypeGroup.setAddDefault(() -> MBDRecipeType.DUMMY.getRegistryName());
        recipeTypeGroup.setOnAdd(type -> {
            var updated = new ArrayList<>(Arrays.stream(recipeTypes)
                    .filter(Objects::nonNull)
                    .filter(existing -> !existing.equals(type))
                    .filter(existing -> type.equals(MBDRecipeType.DUMMY.getRegistryName()) || !existing.equals(MBDRecipeType.DUMMY.getRegistryName()))
                    .toList());
            updated.add(type);
            setRecipeTypes(updated);
        });
        recipeTypeGroup.setOnUpdate(this::setRecipeTypes);
        recipeTypeGroup.setOnRemove(type -> setRecipeTypes(Arrays.stream(recipeTypes)
                .filter(Objects::nonNull)
                .filter(existing -> !existing.equals(type))
                .toList()));
        father.addConfigurators(recipeTypeGroup);

        var group = new ValidatedStringConfigurator("config.recipe_logic_settings.xei_catalyst_group",
                this::xeiCatalystGroup, this::setXeiCatalystGroup, "",
                value -> isValidXeiCatalystGroup(value == null ? "" : value.trim()));
        group.setTips("config.recipe_logic_settings.xei_catalyst_group.tooltip");
        father.addConfigurators(group);

        recipeModifiers.buildConfigurator(father);
    }

    private void setRecipeTypes(List<ResourceLocation> types) {
        recipeTypes = types.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toArray(ResourceLocation[]::new);
        if (recipeTypes.length > 1) {
            recipeTypes = Arrays.stream(recipeTypes)
                    .filter(type -> !type.equals(MBDRecipeType.DUMMY.getRegistryName()))
                    .toArray(ResourceLocation[]::new);
        }
        if (recipeTypes.length == 0) {
            recipeTypes = new ResourceLocation[]{MBDRecipeType.DUMMY.getRegistryName()};
        }
        recipeType = recipeTypes[0];
    }

    private void syncRecipeTypes() {
        if (recipeTypes == null || recipeTypes.length == 0 || Arrays.stream(recipeTypes).allMatch(Objects::isNull)) {
            recipeTypes = new ResourceLocation[]{recipeType == null ? MBDRecipeType.DUMMY.getRegistryName() : recipeType};
        }
        recipeTypes = Arrays.stream(recipeTypes)
                .filter(Objects::nonNull)
                .distinct()
                .toArray(ResourceLocation[]::new);
        if (recipeTypes.length > 1) {
            recipeTypes = Arrays.stream(recipeTypes)
                    .filter(type -> !type.equals(MBDRecipeType.DUMMY.getRegistryName()))
                    .toArray(ResourceLocation[]::new);
        }
        if (recipeTypes.length == 0) {
            recipeTypes = new ResourceLocation[]{MBDRecipeType.DUMMY.getRegistryName()};
        }
        recipeType = recipeTypes[0];
    }
}
