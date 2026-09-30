package com.lowdragmc.mbd2.common.gui.editor;

import com.lowdragmc.lowdraglib.gui.editor.configurator.StringConfigurator;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Keeps partial input editable while only committing valid values to the configuration. */
public final class ValidatedStringConfigurator extends StringConfigurator {
    private final Predicate<String> validator;

    public ValidatedStringConfigurator(String name, Supplier<String> getter, Consumer<String> setter,
                                       String defaultValue, Predicate<String> validator) {
        super(name, getter, setter, defaultValue, false);
        this.validator = validator;
    }

    @Override
    public void init(int width) {
        super.init(width);
        textFieldWidget.setMaxStringLength(32767);
        updateValidationColor();
    }

    private boolean updateValidationColor() {
        boolean valid = validator.test(value);
        if (textFieldWidget != null) textFieldWidget.setTextColor(valid ? 0xFFFFFFFF : 0xFFFF5555);
        return valid;
    }

    @Override
    protected void updateValue() {
        if (updateValidationColor()) super.updateValue();
    }
}
