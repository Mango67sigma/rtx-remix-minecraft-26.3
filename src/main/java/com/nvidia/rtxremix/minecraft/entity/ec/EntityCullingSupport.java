/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity.ec;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the capture follows Entity Culling: the mod is installed, in any version, the
 * switch is on, and its culling flags can be read. Entity Culling hides from vanilla's
 * render whatever no ray from the camera reaches, which a path tracer still needs for
 * shadows and reflections. While this applies, the entity pass replays a culled entity's
 * last pose rather than capturing it afresh, and the block-entity pass keeps the parts of
 * a block entity Entity Culling withheld rather than retiring them.
 *
 * <p>Names no Entity Culling type, so it loads without the mod. The flags are read through
 * {@link EntityCullingFlags}, which is linked only once the mod has been found.
 */
public final class EntityCullingSupport {

    public static final String MOD_ID = "entityculling";
    /** Kill switch: {@code -Drtxremix.entity.entityCulling=false} captures as if Entity Culling were absent. */
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("rtxremix.entity.entityCulling", "true"));

    private EntityCullingSupport() {}

    /**
     * Whether the Entity Culling hooks apply: the mod is installed and the switch is on. The
     * mixin plugin asks before any mixin is applied, so this loads neither an Entity Culling
     * class nor a Minecraft one.
     */
    public static boolean hooksApply() {
        return ENABLED && FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** Whether the capture follows Entity Culling: the hooks apply and its flags can be read. */
    public static boolean active() {
        return Resolution.ACTIVE;
    }

    /**
     * Whether Entity Culling withholds this entity from vanilla's render: it culls entities
     * at all, no ray from the camera reaches this one, the second of grace it gives an
     * entity that has just come back into view is over, and the entity is one it culls.
     * Always false while the capture does not follow Entity Culling.
     */
    public static boolean isCulled(Entity entity) {
        return Resolution.ACTIVE && EntityCullingFlags.isCulled(entity);
    }

    /**
     * Asks Entity Culling to show a block entity it culled for the second of grace it gives
     * whatever comes back into view, so that vanilla extracts it again; see
     * {@link EntityCullingFlags#forceVisible}. Does nothing while the capture does not follow
     * Entity Culling.
     */
    public static void forceVisible(Object blockEntity) {
        if (Resolution.ACTIVE) {
            EntityCullingFlags.forceVisible(blockEntity);
        }
    }

    /**
     * Decides, at the first use in a frame, whether the capture follows Entity Culling, and
     * says in the log why not. Reading the flags resolves the Minecraft types in their
     * signatures, which must wait until the mixins have applied, so it is not decided when
     * the mixin plugin asks {@link #hooksApply()}.
     */
    private static final class Resolution {

        private static final Logger LOGGER = LoggerFactory.getLogger(EntityCullingSupport.class);
        static final boolean ACTIVE = resolve();

        private static boolean resolve() {
            String version = FabricLoader.getInstance().getModContainer(MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse(null);
            if (version == null) return false;
            if (!ENABLED) {
                LOGGER.info("Entity Culling {} found; Remix captures as if it were absent "
                        + "(rtxremix.entity.entityCulling=false)", version);
                return false;
            }
            if (!flagsReadable()) {
                LOGGER.warn("Entity Culling {} found, but without the culling flags this build reads; "
                        + "Remix captures as if it were absent", version);
                return false;
            }
            LOGGER.info("Entity Culling {} found; what it culls keeps its last pose in the Remix scene", version);
            return true;
        }

        /** An Entity Culling without the types the flags are read through fails to link the reader. */
        private static boolean flagsReadable() {
            try {
                return EntityCullingFlags.readable();
            } catch (LinkageError e) {
                return false;
            }
        }
    }
}
