package com.lowdragmc.mbd2.utils;

import com.google.common.base.Strings;
import com.lowdragmc.lowdraglib.gui.editor.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import com.lowdragmc.lowdraglib.syncdata.ManagedFieldUtils;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.utils.ReflectionUtils;
import com.lowdragmc.lowdraglib.utils.TagUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Dedicated-server-safe implementation of LowDragLib's persisted-field parser.
 *
 * <p>LowDragLib discovers configurable setters with {@link Class#getMethods()}. That operation
 * resolves every method signature, including client-only signatures inherited by widgets, and can
 * therefore throw a {@link LinkageError} on a dedicated server. This implementation keeps the
 * normal reflection path and falls back to reading only {@link ConfigSetter} method metadata from
 * the class file. Safe setters are then resolved with method handles, so persisted fields retain
 * their normal side effects without loading unrelated client classes.</p>
 */
public final class PersistedParserCompat {
    private static final String CONFIG_SETTER_DESCRIPTOR = Type.getDescriptor(ConfigSetter.class);

    private PersistedParserCompat() {
    }

    /**
     * Deserializes persisted fields using LowDragLib-compatible semantics.
     *
     * @param tag     source NBT compound
     * @param setters caller-owned reflection setter cache
     * @param clazz   class whose fields should be read
     * @param object  object receiving the persisted values
     */
    public static void deserializeNBT(CompoundTag tag, Map<String, Method> setters, Class<?> clazz, Object object) {
        deserializeNBT(tag, setters, new HashMap<>(), clazz, object, new HashSet<>());
    }

    private static void deserializeNBT(CompoundTag tag,
                                      Map<String, Method> setters,
                                      Map<String, SetterHandle> fallbackSetters,
                                      Class<?> clazz,
                                      Object object,
                                      Set<Class<?>> visited) {
        if (clazz == Object.class || clazz == null || !visited.add(clazz)) {
            return;
        }

        if (!collectReflectiveSetters(setters, clazz)) {
            collectBytecodeSetters(setters, fallbackSetters, clazz);
        }

        Class<?> superclass = null;
        try {
            superclass = clazz.getSuperclass();
        } catch (LinkageError | SecurityException ignored) {
            // A malformed optional superclass should not prevent fields on this class from loading.
        }
        deserializeNBT(tag, setters, fallbackSetters, superclass, object, visited);

        try {
            for (Class<?> interfaceType : clazz.getInterfaces()) {
                deserializeNBT(tag, setters, fallbackSetters, interfaceType, object, visited);
            }
        } catch (LinkageError | SecurityException ignored) {
            // Interface metadata can reference client-only types on a dedicated server.
        }

        Field[] fields;
        try {
            fields = clazz.getDeclaredFields();
        } catch (LinkageError | SecurityException ignored) {
            // Reflection cannot expose a partial Field[] when one field type is unavailable.
            return;
        }

        for (Field field : fields) {
            try {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                String key = field.getName();
                Configurable configurable = field.getAnnotation(Configurable.class);
                if (configurable != null) {
                    if (!configurable.persisted()) {
                        continue;
                    }
                    if (!Strings.isNullOrEmpty(configurable.key())) {
                        key = configurable.key();
                    }
                } else {
                    Persisted persisted = field.getAnnotation(Persisted.class);
                    if (persisted == null) {
                        continue;
                    }
                    if (!Strings.isNullOrEmpty(persisted.key())) {
                        key = persisted.key();
                    }
                }

                Tag nbt = TagUtils.getTagExtended(tag, key);
                if (nbt == null) {
                    continue;
                }

                if (isSubConfigurable(field)) {
                    deserializeSubConfigurable(field, nbt, object);
                    continue;
                }

                var managedKey = ManagedFieldUtils.createKey(field);
                managedKey.writePersistedField(managedKey.createRef(object), nbt);

                // LowDragLib keys setters by the Java field name, even when the persisted NBT key
                // is overridden by @Configurable(key = ...) or @Persisted(key = ...).
                Method setter = setters.get(field.getName());
                if (setter != null) {
                    field.setAccessible(true);
                    invokeSetter(setter, object, field.get(object));
                } else {
                    SetterHandle fallbackSetter = fallbackSetters.get(field.getName());
                    if (fallbackSetter != null) {
                        field.setAccessible(true);
                        invokeSetter(fallbackSetter, object, field.get(object));
                    }
                }
            } catch (LinkageError ignored) {
                // A persisted field may itself be client-only. Skipping that field keeps the
                // server-side template usable while the client reads the same project data.
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static boolean isSubConfigurable(Field field) {
        Configurable configurable = field.getAnnotation(Configurable.class);
        return configurable != null && configurable.subConfigurable()
                || field.isAnnotationPresent(Persisted.class) && field.getAnnotation(Persisted.class).subPersisted();
    }

    private static void deserializeSubConfigurable(Field field, Tag nbt, Object object) {
        try {
            field.setAccessible(true);
            Object value = field.get(object);
            if (value == null) {
                return;
            }
            if (value instanceof ITagSerializable serializable) {
                serializable.deserializeNBT(nbt);
            } else if (nbt instanceof CompoundTag compoundTag) {
                Class<?> rawType = ReflectionUtils.getRawType(field.getGenericType());
                deserializeNBT(compoundTag, new HashMap<>(), new HashMap<>(), rawType, value, new HashSet<>());
            }
        } catch (IllegalAccessException ignored) {
        } catch (LinkageError ignored) {
            // See the field-level guard in deserializeNBT: a client-only nested type is optional
            // for the server-side UI template.
        }
    }

    /**
     * Uses the original reflection path when all method signatures are linkable.
     *
     * @return {@code true} when the public method list was available
     */
    private static boolean collectReflectiveSetters(Map<String, Method> setters, Class<?> clazz) {
        Method[] methods;
        try {
            methods = clazz.getMethods();
        } catch (LinkageError | SecurityException ignored) {
            return false;
        }

        boolean complete = true;
        for (Method method : methods) {
            try {
                ConfigSetter configSetter = method.getAnnotation(ConfigSetter.class);
                if (configSetter != null) {
                    setters.putIfAbsent(configSetter.field(), method);
                }
            } catch (LinkageError | RuntimeException ignored) {
                complete = false;
            }
        }
        return complete;
    }

    private static void collectBytecodeSetters(Map<String, Method> setters,
                                               Map<String, SetterHandle> fallbackSetters,
                                               Class<?> clazz) {
        try (InputStream input = openClassResource(clazz)) {
            if (input == null) {
                return;
            }
            ClassReader reader = new ClassReader(input);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                            if (!CONFIG_SETTER_DESCRIPTOR.equals(annotationDescriptor)) {
                                return null;
                            }
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                private String fieldName;

                                @Override
                                public void visit(String annotationName, Object value) {
                                    if ("field".equals(annotationName) && value instanceof String string) {
                                        fieldName = string;
                                    }
                                }

                                @Override
                                public void visitEnd() {
                                    if (fieldName != null && !setters.containsKey(fieldName)) {
                                        registerBytecodeSetter(fallbackSetters, clazz, access, name, descriptor, fieldName);
                                    }
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException | RuntimeException | LinkageError ignored) {
            // Reflection-based field loading remains useful even when class metadata is not
            // available through the active secure class loader.
        }
    }

    private static InputStream openClassResource(Class<?> clazz) {
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        ClassLoader loader = clazz.getClassLoader();
        InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(resourceName)
                : loader.getResourceAsStream(resourceName);
        return input != null ? input : clazz.getResourceAsStream("/" + resourceName);
    }

    private static void registerBytecodeSetter(Map<String, SetterHandle> fallbackSetters,
                                               Class<?> owner,
                                               int access,
                                               String name,
                                               String descriptor,
                                               String fieldName) {
        if (fallbackSetters.containsKey(fieldName)) {
            return;
        }
        try {
            ClassLoader loader = owner.getClassLoader();
            MethodType methodType = MethodType.fromMethodDescriptorString(descriptor, loader);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            MethodHandle handle;
            boolean staticMethod = Modifier.isStatic(access);
            if (staticMethod) {
                handle = lookup.findStatic(owner, name, methodType);
            } else {
                handle = lookup.findVirtual(owner, name, methodType);
            }
            fallbackSetters.put(fieldName, new SetterHandle(handle, staticMethod));
        } catch (NoSuchMethodException | IllegalAccessException | LinkageError | RuntimeException ignored) {
            // A setter whose own signature is unavailable cannot be called on this distribution.
        }
    }

    private static void invokeSetter(Method setter, Object object, Object value) {
        try {
            setter.setAccessible(true);
            setter.invoke(object, value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static void invokeSetter(SetterHandle setter, Object object, Object value) {
        try {
            if (setter.staticMethod()) {
                setter.handle().invokeWithArguments(value);
            } else {
                setter.handle().invokeWithArguments(object, value);
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private record SetterHandle(MethodHandle handle, boolean staticMethod) {
    }
}
