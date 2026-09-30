package com.lowdragmc.mbd2.integration.appmek;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AEKeyTypesInternal;
import com.lowdragmc.mbd2.XeiTestBootstrap;
import me.ramidzkh.mekae2.ae2.MekanismKeyType;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasBuilder;
import mekanism.api.chemical.infuse.InfuseType;
import mekanism.api.chemical.infuse.InfuseTypeBuilder;
import mekanism.api.chemical.pigment.Pigment;
import mekanism.api.chemical.pigment.PigmentBuilder;
import mekanism.api.chemical.slurry.Slurry;
import mekanism.api.chemical.slurry.SlurryBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.RegistryBuilder;

/** Real AE2/Mekanism key registries, without launching a Minecraft world. */
final class AppliedMekanisticsTestBootstrap {
    private static boolean initialized;
    static Gas gas;
    static Gas secondGas;
    static InfuseType infusion;
    static Pigment pigment;
    static Slurry slurry;

    static synchronized void init() {
        if (initialized) return;
        XeiTestBootstrap.init();
        var gasRegistry = createRegistry(new RegistryBuilder<Gas>().setName(MekanismAPI.GAS_REGISTRY_NAME.location()));
        gasRegistry.register(id("empty"), MekanismAPI.EMPTY_GAS);
        gasRegistry.register(id("gas"), gas = new Gas(GasBuilder.builder()));
        gasRegistry.register(id("second_gas"), secondGas = new Gas(GasBuilder.builder()));
        var infusionRegistry = createRegistry(new RegistryBuilder<InfuseType>().setName(MekanismAPI.INFUSE_TYPE_REGISTRY_NAME.location()));
        infusionRegistry.register(id("empty"), MekanismAPI.EMPTY_INFUSE_TYPE);
        infusionRegistry.register(id("infusion"), infusion = new InfuseType(InfuseTypeBuilder.builder()));
        var pigmentRegistry = createRegistry(new RegistryBuilder<Pigment>().setName(MekanismAPI.PIGMENT_REGISTRY_NAME.location()));
        pigmentRegistry.register(id("empty"), MekanismAPI.EMPTY_PIGMENT);
        pigmentRegistry.register(id("pigment"), pigment = new Pigment(PigmentBuilder.builder()));
        var slurryRegistry = createRegistry(new RegistryBuilder<Slurry>().setName(MekanismAPI.SLURRY_REGISTRY_NAME.location()));
        slurryRegistry.register(id("empty"), MekanismAPI.EMPTY_SLURRY);
        slurryRegistry.register(id("slurry"), slurry = new Slurry(SlurryBuilder.clean()));
        var keyRegistry = createRegistry(new RegistryBuilder<AEKeyType>().setName(id("ae_key_types")));
        AEKeyTypesInternal.setRegistry(() -> keyRegistry);
        AEKeyTypes.register(AEKeyType.items());
        AEKeyTypes.register(AEKeyType.fluids());
        AEKeyTypes.register(MekanismKeyType.TYPE);
        initialized = true;
    }

    @SuppressWarnings("unchecked")
    private static <T> net.minecraftforge.registries.IForgeRegistry<T> createRegistry(RegistryBuilder<T> builder) {
        try {
            // Forge normally invokes this package-private method from NewRegistryEvent.
            var create = RegistryBuilder.class.getDeclaredMethod("create");
            create.setAccessible(true);
            return (net.minecraftforge.registries.IForgeRegistry<T>) create.invoke(builder.hasTags());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot initialize test registry", error);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mbd2_test", path);
    }
}
