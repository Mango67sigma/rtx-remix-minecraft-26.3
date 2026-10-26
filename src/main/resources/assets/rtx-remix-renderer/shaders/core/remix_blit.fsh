// SPDX-FileCopyrightText: Copyright (c) 2026 the rtx-remix-minecraft-26.3 contributors.
// SPDX-License-Identifier: Apache-2.0

#version 330
#extension GL_ARB_separate_shader_objects : require

// Composites the Remix output image over Minecraft's main render target as an
// opaque full-screen triangle (vertex stage: minecraft:core/screenquad). The
// interop image holds its top row first, so the GL-convention screen quad
// samples it with the vertical axis flipped.

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(texture(InSampler, vec2(texCoord.x, 1.0 - texCoord.y)).rgb, 1.0);
}
