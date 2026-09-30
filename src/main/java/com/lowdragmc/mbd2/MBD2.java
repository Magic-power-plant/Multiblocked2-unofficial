package com.lowdragmc.mbd2;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.mbd2.client.ClientProxy;
import com.lowdragmc.mbd2.common.CommonProxy;
import com.lowdragmc.lowdraglib.Platform;
import lombok.Getter;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Random;

/**
 * Forge mod entry point for Multiblocked2.
 *
 * <p>The business goal is to create the mod-side resource workspace, initialize
 * shared logging/state, and choose the correct client or common proxy for the
 * current distribution. Construction and lazy directory creation are performed
 * by Forge during mod loading and should stay on the mod loading thread.</p>
 */
@Mod(MBD2.MOD_ID)
public class MBD2 {
    public static final String MOD_ID = "mbd2";
    public static final String NAME = "Multiblocked2";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAME);
    public static final Random RND = new Random();
    @Getter(lazy = true)
    private static final File location = createDir();

    /**
     * Creates the mod instance registered by Forge.
     *
     * <p>Preconditions: called once by Forge for {@link #MOD_ID}. Side effects:
     * logs platform initialization, creates the resource workspace on first
     * access, and constructs either {@link ClientProxy} or {@link CommonProxy}
     * for distribution-specific registration.</p>
     */
    public MBD2() {
        MBD2.init();
        DistExecutor.unsafeRunForDist(() -> ClientProxy::new, () -> CommonProxy::new);
    }

    private static File createDir() {
        var location = new File(LDLib.getLDLibDir(), "assets/" + MOD_ID);
        if (location.mkdirs()) {
            LOGGER.info("create mbd2 resources folder");
        }
        return location;
    }

    /**
     * Logs the platform initialization banner.
     *
     * <p>Business goal: make the active loader platform visible during startup.
     * Preconditions: logging infrastructure must be available. Side effects:
     * writes one informational log line.</p>
     */
    public static void init() {
        LOGGER.info("{} is initializing on platform: {}", NAME, Platform.platformName());
    }

    /**
     * Creates a resource location in the mod namespace.
     *
     * <p>Preconditions: {@code path} must be a valid Minecraft resource path.
     * Side effects: none.</p>
     *
     * @param path namespace-local path, such as {@code "machine/example"}
     * @return resource location with namespace {@link #MOD_ID}
     */
    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    /**
     * @return {@code true} when Geckolib is loaded and integration code may be used
     */
    public static boolean isGeckolibLoaded() {
        return LDLib.isModLoaded("geckolib");
    }

    /**
     * @return {@code true} when Botania is loaded and related compatibility paths may run
     */
    public static boolean isBotaniaLoaded() {
        return LDLib.isModLoaded("botania");
    }

    /**
     * @return {@code true} when Ars Nouveau is loaded and related compatibility paths may run
     */
    public static boolean isArsNouveauLoaded() {
        return LDLib.isModLoaded("ars_nouveau");
    }

    /**
     * @return {@code true} when Nature's Aura is loaded and related compatibility paths may run
     */
    public static boolean isNaturesAuraLoaded() {
        return LDLib.isModLoaded("naturesaura");
    }

    /**
     * @return {@code true} when PneumaticCraft is loaded and related compatibility paths may run
     */
    public static boolean isPneumaticCraftLoaded() {
        return LDLib.isModLoaded("pneumaticcraft");
    }

    /**
     * @return {@code true} when Embers is loaded and related compatibility paths may run
     */
    public static boolean isEmbersLoaded() {
        return LDLib.isModLoaded("embers");
    }

    /**
     * @return {@code true} when GregTech Modern is loaded and related compatibility paths may run
     */
    public static boolean isGTMLoaded() {
        return LDLib.isModLoaded("gtceu");
    }

    /**
     * @return {@code true} when Mekanism is loaded and related compatibility paths may run
     */
    public static boolean isMekanismLoaded() {
        return LDLib.isModLoaded("mekanism");
    }

    /** Guards all references to the optional Applied Mekanistics chemical bridge. */
    public static boolean isAppliedMekanisticsLoaded() {
        return isAE2Loaded() && isMekanismLoaded() && LDLib.isModLoaded("appmek");
    }

    /**
     * @return {@code true} when Create is loaded and kinetic machine support may be registered
     */
    public static boolean isCreateLoaded() {
        return LDLib.isModLoaded("create");
    }

    /**
     * @return {@code true} when Photon is loaded and related compatibility paths may run
     */
    public static boolean isPhotonLoaded() {
        return LDLib.isModLoaded("photon");
    }

    /**
     * @return {@code true} when KubeJS is loaded and startup registry events may be posted
     */
    public static boolean isKubeJSLoaded() {
        return LDLib.isModLoaded("kubejs");
    }

    /**
     * @return {@code true} when Jade is loaded and related compatibility paths may run
     */
    public static boolean isJadeLoaded() {
        return LDLib.isModLoaded("jade");
    }

    /**
     * @return {@code true} when Applied Energistics 2 is loaded and related compatibility paths may run
     */
    public static boolean isAE2Loaded() {
        return LDLib.isModLoaded("ae2");
    }

    /**
     * @return {@code true} when AE2's legacy interface internals used by MBD are available
     */
    public static boolean isAE2InterfaceLogicAvailable() {
        return isAE2Loaded()
                && isClassPresent("appeng.helpers.InterfaceLogic")
                && isClassPresent("appeng.helpers.MultiCraftingTracker");
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, MBD2.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * @return {@code true} when Blood Magic is loaded and related compatibility paths may run
     */
    public static boolean isBloodMagicLoaded() {
        return LDLib.isModLoaded("bloodmagic");
    }

    /**
     * @return {@code true} when Mana and Artifice is loaded and related compatibility paths may run
     */
    public static boolean isManaAndArtificeLoaded() {
        return LDLib.isModLoaded("mna");
    }

    /**
     * @return {@code true} when Embeddium is loaded and related compatibility paths may run
     */
    public static boolean isEmbeddiumLoaded() {
        return LDLib.isModLoaded("embeddium");
    }
}
