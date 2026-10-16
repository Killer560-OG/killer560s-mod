#version 330

// One particle of a Simple main menu scene (killer560smod mainmenu/SimpleScenes), exactly antialiased at any scale and
// sub-pixel position. Kind 0: a plain disc (a far ember, a star, a spore). Kind 1: a disc with a hot centre and a soft
// Gaussian halo (a near ember, a soft mote). Kind 3: a disc with a wide glow (a firefly). Kind 2: a bubble - a faint body, a bright rim and a small highlight up and to the
// left. Alpha is dithered with the blue-noise tile so a faint halo does not band; the backdrop's vignette darkens it.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 local;
in vec2 pix;
in vec4 vertexColor;
flat in int kind;
flat in vec4 vig;
flat in vec2 vigSide;
flat in vec2 screen;

out vec4 fragColor;

void main() {
    float d = length(local);
    // the width of one pixel in radius units (the quad is axis-aligned and uniformly scaled)
    float px = max(abs(dFdx(local.x)) + abs(dFdy(local.x)), 1e-5);
    vec3 c = vertexColor.rgb;
    float a;
    if (kind == 2) {
        float edge = clamp((1.06 - d) / px + 0.5, 0.0, 1.0);
        float rimW = max(0.13, px * 0.9);
        float rim = exp(-((d - 0.86) * (d - 0.86)) / (rimW * rimW));
        vec2 sp = local - vec2(-0.36, -0.40);
        float spec = exp(-dot(sp, sp) / max(0.022, px * px * 0.5));
        a = clamp((0.13 + 0.82 * rim) * edge + 0.9 * spec, 0.0, 1.0);
        c = mix(c, vec3(1.0), clamp(spec * 1.2, 0.0, 1.0));
    } else {
        float cov = clamp((1.0 - d) / px + 0.5, 0.0, 1.0);
        if (kind == 1) {
            float halo = exp(-d * d / 3.4);
            a = cov + (1.0 - cov) * 0.45 * halo;
            c = mix(c, vec3(1.0), 0.38 * cov * clamp(1.0 - d * d, 0.0, 1.0));
        } else if (kind == 3) {
            // a firefly: a tight inner halo and a wide soft glow
            float halo = 0.45 * exp(-d * d / 2.5) + 0.26 * exp(-d * d / 16.0);
            a = cov + (1.0 - cov) * halo;
            c = mix(c, vec3(1.0), 0.45 * cov * clamp(1.0 - d * d, 0.0, 1.0));
        } else {
            a = cov;
        }
    }
    a *= vertexColor.a;
    float n = texelFetch(Sampler0, ivec2(floor(pix)) & 63, 0).r;
    a = clamp(a + (n - 0.5) / 255.0, 0.0, 1.0);
    if (a <= 0.0) {
        discard;
    }
    vec2 p = pix;
    float k = (1.0 - vig.y * clamp(1.0 - p.y / vig.x, 0.0, 1.0))
            * (1.0 - vig.w * clamp((p.y - (screen.y - vig.z)) / vig.z, 0.0, 1.0))
            * (1.0 - vigSide.y * clamp(1.0 - p.x / vigSide.x, 0.0, 1.0))
            * (1.0 - vigSide.y * clamp(1.0 - (screen.x - p.x) / vigSide.x, 0.0, 1.0));
    fragColor = vec4(c * k, a) * ColorModulator;
}
