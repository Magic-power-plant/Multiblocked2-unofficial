package com.lowdragmc.mbd2.integration.kubejs.events;

import com.lowdragmc.mbd2.api.recipe.MBDRecipeType;
import com.lowdragmc.mbd2.api.recipe.event.RecipeTypeEvent;
import com.lowdragmc.mbd2.api.recipe.event.TransferProxyRecipeEvent;
import com.lowdragmc.mbd2.MBD2;
import com.lowdragmc.mbd2.common.machine.MBDMachine;
import com.lowdragmc.mbd2.common.machine.definition.config.event.*;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.EventResult;
import dev.latvian.mods.kubejs.event.Extra;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.util.MapJS;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Server-side KubeJS event bridge for MBD machine and recipe type events.
 */
public interface MBDServerEvents {
    Map<Class<? extends MachineEvent>, Function<MachineEvent, EventResult>> machineEventHandlers = new HashMap<>();
    Map<Class<? extends RecipeTypeEvent>, Function<RecipeTypeEvent, EventResult>> recipeTypeEventHandlers = new HashMap<>();

    // Server events
    EventHandler AFTER_RECIPE_WORKING = registerMachineEvent("onAfterRecipeWorking",
            MachineAfterRecipeWorkingEvent.class,
            MBDMachineEvents.MachineAfterRecipeWorkingEventJS.class,
            MBDMachineEvents.MachineAfterRecipeWorkingEventJS::new);

    EventHandler BEFORE_RECIPE_WORKING = registerMachineEvent("onBeforeRecipeWorking",
            MachineBeforeRecipeWorkingEvent.class,
            MBDMachineEvents.MachineBeforeRecipeWorkingEventJS.class,
            MBDMachineEvents.MachineBeforeRecipeWorkingEventJS::new);

    EventHandler DROPS = registerMachineEvent("onDrops",
            MachineDropsEvent.class,
            MBDMachineEvents.MachineDropsEventJS.class,
            MBDMachineEvents.MachineDropsEventJS::new);

    EventHandler NEIGHBOR_CHANGED = registerMachineEvent("onNeighborChanged",
            MachineNeighborChangedEvent.class,
            MBDMachineEvents.MachineNeighborChangedEventJS.class,
            MBDMachineEvents.MachineNeighborChangedEventJS::new);

    EventHandler ON_LOAD = registerMachineEvent("onLoad",
            MachineOnLoadEvent.class,
            MBDMachineEvents.MachineOnLoadEventJS.class,
            MBDMachineEvents.MachineOnLoadEventJS::new);

    EventHandler ON_RECIPE_WORKING = registerMachineEvent("onRecipeWorking",
            MachineOnRecipeWorkingEvent.class,
            MBDMachineEvents.MachineOnRecipeWorkingEventJS.class,
            MBDMachineEvents.MachineOnRecipeWorkingEventJS::new);

    EventHandler ON_RECIPE_WAITING = registerMachineEvent("onRecipeWaiting",
            MachineOnRecipeWaitingEvent.class,
            MBDMachineEvents.MachineOnRecipeWaitingEventJS.class,
            MBDMachineEvents.MachineOnRecipeWaitingEventJS::new);

    EventHandler OPEN_UI = registerMachineEvent("onOpenUI",
            MachineOpenUIEvent.class,
            MBDMachineEvents.MachineOpenUIEventJS.class,
            MBDMachineEvents.MachineOpenUIEventJS::new);

    EventHandler PLACED = registerMachineEvent("onPlaced",
            MachinePlacedEvent.class,
            MBDMachineEvents.MachinePlacedEventJS.class,
            MBDMachineEvents.MachinePlacedEventJS::new);

    EventHandler FUEL_RECIPE_MODIFY = registerMachineEvent("onFuelRecipeModify",
            MachineFuelRecipeModifyEvent.class,
            MBDMachineEvents.MachineFuelRecipeModifyEventJS.class,
            MBDMachineEvents.MachineFuelRecipeModifyEventJS::new);

    EventHandler FUEL_BURNING_FINISH = registerMachineEvent("onFuelBurningFinish",
            MachineFuelBurningFinishEvent.class,
            MBDMachineEvents.MachineFuelBurningFinishEventJS.class,
            MBDMachineEvents.MachineFuelBurningFinishEventJS::new);

    EventHandler BEFORE_RECIPE_MODIFY = registerMachineEvent("onBeforeRecipeModify",
            MachineRecipeModifyEvent.Before.class,
            MBDMachineEvents.MachineRecipeModifyEventBeforeJS.class,
            MBDMachineEvents.MachineRecipeModifyEventBeforeJS::new);

