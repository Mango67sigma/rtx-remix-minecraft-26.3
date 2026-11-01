/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity.ec;

import dev.tr7zw.entityculling.EntityCullingModBase;
import dev.tr7zw.entityculling.NMSCullingHelper;
import dev.tr7zw.entityculling.access.Cullable;
import dev.tr7zw.entityculling.versionless.Config;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reads the culling flags Entity Culling's {@code Cullable} mixin adds to every entity and
 * block entity, and asks of an entity the test Entity Culling asks before it withholds one.
 * The only class that names Entity Culling types: {@link EntityCullingSupport} reaches it
 * only after finding the mod, so without the mod nothing links against Entity Culling.
 */
final class EntityCullingFlags {

    private EntityCullingFlags() {}

    /**
     * Whether this Entity Culling declares everything read here, as 1.11.3 does, with the
     * types and static-ness the reads are compiled against: the {@code Cullable} flags and
     * grace, the mod instance and its entity-culling setting, and its test of whether
     * culling applies to an entity at all.
     */
    static boolean readable() {
        try {
            return declares(Cullable.class.getMethod("isCulled"), boolean.class, false)
                    && declares(Cullable.class.getMethod("isForcedVisible"), boolean.class, false)
                    && declares(Cullable.class.getMethod("setTimeout"), void.class, false)
                    && declares(EntityCullingModBase.class.getField("instance"), EntityCullingModBase.class, true)
                    && declares(EntityCullingModBase.class.getField("config"), Config.class, false)
                    && declares(Config.class.getField("skipEntityCulling"), boolean.class, false)
                    && declares(NMSCullingHelper.class.getMethod("ignoresCulling", Entity.class), boolean.class, true);
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            return false;
        }
    }

    private static boolean declares(Method method, Class<?> returnType, boolean isStatic) {
        return method.getReturnType() == returnType && Modifier.isStatic(method.getModifiers()) == isStatic;
    }

    private static boolean declares(Field field, Class<?> type, boolean isStatic) {
        return field.getType() == type && Modifier.isStatic(field.getModifiers()) == isStatic;
    }

    /**
     * Entity Culling's own test before it hands vanilla an empty state for an entity: it
     * culls entities at all, the entity is culled and past its grace, and culling applies to
     * it, which it does not for the player, an entity without a renderer, or one whose
     * renderer opts out, such as the ender dragon, a fishing hook, lightning or a display.
     * Entity Culling raises the flag from its own thread for every entity it traces, whether
     * or not it would withhold that entity, keeps one it has just un-culled visible for a
     * second whatever the flag says, and reads the flag as false while it is switched off.
     */
    static boolean isCulled(Entity entity) {
        EntityCullingModBase mod = EntityCullingModBase.instance;
        return mod != null
                && !mod.config.skipEntityCulling
                && entity instanceof Cullable cullable
                && cullable.isCulled()
                && !cullable.isForcedVisible()
                && !NMSCullingHelper.ignoresCulling(entity);
    }

    /**
     * Gives a block entity Entity Culling culled the second of grace it gives whatever it
     * has just un-culled, during which it lets vanilla extract the block entity again. A
     * block entity outside the view stays withheld however long its grace, and one Entity
     * Culling is not culling, or is already showing, needs none.
     */
    static void forceVisible(Object blockEntity) {
        if (blockEntity instanceof Cullable cullable && cullable.isCulled() && !cullable.isForcedVisible()) {
            cullable.setTimeout();
        }
    }
}
