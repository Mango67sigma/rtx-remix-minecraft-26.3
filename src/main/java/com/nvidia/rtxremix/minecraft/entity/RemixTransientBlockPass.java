/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.List;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures the placeholder block vanilla draws for a moment after a falling block lands.
 *
 * <p>When a falling block entity lands, the server sends a
 * {@code ClientboundAddTransientBlockPacket}, and for up to a second, until the chunk
 * update with the placed block arrives, {@code LevelRenderer.submitTransientBlocks} submits
 * the block as a moving block at its position. Nothing but that submit draws it in the
 * gap, so without this pass the block vanishes from the traced image between the falling
 * entity's removal and the terrain rebuild. {@code LevelRendererTransientBlockMixin} wraps
 * the submit and hands the state here through {@link RemixEntityPass#captureTransientBlock}.
 *
 * <p>The block is run through this pass's own {@link RemixSubmitNodeCollector}, whose
 * {@code submitMovingBlock} tesselates it as vanilla's moving-block feature renderer does,
 * on an identity stack, so the geometry is block-local and the instance root is a
 * translation by the block position: the same capture a falling block entity gets. The
 * render state is created afresh for every packet, and the submitter keys retained parts
 * by owner identity, so each position is lent one owner token for as long as vanilla keeps
 * submitting it ({@link RemixBlockDamagePass.PositionOwners}); a position not submitted in
 * a frame loses its token at the next {@link #beginFrame}, and the submitter's
 * {@code endFrame} retires its parts the frame it was missing. Render thread only; the
 * pass holds no native state of its own.
 */
public final class RemixTransientBlockPass {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixTransientBlockPass.class);
    private static final String TYPE_KEY = "minecraft:transient_block";

    private final RecyclingMatrixStack matrices = new RecyclingMatrixStack();
    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final RemixSubmitNodeCollector collector = new RemixSubmitNodeCollector(provider);
    private final RemixBlockDamagePass.PositionOwners owners = new RemixBlockDamagePass.PositionOwners();
    private final FailureLogThrottle failures = new FailureLogThrottle(LOGGER, "Transient block");
    private float[] reusableBoneData = new float[0];

    /**
     * Starts a frame: drops the tokens of positions the previous frame did not submit.
     * Called when the entity pass opens its frame.
     */
    void beginFrame() {
        owners.endFrame();
        failures.endFrame();
    }

    /** Captures one transient block and submits it inside the submitter's open frame. */
    void capture(MovingBlockRenderState state, RemixEntitySubmitter submitter) {
        BlockPos pos = state.blockPos;
        EntityRenderContext ctx = EntityRenderContext.get();
        provider.beginEntity();
        matrices.pushPose();
        ctx.begin(true);
        List<RemixVertexConsumerProvider.CapturedLayer> layers;
        try {
            collector.submitMovingBlock(matrices, state, 0);
            layers = provider.endEntity();
        } catch (Exception e) {
            failures.noteFailure("capturing", "transient block", pos, e);
            return;
        } finally {
            // Both restore even if tesselation threw, so the next capture starts at the
            // identity root and does not look nested.
            matrices.reset();
            ctx.reset();
        }
        if (layers.isEmpty()) {
            return;
        }

        reusableBoneData = ctx.snapshotBoneTransforms(reusableBoneData);
        RemixEntityPass.EntityCaptureResult result = new RemixEntityPass.EntityCaptureResult(
                owners.ownerFor(pos.asLong()),
                Vec3.atLowerCornerOf(pos),
                0.0f,
                layers,
                reusableBoneData,
                ctx.getBoneCount(),
                TYPE_KEY,
                false);
        try {
            submitter.submitEntity(result, RemixEntitySubmitter.NO_EXTRA_CATEGORIES);
        } catch (Exception e) {
            failures.noteFailure("submitting", "transient block", pos, e);
        }
    }

    /** Positions holding an owner token; for tests. */
    int ownerCount() {
        return owners.size();
    }
}
