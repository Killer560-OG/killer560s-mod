#version 330
#extension GL_ARB_separate_shader_objects : require

// One drifting light (killer560smod mainmenu/SceneShaders): exact antialiased coverage of a disc at any scale and sub-pixel
// position, its colour carried through the glow and vignette drawn above it (an affine map of the colour, so this equals
// drawing the disc under them).

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec2 local;
layout(location = 1) in vec2 pix;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) flat in vec3 glowCol;
layout(location = 4) flat in vec4 glowGeom;
layout(location = 5) flat in float glowA;
layout(location = 6) flat in vec4 vig;
layout(location = 7) flat in vec2 vigSide;
layout(location = 8) flat in vec2 screen;

layout(location = 0) out vec4 fragColor;

void main() {
    float d = length(local);
    // the width of one pixel in radius units (the quad is axis-aligned and uniformly scaled)
    float px = max(abs(dFdx(local.x)) + abs(dFdy(local.x)), 1e-5);
    float cov = clamp((1.0 - d) / px + 0.5, 0.0, 1.0);
    float a = vertexColor.a * cov;
    if (a <= 0.0) {
        discard;
    }
    vec3 c = vertexColor.rgb;
    vec2 p = pix;
    vec2 q = (p - glowGeom.xy) / glowGeom.zw;
    if (abs(q.x) < 2.4 && abs(q.y) < 2.4) {
        c = mix(c, glowCol, glowA * exp(-1.6 * dot(q, q)));
    }
    float k = (1.0 - vig.y * clamp(1.0 - p.y / vig.x, 0.0, 1.0))
            * (1.0 - vig.w * clamp((p.y - (screen.y - vig.z)) / vig.z, 0.0, 1.0))
            * (1.0 - vigSide.y * clamp(1.0 - p.x / vigSide.x, 0.0, 1.0))
            * (1.0 - vigSide.y * clamp(1.0 - (screen.x - p.x) / vigSide.x, 0.0, 1.0));
    fragColor = vec4(c * k, a) * ColorModulator;
}
