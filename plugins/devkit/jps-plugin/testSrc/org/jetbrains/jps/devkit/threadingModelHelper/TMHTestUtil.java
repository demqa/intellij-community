// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jps.devkit.threadingModelHelper;

import com.intellij.compiler.instrumentation.FailSafeClassReader;
import com.intellij.compiler.instrumentation.InstrumenterClassWriter;
import com.intellij.openapi.util.Ref;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.util.TraceClassVisitor;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import static org.jetbrains.jps.devkit.threadingModelHelper.TMHAssertionGenerator.generators;

public final class TMHTestUtil {

  public static void printDebugInfo(byte[] classData, byte[] instrumentedClassData) {
    System.out.println(classDataToText(classData));
    System.out.println();
    System.out.println(classDataToText(instrumentedClassData));
  }

  public static @NotNull String classDataToText(byte[] data) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    @SuppressWarnings("ImplicitDefaultCharsetUsage")
    PrintWriter printWriter = new PrintWriter(buffer);
    TraceClassVisitor visitor = new TraceClassVisitor(printWriter);
    new ClassReader(data).accept(visitor, 0);
    return buffer.toString();
  }

  public static boolean containsMethodCall(byte @NotNull [] classBytes, final @NotNull String methodName) {
    Ref<Boolean> contains = Ref.create(Boolean.FALSE);
    ClassVisitor visitor = new ClassVisitor(Opcodes.API_VERSION) {
      @Override
      public MethodVisitor visitMethod(int access,
                                       String name,
                                       String descriptor,
                                       String signature,
                                       String[] exceptions) {
        return new MethodVisitor(Opcodes.API_VERSION) {
          @Override
          public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (name.equals(methodName)) {
              contains.set(Boolean.TRUE);
            }
          }
        };
      }
    };
    ClassReader reader = new ClassReader(classBytes);
    reader.accept(visitor, 0);
    return contains.get();
  }

  public static List<Integer> getLineNumbers(byte @NotNull [] classBytes) {
    List<Integer> lineNumbers = new ArrayList<>();
    ClassVisitor visitor = new ClassVisitor(Opcodes.API_VERSION) {
      @Override
      public MethodVisitor visitMethod(int access,
                                       String name,
                                       String descriptor,
                                       String signature,
                                       String[] exceptions) {
        return new MethodVisitor(Opcodes.API_VERSION) {
          @Override
          public void visitLineNumber(int line, Label start) {
            lineNumbers.add(line);
          }
        };
      }
    };
    ClassReader reader = new ClassReader(classBytes);
    reader.accept(visitor, 0);
    return lineNumbers;
  }

  public static byte @Nullable [] instrument(byte @NotNull [] classData) {
    FailSafeClassReader reader = new FailSafeClassReader(classData);
    int flags = InstrumenterClassWriter.getAsmClassWriterFlags(InstrumenterClassWriter.getClassFileVersion(reader));
    ClassWriter writer = new ClassWriter(reader, flags);
    boolean instrumented = TMHInstrumenter.instrument(
      reader, writer,
      generators(
        "com/intellij/util/concurrency/fake/ThreadingAssertions",
        "com/intellij/util/concurrency/annotations/fake"
      ),
      true
    );
    return instrumented ? writer.toByteArray() : null;
  }
}
