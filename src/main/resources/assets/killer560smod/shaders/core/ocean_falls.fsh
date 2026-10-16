#version 330

// Ocean's Cavern Falls main menu (killer560smod, mainmenu/OceanFallsScene). Sampler0 is textures/gui/ocean_falls.png,
// 1920x2160, sampled LINEAR: the PLATE on top (his picture with most of its streak lines lifted out where water falls) and
// its maps below (r: where water falls, g: the picture's own streaks, b: where the water lands, a: the lake). The quad
// covers the screen with v 0..0.5. Built by killer560s-mod-logs/waterfall-picture/make_waterfall_maps.py.
//
// The scene clock rides in the vertex colour: rgb is a 24-bit fraction of the 600 s loop (alpha 255). The sines below turn
// a whole number of times a loop (W(k)); the falling noise does not wrap, so the water's pattern changes once a loop, in
// one frame, which in turbulent water reads as nothing.
//
// What moves: the whole mass of the falls (its brightness rolls down in turbulent sheets and slow surges), every streak of
// the picture (lit by dashes travelling down its own column at a heavy-water speed), fine fast threads at the front, spray
// churning up where it all lands and at the foot of each cliff fall, and the lake's glints. The cliffs, the character and
// the land stay still: the maps are zero there.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

const vec2 PIC = vec2(1920.0, 1080.0);
const float LOOP = 600.0;
const vec3 TEAL = vec3(0.40, 0.96, 0.90);
const vec3 HEAD = vec3(0.80, 1.00, 0.97);
const vec3 MIST = vec3(0.55, 0.92, 0.88);

float W(float k) {
    return k * 6.28318530718 / LOOP;
}

float hash(vec2 c) {
    ivec2 q = ivec2(c);
    uint h = uint(q.x) * 374761393u + uint(q.y) * 668265263u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    h ^= h >> 16u;
    return float(h & 0xFFFFFFu) / 16777215.0;
}

// smooth value noise, 0..1, on an integer lattice (an integer hash, so it holds up far from the origin)
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

float fbm(vec2 p) {
    return 0.55 * noise(p) + 0.30 * noise(p * 2.03 + 17.1) + 0.15 * noise(p * 4.07 + 3.3);
}

vec4 maps(vec2 p) {
    return texture(Sampler0, vec2(p.x / PIC.x, 0.5 + clamp(p.y, 0.5, PIC.y - 0.5) / PIC.y * 0.5));
}

vec3 plate(vec2 p) {
    return texture(Sampler0, vec2(p.x / PIC.x, clamp(p.y, 0.5, PIC.y - 0.5) / PIC.y * 0.5)).rgb;
}

vec3 screen(vec3 base, vec3 add) {
    return 1.0 - (1.0 - base) * (1.0 - clamp(add, 0.0, 1.0));
}

void main() {
    vec3 code = floor(vertexColor.rgb * 255.0 + 0.5);
    float T = (code.r * 65536.0 + code.g * 256.0 + code.b) / 16777216.0 * LOOP;
    vec2 p = vec2(texCoord0.x, texCoord0.y * 2.0) * PIC;

    vec4 m = maps(p);
    float water = m.r;
    vec3 col = plate(p);

    if (water > 0.002) {
        float glow = dot(col, vec3(0.2, 0.5, 0.3));
        // The mass of the falls: its brightness rolls down in turbulent sheets (each column band at its own speed) and in
        // slow broad surges, so the whole curtain pours, not just its lines.
        float vs = 640.0 * (0.85 + 0.30 * noise(vec2(p.x / 40.0, 3.0)));
        float sheet = fbm(vec2(p.x / 10.0, (p.y - vs * T) / 70.0));
        float surge = noise(vec2(p.x / 70.0, (p.y - 520.0 * T) / 260.0));
        col *= 1.0 + water * (0.55 * (sheet - 0.5) + 0.40 * (surge - 0.5));

        // Every streak of the picture falling: its own column lit by dashes travelling down it, near ones fast and short,
        // far ones slower and longer, brighter where a surge passes.
        float v1 = 980.0 * (0.80 + 0.40 * noise(vec2(p.x / 23.0, 7.0)));
        float d1 = noise(vec2(p.x / 2.0, (p.y - v1 * T) / 48.0));
        float d2 = noise(vec2(p.x / 2.5 + 31.0, (p.y - 0.68 * v1 * T) / 150.0));
        float dash = 0.85 * smoothstep(0.38, 0.88, d1) + 0.65 * smoothstep(0.30, 0.90, d2);
        float lit = m.g * dash * (0.55 + 0.9 * surge);

        // Fine fast threads all through the curtain at the front, stronger where the haze is bright.
        float v3 = 1250.0 * (0.85 + 0.30 * noise(vec2(p.x / 17.0, 11.0)));
        float d3 = noise(vec2(p.x / 1.7 + 101.0, (p.y - v3 * T) / 34.0));
        float threads = water * pow(smoothstep(0.58, 0.97, d3), 1.4) * (0.06 + 0.55 * glow);

        col = screen(col, TEAL * (1.05 * lit + threads) + HEAD * 0.35 * lit * lit);
    }

    float spray = m.b;
    if (spray > 0.003) {
        // Spray and mist churning up where the water lands: two layers of turbulence rising and rolling sideways.
        float f = fbm(vec2(p.x / 34.0 + 0.20 * T, (p.y + 48.0 * T) / 15.0));
        float f2 = noise(vec2(p.x / 11.0 - 0.45 * T, (p.y + 110.0 * T) / 6.0));
        float g2 = fbm(vec2(p.x / 80.0 - 0.10 * T, (p.y + 22.0 * T) / 30.0));
        col = screen(col, MIST * spray * (0.06 + 0.40 * f * f + 0.22 * smoothstep(0.62, 1.0, f2) + 0.18 * g2));
    }

    float lake = m.a;
    if (lake > 0.003) {
        // Glints drifting and sparkling on the lake, and slow swells of sheen.
        float w1 = 0.5 + 0.5 * sin(p.x * 0.055 - W(180.0) * T + 6.0 * noise(p * vec2(0.018, 0.22)));
        float w2 = 0.5 + 0.5 * sin(p.y * 0.9 + W(70.0) * T + 5.0 * noise(p * vec2(0.03, 0.07) + 11.0));
        float glint = pow(w1, 12.0) * w2 * w2;
        float sheen = 0.5 + 0.5 * sin(p.x * 0.012 + p.y * 0.06 - W(40.0) * T);
        float bright = dot(col, vec3(0.2, 0.5, 0.3));
        col = screen(col, HEAD * lake * (0.5 * glint * (0.3 + bright * 2.0) + 0.05 * sheen * bright));
    }

    fragColor = vec4(col, 1.0) * ColorModulator;
}
