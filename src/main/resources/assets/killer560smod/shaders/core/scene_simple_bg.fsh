#version 330

// The Simple main menu scenes' backdrop (killer560smod mainmenu/SimpleScenes): the theme's base gradient, two washes (a
// colour fading in linearly between two rows), three soft Gaussian glows and the vignette, composited in float and dithered
// once with the 64x64 blue-noise tile in Sampler0 (rows 0-63), so the 8-bit framebuffer shows no bands.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 pix;
flat in vec2 screen;
flat in vec3 baseTop;
flat in vec3 baseBot;
flat in vec4 wash0;
flat in vec2 wash0Y;
flat in vec4 wash1;
flat in vec2 wash1Y;
flat in vec4 glow0;
flat in vec4 glow0G;
flat in vec4 glow1;
flat in vec4 glow1G;
flat in vec4 glow2;
flat in vec4 glow2G;
flat in vec4 vig;
flat in vec2 vigSide;

out vec4 fragColor;

// alpha 0 at row y.x, wash.a at row y.y (either way up), held beyond
vec3 wash(vec3 c, vec4 w, vec2 y, float py) {
    if (w.a <= 0.0 || y.x == y.y) {
        return c;
    }
    float f = clamp((py - y.x) / (y.y - y.x), 0.0, 1.0);
    return mix(c, w.rgb, w.a * f);
}

// Px.softGlow's Gaussian: centre g.xy, radii g.zw
vec3 glow(vec3 c, vec4 col, vec4 g, vec2 p) {
    if (col.a <= 0.0) {
        return c;
    }
    vec2 q = (p - g.xy) / max(g.zw, vec2(1.0));
    if (abs(q.x) > 2.6 || abs(q.y) > 2.6) {
        return c;
    }
    return mix(c, col.rgb, col.a * exp(-1.6 * dot(q, q)));
}

void main() {
    vec2 p = pix;
    vec3 c = mix(baseTop, baseBot, clamp(p.y / screen.y, 0.0, 1.0));
    c = wash(c, wash0, wash0Y, p.y);
    c = wash(c, wash1, wash1Y, p.y);
    c = glow(c, glow0, glow0G, p);
    c = glow(c, glow1, glow1G, p);
    c = glow(c, glow2, glow2G, p);
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
