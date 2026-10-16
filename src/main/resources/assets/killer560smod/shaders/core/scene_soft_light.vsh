#version 330

// Light's Soft Light backdrop (killer560smod mainmenu/SceneShaders, SoftLightSmooth): one full-screen quad. UV0 is the
// fragment's position in screen pixels; Color.r is the parameter slot. The parameters are read here, once a vertex, from
// the data texture (rows 64+, one float per RGBA8 texel, bit-exact) into flat varyings.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

uniform sampler2D Sampler0;

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 pix;
flat out vec3 baseTop;
flat out vec3 baseBot;
flat out vec3 rayCol;
flat out vec4 rayA;
flat out vec4 rayX;
flat out vec4 rayW;
flat out vec3 glowCol;
flat out vec4 glowGeom;
flat out float glowA;
flat out vec4 vig;
flat out vec2 vigSide;
flat out vec2 screen;

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
