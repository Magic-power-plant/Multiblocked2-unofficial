package com.lowdragmc.mbd2;

import com.lowdragmc.lowdraglib.syncdata.TypedPayloadRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

/** Initialize the two registries normally supplied by the Minecraft/LDLib launchers. */
public final class XeiTestBootstrap {
    private static boolean initialized;
    private XeiTestBootstrap() {}
    public static synchronized void init() {
        if (initialized) return;
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (ExceptionInInitializerError error) {
            // Forge's NetworkEvent listener bootstrap requires a full mod launcher in plain JUnit.
            if (error.getCause() == null || !String.valueOf(error.getCause().getMessage()).contains("NetworkEvent")
                    || BuiltInRegistries.ITEM.getKey(Items.STONE) == null) throw error;
        }
        TypedPayloadRegistries.init();
        TypedPayloadRegistries.postInit();
        initialized = true;
    }
}
