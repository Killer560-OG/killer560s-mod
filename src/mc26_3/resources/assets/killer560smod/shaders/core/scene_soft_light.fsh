#version 330
#extension GL_ARB_separate_shader_objects : require

// Light's Soft Light backdrop (killer560smod mainmenu/SceneShaders): the base gradient, four slanted rays with smooth
// sides fading down the screen, the title glow and the vignette, composited in float in the order the GuiRects version draws
// them, then dithered once with the 64x64 blue-noise tile in Sampler0 (rows 0-63) so the 8-bit framebuffer shows no bands.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec2 pix;
layout(location = 1) flat in vec3 baseTop;
layout(location = 2) flat in vec3 baseBot;
layout(location = 3) flat in vec3 rayCol;
layout(location = 4) flat in vec4 rayA;
layout(location = 5) flat in vec4 rayX;
layout(location = 6) flat in vec4 rayW;
layout(location = 7) flat in vec3 glowCol;
layout(location = 8) flat in vec4 glowGeom;
layout(location = 9) flat in float glowA;
layout(location = 10) flat in vec4 vig;
layout(location = 11) flat in vec2 vigSide;
layout(location = 12) flat in vec2 screen;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 p = pix;
    vec3 c = mix(baseTop, baseBot, clamp(p.y / screen.y, 0.0, 1.0));
    // the rays: x0 + 0.55 y, a smooth side profile, fading to nothing at the bottom
    float fade = clamp(1.0 - p.y / screen.y, 0.0, 1.0);
    float xs = p.x - 0.55 * p.y;
    for (int k = 0; k < 4; k++) {
        float d = abs(xs - (rayX[k] + rayW[k] * 0.5)) / rayW[k];
        float prof = 1.02 * (1.0 - smoothstep(0.28, 0.62, d));
        c = mix(c, rayCol, rayA[k] * prof * fade);
    }
    // the glow behind the title (Px.softGlow's Gaussian)
    vec2 q = (p - glowGeom.xy) / glowGeom.zw;
    if (abs(q.x) < 2.4 && abs(q.y) < 2.4) {
        c = mix(c, glowCol, glowA * exp(-1.6 * dot(q, q)));
    }
    // the vignette: top, bottom, left, right, each black over
    float k = (1.0 - vig.y * clamp(1.0 - p.y / vig.x, 0.0, 1.0))
            * (1.0 - vig.w * clamp((p.y - (screen.y - vig.z)) / vig.z, 0.0, 1.0))
            * (1.0 - vigSide.y * clamp(1.0 - p.x / vigSide.x, 0.0, 1.0))
            * (1.0 - vigSide.y * clamp(1.0 - (screen.x - p.x) / vigSide.x, 0.0, 1.0));
    c *= k;
    float n = texelFetch(Sampler0, ivec2(floor(p)) & 63, 0).r;
    c += (n - 0.5) / 255.0;
    fragColor = vec4(c, 1.0) * ColorModulator;
}
