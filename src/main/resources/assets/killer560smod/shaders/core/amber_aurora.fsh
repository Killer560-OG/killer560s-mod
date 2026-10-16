#version 330

// Amber's Aurora main menu (killer560smod, mainmenu/AmberAuroraScene). Sampler0 is textures/gui/amber_aurora.png, 1920x2160:
// the picture on top, its animation maps below (r: how much the sky may move, g: the aurora's own light, b: the stars
// baked into the picture, a: the river's bright water left of x 1600 and the lava right of it), sampled with a LINEAR
// sampler so every displacement is smooth at sub-pixel scale. The quad covers the screen with v 0..0.5.
//
// The scene clock rides in the vertex colour: rgb is a 24-bit fraction of the 600 s loop (alpha 255). Every motion below
// is a sine whose angular speed is a whole number of turns per loop (W(k)), so the loop wraps without a jump.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

const vec2 PIC = vec2(1920.0, 1080.0);
const float LOOP = 600.0;
const vec3 STAR = vec3(1.0, 0.92, 0.80);
const vec2 SUN = vec2(1162.0, 905.0);
const vec2 LAVA = vec2(1752.0, 906.0);

float W(float k) {
    return k * 6.28318530718 / LOOP;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// smooth value noise, 0..1
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec4 maps(vec2 p) {
    return texture(Sampler0, vec2(p.x / PIC.x, 0.5 + clamp(p.y, 0.5, PIC.y - 0.5) / PIC.y * 0.5));
}

vec3 picture(vec2 p) {
    return texture(Sampler0, vec2(p.x / PIC.x, clamp(p.y, 0.5, PIC.y - 0.5) / PIC.y * 0.5)).rgb;
}

vec3 screen(vec3 base, vec3 add) {
    return 1.0 - (1.0 - base) * (1.0 - clamp(add, 0.0, 1.0));
}

void main() {
    vec3 code = floor(vertexColor.rgb * 255.0 + 0.5);
    float T = (code.r * 65536.0 + code.g * 256.0 + code.b) / 16777216.0 * LOOP;
    vec2 p = vec2(texCoord0.x, texCoord0.y * 2.0) * PIC;

    vec4 here = maps(p);
    float sky = here.r;

    // The curtains ripple: travelling waves move the sky sideways (bending the rays a little, more the higher they reach)
    // and up and down (the band edges undulate along their length). Zero on the land, the character and the horizon.
    float lift = 0.65 + 0.35 * clamp((PIC.y - p.y) / 700.0, 0.0, 1.0);
    float dx = 2.8 * sin(p.x * 0.0105 - p.y * 0.0120 + W(19.0) * T)
             + 1.7 * sin(p.x * 0.0230 + p.y * 0.0070 - W(31.0) * T + 1.3)
             + 0.9 * sin(p.x * 0.0470 - p.y * 0.0030 + W(53.0) * T + 4.1);
    float dy = 2.4 * sin(p.x * 0.0085 + W(23.0) * T + 0.7)
             + 1.3 * sin(p.x * 0.0175 - p.y * 0.0100 - W(37.0) * T + 2.2);
    vec2 q = p + sky * lift * vec2(dx, dy);

    vec3 col = picture(q);
    vec4 there = maps(q);
    // Take the stars back out of the moved sky (the screen blend they went in with is undone exactly), so they do not
    // ride the curtains; they are put back, twinkling, where they belong.
    col = clamp(1.0 - (1.0 - col) / max(vec3(1.0) - there.b * STAR, vec3(0.05)), 0.0, 1.0);

    // The aurora's light: soft per-ray flicker (each ray its own phase, from noise along the ray's slant) and a slow
    // brightening travelling along the bands.
    float g = there.g;
    float xr = q.x + (PIC.y - q.y) * 0.18;
    float f1 = 0.5 + 0.5 * sin(W(47.0) * T + 18.85 * noise(vec2(xr / 14.0, 3.7)));
    float f2 = 0.5 + 0.5 * sin(W(29.0) * T + 18.85 * noise(vec2(xr / 41.0, 9.1)));
    float flick = 0.6 * f1 + 0.4 * f2;
    float rip = 0.5 + 0.5 * sin(q.x * 0.0065 - W(41.0) * T + q.y * 0.0030 + 2.0 * noise(q / 260.0));
    float rip2 = 0.5 + 0.5 * sin(q.x * 0.0031 + W(17.0) * T - q.y * 0.0045);
    col *= 1.0 + g * (0.30 * (2.0 * flick - 1.0) + 0.20 * (2.0 * rip - 1.0) + 0.10 * (2.0 * rip2 - 1.0));

    // The stars, where they are, twinkling softly.
    float s = here.b;
    if (s > 0.002) {
        float n = noise(p / 23.0);
        float tw = mix(sin(W(61.0) * T + 6.2832 * n), sin(W(97.0) * T + 9.0 * n), noise(p / 31.0 + 5.0));
        col = screen(col, s * (0.62 + 0.38 * tw) * STAR);
    }

    // The low sun's glow breathing.
    vec2 ds = (p - SUN) / vec2(300.0, 130.0);
    vec2 dc = (p - SUN) / vec2(70.0, 46.0);
    float breathe = 0.5 + 0.5 * sin(W(14.0) * T);
    float breathe2 = 0.5 + 0.5 * sin(W(22.0) * T + 1.1);
    col = screen(col, vec3(1.0, 0.52, 0.14) * exp(-dot(ds, ds) * 1.6) * (0.05 + 0.07 * breathe)
            + vec3(1.0, 0.80, 0.45) * exp(-dot(dc, dc) * 1.8) * (0.06 + 0.10 * breathe2));

    float a = here.a;
    if (a > 0.003) {
        if (p.x < 1600.0) {
            // The river: glints that drift and sparkle on its bright water.
            float w1 = 0.5 + 0.5 * sin(p.x * 0.090 - W(90.0) * T + 6.0 * noise(p * vec2(0.020, 0.25)));
            float w2 = 0.5 + 0.5 * sin(p.y * 1.10 + W(37.0) * T + 5.0 * noise(p * vec2(0.035, 0.08) + 11.0));
            float glint = pow(w1, 10.0) * w2 * w2;
            float sheen = 0.5 + 0.5 * sin(p.x * 0.015 + p.y * 0.05 - W(25.0) * T);
            col = screen(col, vec3(1.0, 0.86, 0.58) * a * (0.55 * glint + 0.06 * sheen));
        } else {
            // The lava: pulsing, with brighter bands running down it.
            float pulse = 0.5 + 0.5 * sin(W(27.0) * T);
            float flow = 0.5 + 0.5 * sin(p.y * 0.16 - W(150.0) * T + 3.0 * noise(p * vec2(0.12, 0.05)));
            col = screen(col, vec3(1.0, 0.62, 0.18) * a * (0.06 + 0.16 * pulse + 0.14 * flow * flow));
        }
    }
    // the lava's light on the rock around it, breathing with it
    vec2 dl = (p - LAVA) / vec2(48.0, 70.0);
    col = screen(col, vec3(1.0, 0.45, 0.10) * exp(-dot(dl, dl)) * (0.04 + 0.08 * (0.5 + 0.5 * sin(W(27.0) * T))));

    fragColor = vec4(col, 1.0) * ColorModulator;
}
