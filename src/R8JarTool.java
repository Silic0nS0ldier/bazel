// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Pre/post-processing for running R8 over the Bazel server deploy jar.
 *
 * <ul>
 *   <li>{@code strip <in.jar> <out.jar>}: removes multi-release variants (META-INF/versions) and
 *       module-info classes that R8's classfile backend cannot handle.
 *   <li>{@code fix <r8out.jar> <orig.jar> <out.jar> [--fix-inner-classes]}: restores the original
 *       manifest (R8 may drop it), removes the stale META-INF/INDEX.LIST, and optionally
 *       reconciles InnerClasses attribute visibility flags with the raw class access flags. R8
 *       full mode rewrites the former to public while leaving the latter package-private, which
 *       breaks JDK annotation proxies (e.g. netty's @Skip).
 * </ul>
 */
public final class R8JarTool {
  private static final long FIXED_TIMESTAMP = 315532800000L; // 1980-01-01, for determinism

  public static void main(String[] args) throws IOException {
    switch (args[0]) {
      case "strip" -> strip(args[1], args[2]);
      case "fix" -> fix(args[1], args[2], args[3], args.length > 4 && args[4].equals("--fix-inner-classes"));
      default -> throw new IllegalArgumentException("unknown command: " + args[0]);
    }
  }

  private static void strip(String in, String out) throws IOException {
    try (ZipFile zf = new ZipFile(in);
        ZipOutputStream zos = newZipOutputStream(out)) {
      for (var en = zf.entries(); en.hasMoreElements(); ) {
        ZipEntry e = en.nextElement();
        String name = e.getName();
        if (name.startsWith("META-INF/versions/")
            || name.equals("module-info.class")
            || name.endsWith("/module-info.class")) {
          continue;
        }
        copyEntry(zos, name, zf.getInputStream(e).readAllBytes());
      }
    }
  }

  private static void fix(String r8out, String orig, String out, boolean fixInnerClasses)
      throws IOException {
    byte[] manifest;
    try (ZipFile zf = new ZipFile(orig)) {
      ZipEntry e = zf.getEntry("META-INF/MANIFEST.MF");
      if (e == null) {
        throw new IOException("no manifest in " + orig);
      }
      manifest = zf.getInputStream(e).readAllBytes();
    }
    Map<String, Integer> rawAccess = fixInnerClasses ? indexRawAccess(r8out) : Map.of();
    try (ZipFile zf = new ZipFile(r8out);
        ZipOutputStream zos = newZipOutputStream(out)) {
      copyEntry(zos, "META-INF/MANIFEST.MF", manifest);
      for (var en = zf.entries(); en.hasMoreElements(); ) {
        ZipEntry e = en.nextElement();
        String name = e.getName();
        if (name.equals("META-INF/MANIFEST.MF") || name.equals("META-INF/INDEX.LIST")) {
          continue;
        }
        byte[] data = zf.getInputStream(e).readAllBytes();
        if (fixInnerClasses && name.endsWith(".class")) {
          data = fixInnerClassesAttr(data, rawAccess);
        }
        copyEntry(zos, name, data);
      }
    }
  }

  /** Maps binary class name to the raw access flags from the class file header. */
  private static Map<String, Integer> indexRawAccess(String jar) throws IOException {
    Map<String, Integer> rawAccess = new HashMap<>();
    try (ZipFile zf = new ZipFile(jar)) {
      for (var en = zf.entries(); en.hasMoreElements(); ) {
        ZipEntry e = en.nextElement();
        if (!e.getName().endsWith(".class")) {
          continue;
        }
        try (DataInputStream in = new DataInputStream(zf.getInputStream(e))) {
          in.skipBytes(8); // magic + version
          int cpCount = in.readUnsignedShort();
          String[] utf = new String[cpCount];
          int[] classIdx = new int[cpCount];
          for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
              case 1 -> utf[i] = in.readUTF();
              case 7 -> classIdx[i] = in.readUnsignedShort();
              case 8, 16, 19, 20 -> in.skipBytes(2);
              case 15 -> in.skipBytes(3);
              case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
              case 5, 6 -> {
                in.skipBytes(8);
                i++;
              }
              default -> throw new IOException("bad cp tag " + tag + " in " + e.getName());
            }
          }
          int access = in.readUnsignedShort();
          int thisClass = in.readUnsignedShort();
          rawAccess.put(utf[classIdx[thisClass]], access);
        }
      }
    }
    return rawAccess;
  }

  private static byte[] fixInnerClassesAttr(byte[] data, Map<String, Integer> rawAccess) {
    ClassReader cr = new ClassReader(data);
    ClassWriter cw = new ClassWriter(0);
    cr.accept(
        new ClassVisitor(Opcodes.ASM9, cw) {
          @Override
          public void visitInnerClass(String name, String outer, String inner, int acc) {
            Integer raw = rawAccess.get(name);
            if (raw != null) {
              int mask = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
              acc = (acc & ~mask) | (raw & mask);
            }
            super.visitInnerClass(name, outer, inner, acc);
          }
        },
        0);
    return cw.toByteArray();
  }

  private static ZipOutputStream newZipOutputStream(String path) throws IOException {
    return new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
  }

  private static void copyEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
    ZipEntry ne = new ZipEntry(name);
    ne.setTime(FIXED_TIMESTAMP);
    zos.putNextEntry(ne);
    zos.write(data);
    zos.closeEntry();
  }

  private R8JarTool() {}
}
