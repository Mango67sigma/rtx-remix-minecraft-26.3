/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.Entity;

/**
 * The entity behind a vanilla {@code EntityRenderState}, attached by
 * {@code EntityRenderStateMixin} and written by {@code EntityRenderDispatcherMixin}
 * as the state is extracted.
 *
 * <p>Vanilla's render state deliberately forgets the entity, but the capture is
 * built on it: retained instances are keyed by the entity's identity, the frame's
 * captured-id set and the light-owner test are keyed by its id, and the first-person
 * local player is recognised by comparing it with the client's player.
 */
public interface RemixEntityRenderStateAccess {

    /** The entity this state was extracted from, or null for a state built by other means. */
    Entity rtxremix$entity();

    void rtxremix$setEntity(Entity entity);
}
