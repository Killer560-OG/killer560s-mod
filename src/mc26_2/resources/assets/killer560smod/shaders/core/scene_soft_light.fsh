#version 330

// Light's Soft Light backdrop (killer560smod mainmenu/SceneShaders): the base gradient, four slanted rays with smooth
// sides fading down the screen, the title glow and the vignette, composited in float in the order the GuiRects version draws
// them, then dithered once with the 64x64 blue-noise tile in Sampler0 (rows 0-63) so the 8-bit framebuffer shows no bands.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 pix;
flat in vec3 baseTop;
flat in vec3 baseBot;
flat in vec3 rayCol;
flat in vec4 rayA;
flat in vec4 rayX;
flat in vec4 rayW;
flat in vec3 glowCol;
flat in vec4 glowGeom;
flat in float glowA;
flat in vec4 vig;
flat in vec2 vigSide;
flat in vec2 screen;

out vec4 fragColor;

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
