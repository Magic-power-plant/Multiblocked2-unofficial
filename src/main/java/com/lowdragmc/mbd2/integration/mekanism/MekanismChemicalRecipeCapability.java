package com.lowdragmc.mbd2.integration.mekanism;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorSelectorConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.configurator.NumberConfigurator;
import com.lowdragmc.lowdraglib.gui.editor.configurator.SearchComponentConfigurator;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.utils.LocalizationUtils;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.mbd2.api.capability.recipe.RecipeCapability;
import com.lowdragmc.mbd2.api.recipe.content.Content;
import com.lowdragmc.mbd2.api.recipe.content.ContentModifier;
import com.lowdragmc.mbd2.api.recipe.content.IContentSerializer;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.ChemicalTankBuilder;
import mekanism.api.chemical.ChemicalTags;
import mekanism.api.chemical.ChemicalType;
import mekanism.api.chemical.ChemicalUtils;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.chemical.IChemicalTank;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.infuse.InfuseType;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.pigment.Pigment;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.slurry.Slurry;
import mekanism.api.chemical.slurry.SlurryStack;
import mekanism.api.recipes.ingredients.ChemicalStackIngredient;
import mekanism.api.recipes.ingredients.creator.IChemicalStackIngredientCreator;
import mekanism.api.recipes.ingredients.creator.IngredientCreatorAccess;
import mekanism.common.registries.MekanismGases;
import mekanism.common.registries.MekanismInfuseTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraftforge.registries.IForgeRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Recipe capability implementation for Mekanism gas, infusion, pigment, and slurry ingredients.
 *
 * <p>The content model deliberately uses Mekanism's {@link ChemicalStackIngredient}. In addition to exact
 * chemical stacks, that model retains the tag key through JSON, NBT, and network sync and exposes the matching
 * predicate used by the runtime tank trait.</p>
 */
