package com.lowdragmc.mbd2.integration.ae2.storage;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/** Recipe-facing key storage. Copies must be detached, including their change listeners. */
public interface MERecipeKeyStorage {
    List<GenericStack> getContents(@Nullable String recipeGroup);

    long insert(AEKey key, long amount, @Nullable String recipeGroup);

    long extract(AEKey key, long amount, @Nullable String recipeGroup);

    Set<String> getRecipeGroups();

    MERecipeKeyStorage copy();
}
