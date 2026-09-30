package com.lowdragmc.mbd2.integration.ae2.storage;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.GenericStack;
import appeng.util.ConfigInventory;
import com.lowdragmc.mbd2.api.recipe.RecipeGroup;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Live view of interface slots; simulations retain occupied slots of every key type. */
public final class MEInterfaceKeyStorage implements MERecipeKeyStorage {
    private final ConfigInventory inventory;

    public MEInterfaceKeyStorage(ConfigInventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public List<GenericStack> getContents(@Nullable String recipeGroup) {
        var contents = new ArrayList<GenericStack>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack != null && stack.amount() > 0) contents.add(stack);
        }
        return contents;
    }

    @Override
    public long insert(AEKey key, long amount, @Nullable String recipeGroup) {
        if (amount <= 0) return 0;
        long remaining = amount;
        inventory.beginBatch();
        try {
            // Top up matching slots before using empty slots. Bound additions before calling AE2 to avoid overflow.
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
                    var stored = inventory.getKey(slot);
                    if (pass == 0 ? !key.equals(stored) : stored != null) continue;
                    long space = Math.max(0, inventory.getMaxAmount(key) - inventory.getAmount(slot));
                    remaining -= inventory.insert(slot, key, Math.min(remaining, space), Actionable.MODULATE);
                }
            }
        } finally {
            inventory.endBatch();
        }
        return amount - remaining;
    }

    @Override
    public long extract(AEKey key, long amount, @Nullable String recipeGroup) {
        if (amount <= 0) return 0;
        long remaining = amount;
        inventory.beginBatch();
        try {
            for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
                remaining -= inventory.extract(slot, key, remaining, Actionable.MODULATE);
            }
        } finally {
            inventory.endBatch();
        }
        return amount - remaining;
    }

    @Override
    public Set<String> getRecipeGroups() {
        return Set.of(RecipeGroup.DEFAULT);
    }

    @Override
    public MEInterfaceKeyStorage copy() {
        return new MEInterfaceKeyStorage(copyInventory(inventory));
    }

    public static ConfigInventory copyInventory(ConfigInventory inventory) {
        var snapshot = ConfigInventory.configStacks(inventory.getFilter(), inventory.size(), null, true);
        AEKeyTypes.getAll().forEach(type -> snapshot.setCapacity(type, inventory.getCapacity(type)));
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (stack != null) {
                snapshot.setCapacity(stack.what().getType(), inventory.getMaxAmount(stack.what()));
                snapshot.setStack(slot, stack);
            }
        }
        return snapshot;
    }
}
