#version 330
#extension GL_ARB_separate_shader_objects : require

// One drifting light of Soft Light (killer560smod mainmenu/SceneShaders): UV0 is the position in units of the disc's radius
// from its centre, plus 16 x the parameter slot in u; Color is the disc's colour and alpha. The glow and vignette above the
// discs are read from the slot so the fragment can apply them to the disc's colour.

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

layout(location = 0) out vec2 local;
layout(location = 1) out vec2 pix;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) flat out vec3 glowCol;
layout(location = 4) flat out vec4 glowGeom;
layout(location = 5) flat out float glowA;
layout(location = 6) flat out vec4 vig;
layout(location = 7) flat out vec2 vigSide;
layout(location = 8) flat out vec2 screen;

int slotRow;

float P(int i) {
    uvec4 b = uvec4(texelFetch(Sampler0, ivec2(i & 63, slotRow + (i >> 6)), 0) * 255.0 + 0.5);
    return uintBitsToFloat(b.r | (b.g << 8u) | (b.b << 16u) | (b.a << 24u));
}

vec4 P4(int i) {
    return vec4(P(i), P(i + 1), P(i + 2), P(i + 3));
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    int slot = int(floor((UV0.x + 8.0) / 16.0));
    local = UV0 - vec2(float(slot) * 16.0, 0.0);
    slotRow = 64 + slot * 2;
    pix = Position.xy * P(0);
    vertexColor = Color;
    screen = vec2(P(1), P(2));
    glowCol = vec3(P(24), P(25), P(26));
    glowGeom = P4(27);
    glowA = P(31);
    vig = P4(32);
    vigSide = vec2(P(36), P(37));
}
