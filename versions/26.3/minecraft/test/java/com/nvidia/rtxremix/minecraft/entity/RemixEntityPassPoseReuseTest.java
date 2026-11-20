/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.entity.RemixEntityPass.PoseReuse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static com.nvidia.rtxremix.minecraft.entity.RemixEntityPass.CULLED_POSE_REFRESH_FRAMES;
import static com.nvidia.rtxremix.minecraft.entity.RemixEntityPass.POSE_REFRESH_FRAMES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixEntityPassPoseReuseTest {

    /** One entity's recorded pose, swept the way {@code RemixEntityPass.submitOffFrustum} sweeps it. */
    private static final class SweptEntity {
        final int id;
        PoseReuse recordedFor;
        long usedFrame;
        boolean recorded;

        SweptEntity(int id) {
            this.id = id;
        }

        /** Sweeps the entity on one frame; true for a full capture, false for a replay. */
        boolean capturesOn(long frame, PoseReuse reuse) {
            if (reuse != PoseReuse.NONE && recorded
                    && RemixEntityPass.replaysPose(reuse, recordedFor, usedFrame, id, frame)) {
                usedFrame = frame;
                return false;
            }
            if (reuse != PoseReuse.NONE) {
                recorded = true;
                recordedFor = reuse;
                usedFrame = frame;
            }
            return true;
        }
    }

    @ParameterizedTest
    @CsvSource({
            "false, 100.0, NONE",
            "false, 4096.0, NONE",
            "false, 4097.0, DISTANT",
            "true, 0.0, CULLED",
            "true, 100.0, CULLED",
            "true, 10000.0, CULLED"
    })
    void aPoseIsOpenToReuseBeyondTheReuseDistanceOrWhenCulled(boolean culled, double distSq, PoseReuse expected) {
        assertEquals(expected, RemixEntityPass.poseReuse(culled, distSq));
    }

    @Test
    void aCloseEntityEntityCullingLeftAloneIsNeverReplayed() {
        for (long frame = 1; frame <= 16; frame++) {
            assertFalse(RemixEntityPass.replaysPose(PoseReuse.NONE, PoseReuse.NONE, frame - 1, 7, frame));
        }
    }

    @Test
    void aReculledEntityIsCapturedBeforeItReplaysAgain() {
        // Culled at sweep frame 100, on screen and captured in place from 110 to 169 while
        // it turns around, culled again at 170: the pose from frame 100 must not come back.
        SweptEntity zombie = new SweptEntity(41);
        assertTrue(zombie.capturesOn(100, PoseReuse.CULLED), "the first culled frame records the pose");
        for (long frame = 101; frame < 110; frame++) {
            zombie.capturesOn(frame, PoseReuse.CULLED);
        }
        long beforeOnScreen = zombie.usedFrame;

        assertTrue(zombie.capturesOn(170, PoseReuse.CULLED),
                "a pose last used on frame " + beforeOnScreen + " was replayed on frame 170");
        assertEquals(170, zombie.usedFrame);
    }

    @Test
    void aPoseTheSweepMissedForAFrameIsNotReplayed() {
        long frame = 1001;
        assertTrue(RemixEntityPass.replaysPose(PoseReuse.CULLED, PoseReuse.CULLED, frame - 1, 7, frame));
        assertFalse(RemixEntityPass.replaysPose(PoseReuse.CULLED, PoseReuse.CULLED, frame - 2, 7, frame));
        assertFalse(RemixEntityPass.replaysPose(PoseReuse.DISTANT, PoseReuse.DISTANT, frame - 2, 6, frame));
    }

    @Test
    void aPoseRecordedForOneReuseIsNotReplayedForTheOther() {
        SweptEntity cow = new SweptEntity(13);
        cow.capturesOn(200, PoseReuse.DISTANT);
        assertTrue(cow.capturesOn(201, PoseReuse.CULLED), "a distant pose was replayed for a culled entity");
        for (long frame = 202; frame < 260; frame++) {
            cow.capturesOn(frame, PoseReuse.CULLED);
        }
        assertTrue(cow.capturesOn(260, PoseReuse.DISTANT), "a culled pose was replayed for a distant entity");
    }

    @Test
    void aCulledEntityIsCapturedOnceThenRefreshedOncePerCadence() {
        SweptEntity entity = new SweptEntity(41);
        int frames = 12 * CULLED_POSE_REFRESH_FRAMES;
        int captures = 0;
        long longestReplay = 0;
        long recordedFrame = 0;
        for (long frame = 1; frame <= frames; frame++) {
            if (entity.capturesOn(frame, PoseReuse.CULLED)) {
                captures++;
                recordedFrame = frame;
            } else {
                longestReplay = Math.max(longestReplay, frame - recordedFrame);
            }
        }

        assertTrue(captures <= frames / CULLED_POSE_REFRESH_FRAMES + 1,
                "a culled entity was captured " + captures + " times in " + frames + " frames");
        assertTrue(longestReplay < CULLED_POSE_REFRESH_FRAMES,
                "a pose was replayed " + longestReplay + " frames after it was recorded");
    }

    @Test
    void aDistantEntityKeepsItsShortCadence() {
        SweptEntity entity = new SweptEntity(3);
        long recordedFrame = 0;
        long longestReplay = 0;
        for (long frame = 1; frame <= 64; frame++) {
            if (entity.capturesOn(frame, PoseReuse.DISTANT)) {
                recordedFrame = frame;
            } else {
                longestReplay = Math.max(longestReplay, frame - recordedFrame);
            }
        }

        assertTrue(longestReplay < POSE_REFRESH_FRAMES,
                "a distant pose was replayed " + longestReplay + " frames after it was recorded");
    }

    @Test
    void culledEntitiesSpreadTheirRefreshesAcrossFrames() {
        long frame = 5000L;
        Set<Integer> dueNow = new HashSet<>();
        for (int id = 0; id < CULLED_POSE_REFRESH_FRAMES; id++) {
            if (!RemixEntityPass.replaysPose(PoseReuse.CULLED, PoseReuse.CULLED, frame - 1, id, frame)) {
                dueNow.add(id);
            }
        }

        assertEquals(1, dueNow.size(), "one in a cadence's worth of poses falls due on a given frame");
    }

    @ParameterizedTest
    @CsvSource({
            "true, true, true",
            "true, false, false",
            "false, true, false",
            "false, false, false"
    })
    void pastItsRangeTheSweepAdmitsOnlyACulledEntityVanillaWouldRender(
            boolean culled, boolean vanillaRenders, boolean expected) {
        assertEquals(expected, RemixEntityPass.sweepAdmitsPastRange(culled, vanillaRenders));
    }

    @Test
    void aCulledGhastPastTheSweepRangeStaysInTheScene() {
        double maxDistSq = 112.0 * 112.0;
        double ghastDistSq = 150.0 * 150.0;

        assertFalse(RemixEntityPass.sweepAdmits(ghastDistSq, maxDistSq, false));
        assertTrue(RemixEntityPass.sweepAdmitsPastRange(true, true),
                "vanilla would draw it, so Entity Culling's empty state must not drop it");
        assertFalse(RemixEntityPass.sweepAdmitsPastRange(false, true),
                "an entity Entity Culling left alone past the range stays out, as before");
    }
}
