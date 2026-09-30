package com.lowdragmc.mbd2.api.pattern.preview;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

/** Component.copy() shares siblings and translation arguments; persisted snapshots must not. */
public final class ComponentSnapshots {
    private ComponentSnapshots() {}

    public static Component copy(Component component) {
        return Objects.requireNonNull(Component.Serializer.fromJson(Component.Serializer.toJson(component)));
    }

    public static List<Component> copy(List<Component> components) {
        return components.stream().map(ComponentSnapshots::copy).toList();
    }
}
