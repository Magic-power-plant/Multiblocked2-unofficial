package com.lowdragmc.mbd2.core.mixins.lowdraglib;

import com.lowdragmc.lowdraglib.gui.editor.runtime.PersistedParser;
import com.lowdragmc.mbd2.utils.PersistedParserCompat;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Keeps LowDragLib persisted-data loading usable on a dedicated server.
 *
 * <p>Some configurable widgets inherit client-only method signatures such as
 * {@code GuiGraphics}. The compatibility parser keeps field loading and safe setter side effects
 * available on both distributions.</p>
 */
@Mixin(value = PersistedParser.class, remap = false)
public abstract class PersistedParserMixin {

    /**
     * Replaces LowDragLib's deserializer with the server-safe compatibility implementation.
     *
     * @param tag     source NBT compound
     * @param setters caller-owned setter cache
     * @param clazz   class whose fields should be read
     * @param object  object receiving the persisted values
     * @author pingsu
     * @reason Avoid resolving client-only method signatures during server-side deserialization.
     */
    @Overwrite(remap = false)
    public static void deserializeNBT(CompoundTag tag, Map<String, Method> setters, Class<?> clazz, Object object) {
        PersistedParserCompat.deserializeNBT(tag, setters, clazz, object);
    }
}
