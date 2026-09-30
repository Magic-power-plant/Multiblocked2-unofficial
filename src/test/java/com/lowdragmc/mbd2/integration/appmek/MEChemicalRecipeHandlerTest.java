package com.lowdragmc.mbd2.integration.appmek;

import appeng.api.stacks.GenericStack;
import appeng.util.ConfigInventory;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.mbd2.api.capability.recipe.IO;
import com.lowdragmc.mbd2.api.recipe.RecipeConsumptionTracker;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.slurry.SlurryStack;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.trait.ITrait;
import com.lowdragmc.mbd2.common.trait.RecipeCapabilityTrait;
import com.lowdragmc.mbd2.common.trait.RecipeCapabilityTraitDefinition;
import com.lowdragmc.mbd2.integration.ae2.storage.MEInterfaceKeyStorage;
import com.lowdragmc.mbd2.integration.mekanism.MekanismChemicalRecipeCapability;
import mekanism.api.chemical.gas.GasStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MEChemicalRecipeHandlerTest {
    @BeforeAll static void bootstrap() { AppliedMekanisticsTestBootstrap.init(); }

    @Test void simulationCannotReuseTheSameChemicalForTwoInputs() {
        var buffer = MEChemicalStorageTest.buffer(1000);
        buffer.insert(MEChemicalStorageTest.gasKey(), 100, "AAAA");
        var handler = new MEChemicalRecipeHandler<>(trait(IO.IN), MekanismChemicalRecipeCapability.CAP_GAS,
                GasStack.class, () -> buffer, true);
        var ingredient = MekanismChemicalRecipeCapability.CAP_GAS.createIngredient(AppliedMekanisticsTestBootstrap.gas, 60);
        var remaining = handler.handleRecipe(IO.IN, null, List.of(ingredient, ingredient), null, true, "AAAA");
        assertNotNull(remaining);
        assertEquals(1, remaining.size());
        assertEquals(20, remaining.get(0).getNeededAmount(new GasStack(AppliedMekanisticsTestBootstrap.gas, 1)));
        assertEquals(100, buffer.getContents("AAAA").get(0).amount());
        assertEquals(remaining, handler.handleRecipe(IO.IN, null, List.of(ingredient, ingredient), null, false, "AAAA"));
        assertTrue(buffer.isEmpty());
    }

    @Test void patternGroupsAndInputOnlySemanticsAreHonored() {
        var buffer = MEChemicalStorageTest.buffer(1000);
        buffer.insert(MEChemicalStorageTest.gasKey(), 100, "AAAA");
        var handler = new MEChemicalRecipeHandler<>(trait(IO.BOTH), MekanismChemicalRecipeCapability.CAP_GAS,
                GasStack.class, () -> buffer, true);
        var ingredient = MekanismChemicalRecipeCapability.CAP_GAS.createIngredient(AppliedMekanisticsTestBootstrap.gas, 100);
        assertNotNull(handler.handleRecipe(IO.IN, null, List.of(ingredient), null, false, "BBBB"));
        assertNotNull(handler.handleRecipe(IO.OUT, null, List.of(ingredient), null, false, "AAAA"));
        assertEquals(100, buffer.getContents("AAAA").get(0).amount());
        assertNull(handler.handleRecipe(IO.IN, null, List.of(ingredient), null, false, "AAAA"));
        assertTrue(buffer.isEmpty());
    }

    @Test void interfaceOutputsRespectCapacityAndReturnTheUnstoredRemainder() {
        var inventory = ConfigInventory.configStacks(null, 1, null, true);
        var key = MEChemicalStorageTest.gasKey();
        inventory.setCapacity(key.getType(), 100);
        inventory.setStack(0, new GenericStack(key, 80));
        var storage = new MEInterfaceKeyStorage(inventory);
        var handler = new MEChemicalRecipeHandler<>(trait(IO.BOTH), MekanismChemicalRecipeCapability.CAP_GAS,
                GasStack.class, () -> storage, false);
        var output = MekanismChemicalRecipeCapability.CAP_GAS.createIngredient(AppliedMekanisticsTestBootstrap.gas, 50);
        var simulated = handler.handleRecipe(IO.OUT, null, List.of(output), null, true);
        assertNotNull(simulated);
        assertEquals(30, MekanismChemicalRecipeCapability.CAP_GAS.getAmount(simulated.get(0)));
        assertEquals(80, inventory.getAmount(0));
        var actual = handler.handleRecipe(IO.OUT, null, List.of(output), null, false);
        assertEquals(30, MekanismChemicalRecipeCapability.CAP_GAS.getAmount(actual.get(0)));
        assertEquals(100, inventory.getAmount(0));
    }

    @Test void traitIODisablesChemicalConsumption() {
        var buffer = MEChemicalStorageTest.buffer(1000);
        buffer.insert(MEChemicalStorageTest.gasKey(), 100, null);
        var handler = new MEChemicalRecipeHandler<>(trait(IO.OUT), MekanismChemicalRecipeCapability.CAP_GAS,
                GasStack.class, () -> buffer, false);
        var input = MekanismChemicalRecipeCapability.CAP_GAS.createIngredient(AppliedMekanisticsTestBootstrap.gas, 100);
        assertNotNull(handler.handleRecipe(IO.IN, null, List.of(input), null, false));
        assertEquals(100, buffer.getContents(null).get(0).amount());
    }

    @Test void allFourHandlerTypesConsumeAndProduceExactLongAmounts() {
        verifyLongIO(MekanismChemicalRecipeCapability.CAP_GAS, GasStack.class, AppliedMekanisticsTestBootstrap.gas);
        verifyLongIO(MekanismChemicalRecipeCapability.CAP_INFUSE, InfusionStack.class, AppliedMekanisticsTestBootstrap.infusion);
        verifyLongIO(MekanismChemicalRecipeCapability.CAP_PIGMENT, PigmentStack.class, AppliedMekanisticsTestBootstrap.pigment);
        verifyLongIO(MekanismChemicalRecipeCapability.CAP_SLURRY, SlurryStack.class, AppliedMekanisticsTestBootstrap.slurry);
    }

    private <C extends Chemical<C>, S extends ChemicalStack<C>> void verifyLongIO(
            MekanismChemicalRecipeCapability<C, S> capability, Class<S> stackType, C chemical) {
        long amount = (1L << 54) + 37;
        var inventory = ConfigInventory.configStacks(null, 1, null, true);
        var storage = new MEInterfaceKeyStorage(inventory);
        var handler = new MEChemicalRecipeHandler<>(trait(IO.BOTH), capability, stackType, () -> storage, false);
        var requirement = capability.createIngredient(chemical, amount);
        assertNull(handler.handleRecipe(IO.OUT, null, List.of(requirement), "chemical", true));
        assertTrue(inventory.isEmpty());
        assertNull(handler.handleRecipe(IO.OUT, null, List.of(requirement), "chemical", false));
        assertEquals(amount, inventory.getAmount(0));
        try (var tracker = RecipeConsumptionTracker.start()) {
            assertNull(handler.handleRecipe(IO.IN, null, List.of(requirement), "chemical", true));
            assertTrue(tracker.snapshot().isEmpty());
            assertEquals(amount, inventory.getAmount(0));
            assertNull(handler.handleRecipe(IO.IN, null, List.of(requirement), "chemical", false));
            var entry = tracker.snapshot().getEntries().get(0);
            assertEquals(amount, stackType.cast(entry.content()).getAmount());
            assertEquals("chemical", entry.slotName());
        }
        assertTrue(inventory.isEmpty());
    }

    @Test void integrationFactoryRegistersEachChemicalCapabilityOnce() {
        var storage = MEChemicalStorageTest.buffer(1000);
        var handlers = AppliedMekanisticsCompat.createRecipeHandlers(trait(IO.BOTH), () -> storage, false);
        assertEquals(java.util.Set.of(MekanismChemicalRecipeCapability.CAP_GAS,
                        MekanismChemicalRecipeCapability.CAP_INFUSE, MekanismChemicalRecipeCapability.CAP_PIGMENT,
                        MekanismChemicalRecipeCapability.CAP_SLURRY),
                handlers.stream().map(handler -> handler.getRecipeCapability()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, handlers.size());
    }

    @Test void differentChemicalFormsCannotSatisfyGasInput() {
        var buffer = MEChemicalStorageTest.buffer(1000);
        var infusion = me.ramidzkh.mekae2.ae2.MekanismKey.of(new InfusionStack(AppliedMekanisticsTestBootstrap.infusion, 100));
        buffer.insert(infusion, 100, null);
        var handler = new MEChemicalRecipeHandler<>(trait(IO.IN), MekanismChemicalRecipeCapability.CAP_GAS,
                GasStack.class, () -> buffer, false);
        var requirement = MekanismChemicalRecipeCapability.CAP_GAS.createIngredient(AppliedMekanisticsTestBootstrap.gas, 100);
        assertNotNull(handler.handleRecipe(IO.IN, null, List.of(requirement), null, false));
        assertEquals(100, buffer.getContents(null).get(0).amount());
    }

    @Test void matchingInputCanBeCollectedFromSeveralInterfaceSlots() {
        var inventory = ConfigInventory.configStacks(null, 2, null, true);
        var key = MEChemicalStorageTest.gasKey();
        inventory.setStack(0, new GenericStack(key, 30));
        inventory.setStack(1, new GenericStack(key, 70));
        var storage = new MEInterfaceKeyStorage(inventory);
        var handler = new MEChemicalRecipeHandler<>(trait(IO.IN), MekanismChemicalRecipeCapability.CAP_GAS,
                GasStack.class, () -> storage, false);
        var requirement = MekanismChemicalRecipeCapability.CAP_GAS.createIngredient(AppliedMekanisticsTestBootstrap.gas, 100);
        assertNull(handler.handleRecipe(IO.IN, null, List.of(requirement), null, true));
        assertEquals(30, inventory.getAmount(0));
        assertEquals(70, inventory.getAmount(1));
        assertNull(handler.handleRecipe(IO.IN, null, List.of(requirement), null, false));
        assertTrue(inventory.isEmpty());
    }

    private static RecipeCapabilityTrait trait(IO io) {
        var definition = new TestDefinition();
        definition.setRecipeHandlerIO(io);
        return new TestTrait(definition);
    }

    @LDLRegister(name = "appmek_test", group = "test")
    private static final class TestDefinition extends RecipeCapabilityTraitDefinition {
        @Override public ITrait createTrait(MBDMachine machine) { return new TestTrait(this); }
        @Override public IGuiTexture getIcon() { return IGuiTexture.EMPTY; }
    }

    private static final class TestTrait extends RecipeCapabilityTrait {
        TestTrait(TestDefinition definition) { super(null, definition); }
    }
}
