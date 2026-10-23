package com.lowdragmc.mbd2.client.renderer;

import com.lowdragmc.mbd2.common.event.CustomRendererEvent;
import com.lowdragmc.mbd2.integration.kubejs.events.CustomRendererEventJS;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomRendererRegistryTest {
    private static final ResourceLocation JAVA = ResourceLocation.parse("test:java");
    private static final ResourceLocation SCRIPT = ResourceLocation.parse("test:script");
    private static final ResourceLocation BEACON = ResourceLocation.parse("mbd2:beacon");
    private static final KubeJSRenderer.RenderSupplier JAVA_RENDERER = (be, partial, stack, buffer, light, overlay, data) -> {};
    private static final KubeJSRenderer.RenderSupplier SCRIPT_RENDERER = (be, partial, stack, buffer, light, overlay, data) -> {};

    @AfterEach
    void clearCallbacks() {
        KubeJSRenderer.renderFunctions.clear();
    }

    @Test
    void initializesDefaultsThenJavaThenScriptRegistrations() {
        CustomRendererRegistry.reload(event -> {
            assertTrue(KubeJSRenderer.renderFunctions.containsKey(BEACON));
            event.addRenderer(JAVA, JAVA_RENDERER);
            event.addRenderer(SCRIPT, JAVA_RENDERER);
        }, event -> {
            assertSame(JAVA_RENDERER, KubeJSRenderer.renderFunctions.get(JAVA));
            new CustomRendererEventJS(event).addRenderer(SCRIPT, SCRIPT_RENDERER);
        });

        assertSame(JAVA_RENDERER, KubeJSRenderer.renderFunctions.get(JAVA));
        assertSame(SCRIPT_RENDERER, KubeJSRenderer.renderFunctions.get(SCRIPT));
        assertDoesNotThrow(() -> KubeJSRenderer.renderFunctions.get(BEACON)
                .render(null, 0, new PoseStack(), null, 0, 0, new CompoundTag()));
    }

    @Test
    void creatingEventPayloadsPreservesAllExistingRegistrations() {
        new CustomRendererEvent().addRenderer(JAVA, JAVA_RENDERER);
        new CustomRendererEventJS().addRenderer(SCRIPT, SCRIPT_RENDERER);
        new CustomRendererEvent();
        new CustomRendererEventJS();

        assertSame(JAVA_RENDERER, KubeJSRenderer.renderFunctions.get(JAVA));
        assertSame(SCRIPT_RENDERER, KubeJSRenderer.renderFunctions.get(SCRIPT));
    }

    @Test
    void javaRegistrationAndRenderingDoNotResolveKubeJsOrJeiClasses() throws Exception {
        var root = KubeJSRenderer.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new URL[]{root}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("dev.latvian.mods.") || name.startsWith("mezz.jei.")) {
                        throw new ClassNotFoundException("Optional integration unavailable: " + name);
                    }
                    if (name.startsWith("com.lowdragmc.mbd2.client.renderer.")
                            || name.equals("com.lowdragmc.mbd2.common.event.CustomRendererEvent")) {
                        var loaded = findLoadedClass(name);
                        if (loaded == null) {
                            loaded = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        }) {
            var rendererType = loader.loadClass(KubeJSRenderer.class.getName());
            var supplierType = loader.loadClass(KubeJSRenderer.RenderSupplier.class.getName());
            var eventType = loader.loadClass(CustomRendererEvent.class.getName());
            var calls = new AtomicInteger();
            var callback = Proxy.newProxyInstance(loader, new Class<?>[]{supplierType}, (proxy, method, arguments) -> {
                calls.incrementAndGet();
                return null;
            });
            eventType.getMethod("addRenderer", ResourceLocation.class, supplierType)
                    .invoke(eventType.getConstructor().newInstance(), JAVA, callback);
            var renderer = rendererType.getConstructor().newInstance();
            rendererType.getField("rendererName").set(renderer, JAVA);
            rendererType.getMethod("render", net.minecraft.world.level.block.entity.BlockEntity.class,
                            float.class, PoseStack.class, net.minecraft.client.renderer.MultiBufferSource.class, int.class, int.class)
                    .invoke(renderer, null, 0f, new PoseStack(), null, 0, 0);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void newRegistrationPassDropsStaleCallbacksAndWorksWithoutScripts() {
        CustomRendererRegistry.reload(event -> event.addRenderer(JAVA, JAVA_RENDERER),
                event -> event.addRenderer(SCRIPT, SCRIPT_RENDERER));
        CustomRendererRegistry.reload(event -> event.addRenderer(JAVA, SCRIPT_RENDERER), event -> {});

        assertSame(SCRIPT_RENDERER, KubeJSRenderer.renderFunctions.get(JAVA));
        assertFalse(KubeJSRenderer.renderFunctions.containsKey(SCRIPT));
        assertTrue(KubeJSRenderer.renderFunctions.containsKey(BEACON));
    }
}
