// SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
// SPDX-License-Identifier: Apache-2.0

#version 330
#extension GL_ARB_separate_shader_objects : require

// Seeds Minecraft's depth buffer with the Remix primary depth so the weather
// draw that follows is occluded by path-traced geometry. Remix writes a
// conventional depth (near 0, far 1) for the camera's 0.05..1000 planes;
// Minecraft 26.x tests reverse-Z (clear 0, GREATER_THAN_OR_EQUAL) against a
// projection whose planes are swapped, and a swapped-plane projection yields
// exactly 1 - conventional depth, so the complement is the matching value. The
// interop image holds its top row first, hence the vertical flip.

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

void main() {
    float remixDepth = texture(InSampler, vec2(texCoord.x, 1.0 - texCoord.y)).r;
    gl_FragDepth = 1.0 - remixDepth;
}