    EventHandler AFTER_RECIPE_MODIFY = registerMachineEvent("onAfterRecipeModify",
            MachineRecipeModifyEvent.After.class,
            MBDMachineEvents.MachineRecipeModifyEventAfterJS.class,
            MBDMachineEvents.MachineRecipeModifyEventAfterJS::new);

    EventHandler RECIPE_STATUS_CHANGED = registerMachineEvent("onRecipeStatusChanged",
            MachineRecipeStatusChangedEvent.class,
            MBDMachineEvents.MachineRecipeStatusChangedEventJS.class,
            MBDMachineEvents.MachineRecipeStatusChangedEventJS::new);

    EventHandler REMOVED = registerMachineEvent("onRemoved",
            MachineRemovedEvent.class,
            MBDMachineEvents.MachineRemovedEventJS.class,
            MBDMachineEvents.MachineRemovedEventJS::new);

    EventHandler RIGHT_CLICK = registerMachineEvent("onRightClick",
            MachineRightClickEvent.class,
            MBDMachineEvents.MachineRightClickEventJS.class,
            MBDMachineEvents.MachineRightClickEventJS::new);

    EventHandler STATE_CHANGED = registerMachineEvent("onStateChanged",
            MachineStateChangedEvent.class,
            MBDMachineEvents.MachineStateChangedEventJS.class,
            MBDMachineEvents.MachineStateChangedEventJS::new);

    EventHandler STRUCTURE_FORMED = registerMachineEvent("onStructureFormed",
            MachineStructureFormedEvent.class,
            MBDMachineEvents.MachineStructureFormedEventJS.class,
            MBDMachineEvents.MachineStructureFormedEventJS::new);

    EventHandler STRUCTURE_INVALID = registerMachineEvent("onStructureInvalid",
            MachineStructureInvalidEvent.class,
            MBDMachineEvents.MachineStructureInvalidEventJS.class,
            MBDMachineEvents.MachineStructureInvalidEventJS::new);

    EventHandler TICK = registerMachineEvent("onTick",
            MachineTickEvent.class,
            MBDMachineEvents.MachineTickEventJS.class,
            MBDMachineEvents.MachineTickEventJS::new);

    EventHandler FIXED_TICK = registerMachineEvent("onFixedTick",
            MachineFixedTickEvent.class,
            MBDMachineEvents.MachineFixedTickEventJS.class,
            MBDMachineEvents.MachineFixedTickEventJS::new);

    EventHandler FIXED_TICK_EVERY = MBDMachineEvents.MBD_MACHINE_EVENTS.server("onFixedTickEvery",
            () -> MBDMachineEvents.MachineFixedTickEventJS.class).extra(FixedTickKey.EXTRA);

    EventHandler USE_CATALYST = registerMachineEvent("onUseCatalyst",
            MachineUseCatalystEvent.class,
            MBDMachineEvents.MachineUseCatalystEventJS.class,
            MBDMachineEvents.MachineUseCatalystEventJS::new);

    EventHandler MACHINE_UI = registerMachineEvent("onUI",
            MachineUIEvent.class,
            MBDMachineEvents.MachineUIEventJS.class,
            MBDMachineEvents.MachineUIEventJS::new);

    EventHandler MACHINE_ON_CONSUME_INPUTS_AFTER_WORKING = registerMachineEvent("onConsumeInputsAfterWorking",
            MachineOnConsumeInputsAfterWorkingEvent.class,
            MBDMachineEvents.MachineOnConsumeInputsAfterWorkingEventJS.class,
            MBDMachineEvents.MachineOnConsumeInputsAfterWorkingEventJS::new);

    EventHandler MACHINE_RECIPE_INPUTS_CONSUMED = registerMachineEvent("onRecipeInputsConsumed",
            MachineRecipeInputsConsumedEvent.class,
            MBDMachineEvents.MachineRecipeInputsConsumedEventJS.class,
            MBDMachineEvents.MachineRecipeInputsConsumedEventJS::new);

    EventHandler MACHINE_ON_RECIPE_FINISH = registerMachineEvent("onRecipeFinish",
            MachineOnRecipeFinishEvent.class,
            MBDMachineEvents.MachineOnRecipeFinishEventJS.class,
            MBDMachineEvents.MachineOnRecipeFinishEventJS::new);

    // Recipe events

    EventHandler TRANSFER_PROXY_RECIPE = registerTransferProxyRecipeEvent();

    static void init() {
        // NO-OP
    }

