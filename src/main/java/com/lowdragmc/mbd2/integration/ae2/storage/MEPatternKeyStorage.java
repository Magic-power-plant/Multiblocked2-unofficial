package com.lowdragmc.mbd2.integration.ae2.storage;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.lowdragmc.lowdraglib.syncdata.IContentChangeAware;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.mbd2.api.recipe.RecipeGroup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Persistent addon-key buffer. Each resource retains its pattern's recipe group across saves and snapshots. */
public final class MEPatternKeyStorage implements MERecipeKeyStorage, ITagSerializable<CompoundTag>, IContentChangeAware {
    public record Entry(GenericStack stack, String recipeGroup) {}

    private final long capacity;
    private final Predicate<AEKey> filter;
    private final List<Entry> entries = new ArrayList<>();
    private Runnable onContentsChanged = () -> {};

    public MEPatternKeyStorage(long capacity, Predicate<AEKey> filter) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.filter = filter;
    }

    public List<Entry> getEntries() {
        return List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public Runnable getOnContentsChanged() {
        return onContentsChanged;
    }

    @Override
    public void setOnContentsChanged(Runnable listener) {
        onContentsChanged = listener == null ? () -> {} : listener;
    }

    @Override
    public List<GenericStack> getContents(@Nullable String recipeGroup) {
        var group = RecipeGroup.normalizeOrDefault(recipeGroup);
        return entries.stream().filter(entry -> RecipeGroup.matches(group, entry.recipeGroup()))
                .map(Entry::stack).toList();
    }

    @Override
    public Set<String> getRecipeGroups() {
        var groups = new LinkedHashSet<String>();
        entries.forEach(entry -> groups.add(entry.recipeGroup()));
        if (groups.isEmpty()) groups.add(RecipeGroup.DEFAULT);
        return groups;
    }

    @Override
    public long insert(AEKey key, long amount, @Nullable String recipeGroup) {
        if (amount <= 0 || !filter.test(key)) return 0;
        var group = RecipeGroup.normalizeOrDefault(recipeGroup);
        long storedTotal = 0;
        int target = -1;
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (entry.stack().what().equals(key)) {
                storedTotal = saturatedAdd(storedTotal, entry.stack().amount());
                if (entry.recipeGroup().equals(group)) target = i;
            }
        }
        // A KeyCounter represents all groups together, so a key's total must fit in a long as well.
        long storedInGroup = target < 0 ? 0 : entries.get(target).stack().amount();
        long accepted = Math.min(amount, Math.min(Math.max(0, capacity - storedInGroup), Long.MAX_VALUE - storedTotal));
        if (accepted > 0) {
            var updated = new Entry(new GenericStack(key, storedInGroup + accepted), group);
            if (target < 0) entries.add(updated);
            else entries.set(target, updated);
            onContentsChanged.run();
        }
        return accepted;
    }

    @Override
    public long extract(AEKey key, long amount, @Nullable String recipeGroup) {
        if (amount <= 0) return 0;
        var group = RecipeGroup.normalizeOrDefault(recipeGroup);
        long remaining = amount;
        for (int i = 0; i < entries.size() && remaining > 0; ) {
            var entry = entries.get(i);
            if (!entry.stack().what().equals(key) || !RecipeGroup.matches(group, entry.recipeGroup())) {
                i++;
                continue;
            }
            long extracted = Math.min(remaining, entry.stack().amount());
            remaining -= extracted;
            long stored = entry.stack().amount() - extracted;
            if (stored == 0) entries.remove(i);
            else entries.set(i++, new Entry(new GenericStack(key, stored), entry.recipeGroup()));
        }
        if (remaining < amount) onContentsChanged.run();
        return amount - remaining;
    }

    public void getAvailableStacks(KeyCounter counter) {
        entries.forEach(entry -> counter.add(entry.stack().what(), entry.stack().amount()));
    }

    @Override
    public MEPatternKeyStorage copy() {
        var copy = new MEPatternKeyStorage(capacity, filter);
        copy.entries.addAll(entries);
        return copy;
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = new CompoundTag();
        var stacks = new ListTag();
        for (var entry : entries) {
            var stored = GenericStack.writeTag(entry.stack());
            stored.putString("recipeGroup", entry.recipeGroup());
            stacks.add(stored);
        }
        tag.put("stacks", stacks);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        entries.clear();
        var stacks = tag.getList("stacks", Tag.TAG_COMPOUND);
        for (int i = 0; i < stacks.size(); i++) {
            var stored = stacks.getCompound(i);
            var stack = GenericStack.readTag(stored);
            if (stack != null && stack.amount() > 0) {
                // Preserve saved amounts even if the configured input limit was lowered later.
                entries.add(new Entry(stack, RecipeGroup.normalizeOrDefault(stored.getString("recipeGroup"))));
            }
        }
    }

    public static long saturatedAdd(long left, long right) {
        return left + Math.min(right, Long.MAX_VALUE - left);
    }
}
