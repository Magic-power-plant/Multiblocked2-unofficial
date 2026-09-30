package com.lowdragmc.mbd2.common.machine.definition.config;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.configurator.*;
import com.lowdragmc.lowdraglib.gui.editor.ui.Editor;
import com.lowdragmc.lowdraglib.gui.widget.DialogWidget;
import com.lowdragmc.lowdraglib.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.mbd2.common.gui.editor.MachineEditor;
import com.lowdragmc.mbd2.common.gui.editor.MachineProject;
import com.lowdragmc.mbd2.common.gui.editor.ValidatedStringConfigurator;
import com.lowdragmc.mbd2.api.pattern.preview.ComponentSnapshots;
import com.lowdragmc.mbd2.common.gui.editor.machine.MachineConfigPanel;
import com.lowdragmc.mbd2.common.trait.CapabilityIO;
import com.lowdragmc.mbd2.common.trait.ToggleAutoIO;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted settings that turn a machine definition into a multiblock part.
 * <p>
 * Enabled part settings allow {@link com.lowdragmc.mbd2.common.machine.MBDPartMachine}
 * instances to join controllers, apply part-side recipe modifiers, and proxy
 * selected controller capabilities through the part block. The config is
 * consumed by both controller structure formation and part capability lookup.
 */
@Getter
@Builder
@Accessors(fluent = true)
public class ConfigPartSettings implements IToggleConfigurable, IPersistedSerializable {

    @Getter
    @Setter
    @Persisted
    protected boolean enable;

    @Override
    public boolean isEnable() {
        return enable;
    }

    /**
     * Enables or disables part behavior.
     * <p>
     * On the client editor, enabling part mode may prompt the user to create a
     * {@code formed} state because parts commonly need a separate formed visual.
     *
     * @param enable whether this definition should behave as a multiblock part
     */
    @Override
    public void setEnable(boolean enable) {
        if (enable && !this.enable && LDLib.isClient() && Editor.INSTANCE instanceof MachineEditor machineEditor
                && machineEditor.getCurrentProject() instanceof MachineProject project) {
            // ask to add the "formed" state
            if (!project.getDefinition().stateMachine().hasState("formed")) {
                DialogWidget.showCheckBox(Editor.INSTANCE, "config.part_settings.formed_tips", "config.part_settings.formed_tips.info", result -> {
                    if (result) {
                        var state = project.getDefinition().stateMachine().getRootState();
                        var newState = state.addChild("formed");
                        machineEditor.getTabPages().tabs.values().stream()
                                .filter(MachineConfigPanel.class::isInstance)
                                .map(MachineConfigPanel.class::cast)
                                .findAny().ifPresent(panel -> panel.onStateAdded(newState));
                    }
                });
            }
        }
        this.enable = enable;
    }

    @Configurable(name = "config.part_settings.can_share", tips = {"config.part_settings.can_share.tooltip"})
    @Builder.Default
    protected boolean canShare = true;
    @Builder.Default
    protected final RecipeModifier.RecipeModifiers recipeModifiers = new RecipeModifier.RecipeModifiers();
    @Builder.Default
    protected final List<ProxyCapability> proxyControllerCapabilities = new ArrayList<>();
    @Builder.Default
    @Persisted
    protected List<Component> xeiPartTooltips = new ArrayList<>();

    public List<Component> xeiPartTooltips() {
        return ComponentSnapshots.copy(xeiPartTooltips);
    }

    public void setXeiPartTooltips(List<Component> lines) {
        xeiPartTooltips = lines == null ? new ArrayList<>() : new ArrayList<>(ComponentSnapshots.copy(lines));
    }

    public static class ConfigPartSettingsBuilder {
        public ConfigPartSettingsBuilder xeiPartTooltips(List<Component> lines) {
            this.xeiPartTooltips$value = lines == null ? new ArrayList<>() : new ArrayList<>(ComponentSnapshots.copy(lines));
            this.xeiPartTooltips$set = true;
            return this;
        }
    }

