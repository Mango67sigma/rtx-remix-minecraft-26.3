/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity.ec;

import dev.tr7zw.entityculling.access.Cullable;
import net.minecraft.world.entity.Entity;

/**
 * Reads the culling flags Entity Culling's {@code Cullable} mixin adds to every entity. The
 * only class that names an Entity Culling type: {@link EntityCullingSupport} reaches it only
 * after finding the mod, so without the mod nothing links against Entity Culling.
 */
final class EntityCullingFlags {

    private EntityCullingFlags() {}

    /** Whether this Entity Culling declares the two flags read here, as 1.11.3 does. */
    static boolean readable() {
        return declaresFlag("isCulled") && declaresFlag("isForcedVisible");
    }

    private static boolean declaresFlag(String name) {
        try {
            return Cullable.class.getMethod(name).getReturnType() == boolean.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Culled and past its grace. Entity Culling raises the flag from its own thread when no
     * ray reaches the entity, keeps an entity it has just un-culled visible for a second
     * whatever the flag says, and reads the flag as false while it is switched off.
     */
    static boolean isCulled(Entity entity) {
        return entity instanceof Cullable cullable && cullable.isCulled() && !cullable.isForcedVisible();
    }
}
