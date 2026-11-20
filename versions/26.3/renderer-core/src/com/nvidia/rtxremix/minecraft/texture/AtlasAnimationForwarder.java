/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * Collects animated sprite upload patches each tick and flushes them to Remix
 * in a single batched call before present.
 *
 * <p>LabPBR-aware: sprites that have {@code _n} / {@code _s} siblings are
 * re-processed on every animation frame so AO (from {@code _n.b}) and the
 * animated LabPBR material channels in {@code _s} stay in phase with albedo.
 * Without this, later albedo frames would overwrite the AO-modulated atlas
 * while roughness, metallic, SSS, and emissive atlases stayed stuck on the
 * init-time sidecar frame. Non-LabPBR sprites are copied as albedo only.
 *
 * <p>All patches for one frame across every animated atlas texture go out in
 * one {@code remixapi_UpdateTextureFromMemory} call using the multi-handle
 * batch API. This amortizes the JNI crossing and, on the dxvk side, lets mip
 * regeneration be scoped to the dirty rect per atlas instead of regenerating
 * the whole atlas once per atlas.
 */
public final class AtlasAnimationForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtlasAnimationForwarder.class);

    // Gate on patch queueing: read by addPatch and toggled by
    // init/clear/pause/resume, all of which run on the render thread. Each
    // toggle is sequenced against the state it guards at its own call site.
    private static volatile boolean active = false;

    private static Thread expectedThread;
    private static final List<PatchRecord> pendingPatches = new ArrayList<>();
    private static final Map<Identifier, AnimationPlayback> animationStates = new HashMap<>();
    private static Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBRMap;
    private static Set<Identifier> blockAtlasSpriteIds;
    private static final ThreadLocal<Identifier> currentAtlasId = new ThreadLocal<>();

    // Reusable scratch between CPU-side patch composition and the final
    // DirectBuffer copy. Pre-sized for a typical 64x64 RGBA sprite so the
    // first frame of every animation lands without a realloc; grown
    // monotonically beyond that and never shrunk, so steady-state allocates
    // no garbage.
    private static final int kInitialScratchBytes = 64 * 64 * 4;
    private static byte[] scratch = new byte[kInitialScratchBytes];
    // The content patch in scratch widened by the sprite's padding ring; grown the
    // same way. A 64x64 sprite with a 16-texel ring is 96x96.
    private static byte[] paddedScratch = new byte[96 * 96 * 4];

    private static final class PatchRecord {
        final Identifier id;
        final int dstX;
        final int dstY;
        final int srcX;
        final int srcY;
        final int width;
        final int height;
        final int srcImageWidth;
        final int srcImageHeight;
        /**
         * Raw pointer into the owning sprite sheet's mip-0 memory, or into the
         * blend buffer of the sprite's AnimationState for an interpolated frame.
         * Read-only and valid only until flush() is called on the same
         * render-thread frame that produced this record:
         *   1. The owning SpriteContents and AnimationState must not be closed (atlas rebuild
         *      or world unload invalidates).
         *   2. The block atlas must not be re-stitched between capture
         *      and flush. RemixRenderer clears the forwarder on
         *      pendingTextureReload before rebuild to enforce this.
         *   3. Capture and flush must both run on the render thread
         *      (thread check in addPatch). Moving flush off the render
         *      thread breaks this contract and requires copying bytes
         *      at capture time.
         */
        final long pixelPtr;
        final int rowPitch;
        /**
         * The sprite's stitcher padding. {@code dstX/dstY} and {@code width/height} are
         * the content rectangle; the patch sent to Remix covers the content plus this
         * ring of replicated edge texels, which is what vanilla's draw leaves in the slot.
         */
        final int padding;

        PatchRecord(Identifier id, int dstX, int dstY, int srcX, int srcY,
                    int width, int height, int srcImageWidth, int srcImageHeight,
                    long pixelPtr, int rowPitch, int padding) {
            this.id = id;
            this.dstX = dstX;
            this.dstY = dstY;
            this.srcX = srcX;
            this.srcY = srcY;
            this.width = width;
            this.height = height;
            this.srcImageWidth = srcImageWidth;
            this.srcImageHeight = srcImageHeight;
            this.pixelPtr = pixelPtr;
            this.rowPitch = rowPitch;
            this.padding = padding;
        }

        boolean isInterpolatedUpload() {
            return srcX == 0 && srcY == 0 &&
                   srcImageWidth == width && srcImageHeight == height;
        }
    }

    private static final class FrameOrigin {
        final int x;
        final int y;

        FrameOrigin(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class FrameSampling {
        final FrameOrigin current;
        final FrameOrigin next;
        final float nextBlend;

        FrameSampling(FrameOrigin current, FrameOrigin next, float nextBlend) {
            this.current = current;
            this.next = next;
            this.nextBlend = nextBlend;
        }
    }

    private static final class AnimationPlayback {
        int sequenceIndex;
        int interpolationTick;
        boolean initialized;
    }

    public static void init(
            Map<String, BlockTextureManager.AnimatedSpriteLabPBR> labPBR,
            Set<Identifier> blockAtlasSprites) {
        labPBRMap = labPBR;
        blockAtlasSpriteIds = Set.copyOf(blockAtlasSprites);
        pendingPatches.clear();
        animationStates.clear();
        expectedThread = null;
        // Flip active on LAST so any addPatch probe that races initialization
        // sees a fully constructed forwarder (or no forwarder at all).
        active = true;
        LOGGER.info("AtlasAnimationForwarder initialized ({} LabPBR sprites, {} atlas sprites)",
                    labPBR.size(), blockAtlasSpriteIds.size());
    }

    /**
     * @param dstX    atlas column of the sprite's first content texel
     * @param dstY    atlas row of the sprite's first content texel
     * @param padding the sprite's stitcher padding; the flushed patch also rewrites this
     *                ring around the content with replicated edges (0 for none)
     */
    public static void addPatch(Identifier spriteId, int dstX, int dstY,
                                int srcX, int srcY, int width, int height,
                                int srcImageWidth, int srcImageHeight,
                                long pixelPtr, int rowPitch, int padding) {
        // Drop patches when the forwarder is not active (SDK missing, RTX
        // disabled, or post-shutdown/reload). Prevents an unbounded queue
        // when the animation tick hook still fires but we'll never flush.
        if (!active) return;

        if (!BlockTextureManager.BLOCK_ATLAS_ID.equals(currentAtlasId.get())) {
            return;
        }

        Set<Identifier> allowedSprites = blockAtlasSpriteIds;
        if (allowedSprites == null || !allowedSprites.contains(spriteId)) {
            return;
        }

        Thread current = Thread.currentThread();
        if (expectedThread == null) {
            expectedThread = current;
        } else if (current != expectedThread) {
            // Java assertions are stripped without -ea, so log + drop instead:
            // a stray cross-thread call would race the render-thread flush
            // and produce torn batches. Drop the patch and surface the
            // violation in production logs.
            LOGGER.error("addPatch called from {} but expected {}; dropping patch",
                         current.getName(), expectedThread.getName());
            return;
        }

        // The ring starts at the slot corner, dst - padding, which is never negative.
        int ring = Math.max(0, Math.min(padding, Math.min(dstX, dstY)));
        pendingPatches.add(new PatchRecord(spriteId, dstX, dstY, srcX, srcY,
                                           width, height, srcImageWidth, srcImageHeight,
                                           pixelPtr, rowPitch, ring));
    }

    /**
     * Whether {@link #addPatch} would queue a patch for {@code spriteId} now, so a caller
     * can skip building one. Read-only; the thread check stays in {@link #addPatch}.
     */
    public static boolean acceptsPatch(Identifier spriteId) {
        if (!active || !BlockTextureManager.BLOCK_ATLAS_ID.equals(currentAtlasId.get())) {
            return false;
        }
        Set<Identifier> allowedSprites = blockAtlasSpriteIds;
        return allowedSprites != null && allowedSprites.contains(spriteId);
    }

    /**
     * Queues frame {@code frameIndex} of a flipbook sheet, read in place: what 1.21.1's
     * {@code SpriteContents.AnimatedTexture.uploadFrame} handed to {@code upload}.
     *
     * @param padding      the sprite's stitcher padding, refreshed with the frame's edges
     * @param sheet        the sprite's full RGBA sheet, frame 0 at its origin
     * @param frameRowSize frames per sheet row
     */
    public static void addFramePatch(Identifier spriteId, int dstX, int dstY, int width, int height,
                                     int padding, NativeImage sheet, int frameRowSize, int frameIndex) {
        if (sheet == null || sheet.isClosed() || sheet.format() != NativeImage.Format.RGBA || frameRowSize <= 0) {
            return;
        }
        int srcX = (frameIndex % frameRowSize) * width;
        int srcY = (frameIndex / frameRowSize) * height;
        int sheetWidth = sheet.getWidth();
        int sheetHeight = sheet.getHeight();
        if (srcX + width > sheetWidth || srcY + height > sheetHeight) {
            return;
        }
        long pixelPtr = sheet.getPointer() + ((long) srcY * sheetWidth + srcX) * 4;
        addPatch(spriteId, dstX, dstY, srcX, srcY, width, height,
                 sheetWidth, sheetHeight, pixelPtr, sheetWidth * 4, padding);
    }

    /**
     * Blends two frames of a flipbook sheet into {@code blendTarget} and queues the result,
     * as 1.21.1's {@code SpriteContents.InterpolationData.uploadInterpolatedFrame} did. The
     * patch is sprite-sized with its source at the origin, which is how
     * {@link PatchRecord#isInterpolatedUpload} recognises it.
     *
     * @param padding       the sprite's stitcher padding, refreshed with the blend's edges
     * @param currentWeight weight of the current frame: {@code 1 - subFrame / frameTime}
     * @param blendTarget   {@code width * height * 4} writable bytes, alive until {@link #flush}
     */
    public static void addBlendedPatch(Identifier spriteId, int dstX, int dstY, int width, int height,
                                       int padding, NativeImage sheet, int frameRowSize, int currentIndex, int nextIndex,
                                       double currentWeight, long blendTarget) {
        if (sheet == null || sheet.isClosed() || sheet.format() != NativeImage.Format.RGBA
                || frameRowSize <= 0 || blendTarget == 0L) {
            return;
        }
        int sheetWidth = sheet.getWidth();
        int sheetHeight = sheet.getHeight();
        int currentX = (currentIndex % frameRowSize) * width;
        int currentY = (currentIndex / frameRowSize) * height;
        int nextX = (nextIndex % frameRowSize) * width;
        int nextY = (nextIndex / frameRowSize) * height;
        if (currentX + width > sheetWidth || currentY + height > sheetHeight
                || nextX + width > sheetWidth || nextY + height > sheetHeight) {
            return;
        }
        blendFrames(sheet.getPointer(), sheetWidth, currentX, currentY, nextX, nextY,
                    width, height, currentWeight, blendTarget);
        addPatch(spriteId, dstX, dstY, 0, 0, width, height, width, height, blendTarget, width * 4, padding);
    }

    /**
     * 1.21.1's interpolation, channel by channel: red, green and blue are
     * {@code (int) (w * current + (1 - w) * next)} and alpha is the current frame's.
     */
    static void blendFrames(long sheet, int sheetWidth, int currentX, int currentY, int nextX, int nextY,
                            int width, int height, double currentWeight, long dst) {
        double nextWeight = 1.0 - currentWeight;
        for (int y = 0; y < height; y++) {
            long current = sheet + ((long) (currentY + y) * sheetWidth + currentX) * 4;
            long next = sheet + ((long) (nextY + y) * sheetWidth + nextX) * 4;
            long out = dst + (long) y * width * 4;
            for (int x = 0; x < width; x++, current += 4, next += 4, out += 4) {
                for (int c = 0; c < 3; c++) {
                    int a = MemoryUtil.memGetByte(current + c) & 0xFF;
                    int b = MemoryUtil.memGetByte(next + c) & 0xFF;
                    MemoryUtil.memPutByte(out + c, (byte) (int) (currentWeight * a + nextWeight * b));
                }
                MemoryUtil.memPutByte(out + 3, MemoryUtil.memGetByte(current + 3));
            }
        }
    }

    private static FrameSampling resolveLabPBRFrameSampling(
            PatchRecord p, BlockTextureManager.AnimatedSpriteLabPBR pbr) {
        if (pbr == null || pbr.frameIndices.length == 0) {
            FrameOrigin origin = new FrameOrigin(p.srcX, p.srcY);
            return new FrameSampling(origin, origin, 0.0f);
        }

        AnimationPlayback playback = animationStates.computeIfAbsent(p.id, ignored -> new AnimationPlayback());

        if (p.isInterpolatedUpload()) {
            if (!playback.initialized) {
                playback.sequenceIndex = 0;
                playback.interpolationTick = 0;
                playback.initialized = true;
            }

            int currentSequenceIndex = playback.sequenceIndex;
            int nextSequenceIndex = (currentSequenceIndex + 1) % pbr.frameIndices.length;
            int frameTime = Math.max(1, pbr.frameTimes[currentSequenceIndex]);
            int interpolationTick = Math.min(frameTime - 1, playback.interpolationTick + 1);
            playback.interpolationTick = interpolationTick;

            FrameOrigin current = frameOriginFromFrameIndex(
                pbr.frameIndices[currentSequenceIndex], pbr.frameColumns, p.width, p.height);
            FrameOrigin next = frameOriginFromFrameIndex(
                pbr.frameIndices[nextSequenceIndex], pbr.frameColumns, p.width, p.height);
            float nextBlend = frameTime > 1 ? (float) interpolationTick / (float) frameTime : 0.0f;
            return new FrameSampling(current, next, nextBlend);
        }

        int frameIndex = frameIndexFromOrigin(p);
        int expectedNext = playback.initialized
            ? (playback.sequenceIndex + 1) % pbr.frameIndices.length
            : -1;
        playback.sequenceIndex = findSequenceIndex(pbr.frameIndices, frameIndex, expectedNext);
        playback.interpolationTick = 0;
        playback.initialized = true;

        FrameOrigin origin = new FrameOrigin(p.srcX, p.srcY);
        return new FrameSampling(origin, origin, 0.0f);
    }

    private static int frameIndexFromOrigin(PatchRecord p) {
        int columns = Math.max(1, p.srcImageWidth / Math.max(1, p.width));
        return (p.srcY / Math.max(1, p.height)) * columns + (p.srcX / Math.max(1, p.width));
    }

    private static FrameOrigin frameOriginFromFrameIndex(int frameIndex, int frameColumns, int width, int height) {
        int columns = Math.max(1, frameColumns);
        int x = (frameIndex % columns) * width;
        int y = (frameIndex / columns) * height;
        return new FrameOrigin(x, y);
    }

    private static int findSequenceIndex(int[] frameIndices, int frameIndex, int expectedNext) {
        if (expectedNext >= 0 && expectedNext < frameIndices.length && frameIndices[expectedNext] == frameIndex) {
            return expectedNext;
        }

        for (int i = 0; i < frameIndices.length; i++) {
            if (frameIndices[i] == frameIndex) {
                return i;
            }
        }

        return 0;
    }

    /**
     * Builds one multi-handle batch covering animated albedo and LabPBR atlas
     * sidecars, then issues a single {@code remixapi_UpdateTextureFromMemory}
     * call. LabPBR data is applied per patch using pre-computed tables cached
     * at init.
     *
     * @param api            the Remix API handle
     * @param albedoHandle   handle of the albedo atlas texture (required)
     * @param emissiveHandle handle of the emissive atlas, or 0 if none exists
     * @param roughnessHandle handle of the roughness atlas, or 0 if none exists
     * @param metallicHandle handle of the metallic atlas, or 0 if none exists
     * @param sssHandle      handle of the SSS atlas, or 0 if none exists
     */
    public static void flush(RemixAPI api, long albedoHandle, long emissiveHandle,
                             long roughnessHandle, long metallicHandle, long sssHandle) {
        int count = pendingPatches.size();
        if (count == 0 || albedoHandle == 0) {
            return;
        }

        List<ByteBuffer> ownedBuffers = new ArrayList<>(count * 2);

        try {
            TexturePatch[] albedoPatches = new TexturePatch[count];
            List<TexturePatch> roughnessPatches = new ArrayList<>();
            List<TexturePatch> metallicPatches = new ArrayList<>();
            List<TexturePatch> emissivePatches = new ArrayList<>();
            List<TexturePatch> sssPatches = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                PatchRecord p = pendingPatches.get(i);
                BlockTextureManager.AnimatedSpriteLabPBR pbr =
                    (labPBRMap != null) ? labPBRMap.get(p.id.toString()) : null;

                final int patchBytes = p.width * p.height * 4;
                final int patchRowBytes = p.width * 4;
                ensureScratch(patchBytes);
                ByteBuffer srcBuf = MemoryUtil.memByteBuffer(p.pixelPtr, p.rowPitch * p.height);
                FrameSampling labPbrSampling = resolveLabPBRFrameSampling(p, pbr);

                // --- Albedo patch ---
                if (pbr != null && pbr.aoTable != null) {
                    LabPBRConverter.applyAOToPatch(
                        scratch, patchRowBytes,
                        srcBuf, p.rowPitch,
                        pbr.aoTable, pbr.aoW, pbr.aoH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        p.width, p.height);
                } else {
                    LabPBRConverter.copyAlbedoPatch(
                        scratch, patchRowBytes,
                        srcBuf, p.rowPitch,
                        p.width, p.height);
                }
                ByteBuffer albedoDst = stagePatch(p, 4);
                ownedBuffers.add(albedoDst);

                albedoPatches[i] = createPatch(p, albedoDst, 4, TextureFormat.RGBA8_UNORM);

                // --- _s RGB material channels ---
                // LabPBR packs roughness/metallic/SSS into _s.rgb. These
                // atlases are separate Remix textures, so they need matching
                // animation patches just like _s.alpha emissive.
                if (pbr != null && pbr.sRoughnessTable != null && roughnessHandle != 0) {
                    int patchR8Bytes = p.width * p.height;
                    ensureScratch(patchR8Bytes);
                    LabPBRConverter.buildR8Patch(
                        scratch, p.width,
                        pbr.sRoughnessTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height);
                    ByteBuffer roughnessDst = stagePatch(p, 1);
                    ownedBuffers.add(roughnessDst);
                    roughnessPatches.add(createPatch(p, roughnessDst, 1, TextureFormat.R8_UNORM));
                }

                if (pbr != null && pbr.sMetallicTable != null && metallicHandle != 0) {
                    int patchR8Bytes = p.width * p.height;
                    ensureScratch(patchR8Bytes);
                    LabPBRConverter.buildR8Patch(
                        scratch, p.width,
                        pbr.sMetallicTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height);
                    ByteBuffer metallicDst = stagePatch(p, 1);
                    ownedBuffers.add(metallicDst);
                    metallicPatches.add(createPatch(p, metallicDst, 1, TextureFormat.R8_UNORM));
                }

                if (pbr != null && pbr.hasSss && pbr.sSssTable != null && sssHandle != 0) {
                    LabPBRConverter.buildSssPatch(
                        scratch, patchRowBytes,
                        pbr.sSssTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height);
                    ByteBuffer sssDst = stagePatch(p, 4);
                    ownedBuffers.add(sssDst);
                    sssPatches.add(createPatch(p, sssDst, 4, TextureFormat.RGBA8_UNORM));
                }

                // --- Emissive patch (authored _s alpha, else a built-in mask) ---
                if (emissiveHandle == 0) continue;
                final boolean hasLabPBREmissive = pbr != null && pbr.hasPerPixelEmissive;
                BuiltinEmission.Mask builtinMask = hasLabPBREmissive ? null : BuiltinEmission.mask(p.id);

                // Skip sprites with neither. Vanilla block luminance affects
                // cast light, not surface emission.
                if (!hasLabPBREmissive && builtinMask == null) continue;

                if (hasLabPBREmissive) {
                    LabPBRConverter.buildEmissivePatch(
                        scratch, patchRowBytes,
                        srcBuf, p.rowPitch,
                        pbr.sAlphaTable, pbr.sW, pbr.sH,
                        labPbrSampling.current.x, labPbrSampling.current.y,
                        labPbrSampling.next.x, labPbrSampling.next.y,
                        labPbrSampling.nextBlend,
                        p.width, p.height,
                        0f);
                } else {
                    // The mask is over the sprite's own UV space, which every animation frame shares.
                    LabPBRConverter.buildEmissiveMaskPatch(
                        scratch, patchRowBytes,
                        srcBuf, p.rowPitch,
                        p.width, p.height, builtinMask);
                }

                ByteBuffer emissiveDst = stagePatch(p, 4);
                ownedBuffers.add(emissiveDst);

                emissivePatches.add(createPatch(p, emissiveDst, 4, TextureFormat.RGBA8_UNORM));
            }

            // One batch per frame: the runtime handles each entry's texture
            // independently, so all five channels travel in a single call.
            List<TextureBatchEntry> entries = new ArrayList<>(5);
            addBatchEntry(entries, albedoHandle, albedoPatches);
            addBatchEntry(entries, roughnessHandle, roughnessPatches);
            addBatchEntry(entries, metallicHandle, metallicPatches);
            addBatchEntry(entries, emissiveHandle, emissivePatches);
            addBatchEntry(entries, sssHandle, sssPatches);

            TexturePatchInfo info = new TexturePatchInfo();
            info.entries = entries.toArray(new TextureBatchEntry[0]);
            api.updateTextureFromMemory(info);

        } catch (Exception e) {
            LOGGER.error("Error flushing animation patches", e);
        } finally {
            for (ByteBuffer buffer : ownedBuffers) {
                DirectBufferPool.release(buffer);
            }
            pendingPatches.clear();
        }
    }

    private static void ensureScratch(int byteCount) {
        if (scratch.length < byteCount) {
            scratch = new byte[byteCount];
        }
    }

    /**
     * Copies the content patch just composed in {@link #scratch} ({@code width x height}
     * texels, tightly packed) into a pooled direct buffer. With a padding ring the buffer
     * holds the padded rectangle, the content at ({@code padding}, {@code padding}) and the
     * ring filled with its replicated edges, so an animated sprite's ring follows its frame
     * the way vanilla's draw over the whole slot does.
     */
    private static ByteBuffer stagePatch(PatchRecord p, int bytesPerTexel) {
        byte[] source = scratch;
        int bytes = p.width * p.height * bytesPerTexel;
        if (p.padding > 0) {
            int paddedW = p.width + 2 * p.padding;
            int paddedH = p.height + 2 * p.padding;
            bytes = paddedW * paddedH * bytesPerTexel;
            if (paddedScratch.length < bytes) {
                paddedScratch = new byte[bytes];
            }
            padPatch(scratch, p.width, p.height, bytesPerTexel, p.padding, paddedScratch);
            source = paddedScratch;
        }
        ByteBuffer dst = DirectBufferPool.acquire(bytes);
        dst.put(source, 0, bytes);
        dst.flip();
        return dst;
    }

    /**
     * Writes a tightly packed {@code w x h} patch into {@code out} as a
     * {@code (w + 2 * padding) x (h + 2 * padding)} patch whose ring replicates the edges
     * ({@link LabPBRConverter#fillPaddingRing}). {@code out} must hold the padded patch.
     */
    static void padPatch(byte[] content, int w, int h, int bytesPerTexel, int padding, byte[] out) {
        int paddedW = w + 2 * padding;
        int paddedH = h + 2 * padding;
        int rowBytes = w * bytesPerTexel;
        int paddedRowBytes = paddedW * bytesPerTexel;
        for (int y = 0; y < h; y++) {
            System.arraycopy(content, y * rowBytes, out,
                    (y + padding) * paddedRowBytes + padding * bytesPerTexel, rowBytes);
        }
        LabPBRConverter.fillPaddingRing(out, paddedW, paddedH, bytesPerTexel,
                padding, padding, w, h, padding);
    }

    /** The Remix patch for {@code p}'s content plus its padding ring, from the slot corner. */
    static TexturePatch createPatch(PatchRecord p, ByteBuffer data, int bytesPerTexel, int format) {
        TexturePatch patch = new TexturePatch();
        patch.dstX = p.dstX - p.padding;
        patch.dstY = p.dstY - p.padding;
        patch.width = p.width + 2 * p.padding;
        patch.height = p.height + 2 * p.padding;
        patch.pixelData = data;
        patch.rowPitch = patch.width * bytesPerTexel;
        patch.format = format;
        return patch;
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, TexturePatch[] patches) {
        if (handle == 0 || patches.length == 0) {
            return;
        }
        TextureBatchEntry entry = new TextureBatchEntry();
        entry.handle = handle;
        entry.patches = patches;
        entries.add(entry);
    }

    private static void addBatchEntry(List<TextureBatchEntry> entries, long handle, List<TexturePatch> patches) {
        if (handle == 0 || patches.isEmpty()) {
            return;
        }
        addBatchEntry(entries, handle, patches.toArray(new TexturePatch[0]));
    }

    public static void clear() {
        // Flip active OFF first so a concurrent addPatch drops its record
        // rather than inserting into a list we're about to wipe.
        active = false;
        pendingPatches.clear();
        labPBRMap = null;
        blockAtlasSpriteIds = null;
        animationStates.clear();
        expectedThread = null;
        currentAtlasId.remove();
        LOGGER.info("AtlasAnimationForwarder cleared");
    }

    public static void beginAtlasUpload(Identifier atlasId) {
        currentAtlasId.set(atlasId);
    }

    public static void endAtlasUpload() {
        currentAtlasId.remove();
    }

    /**
     * Deactivates queueing and drops any pending records without wiping
     * {@link #labPBRMap} / {@link #blockAtlasSpriteIds}.
     * Use for reversible pauses like the RTX-toggle path where
     * {@link #resume()} should be a cheap flip without a resource-pack reload.
     */
    public static void pause() {
        active = false;
        pendingPatches.clear();
        animationStates.clear();
    }

    public static void resume() {
        active = blockAtlasSpriteIds != null;
    }

    private AtlasAnimationForwarder() {}
}
