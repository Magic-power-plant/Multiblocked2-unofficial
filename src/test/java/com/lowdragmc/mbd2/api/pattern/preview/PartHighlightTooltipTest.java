package com.lowdragmc.mbd2.api.pattern.preview;

import com.lowdragmc.mbd2.XeiTestBootstrap;
import com.lowdragmc.mbd2.common.machine.definition.MBDMachineDefinition;
import com.lowdragmc.mbd2.common.machine.definition.config.ConfigPartSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PartHighlightTooltipTest {
    @BeforeAll static void bootstrap() { XeiTestBootstrap.init(); }
    @Test void configBuilderCopiesItsInputAndCreateBuilderAppliesDescriptions() {
        var text = Component.literal("before");
        var input = new ArrayList<Component>(List.of(text));
        var settings = ConfigPartSettings.builder().xeiPartTooltips(input).build();
        text.append(" after"); input.clear();
        assertEquals("before", settings.xeiPartTooltips().get(0).getString());
        var kinetic = com.lowdragmc.mbd2.integration.create.machine.CreateKineticMachineDefinition.builder()
                .xeiPartTooltip("kinetic").xeiCatalystPriority(5).build();
        kinetic.loadFactory();
        assertEquals("kinetic", kinetic.partSettings().xeiPartTooltips().get(0).getString());
        assertEquals(5, kinetic.recipeLogicSettings().xeiCatalystPriority());
    }
    @Test void deepSnapshotsIsolateSiblingsAndTranslationArguments() {
        var child = Component.literal("original");
        var original = Component.translatable("test.key", child).append(child);
        var settings = ConfigPartSettings.builder().build();
        settings.setXeiPartTooltips(List.of(original));
        var before = Component.Serializer.toJson(settings.xeiPartTooltips().get(0));
        child.append(" mutated");
        assertEquals(before, Component.Serializer.toJson(settings.xeiPartTooltips().get(0)));
        settings.xeiPartTooltips().get(0).getSiblings().clear();
        assertEquals(before, Component.Serializer.toJson(settings.xeiPartTooltips().get(0)));
    }

    @Test void replacementAppendEmptyAndUnsupportedBuildersHaveExplicitSemantics() {
        var builder = MBDMachineDefinition.builder().id(ResourceLocation.fromNamespaceAndPath("test", "part"))
                .xeiPartTooltips(List.of(Component.literal("one"))).xeiPartTooltip("two");
        var first = builder.build(); first.loadFactory();
        builder.xeiPartTooltip("later");
        assertEquals(List.of("one", "two"), first.partSettings().xeiPartTooltips().stream().map(Component::getString).toList());
        var cleared = builder.xeiPartTooltips(List.of()).build(); cleared.loadFactory();
        assertTrue(cleared.partSettings().xeiPartTooltips().isEmpty());
        var multi = com.lowdragmc.mbd2.common.machine.definition.MultiblockMachineDefinition.builder()
                .xeiPartTooltip("multi").xeiPartTooltipKey("test.key").build();
        multi.loadFactory();
        assertEquals(2, multi.partSettings().xeiPartTooltips().size());
        assertThrows(IllegalStateException.class, () -> com.lowdragmc.mbd2.common.machine.definition.EntityMachineDefinition.builder()
                .xeiPartTooltip("unsupported").build());
    }

    @Test void composerPreservesStandardLinesOnceAndReadsCustomDescriptionLive() {
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
        var settings = ConfigPartSettings.builder().build();
        settings.setXeiPartTooltips(List.of(Component.literal("old")));
        var entry = new PartHighlightEntry(PartHighlightIndex.ItemKey.of(stack), stack, java.util.Set.of(net.minecraft.core.BlockPos.ZERO),
                List.of(new PartHighlightIndex.ConstraintHint("source1", List.of(Component.literal("max=2"))),
                        new PartHighlightIndex.ConstraintHint("source2", List.of(Component.literal("max=5")))), List.of());
        PartTooltipProvider provider = (s, context) -> settings.xeiPartTooltips();
        var standard = List.<Component>of(Component.literal("Name"), Component.literal("General description"));
        var context = new PartTooltipProvider.Context(ResourceLocation.fromNamespaceAndPath("test", "controller"), PartHighlightIndex::unavailable);
        var before = com.lowdragmc.mbd2.client.xei.PartHighlightTooltipComposer.append(standard, entry, 0, provider, context);
        assertEquals(1, before.stream().filter(c -> c.getString().equals("Name")).count());
        assertEquals("old", before.get(2).getString());
        assertTrue(before.stream().anyMatch(c -> c.getString().equals("max=2")));
        assertTrue(before.stream().anyMatch(c -> c.getString().equals("max=5")));
        settings.setXeiPartTooltips(List.of(Component.literal("new")));
        var after = com.lowdragmc.mbd2.client.xei.PartHighlightTooltipComposer.append(standard, entry, 1, provider, context);
        assertEquals("new", after.get(2).getString());
        assertEquals(before.size() - 1, after.size());
        assertEquals(2, standard.size());
    }
    @Test
    void persistedComponentsPreserveStyleAndMissingFieldClearsPreviousValue() {
        var settings = ConfigPartSettings.builder().build();
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable("test.part.key").withStyle(net.minecraft.ChatFormatting.GREEN));
        settings.setXeiPartTooltips(lines);
        lines.clear();
        var restored = ConfigPartSettings.builder().build();
        restored.deserializeNBT(settings.serializeNBT());
        assertEquals(1, restored.xeiPartTooltips().size());
        assertEquals(Component.Serializer.toJson(settings.xeiPartTooltips().get(0)),
                Component.Serializer.toJson(restored.xeiPartTooltips().get(0)));
        restored.deserializeNBT(new CompoundTag());
        assertTrue(restored.xeiPartTooltips().isEmpty());
    }

    @Test
    void builderCompositionRetainsFactorySettingsInEitherOrder() {
        var id = ResourceLocation.fromNamespaceAndPath("test", "part");
        var factory = (MBDMachineDefinition.ConfigPartSettingsFactory) () -> ConfigPartSettings.builder().canShare(false).build();
        var a = MBDMachineDefinition.builder().id(id).partSettings(factory).xeiPartTooltip("first").build();
        var b = MBDMachineDefinition.builder().id(id).xeiPartTooltip("first").partSettings(factory).build();
        a.loadFactory();
        b.loadFactory();
        assertFalse(a.partSettings().canShare());
        assertFalse(b.partSettings().canShare());
        assertEquals(List.of("first"), a.partSettings().xeiPartTooltips().stream().map(Component::getString).toList());
        assertEquals(a.partSettings().xeiPartTooltips(), b.partSettings().xeiPartTooltips());
    }
}
