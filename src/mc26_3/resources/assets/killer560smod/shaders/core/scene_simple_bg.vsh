#version 330
#extension GL_ARB_separate_shader_objects : require

// The Simple main menu scenes' backdrop (killer560smod mainmenu/SimpleScenes): one full-screen quad. UV0 is the fragment's
// position in screen pixels; Color.r is the parameter slot. The parameters are read here, once a vertex, from the data
// texture (rows 64+, one float per RGBA8 texel, bit-exact) into flat varyings (52 components, under GL 3.3's 60).

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 pix;
layout(location = 1) flat out vec2 screen;
layout(location = 2) flat out vec3 baseTop;
layout(location = 3) flat out vec3 baseBot;
layout(location = 4) flat out vec4 wash0;
layout(location = 5) flat out vec2 wash0Y;
layout(location = 6) flat out vec4 wash1;
layout(location = 7) flat out vec2 wash1Y;
layout(location = 8) flat out vec4 glow0;
layout(location = 9) flat out vec4 glow0G;
layout(location = 10) flat out vec4 glow1;
layout(location = 11) flat out vec4 glow1G;
layout(location = 12) flat out vec4 glow2;
layout(location = 13) flat out vec4 glow2G;
layout(location = 14) flat out vec4 vig;
layout(location = 15) flat out vec2 vigSide;

int slotRow;

float P(int i) {
    uvec4 b = uvec4(texelFetch(Sampler0, ivec2(i & 63, slotRow + (i >> 6)), 0) * 255.0 + 0.5);
    return uintBitsToFloat(b.r | (b.g << 8u) | (b.b << 16u) | (b.a << 24u));
}

vec3 P3(int i) {
    return vec3(P(i), P(i + 1), P(i + 2));
}

vec4 P4(int i) {
    return vec4(P(i), P(i + 1), P(i + 2), P(i + 3));
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    pix = UV0;
    slotRow = 64 + int(Color.r * 255.0 + 0.5) * 2;
    screen = vec2(P(1), P(2));
    baseTop = P3(3);
    baseBot = P3(6);
    wash0 = P4(9);
    wash0Y = vec2(P(13), P(14));
    wash1 = P4(15);
    wash1Y = vec2(P(19), P(20));
    glow0 = P4(21);
    glow0G = P4(25);
    glow1 = P4(29);
    glow1G = P4(33);
    glow2 = P4(37);
    glow2G = P4(41);
    vig = P4(45);
    vigSide = vec2(P(49), P(50));
}
