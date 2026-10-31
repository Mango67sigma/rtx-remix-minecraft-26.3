/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

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

    private static final double NEAR_SQ = 10.0 * 10.0;
    private static final double REUSE_EDGE_SQ = 64.0 * 64.0;
    private static final double FAR_SQ = 100.0 * 100.0;

    @ParameterizedTest
    @CsvSource({
            "false, 100.0, false",
            "false, 4096.0, false",
            "false, 4097.0, true",
            "true, 0.0, true",
            "true, 100.0, true",
            "true, 10000.0, true"
    })
    void aPoseIsReusedBeyondTheReuseDistanceOrWhenCulled(boolean culled, double distSq, boolean expected) {
        assertEquals(expected, RemixEntityPass.reusesPose(culled, distSq));
    }

    @Test
    void aCulledEntityNextToTheCameraStillReusesItsPose() {
        assertFalse(RemixEntityPass.reusesPose(false, NEAR_SQ));
        assertTrue(RemixEntityPass.reusesPose(true, NEAR_SQ));
        assertFalse(RemixEntityPass.reusesPose(false, REUSE_EDGE_SQ));
        assertTrue(RemixEntityPass.reusesPose(false, FAR_SQ));
    }

    @Test
    void aCulledPoseReplaysUntilItsLongCadenceRunsOut() {
        int id = 7;
        // A sweep frame on which neither id's stagger falls due.
        long frame = 1000L;
        assertFalse(Math.floorMod(id + frame, CULLED_POSE_REFRESH_FRAMES) == 0);

        assertFalse(RemixEntityPass.poseRefreshDue(true, 1, id, frame));
        assertFalse(RemixEntityPass.poseRefreshDue(true, CULLED_POSE_REFRESH_FRAMES - 1, id, frame));
        assertTrue(RemixEntityPass.poseRefreshDue(true, CULLED_POSE_REFRESH_FRAMES, id, frame));
        // The same age would long since have refreshed an entity Entity Culling left alone.
        assertTrue(RemixEntityPass.poseRefreshDue(false, POSE_REFRESH_FRAMES, id, frame));
    }

    @Test
    void aPoseOlderThanTheCadenceIsRefreshedAtOnce() {
        // A pose recorded before the entity was captured in place for a while: the age
        // test, not the stagger, keeps it from being replayed. Neither frame is a stagger
        // frame for the id.
        assertFalse(Math.floorMod(7 + 1000L, CULLED_POSE_REFRESH_FRAMES) == 0);
        assertFalse(Math.floorMod(7 + 1002L, POSE_REFRESH_FRAMES) == 0);
        assertTrue(RemixEntityPass.poseRefreshDue(true, 10 * CULLED_POSE_REFRESH_FRAMES, 7, 1000L));
        assertTrue(RemixEntityPass.poseRefreshDue(false, 10 * POSE_REFRESH_FRAMES, 7, 1002L));
    }

    @Test
    void aCulledEntityIsCapturedOnceThenRefreshedNoMoreThanOncePerCadence() {
        int id = 41;
        int frames = 12 * CULLED_POSE_REFRESH_FRAMES;
        int captures = 0;
        long recordedFrame = Long.MIN_VALUE;
        long longestReplay = 0;
        for (long frame = 1; frame <= frames; frame++) {
            boolean hasPose = recordedFrame != Long.MIN_VALUE;
            if (!hasPose || RemixEntityPass.poseRefreshDue(true, frame - recordedFrame, id, frame)) {
                // The first culled frame finds no pose, so it is a full capture that records one.
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
    void culledEntitiesSpreadTheirRefreshesAcrossFrames() {
        long frame = 5000L;
        Set<Integer> dueNow = new HashSet<>();
        for (int id = 0; id < CULLED_POSE_REFRESH_FRAMES; id++) {
            if (RemixEntityPass.poseRefreshDue(true, 1, id, frame)) {
                dueNow.add(id);
            }
        }

        assertEquals(1, dueNow.size(), "one in a cadence's worth of fresh poses falls due on a given frame");
    }

    @Test
    void anUnculledPoseKeepsItsShortCadence() {
        int id = 3;
        for (long frame = 1; frame <= 64; frame++) {
            for (long age = 0; age < 2 * POSE_REFRESH_FRAMES; age++) {
                boolean expected = age >= POSE_REFRESH_FRAMES
                        || Math.floorMod(id + frame, POSE_REFRESH_FRAMES) == 0;
                assertEquals(expected, RemixEntityPass.poseRefreshDue(false, age, id, frame),
                        "age " + age + " at frame " + frame);
            }
        }
    }
}
