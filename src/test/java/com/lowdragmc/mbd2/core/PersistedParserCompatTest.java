package com.lowdragmc.mbd2.core;

import com.lowdragmc.mbd2.utils.PersistedParserCompat;
import net.minecraft.nbt.CompoundTag;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistedParserCompatTest {
    @Test
    void deserializesWithoutResolvingUnavailablePublicMethodSignatures() throws Exception {
        Class<?> broken = new BrokenClassLoader().loadClass("probe.Broken");
        assertThrows(LinkageError.class, broken::getMethods);

        Object object = broken.getDeclaredConstructor().newInstance();
        assertDoesNotThrow(() -> PersistedParserCompat.deserializeNBT(
                new CompoundTag(), new HashMap<String, Method>(), broken, object));
    }

    private static byte[] brokenClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Broken", null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor safe = writer.visitMethod(Opcodes.ACC_PUBLIC, "safe", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        safe.visitCode();
        safe.visitVarInsn(Opcodes.ALOAD, 1);
        safe.visitInsn(Opcodes.ARETURN);
        safe.visitMaxs(1, 2);
        safe.visitEnd();

        MethodVisitor broken = writer.visitMethod(Opcodes.ACC_PUBLIC, "broken", "(Lprobe/Missing;)V", null, null);
        broken.visitCode();
        broken.visitInsn(Opcodes.RETURN);
        broken.visitMaxs(0, 2);
        broken.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class BrokenClassLoader extends ClassLoader {
        private final byte[] bytes = brokenClassBytes();

        private BrokenClassLoader() {
            super(PersistedParserCompatTest.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals("probe.Broken")) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name.equals("probe/Broken.class")) {
                return new ByteArrayInputStream(bytes);
            }
            return super.getResourceAsStream(name);
        }
    }
}