    static <E extends MachineEvent> EventHandler registerMachineEvent(String name, Class<E> eventClass,
                                                                      Class<? extends MBDMachineEvents.MachineEventJS<E>> eventJSClass,
                                                                      Function<E, MBDMachineEvents.MachineEventJS<E>> eventJSFactory) {
        var handler = MBDMachineEvents.MBD_MACHINE_EVENTS.server(name, () -> eventJSClass).extra(Extra.ID);
        machineEventHandlers.put(eventClass, event -> {
            var machineId = event.machine.getDefinition().id();
            var result = handler.post(eventJSFactory.apply((E) event), machineId);
            if (event instanceof MachineStructureFormedEvent) {
                MBD2.LOGGER.trace("[MBD2/Fusion] KubeJS onStructureFormed posted machine={}, pos={}, result={}",
                        machineId, event.machine.getPos(), result);
            }
            return result;
        });
        return handler;
    }

    static <E extends RecipeTypeEvent> EventHandler registerRecipeTypeEvent(String name, Class<? extends RecipeTypeEvent> eventClass,
                                                                            Class<? extends MBDRecipeTypeEvents.RecipeTypeEventJS<E>> eventJSClass,
                                                                            Function<E, MBDRecipeTypeEvents.RecipeTypeEventJS<E>> eventJSFactory) {
        var handler = MBDRecipeTypeEvents.MBD_RECIPE_TYPE_EVENTS.server(name, () -> eventJSClass).extra(Extra.ID);
        recipeTypeEventHandlers.put(eventClass, event -> handler.post(eventJSFactory.apply((E) event), event.recipeType.getRegistryName()));
        return handler;
    }

    /**
     * Registers proxy conversion separately because its listener key is the
     * receiving MBD recipe type plus traversal number.
     */
    static EventHandler registerTransferProxyRecipeEvent() {
        var handler = MBDRecipeTypeEvents.MBD_RECIPE_TYPE_EVENTS.server("onTransferProxyRecipe",
                () -> MBDRecipeTypeEvents.TransferProxyRecipeEventJS.class).extra(ProxyRecipeTraversalKey.EXTRA);
        recipeTypeEventHandlers.put(TransferProxyRecipeEvent.class, event -> {
            var transferEvent = (TransferProxyRecipeEvent) event;
            var key = ProxyRecipeTraversalKey.from(transferEvent);
            return handler.post(new MBDRecipeTypeEvents.TransferProxyRecipeEventJS(transferEvent), key);
        });
        return handler;
    }

    /**
     * Resolves the number of KubeJS-requested traversals for one receiving MBD
     * recipe type. A bare {@code mbd_recipe_type} selects traversal one;
     * registering {@code mbd_recipe_type@2} requests both traversal one and
     * two for that target.
     */
    static int getProxyRecipeTraversalCount(MBDRecipeType recipeType, RecipeType<?> proxyType) {
        if (recipeType == null || recipeType.getRegistryName() == null) {
            return 1;
        }
        var recipeTypeId = recipeType.getRegistryName();
        var traversalCount = 1;
        for (var extraId : TRANSFER_PROXY_RECIPE.findUniqueExtraIds(ScriptType.SERVER)) {
            if (extraId instanceof ProxyRecipeTraversalKey key && key.recipeTypeId.equals(recipeTypeId)) {
                traversalCount = Math.max(traversalCount, key.traversal);
            }
        }
        return traversalCount;
    }

    static EventResult postMachineEvent(MachineEvent machineEvent) {
        var handler = machineEventHandlers.get(machineEvent.getClass());
        if (machineEvent instanceof MachineStructureFormedEvent) {
            MBD2.LOGGER.trace("[MBD2/Fusion] KubeJS postMachineEvent event={}, machine={}, pos={}, handler={}",
                    machineEvent.getClass().getSimpleName(), machineEvent.machine.getDefinition().id(),
                    machineEvent.machine.getPos(), handler != null);
        }
        return Optional.ofNullable(handler).map(eventHandler -> eventHandler.apply(machineEvent)).orElse(EventResult.PASS);
    }

    static EventResult postMachineFixedTickEvery(MBDMachine machine, long timer) {
        if (!FIXED_TICK_EVERY.hasListeners()) {
            return EventResult.PASS;
        }
        var machineId = machine.getDefinition().id();
        var result = EventResult.PASS;
        for (var object : collectFixedTickKeys()) {
            if (object instanceof FixedTickKey key && key.machineId.equals(machineId) && timer % key.interval == 0) {
                var event = new MachineFixedTickEvent(machine, key.interval, timer);
                var postResult = FIXED_TICK_EVERY.post(new MBDMachineEvents.MachineFixedTickEventJS(event), key);
                if (postResult != EventResult.PASS) {
                    result = postResult;
                }
            }
        }
        return result;
    }

    static Set<Object> collectFixedTickKeys() {
        var keys = new LinkedHashSet<>();
        keys.addAll(FIXED_TICK_EVERY.findUniqueExtraIds(ScriptType.SERVER));
        keys.addAll(FIXED_TICK_EVERY.findUniqueExtraIds(ScriptType.STARTUP));
        return keys;
    }

