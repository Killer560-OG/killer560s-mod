#version 330
#extension GL_ARB_separate_shader_objects : require

// Light's Soft Light backdrop (killer560smod mainmenu/SceneShaders, SoftLightSmooth): one full-screen quad. UV0 is the
// fragment's position in screen pixels; Color.r is the parameter slot. The parameters are read here, once a vertex, from
// the data texture (rows 64+, one float per RGBA8 texel, bit-exact) into flat varyings.

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
layout(location = 1) flat out vec3 baseTop;
layout(location = 2) flat out vec3 baseBot;
layout(location = 3) flat out vec3 rayCol;
layout(location = 4) flat out vec4 rayA;
layout(location = 5) flat out vec4 rayX;
layout(location = 6) flat out vec4 rayW;
layout(location = 7) flat out vec3 glowCol;
layout(location = 8) flat out vec4 glowGeom;
layout(location = 9) flat out float glowA;
layout(location = 10) flat out vec4 vig;
layout(location = 11) flat out vec2 vigSide;
layout(location = 12) flat out vec2 screen;

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
    rayCol = P3(9);
    rayA = P4(12);
    rayX = P4(16);
    rayW = P4(20);
    glowCol = P3(24);
    glowGeom = P4(27);
    glowA = P(31);
    vig = P4(32);
    vigSide = vec2(P(36), P(37));
}
