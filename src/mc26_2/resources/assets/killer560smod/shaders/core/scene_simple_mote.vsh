#version 330

// One particle of a Simple main menu scene (killer560smod mainmenu/SimpleScenes): an ember, a bubble, a star or a firefly.
// UV0 is the position in units of the particle's core radius from its centre, plus 16 x the parameter slot in u and 16 x
// the particle kind in v; Color is its colour and alpha. The vignette drawn with the backdrop is read from the slot so the
// fragment can darken the particle the same way.

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

out vec2 local;
out vec2 pix;
out vec4 vertexColor;
flat out int kind;
flat out vec4 vig;
flat out vec2 vigSide;
flat out vec2 screen;

int slotRow;

float P(int i) {
    uvec4 b = uvec4(texelFetch(Sampler0, ivec2(i & 63, slotRow + (i >> 6)), 0) * 255.0 + 0.5);
    return uintBitsToFloat(b.r | (b.g << 8u) | (b.b << 16u) | (b.a << 24u));
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    int slot = int(floor((UV0.x + 8.0) / 16.0));
    kind = int(floor((UV0.y + 8.0) / 16.0));
    local = UV0 - vec2(float(slot) * 16.0, float(kind) * 16.0);
    slotRow = 64 + slot * 2;
    pix = Position.xy * P(0);
    vertexColor = Color;
    screen = vec2(P(1), P(2));
    vig = vec4(P(45), P(46), P(47), P(48));
    vigSide = vec2(P(49), P(50));
}
