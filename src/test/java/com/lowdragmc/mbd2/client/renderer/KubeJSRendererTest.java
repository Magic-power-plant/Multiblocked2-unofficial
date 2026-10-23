package com.lowdragmc.mbd2.client.renderer;

import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib.client.renderer.IRenderer;
import com.lowdragmc.lowdraglib.client.renderer.impl.UIResourceRenderer;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.Direction;
import com.lowdragmc.mbd2.XeiTestBootstrap;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KubeJSRendererTest {
    private static final ResourceLocation NAME = ResourceLocation.parse("test:renderer");

    @BeforeAll
    static void bootstrap() {
        XeiTestBootstrap.init();
    }

    @AfterEach
    void clearCallbacks() {
        KubeJSRenderer.renderFunctions.clear();
    }

    @Test
    void passesFrameArgumentsAndRestoresPoseAfterRendering() {
        var renderer = new KubeJSRenderer();
        renderer.setRendererName(NAME);
        var data = new CompoundTag();
        data.putInt("color", 0x123456);
        renderer.setData(data);
        var pose = new PoseStack();
        pose.translate(2, 3, 4);
        var original = pose.last();
        var calls = new AtomicInteger();
        CustomRendererRegistry.register(NAME, (be, partial, stack, buffer, light, overlay, tag) -> {
            calls.incrementAndGet();
            assertNull(be);
            assertSame(pose, stack);
            assertNull(buffer);
            assertEquals(0.5f, partial);
            assertEquals(123, light);
            assertEquals(456, overlay);
            assertSame(data, tag);
            stack.translate(10, 20, 30);
        });

        renderer.render(null, 0.5f, pose, null, 123, 456);

        assertEquals(1, calls.get());
        assertSame(original, pose.last());
    }

    @Test
    void restoresPoseWhenCallbackFails() {
        var renderer = new KubeJSRenderer();
        renderer.setRendererName(NAME);
        var pose = new PoseStack();
        var original = pose.last();
        var failure = new IllegalStateException("broken callback");
        CustomRendererRegistry.register(NAME, (be, partial, stack, buffer, light, overlay, data) -> {
            stack.translate(10, 20, 30);
            throw failure;
        });

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> renderer.render(null, 0, pose, null, 0, 0)));
        assertSame(original, pose.last());
    }

    @Test
    void unknownRendererDoesNotChangePose() {
        var renderer = new KubeJSRenderer();
        renderer.setRendererName(NAME);
        var pose = new PoseStack();
        var original = pose.last();

        renderer.render(null, 0, pose, null, 0, 0);

        assertSame(original, pose.last());
    }

    @Test
    void exposesConfiguredVisibilityAndDistance() {
        var renderer = new KubeJSRenderer();
        assertTrue(renderer.hasTESR(null));
        assertFalse(renderer.isGlobalRenderer(null));
        assertEquals(64, renderer.getViewDistance());
        renderer.setRenderOffScreen(true);
        renderer.setMaxRenderDistance(192);
        assertTrue(renderer.isGlobalRenderer(null));
        assertEquals(192, renderer.getViewDistance());
        renderer.setMaxRenderDistance(0);
        assertEquals(1, renderer.getViewDistance());
    }

    @Test
    void appliesDistanceLimitThroughMachineAndResourceWrappers() {
        var renderer = new KubeJSRenderer();
        renderer.setMaxRenderDistance(32);
        IRenderer resource = new UIResourceRenderer(Either.left("custom")) {
            @Override
            public IRenderer getRenderer() {
                return renderer;
            }
        };
        var composite = new MachineStateRenderer(resource, IRenderer.EMPTY, Direction.NORTH);

        assertEquals(32, KubeJSRenderer.limitRenderDistance(composite, 128));
        assertEquals(16, KubeJSRenderer.limitRenderDistance(composite, 16));
        assertEquals(192, KubeJSRenderer.limitRenderDistance(IRenderer.EMPTY, 192));
        assertEquals(192, KubeJSRenderer.limitRenderDistance(
                new MachineStateRenderer(IRenderer.EMPTY, IRenderer.EMPTY, Direction.NORTH), 192));
    }

    @Test
    void cyclicResourceReferencesDoNotHangDistanceChecks() {
        IRenderer cyclic = new UIResourceRenderer(Either.left("cycle")) {
            @Override
            public IRenderer getRenderer() {
                return this;
            }
        };
        assertEquals(128, KubeJSRenderer.limitRenderDistance(cyclic, 128));
    }

    @Test
    void persistsUpstreamRendererFields() {
        var original = new KubeJSRenderer();
        original.setRendererName(NAME);
        original.setRenderOffScreen(true);
        original.setMaxRenderDistance(128);
        original.getData().putFloat("glow_radius", 0.75f);
        var restored = new KubeJSRenderer();
        restored.deserializeNBT(original.serializeNBT());

        assertEquals(NAME, restored.getRendererName());
        assertEquals(original.getData(), restored.getData());
        assertTrue(restored.isGlobalRenderer(null));
        assertEquals(128, restored.getViewDistance());
        assertEquals("kubejs", KubeJSRenderer.class.getAnnotation(LDLRegisterClient.class).name());
    }

    @Test
    void retainsCallbackParameterNamesForKubeJS() throws ReflectiveOperationException {
        var method = KubeJSRenderer.RenderSupplier.class.getDeclaredMethods()[0];
        assertTrue(method.getParameters()[0].isNamePresent());
        assertEquals("blockEntity", method.getParameters()[0].getName());
        assertEquals("data", method.getParameters()[6].getName());
    }
}
