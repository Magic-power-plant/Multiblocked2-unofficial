package com.lowdragmc.mbd2.common.xei;

import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.api.recipe.MBDRecipeType;
import com.lowdragmc.mbd2.common.machine.definition.MBDMachineDefinition;
import com.lowdragmc.mbd2.common.machine.definition.config.ConfigRecipeLogicSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Selects one registered machine per explicit group, independently for each recipe category. */
public final class XeiCatalystResolver {
    private XeiCatalystResolver() {}

    public record ResolvedCatalyst(ItemStack stack, ResourceLocation representative, String group,
                                   List<ResourceLocation> members) {
        public ResolvedCatalyst {
            stack = stack.copyWithCount(1);
            members = List.copyOf(members);
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }

    private record Bucket(boolean grouped, String id) implements Comparable<Bucket> {
        @Override
        public int compareTo(Bucket other) {
            int kind = Boolean.compare(grouped, other.grouped);
            return kind != 0 ? kind : id.compareTo(other.id);
        }
    }

    public static List<ResolvedCatalyst> resolve(MBDRecipeType recipeType,
                                                  Iterable<MBDMachineDefinition> definitions) {
        Map<Bucket, List<MBDMachineDefinition>> buckets = new TreeMap<>();
        Map<ResourceLocation, ItemStack> validStacks = new java.util.HashMap<>();
        Map<String, Integer> invalidGroups = new TreeMap<>();
        for (var definition : definitions) {
            if (definition == null || !definition.recipeLogicSettings().isRecipeTypeAllowed(recipeType)) continue;
            String group = definition.recipeLogicSettings().xeiCatalystGroup();
            group = group == null ? "" : group.trim();
            if (!ConfigRecipeLogicSettings.isValidXeiCatalystGroup(group)) {
                MBD2.LOGGER.warn("Ignoring invalid XEI catalyst group {} on {}", group, definition.id());
                group = "";
            }
            var item = definition.item();
            var itemId = item == null ? null : BuiltInRegistries.ITEM.getKey(item);
            var stack = item == null || item == Items.AIR || itemId == null
                    || BuiltInRegistries.ITEM.get(itemId) != item ? ItemStack.EMPTY : item.getDefaultInstance();
            if (stack.isEmpty()) {
                if (!group.isEmpty()) invalidGroups.merge(group, 1, Integer::sum);
                continue;
            }
            var bucket = group.isEmpty() ? new Bucket(false, definition.id().toString()) : new Bucket(true, group);
            validStacks.put(definition.id(), stack.copyWithCount(1));
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(definition);
        }
        Comparator<MBDMachineDefinition> preference = Comparator
                .comparingInt((MBDMachineDefinition d) -> d.recipeLogicSettings().xeiCatalystPriority())
                .reversed().thenComparing(d -> d.id().toString());
        var result = new ArrayList<ResolvedCatalyst>();
        for (var bucket : buckets.entrySet()) {
            var members = bucket.getValue();
            members.sort(preference);
            var chosen = members.get(0);
            var stack = validStacks.get(chosen.id());
            MBD2.LOGGER.debug("XEI catalyst type={} group={} representative={} priority={} item={}",
                    recipeType.getRegistryName(), bucket.getKey(), chosen.id(),
                    chosen.recipeLogicSettings().xeiCatalystPriority(), BuiltInRegistries.ITEM.getKey(stack.getItem()));
            var memberIds = members.stream().map(MBDMachineDefinition::id).toList();
            int duplicate = -1;
            for (int i = 0; i < result.size(); i++) {
                if (ItemStack.isSameItemSameTags(result.get(i).stack(), stack)) {
                    duplicate = i;
                    break;
                }
            }
            if (duplicate < 0) {
                result.add(new ResolvedCatalyst(stack, chosen.id(), bucket.getKey().grouped ? bucket.getKey().id : "", memberIds));
            } else {
                var old = result.get(duplicate);
                var combined = new ArrayList<>(old.members());
                combined.addAll(memberIds);
                result.set(duplicate, new ResolvedCatalyst(old.stack(), old.representative(), old.group(), combined));
            }
        }
        for (var group : invalidGroups.keySet()) {
            if (!buckets.containsKey(new Bucket(true, group)))
                MBD2.LOGGER.warn("No valid XEI catalyst for group {} in recipe type {}", group, recipeType.getRegistryName());
        }
        return List.copyOf(result);
    }
}