    static EventResult postRecipeTypeEvent(RecipeTypeEvent recipeTypeEvent) {
        return Optional.ofNullable(recipeTypeEventHandlers.get(recipeTypeEvent.getClass())).map(handler -> handler.apply(recipeTypeEvent)).orElse(EventResult.PASS);
    }

    /**
     * KubeJS extra id for one receiving MBD recipe type traversal.
     */
    record ProxyRecipeTraversalKey(ResourceLocation recipeTypeId, int traversal) {
        static final Extra EXTRA = new Extra()
                .transformer(ProxyRecipeTraversalKey::of)
                .validator(ProxyRecipeTraversalKey.class::isInstance)
                .toString(value -> value.toString());

        public ProxyRecipeTraversalKey {
            if (recipeTypeId == null) {
                throw new IllegalArgumentException("MBD recipe type id cannot be null");
            }
            if (traversal < 1) {
                throw new IllegalArgumentException("Proxy recipe traversal must be at least 1");
            }
        }

        static ProxyRecipeTraversalKey from(TransferProxyRecipeEvent transferEvent) {
            return new ProxyRecipeTraversalKey(transferEvent.recipeType.getRegistryName(), transferEvent.traversal);
        }

        public static ProxyRecipeTraversalKey of(Object value) {
            if (value instanceof ProxyRecipeTraversalKey key) {
                return key;
            }
            if (value instanceof CharSequence chars) {
                var raw = chars.toString();
                var at = raw.lastIndexOf('@');
                if (at < 0) {
                    var recipeTypeId = ResourceLocation.tryParse(raw);
                    if (recipeTypeId != null) {
                        return new ProxyRecipeTraversalKey(recipeTypeId, 1);
                    }
                }
                if (at > 0 && at < raw.length() - 1) {
                    var recipeTypeId = ResourceLocation.tryParse(raw.substring(0, at));
                    if (recipeTypeId == null) {
                        throw new IllegalArgumentException("Invalid MBD recipe type id: " + raw.substring(0, at));
                    }
                    try {
                        return new ProxyRecipeTraversalKey(recipeTypeId, Integer.parseInt(raw.substring(at + 1)));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid proxy recipe traversal: " + raw.substring(at + 1), e);
                    }
                }
            }
            throw new IllegalArgumentException("Use 'namespace:mbd_recipe_type' or 'namespace:mbd_recipe_type@traversal', for example 'mbd2:fluid_gas@2'");
        }

        @Override
        public String toString() {
            return recipeTypeId + "@" + traversal;
        }
    }

    record FixedTickKey(ResourceLocation machineId, int interval) {
        static final Extra EXTRA = new Extra()
                .transformer(FixedTickKey::of)
                .validator(FixedTickKey.class::isInstance)
                .toString(value -> value.toString())
                .required();

        static FixedTickKey of(Object value) {
            if (value instanceof FixedTickKey key) {
                return key;
            }
            if (value instanceof CharSequence chars) {
                var raw = chars.toString();
                var at = raw.lastIndexOf('@');
                if (at > 0 && at < raw.length() - 1) {
                    return new FixedTickKey(parseMachineId(raw.substring(0, at)), parseInterval(raw.substring(at + 1)));
                }
            }
            var map = MapJS.of(value);
            if (!map.isEmpty()) {
                var machineId = firstPresent(map.get("id"), map.get("machine"), map.get("machineId"))
                        .map(FixedTickKey::parseMachineId)
                        .orElseThrow(() -> new IllegalArgumentException("Missing machine id for fixed tick listener"));
                var interval = firstPresent(map.get("interval"), map.get("ticks"), map.get("step"))
                        .map(FixedTickKey::parseInterval)
                        .orElseThrow(() -> new IllegalArgumentException("Missing interval for fixed tick listener"));
                return new FixedTickKey(machineId, interval);
            }
            throw new IllegalArgumentException("Use { id: 'namespace:machine', interval: ticks } or 'namespace:machine@ticks'");
        }

        static Optional<Object> firstPresent(Object... values) {
            for (var value : values) {
                if (value != null) {
                    return Optional.of(value);
                }
            }
            return Optional.empty();
        }

        static ResourceLocation parseMachineId(Object id) {
            if (id instanceof ResourceLocation resourceLocation) {
                return resourceLocation;
            }
            var location = ResourceLocation.tryParse(String.valueOf(id));
            if (location == null) {
                throw new IllegalArgumentException("Invalid machine id: " + id);
            }
            return location;
        }

        static int parseInterval(Object interval) {
            var value = interval instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(interval));
            if (value < 1) {
                throw new IllegalArgumentException("Fixed tick interval must be at least 1");
            }
            return value;
        }

        @Override
        public String toString() {
            return machineId + "@" + interval;
        }
    }
}