    @Override
    public CompoundTag serializeNBT() {
        var tag = IPersistedSerializable.super.serializeNBT();
        tag.put("recipeModifiers", recipeModifiers.serializeNBT());
        var proxyCapabilities = new ListTag();
        for (ProxyCapability proxyCapability : proxyControllerCapabilities) {
            proxyCapabilities.add(proxyCapability.serializeNBT());
        }
        tag.put("proxyControllerCapabilities", proxyCapabilities);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        xeiPartTooltips = new ArrayList<>();
        IPersistedSerializable.super.deserializeNBT(tag);
        recipeModifiers.deserializeNBT(tag.getList("recipeModifiers", Tag.TAG_COMPOUND));
        proxyControllerCapabilities.clear();
        var proxyCapabilities = tag.getList("proxyControllerCapabilities", Tag.TAG_COMPOUND);
        for (int i = 0; i < proxyCapabilities.size(); i++) {
            var proxyCapability = new ProxyCapability();
            proxyCapability.deserializeNBT(proxyCapabilities.getCompound(i));
            proxyControllerCapabilities.add(proxyCapability);
        }
    }

    @Override
    public void buildConfigurator(ConfiguratorGroup father) {
        IToggleConfigurable.super.buildConfigurator(father);
        var xeiTooltips = new ArrayConfiguratorGroup<>("config.part_settings.xei_part_tooltips", false,
                this::xeiPartTooltips, (getter, setter) -> new ValidatedStringConfigurator("",
                        () -> Component.Serializer.toJson(getter.get()), json -> setter.accept(Component.Serializer.fromJson(json)),
                        "{\"text\":\"\"}", json -> {
                    try {
                        return Component.Serializer.fromJson(json) != null;
                    } catch (RuntimeException ignored) {
                        return false;
                    }
                }), true);
        xeiTooltips.setTips("config.part_settings.xei_part_tooltips.tooltip");
        xeiTooltips.setAddDefault(() -> Component.literal(""));
        xeiTooltips.setOnAdd(line -> xeiPartTooltips.add(ComponentSnapshots.copy(line)));
        xeiTooltips.setOnRemove(line -> xeiPartTooltips.remove(line));
        xeiTooltips.setOnUpdate(this::setXeiPartTooltips);
        father.addConfigurators(xeiTooltips);
        recipeModifiers.buildConfigurator(father);
        var proxyCapabilities = new ArrayConfiguratorGroup<>("config.part_settings.proxy_controller_capabilities", false,
                () -> proxyControllerCapabilities, (getter, setter) -> {
            var proxyCapability = getter.get();
            var group = new ConfiguratorGroup("config.part_settings.proxy_capability.trait_filter", false);
            proxyCapability.buildConfigurator(group);
            return group;
        }, true);
        proxyCapabilities.setTips("config.part_settings.proxy_controller_capabilities.tooltip.0", "config.part_settings.proxy_controller_capabilities.tooltip.1");
        proxyCapabilities.setAddDefault(ProxyCapability::new);
        proxyCapabilities.setOnAdd(proxyControllerCapabilities::add);
        proxyCapabilities.setOnRemove(proxyControllerCapabilities::remove);
        proxyCapabilities.setOnUpdate(list -> {
            proxyControllerCapabilities.clear();
            proxyControllerCapabilities.addAll(list);
        });
        father.addConfigurators(proxyCapabilities);
    }

    /**
     * Rule for proxying matching controller traits through this part.
     * <p>
     * The rule can filter controller traits by name, expose selected sides and
     * IO directions through {@link CapabilityIO}, and optionally run auto-IO
     * from the part position against the proxied controller trait.
     */
    @Getter
    public static class ProxyCapability implements IConfigurable, IPersistedSerializable {
        @Configurable(name = "config.part_settings.proxy_capability.trait_name_filter",
                tips = {"config.part_settings.proxy_capability.trait_name_filter.tooltip.0",
                        "config.part_settings.proxy_capability.trait_name_filter.tooltip.1"})
        private String traitNameFilter = "";
        @Configurable(name = "config.definition.trait.capability_io", subConfigurable = true,
                tips = {"config.definition.trait.capability_io.tooltip.0", "config.definition.trait.capability_io.tooltip.1"})
        private final CapabilityIO capabilityIO = new CapabilityIO();
        @Configurable(name = "config.definition.trait.auto_io", subConfigurable = true,
                tips = {"config.definition.trait.auto_io.tooltip"})
        private final ToggleAutoIO autoIO = new ToggleAutoIO();

        /**
         * Tests whether a controller trait name is accepted by this proxy rule.
         *
         * @param traitName controller trait definition name
         * @return {@code true} when the filter is blank or the trait name
         * contains the filter text
         */
        public boolean matchesTraitName(String traitName) {
            return traitName != null && (traitNameFilter == null || traitNameFilter.isBlank() || traitName.contains(traitNameFilter));
        }
    }
}
