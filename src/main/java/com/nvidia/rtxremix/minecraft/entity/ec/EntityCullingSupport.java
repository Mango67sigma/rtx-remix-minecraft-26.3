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

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityCullingSupport.class);

    public static final String MOD_ID = "entityculling";
    /** Kill switch: {@code -Drtxremix.entity.entityCulling=false} captures as if Entity Culling were absent. */
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("rtxremix.entity.entityCulling", "true"));
    private static final boolean ACTIVE = resolve();

    private EntityCullingSupport() {}

    /** Whether the capture follows Entity Culling. */
    public static boolean active() {
        return ACTIVE;
    }

    /**
     * Whether Entity Culling has culled this entity: no ray from the camera reaches it, and
     * the second of grace it gives an entity that has just come back into view is over.
     * Always false while the capture does not follow Entity Culling.
     */
    public static boolean isCulled(Entity entity) {
        return ACTIVE && EntityCullingFlags.isCulled(entity);
    }

    /** Decides once whether the capture follows Entity Culling, and says in the log why not. */
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

    /** An Entity Culling without the interface the flags live on fails to link the reader. */
    private static boolean flagsReadable() {
        try {
            return EntityCullingFlags.readable();
        } catch (LinkageError e) {
            return false;
        }
    }
}