public class MekanismChemicalRecipeCapability<CHEMICAL extends Chemical<CHEMICAL>, STACK extends ChemicalStack<CHEMICAL>>
        extends RecipeCapability<ChemicalStackIngredient<CHEMICAL, STACK>> {
    public static final MekanismChemicalRecipeCapability<Gas, GasStack> CAP_GAS =
            new MekanismChemicalRecipeCapability<>("mek_gas",
                    MekanismAPI.EMPTY_GAS,
                    MekanismGases.HYDROGEN::getChemical,
                    MekanismAPI::gasRegistry,
                    GasStack::new,
                    ChemicalTankBuilder.GAS,
                    ChemicalTankWidget.Gas::new,
                    ChemicalTags.GAS,
                    IngredientCreatorAccess.gas());

    public static final MekanismChemicalRecipeCapability<InfuseType, InfusionStack> CAP_INFUSE =
            new MekanismChemicalRecipeCapability<>("mek_infuse",
                    MekanismAPI.EMPTY_INFUSE_TYPE,
                    MekanismInfuseTypes.DIAMOND::getChemical,
                    MekanismAPI::infuseTypeRegistry,
                    InfusionStack::new,
                    ChemicalTankBuilder.INFUSION,
                    ChemicalTankWidget.Infuse::new,
                    ChemicalTags.INFUSE_TYPE,
                    IngredientCreatorAccess.infusion());

    public static final MekanismChemicalRecipeCapability<Pigment, PigmentStack> CAP_PIGMENT =
            new MekanismChemicalRecipeCapability<>("mek_pigment",
                    MekanismAPI.EMPTY_PIGMENT,
                    null,
                    MekanismAPI::pigmentRegistry,
                    PigmentStack::new,
                    ChemicalTankBuilder.PIGMENT,
                    ChemicalTankWidget.Pigment::new,
                    ChemicalTags.PIGMENT,
                    IngredientCreatorAccess.pigment());

    public static final MekanismChemicalRecipeCapability<Slurry, SlurryStack> CAP_SLURRY =
            new MekanismChemicalRecipeCapability<>("mek_slurry",
                    MekanismAPI.EMPTY_SLURRY,
                    null,
                    MekanismAPI::slurryRegistry,
                    SlurryStack::new,
                    ChemicalTankBuilder.SLURRY,
                    ChemicalTankWidget.Slurry::new,
                    ChemicalTags.SLURRY,
                    IngredientCreatorAccess.slurry());

    public final CHEMICAL empty;
    @Nullable
    public final Supplier<CHEMICAL> defaultChemical;
    public final Supplier<IForgeRegistry<CHEMICAL>> registry;
    public final BiFunction<CHEMICAL, Long, STACK> createStack;
    public final ChemicalTankBuilder<CHEMICAL, STACK, ? extends IChemicalTank<CHEMICAL, STACK>> tankBuilder;
    public final Supplier<ChemicalTankWidget<CHEMICAL, STACK>> createTankWidget;
    public final ChemicalTags<CHEMICAL> chemicalTags;
    private final IChemicalStackIngredientCreator<CHEMICAL, STACK, ? extends ChemicalStackIngredient<CHEMICAL, STACK>> ingredientCreator;

    protected MekanismChemicalRecipeCapability(String name,
                                               CHEMICAL empty,
                                               @Nullable Supplier<CHEMICAL> defaultChemical,
                                               Supplier<IForgeRegistry<CHEMICAL>> registry,
                                               BiFunction<CHEMICAL, Long, STACK> createStack,
                                               ChemicalTankBuilder<CHEMICAL, STACK, ? extends IChemicalTank<CHEMICAL, STACK>> tankBuilder,
                                               Supplier<ChemicalTankWidget<CHEMICAL, STACK>> createTankWidget,
                                               ChemicalTags<CHEMICAL> chemicalTags,
                                               IChemicalStackIngredientCreator<CHEMICAL, STACK, ? extends ChemicalStackIngredient<CHEMICAL, STACK>> ingredientCreator) {
        super(name, new ChemicalStackIngredientContentSerializer<>(empty, registry, createStack, chemicalTags, ingredientCreator));
        this.empty = empty;
        this.defaultChemical = defaultChemical;
        this.registry = registry;
        this.createStack = createStack;
        this.tankBuilder = tankBuilder;
        this.createTankWidget = createTankWidget;
        this.chemicalTags = chemicalTags;
        this.ingredientCreator = ingredientCreator;
    }

    /** Returns a concrete stack used by tank previews and widget configuration. */
    public STACK createDefaultStack() {
        return createStack.apply(createDefaultChemical(), 1000L);
    }

    public CHEMICAL createDefaultChemical() {
        return defaultChemical == null ? registry.get().getValues().stream().findAny().orElse(empty) : defaultChemical.get();
    }

    /** Creates an exact chemical ingredient, treating a non-positive amount as an empty requirement. */
    public ChemicalStackIngredient<CHEMICAL, STACK> createIngredient(CHEMICAL chemical, long amount) {
        if (amount <= 0 || chemical == null || chemical.isEmptyType()) {
            return emptyIngredient();
        }
        return ingredientCreator.from(chemical, amount);
    }

    /** Creates an ingredient backed by the supplied Mekanism chemical tag. */
    public ChemicalStackIngredient<CHEMICAL, STACK> createTagIngredient(ResourceLocation tag, long amount) {
        return createTagIngredient(chemicalTags.tag(tag), amount);
    }

    /** Creates an ingredient backed by the supplied typed Mekanism chemical tag. */
    public ChemicalStackIngredient<CHEMICAL, STACK> createTagIngredient(TagKey<CHEMICAL> tag, long amount) {
        if (amount <= 0) {
            return emptyIngredient();
        }
        return ingredientCreator.from(tag, amount);
    }

    /** Returns a content copy with every alternative set to the requested remaining amount. */
    public ChemicalStackIngredient<CHEMICAL, STACK> withAmount(ChemicalStackIngredient<CHEMICAL, STACK> content, long amount) {
        return serializer.copyWithModifier(content, ContentModifier.value(amount));
    }

    /** Returns whether the ingredient represents an intentionally empty, zero-sized requirement. */
    public boolean isEmptyIngredient(ChemicalStackIngredient<CHEMICAL, STACK> content) {
        return content instanceof EmptyChemicalStackIngredient;
    }

    /** Returns the first resolved stack for previews and deterministic tag outputs, or {@code null} when none exist. */
    @Nullable
    public STACK getDisplayStack(ChemicalStackIngredient<CHEMICAL, STACK> content) {
        var representations = content.getRepresentations();
        return representations.isEmpty() ? null : representations.get(0);
    }

    /** Returns the amount currently required by the first ingredient alternative. */
    public long getAmount(ChemicalStackIngredient<CHEMICAL, STACK> content) {
        return ChemicalStackIngredientContentSerializer.getFirstAmount(content.serialize());
    }

    @Override
    public ChemicalStackIngredient<CHEMICAL, STACK> createDefaultContent() {
        return createIngredient(createDefaultChemical(), 1000L);
    }

    @Override
    public Widget createPreviewWidget(ChemicalStackIngredient<CHEMICAL, STACK> content) {
        var previewGroup = new WidgetGroup(0, 0, 18, 18);
        var tankWidget = createTankWidget.get();
        var stack = getDisplayStack(of(content));
        if (stack != null && tankBuilder.create(stack.getAmount(), null) instanceof IChemicalHandler handler) {
            handler.insertChemical(stack, Action.EXECUTE);
            tankWidget.setChemicalTank(handler, 0);
        }
        previewGroup.addWidget(tankWidget);
        return previewGroup;
    }

    @Override
    public Widget createXEITemplate() {
        var tankWidget = createTankWidget.get();
        tankWidget.initTemplate();
        tankWidget.setSize(new Size(20, 58));
        tankWidget.setOverlay(new ResourceTexture("mbd2:textures/gui/fluid_tank_overlay.png"));
        tankWidget.setShowAmount(false);
        return tankWidget;
    }

    @Override
    public void bindXEIWidget(Widget widget, Content content, IngredientIO ingredientIO) {
        if (widget instanceof ChemicalTankWidget tankWidget) {
            var ingredient = of(content.content);
            var stack = getDisplayStack(ingredient);
            if (stack != null && tankBuilder.create(stack.getAmount(), null) instanceof IChemicalHandler handler) {
                handler.insertChemical(stack, Action.EXECUTE);
                tankWidget.setChemicalTank(handler, 0);
            }
            if (tankWidget.getOverlay() == null || tankWidget.getOverlay() == IGuiTexture.EMPTY) {
                tankWidget.setOverlay(content.createOverlay());
            } else {
                var groupTexture = new GuiTextureGroup(tankWidget.getOverlay(), content.createOverlay());
                tankWidget.setOverlay(groupTexture);
            }
            tankWidget.setIngredientIO(ingredientIO);
            tankWidget.setAllowClickDrained(false);
            tankWidget.setAllowClickFilled(false);
            tankWidget.setXEIChance(content.chance);
        }
    }

    @Override
    public Component getLeftErrorInfo(List<ChemicalStackIngredient<CHEMICAL, STACK>> left) {
        var result = Component.empty();
        for (int i = 0; i < left.size(); i++) {
            var ingredient = left.get(i);
            result.append(getAmount(ingredient) + "x ");
            var stack = getDisplayStack(ingredient);
            if (stack != null) {
                result.append(stack.getTextComponent());
            } else {
                result.append(Component.literal(getIngredientDescription(ingredient)));
            }
            if (i < left.size() - 1) {
                result.append(", ");
            }
        }
        return result;
    }

    @Override
    public void createContentConfigurator(ConfiguratorGroup father, Supplier<ChemicalStackIngredient<CHEMICAL, STACK>> supplier,
                                          Consumer<ChemicalStackIngredient<CHEMICAL, STACK>> onUpdate) {
        father.addConfigurators(new ConfiguratorSelectorConfigurator<>("recipe.capability.mek_chemical.ingredient.type", false,
                () -> getIngredientKind(supplier.get()), kind -> {
            var amount = Math.max(1L, getAmount(supplier.get()));
            if (kind == IngredientKind.TAG) {
                onUpdate.accept(createTagIngredient(getDefaultTag(), amount));
            } else {
                onUpdate.accept(createIngredient(createDefaultChemical(), amount));
            }
        }, IngredientKind.CHEMICAL, true, List.of(IngredientKind.CHEMICAL, IngredientKind.TAG), IngredientKind::translationKey,
                (kind, group) -> {
                    if (kind == IngredientKind.TAG) {
                        group.addConfigurators(new SearchComponentConfigurator<>("recipe.capability.mek_chemical.tag",
                                () -> getIngredientTag(supplier.get()),
                                tag -> onUpdate.accept(createTagIngredient(tag, Math.max(1L, getAmount(supplier.get())))),
                                getDefaultTag(), true, this::searchTags, ResourceLocation::toString));
                    } else {
                        createChemicalContentConfigurator(group,
                                () -> {
                                    var stack = getDisplayStack(supplier.get());
                                    return stack == null ? createDefaultChemical() : stack.getType();
                                }, chemical -> onUpdate.accept(createIngredient(chemical, Math.max(1L, getAmount(supplier.get())))));
                    }
                }));
        father.addConfigurators(new NumberConfigurator("recipe.capability.mek_chemical.amount",
                () -> Math.max(1L, getAmount(supplier.get())),
                number -> onUpdate.accept(withAmount(supplier.get(), number.longValue())), 1, true).setRange(1, Long.MAX_VALUE));
    }

    /** Adds a concrete-chemical selector for callers such as the tank filter editor. */
    public void createChemicalContentConfigurator(ConfiguratorGroup father, Supplier<CHEMICAL> supplier, Consumer<CHEMICAL> onUpdate) {
        father.addConfigurators(new SearchComponentConfigurator<>("recipe.capability.mek_chemical.type",
                supplier, onUpdate, empty, true, this::searchChemicals, value -> {
            if (value.isEmptyType()) {
                return "empty";
            }
            var key = registry.get().getKey(value);
            return key == null ? "empty" : key.toString();
        }));
    }

    private void searchChemicals(String word, Consumer<CHEMICAL> find) {
        var lowerCase = word.toLowerCase(Locale.ROOT);
        if ("empty".contains(lowerCase)) {
            find.accept(empty);
            return;
        }
        var words = lowerCase.split(" ");
        for (var entry : registry.get().getEntries()) {
            var key = entry.getKey();
            var chemical = entry.getValue();
            for (String part : words) {
                if (key.toString().toLowerCase(Locale.ROOT).contains(part)
                        || LocalizationUtils.format(chemical.getTranslationKey()).toLowerCase(Locale.ROOT).contains(part)) {
                    find.accept(chemical);
                    break;
                }
            }
        }
    }

    private void searchTags(String word, Consumer<ResourceLocation> find) {
        var manager = chemicalTags.getManager();
        if (manager.isEmpty()) {
            return;
        }
        var lowerCase = word.toLowerCase(Locale.ROOT);
        for (var tag : manager.get()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            var tagId = tag.getKey().location();
            if (tagId.toString().toLowerCase(Locale.ROOT).contains(lowerCase)) {
                find.accept(tagId);
            }
        }
    }

    private ResourceLocation getDefaultTag() {
        var manager = chemicalTags.getManager();
        if (manager.isPresent()) {
            for (var tag : manager.get()) {
                return tag.getKey().location();
            }
        }
        return new ResourceLocation("minecraft", "empty");
    }

    private ResourceLocation getIngredientTag(ChemicalStackIngredient<CHEMICAL, STACK> content) {
        var serialized = content.serialize();
        if (serialized.isJsonObject()) {
            var object = serialized.getAsJsonObject();
            if (object.has("tag")) {
                var tag = ResourceLocation.tryParse(object.get("tag").getAsString());
                if (tag != null) {
                    return tag;
                }
            }
        }
        return getDefaultTag();
    }

    private IngredientKind getIngredientKind(ChemicalStackIngredient<CHEMICAL, STACK> content) {
        var serialized = content.serialize();
        return serialized.isJsonObject() && serialized.getAsJsonObject().has("tag") ? IngredientKind.TAG : IngredientKind.CHEMICAL;
    }

    private String getIngredientDescription(ChemicalStackIngredient<CHEMICAL, STACK> content) {
        var serialized = content.serialize();
        if (serialized.isJsonObject() && serialized.getAsJsonObject().has("tag")) {
            return "#" + serialized.getAsJsonObject().get("tag").getAsString();
        }
        return isEmptyIngredient(content) ? "empty" : "unknown";
    }

    @SuppressWarnings("unchecked")
    private ChemicalStackIngredient<CHEMICAL, STACK> emptyIngredient() {
        return new EmptyChemicalStackIngredient<>((STACK) empty.getStack(0));
    }

    private enum IngredientKind {
        CHEMICAL("recipe.capability.mek_chemical.ingredient.type.chemical"),
        TAG("recipe.capability.mek_chemical.ingredient.type.tag");

        private final String translationKey;

        IngredientKind(String translationKey) {
            this.translationKey = translationKey;
        }

        private String translationKey() {
            return translationKey;
        }
    }

    private static final class EmptyChemicalStackIngredient<CHEMICAL extends Chemical<CHEMICAL>, STACK extends ChemicalStack<CHEMICAL>>
            implements ChemicalStackIngredient<CHEMICAL, STACK> {
        private final STACK emptyStack;

        private EmptyChemicalStackIngredient(STACK emptyStack) {
            this.emptyStack = emptyStack;
        }

        @Override
        public boolean test(STACK stack) {
            return stack.isEmpty();
        }

        @Override
        public boolean testType(STACK stack) {
            return false;
        }

        @Override
        public boolean testType(CHEMICAL chemical) {
            return false;
        }

        @Override
        public STACK getMatchingInstance(STACK stack) {
            return emptyStack;
        }

        @Override
        public long getNeededAmount(STACK stack) {
            return 0;
        }

        @Override
        public boolean hasNoMatchingInstances() {
            return true;
        }

        @Override
        public List<STACK> getRepresentations() {
            return List.of();
        }

        @Override
        public void write(FriendlyByteBuf buffer) {
            throw new UnsupportedOperationException("Empty chemical ingredients are encoded by the MBD serializer");
        }

        @Override
        public JsonElement serialize() {
            var json = new JsonObject();
            json.addProperty("type", "minecraft:empty");
            json.addProperty("amount", 0);
            return json;
        }
    }

    private static final class ChemicalStackIngredientContentSerializer<CHEMICAL extends Chemical<CHEMICAL>, STACK extends ChemicalStack<CHEMICAL>>
            implements IContentSerializer<ChemicalStackIngredient<CHEMICAL, STACK>> {
        private final CHEMICAL empty;
        private final Supplier<IForgeRegistry<CHEMICAL>> registry;
        private final BiFunction<CHEMICAL, Long, STACK> createStack;
        private final ChemicalTags<CHEMICAL> chemicalTags;
        private final IChemicalStackIngredientCreator<CHEMICAL, STACK, ? extends ChemicalStackIngredient<CHEMICAL, STACK>> ingredientCreator;

        private ChemicalStackIngredientContentSerializer(CHEMICAL empty,
                                                          Supplier<IForgeRegistry<CHEMICAL>> registry,
                                                          BiFunction<CHEMICAL, Long, STACK> createStack,
                                                          ChemicalTags<CHEMICAL> chemicalTags,
                                                          IChemicalStackIngredientCreator<CHEMICAL, STACK, ? extends ChemicalStackIngredient<CHEMICAL, STACK>> ingredientCreator) {
            this.empty = empty;
            this.registry = registry;
            this.createStack = createStack;
            this.chemicalTags = chemicalTags;
            this.ingredientCreator = ingredientCreator;
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, ChemicalStackIngredient<CHEMICAL, STACK> content) {
            boolean emptyIngredient = content instanceof EmptyChemicalStackIngredient;
            buf.writeBoolean(emptyIngredient);
            if (!emptyIngredient) {
                content.write(buf);
            }
        }

        @Override
        public ChemicalStackIngredient<CHEMICAL, STACK> fromNetwork(FriendlyByteBuf buf) {
            return buf.readBoolean() ? emptyIngredient() : ingredientCreator.read(buf);
        }

        @Override
        public ChemicalStackIngredient<CHEMICAL, STACK> fromJson(JsonElement json) {
            if (json != null && json.isJsonObject()) {
                var object = json.getAsJsonObject();
                long amount = getAmount(object);
                if (amount <= 0) {
                    return emptyIngredient();
                }
                if (object.has("type")) {
                    String id = object.get("type").getAsString();
                    if ("minecraft:empty".equals(id) || "empty".equals(id)) {
                        return emptyIngredient();
                    }
                    if (id.startsWith("#")) {
                        return createTagIngredient(parseResourceLocation(id.substring(1)), amount);
                    }
                    CHEMICAL chemical = ChemicalUtils.readChemicalFromRegistry(parseResourceLocation(id), empty, registry.get());
                    return createIngredient(chemical, amount);
                }
            }
            return ingredientCreator.deserialize(json);
        }

        @Override
        public JsonElement toJson(ChemicalStackIngredient<CHEMICAL, STACK> content) {
            if (content instanceof EmptyChemicalStackIngredient) {
                return content.serialize();
            }
            var serialized = content.serialize();
            // Keep existing exact-stack recipe JSON stable while using Mekanism's native tag/multi format where needed.
            if (serialized.isJsonObject() && !serialized.getAsJsonObject().has("tag")) {
                var representations = content.getRepresentations();
                if (representations.size() == 1) {
                    var stack = representations.get(0);
                    var legacy = new JsonObject();
                    legacy.addProperty("type", stack.getType().getRegistryName().toString());
                    legacy.addProperty("amount", stack.getAmount());
                    return legacy;
                }
            }
            return serialized;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public ChemicalStackIngredient<CHEMICAL, STACK> of(Object o) {
            if (o instanceof ChemicalStackIngredient<?, ?> ingredient) {
                return isCompatibleIngredient(ingredient) ? (ChemicalStackIngredient<CHEMICAL, STACK>) ingredient : emptyIngredient();
            }
            if (o instanceof ChemicalStack<?> stack) {
                if (!isCompatibleChemical(stack.getType()) || stack.isEmpty()) {
                    return emptyIngredient();
                }
                return ingredientCreator.from((STACK) stack);
            }
            if (o instanceof Chemical<?> chemical) {
                return isCompatibleChemical(chemical) ? createIngredient((CHEMICAL) chemical, 1) : emptyIngredient();
            }
            if (o instanceof TagKey<?> tag) {
                return createTagIngredient(tag.location(), 1);
            }
            if (o instanceof JsonElement json) {
                return fromJson(json);
            }
            if (o instanceof CharSequence chars) {
                return parseInput(chars.toString());
            }
            return emptyIngredient();
        }

        @Override
        public boolean supportsOutputMultiplier(ChemicalStackIngredient<CHEMICAL, STACK> content, long multiplier) {
            return supportsMultiplier(content.serialize(), multiplier);
        }

        @Override
        public ChemicalStackIngredient<CHEMICAL, STACK> copyInner(ChemicalStackIngredient<CHEMICAL, STACK> content) {
            return fromJson(toJson(content));
        }

        @Override
        public ChemicalStackIngredient<CHEMICAL, STACK> copyWithModifier(ChemicalStackIngredient<CHEMICAL, STACK> content, ContentModifier modifier) {
            var modified = modifyAmounts(content.serialize(), modifier);
            return modified == null ? emptyIngredient() : fromJson(modified);
        }

        @Override
        public ChemicalStackIngredient<CHEMICAL, STACK> deepCopyInner(ChemicalStackIngredient<CHEMICAL, STACK> content) {
            return copyInner(content);
        }

        private ChemicalStackIngredient<CHEMICAL, STACK> createIngredient(CHEMICAL chemical, long amount) {
            if (amount <= 0 || chemical == null || chemical.isEmptyType()) {
                return emptyIngredient();
            }
            return ingredientCreator.from(chemical, amount);
        }

        private ChemicalStackIngredient<CHEMICAL, STACK> createTagIngredient(ResourceLocation tag, long amount) {
            if (amount <= 0) {
                return emptyIngredient();
            }
            return ingredientCreator.from(chemicalTags.tag(tag), amount);
        }

        @SuppressWarnings("unchecked")
        private ChemicalStackIngredient<CHEMICAL, STACK> emptyIngredient() {
            return new EmptyChemicalStackIngredient<>((STACK) empty.getStack(0));
        }

        private boolean isCompatibleIngredient(ChemicalStackIngredient<?, ?> ingredient) {
            return switch (ChemicalType.getTypeFor(empty)) {
                case GAS -> ingredient instanceof ChemicalStackIngredient.GasStackIngredient;
                case INFUSION -> ingredient instanceof ChemicalStackIngredient.InfusionStackIngredient;
                case PIGMENT -> ingredient instanceof ChemicalStackIngredient.PigmentStackIngredient;
                case SLURRY -> ingredient instanceof ChemicalStackIngredient.SlurryStackIngredient;
            };
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private boolean isCompatibleChemical(Chemical<?> chemical) {
            return ChemicalType.getTypeFor((Chemical) chemical) == ChemicalType.getTypeFor(empty);
        }

        private ChemicalStackIngredient<CHEMICAL, STACK> parseInput(String rawInput) {
            String input = rawInput.trim();
            if (input.isEmpty() || input.equals("-") || input.equals("empty") || input.equals("minecraft:empty")) {
                return emptyIngredient();
            }

            String id;
            long amount;
            int multiplierSeparator = input.indexOf('x');
            if (multiplierSeparator > 0 && multiplierSeparator < input.length() - 2 && input.charAt(multiplierSeparator + 1) == ' ') {
                amount = parseAmount(input.substring(0, multiplierSeparator), rawInput);
                id = input.substring(multiplierSeparator + 2).trim();
            } else {
                String[] parts = input.split("\\s+");
                if (parts.length == 1) {
                    id = parts[0];
                    amount = 1;
                } else if (parts.length == 2) {
                    id = parts[0];
                    amount = parseAmount(parts[1], rawInput);
                } else {
                    throw new IllegalStateException("Invalid chemical input: " + rawInput);
                }
            }

            if (amount == 0) {
                return emptyIngredient();
            }
            if (id.startsWith("#")) {
                return createTagIngredient(parseResourceLocation(id.substring(1)), amount);
            }
            CHEMICAL chemical = ChemicalUtils.readChemicalFromRegistry(parseResourceLocation(id), empty, registry.get());
            return createIngredient(chemical, amount);
        }

        private static long parseAmount(String value, String rawInput) {
            try {
                long amount = Long.parseLong(value);
                if (amount < 0) {
                    throw new IllegalStateException("Invalid chemical input: " + rawInput);
                }
                return amount;
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Invalid chemical input: " + rawInput, exception);
            }
        }

        private static ResourceLocation parseResourceLocation(String id) {
            var location = ResourceLocation.tryParse(id);
            if (location == null) {
                throw new IllegalArgumentException("Invalid chemical id: " + id);
            }
            return location;
        }

        private static boolean supportsMultiplier(JsonElement json, long multiplier) {
            if (multiplier < 0) {
                return false;
            }
            if (json.isJsonArray()) {
                for (var element : json.getAsJsonArray()) {
                    if (!supportsMultiplier(element, multiplier)) {
                        return false;
                    }
                }
                return true;
            }
            if (!json.isJsonObject()) {
                return false;
            }
            return IContentSerializer.supportsOutputMultiplier(getAmount(json.getAsJsonObject()), multiplier, Long.MAX_VALUE);
        }

        @Nullable
        private static JsonElement modifyAmounts(JsonElement json, ContentModifier modifier) {
            if (json.isJsonArray()) {
                var array = new JsonArray();
                for (var element : json.getAsJsonArray()) {
                    var modified = modifyAmounts(element, modifier);
                    if (modified != null) {
                        array.add(modified);
                    }
                }
                if (array.isEmpty()) {
                    return null;
                }
                return array.size() == 1 ? array.get(0) : array;
            }
            if (!json.isJsonObject()) {
                return null;
            }
            var copy = json.getAsJsonObject().deepCopy();
            long amount = modifier.apply(getAmount(copy)).longValue();
            if (amount <= 0) {
                return null;
            }
            copy.addProperty("amount", amount);
            return copy;
        }

        private static long getFirstAmount(JsonElement json) {
            if (json.isJsonArray()) {
                var array = json.getAsJsonArray();
                return array.isEmpty() ? 0 : getFirstAmount(array.get(0));
            }
            return json.isJsonObject() ? getAmount(json.getAsJsonObject()) : 0;
        }

        private static long getAmount(JsonObject object) {
            return object.has("amount") ? object.get("amount").getAsLong() : 0;
        }
    }
}
