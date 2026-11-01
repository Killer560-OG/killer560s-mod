#version 330

// Ocean's Cavern Falls main menu (killer560smod, mainmenu/OceanFallsScene). Sampler0 is textures/gui/ocean_falls.png,
// 2560x2880 (two 2560x1440 halves; everything here is in 1920x1080 units), sampled LINEAR: the PLATE on top (his picture with most of its streak lines lifted out where water falls) and
// its maps below (r: where water falls, g: the picture's own streaks, b: where the water lands, a: the lake). The quad
// covers the screen with v 0..0.5. Built by a local script (make_waterfall_maps.py).
//
// The scene clock rides in the vertex colour: rgb is a 24-bit fraction of the 600 s loop (alpha 255). The sines below turn
// a whole number of times a loop (W(k)); the falling noise does not wrap, so the water's pattern changes once a loop, in
// one frame, which in turbulent water reads as nothing.
//
// What moves: the whole mass of the falls (its brightness rolls down in turbulent sheets and slow surges), every streak of
// the picture (lit by dashes travelling down its own column at a heavy-water speed), fine fast threads at the front, spray
// churning up where it all lands and at the foot of each cliff fall, the mist billowing, and the lake itself (waves rolling
// toward the viewer and rings from the falls bend its reflections; the falls reflected; current lines; glints on the
// crests). The cliffs, the character and the land stay still: the maps are zero there.

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

// The dashes lighting a falling streak at p (the same pattern the falls use, so a reflection of them matches).
float dashes(vec2 p, float T) {
    float v1 = 980.0 * (0.80 + 0.40 * noise(vec2(p.x / 23.0, 7.0)));
    float d1 = noise(vec2(p.x / 2.0, (p.y - v1 * T) / 48.0));
    float d2 = noise(vec2(p.x / 2.5 + 31.0, (p.y - 0.68 * v1 * T) / 150.0));
    return 0.85 * smoothstep(0.38, 0.88, d1) + 0.65 * smoothstep(0.30, 0.90, d2);
}

const float HORIZON = 926.0;

void main() {
    vec3 code = floor(vertexColor.rgb * 255.0 + 0.5);
    float T = (code.r * 65536.0 + code.g * 256.0 + code.b) / 16777216.0 * LOOP;
    vec2 p = vec2(texCoord0.x, texCoord0.y * 2.0) * PIC;

    vec4 m = maps(p);
    float water = m.r;
    float lake = m.a;
    float spray = m.b;

    // Where the picture is read from: the mist billows (its own picture rolled about by drifting turbulence) and the lake
    // moves (waves rolling toward the viewer and rings spreading from where the falls land bend its reflections). A
    // displaced read only counts where it lands on the same kind of water, so no rock, cliff or character is pulled in.
    vec2 q = p;
    float persp = clamp((p.y - HORIZON + 4.0) / 158.0, 0.0, 1.0);      // 0 at the far shore, 1 at the bottom
    float depth = 1.0 / (persp + 0.08);
    float gx = (p.x - 1100.0) / (persp * 0.9 + 0.1);                    // across the water, in ground units
    float wave = 0.0;
    if (spray > 0.003) {
        vec2 n = vec2(fbm(vec2((p.x - 30.0 * T) / 70.0, p.y / 26.0 + 0.8 * T)) - 0.5,
                      fbm(vec2((p.x + 20.0 * T) / 55.0 + 40.0, (p.y + 45.0 * T) / 22.0)) - 0.5);
        vec2 qs = p + spray * vec2(26.0, 16.0) * n;
        q = mix(p, qs, clamp(maps(qs).b * 1.6, 0.0, 1.0));
    }
    if (lake > 0.003) {
        float w1 = sin(depth * 2.2 + W(420.0) * T + 0.004 * gx);
        float w2 = sin(depth * 3.7 - gx * 0.010 + W(560.0) * T + 1.3);
        float w3 = noise(vec2(gx / 60.0 - 0.7 * T, depth * 6.0 + 2.2 * T)) - 0.5;
        wave = 0.5 * w2 + 0.3 * w1 + 0.6 * w3;
        vec2 d = vec2(5.0 * w2 + 9.0 * w3, 2.2 * w1 + 2.5 * w3) * (0.25 + persp);
        // rings spreading from where the falls land on the far side
        for (int i = 0; i < 5; i++) {
            vec2 c = vec2(780.0 + 185.0 * float(i), HORIZON + 4.0);
            vec2 e = (p - c) * vec2(1.0, 4.2);
            float r = length(e);
            float ring = sin(r * 0.085 - W(520.0 + 40.0 * float(i)) * T) * exp(-r / 300.0);
            d += e / (r + 1.0) * vec2(1.0, 0.25) * ring * 3.5;
            wave += 0.6 * ring;
        }
        vec2 ql = p + d * lake * lake;
        q = mix(q, ql, clamp(maps(ql).a * 1.4, 0.0, 1.0));
    }
    vec3 col = plate(q);

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

    if (spray > 0.003) {
        // Spray and mist churning up where the water lands: two layers of turbulence rising and rolling sideways.
        float f = fbm(vec2(p.x / 34.0 + 0.20 * T, (p.y + 48.0 * T) / 15.0));
        float f2 = noise(vec2(p.x / 11.0 - 0.45 * T, (p.y + 110.0 * T) / 6.0));
        float g2 = fbm(vec2(p.x / 80.0 - 0.10 * T, (p.y + 22.0 * T) / 30.0));
        col = screen(col, MIST * spray * (0.06 + 0.40 * f * f + 0.22 * smoothstep(0.62, 1.0, f2) + 0.18 * g2));
    }

    if (lake > 0.003) {
        float bright = dot(col, vec3(0.2, 0.5, 0.3));
        // The falls reflected: the stretched mirror of their foot, lit by the same dashes (so the reflection of falling
        // water runs up toward the far shore), bent by the waves and fading toward the viewer.
        vec2 mp = vec2(q.x, HORIZON - (q.y - HORIZON) * 1.6);
        vec4 mm = maps(mp);
        float refl = mm.g * dashes(mp, T) * pow(1.0 - persp, 1.3) * (0.7 + 0.3 * wave);
        // Current lines drifting across, thin and bright where the water holds light.
        float cur = smoothstep(0.72, 0.96, noise(vec2((gx + 70.0 * T) / 170.0, depth * 9.0 + 0.2 * T)));
        float cur2 = smoothstep(0.75, 0.97, noise(vec2((gx - 45.0 * T) / 110.0 + 50.0, depth * 13.0)));
        // Glints riding the wave crests.
        float crest = 0.5 + 0.5 * sin(depth * 3.7 - gx * 0.010 + W(560.0) * T + 1.3 + 2.0 * wave);
        float glint = pow(crest, 14.0) * (0.5 + 0.5 * noise(vec2(gx / 25.0 + 3.0 * T, depth * 20.0)));
        // A slow swell of sheen rolling toward the viewer.
        float sheen = 0.5 + 0.5 * sin(depth * 1.3 + W(180.0) * T);
        col = screen(col, lake * (TEAL * (0.55 * refl + (0.10 * cur + 0.07 * cur2) * (0.25 + 2.2 * bright))
                + HEAD * (0.55 * glint * (0.25 + 2.0 * bright) + 0.05 * sheen * bright)));
        // the light itself rocks with the waves
        col *= 1.0 + lake * 0.10 * wave;
    }

    fragColor = vec4(col, 1.0) * ColorModulator;
}
