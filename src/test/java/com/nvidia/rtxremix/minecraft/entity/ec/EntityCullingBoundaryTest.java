/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity.ec;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Entity Culling is optional, so one class alone may name its types: a reference anywhere
 * else would link against a mod that may be absent. Its mixin plugin and its mixin name
 * none either. Entity Culling is not on the test classpath, so the compiled classes are
 * read rather than loaded.
 */
class EntityCullingBoundaryTest {

    private static final String ENTITY_CULLING_PACKAGE = "dev.tr7zw";
    private static final String MOD_PACKAGE = ENTITY_CULLING_PACKAGE + ".entityculling";
    /** What the flags reader reads: the flags, the mod and its setting, and its culling test. */
    private static final Set<String> READ_TYPES = Set.of(
            MOD_PACKAGE + ".access.Cullable",
            MOD_PACKAGE + ".EntityCullingModBase",
            MOD_PACKAGE + ".NMSCullingHelper",
            MOD_PACKAGE + ".versionless.Config");
    private static final String FLAGS_READER = "com.nvidia.rtxremix.minecraft.entity.ec.EntityCullingFlags";

    @Test
    void onlyTheFlagsReaderNamesAnEntityCullingType() throws Exception {
        Map<String, Set<String>> namers = new TreeMap<>();
        Path root = classesRoot();
        List<Path> classFiles;
        try (Stream<Path> files = Files.walk(root)) {
            classFiles = files.filter(path -> path.toString().endsWith(".class")).toList();
        }
        for (Path classFile : classFiles) {
            ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
            Set<String> named = entityCullingTypesNamedBy(reader);
            if (!named.isEmpty()) {
                namers.put(reader.getClassName(), named);
            }
        }

        Set<String> readTypes = new TreeSet<>();
        READ_TYPES.forEach(type -> readTypes.add(internalName(type)));
        assertEquals(Map.of(internalName(FLAGS_READER), readTypes), namers,
                "only the flags reader may name an Entity Culling type, and only those it reads");
    }

    /** Every Entity Culling type a class names: supertypes, members, signatures, code and annotations. */
    private static Set<String> entityCullingTypesNamedBy(ClassReader reader) {
        String packagePrefix = internalName(ENTITY_CULLING_PACKAGE) + "/";
        Set<String> named = new TreeSet<>();
        Remapper recorder = new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                if (internalName.startsWith(packagePrefix)) {
                    named.add(internalName);
                }
                return internalName;
            }
        };
        reader.accept(new ClassRemapper(new ClassNode(), recorder), 0);
        return named;
    }

    private static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }

    private static Path classesRoot() throws Exception {
        return Path.of(RtxRemixMod.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
