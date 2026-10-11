#version 330

// Held Item Outline, pass 2 of 2: combines pass 1's per-row distances into exact Euclidean distances within the outline
// radius and blends the result over the frame: the fill on the whole item, then the outline (solid out to the width,
// fading out over the next pixel) on top.
//   Outline Side Far (mode 0): around the whole item.
//   Near (1): around the near face, over the item's thin side walls.
//   Both (2): around the near face and around the far face, joined by a line at every corner of the extruded slab
//             (SegInfo.x segments, window pixels), all hidden where the near face covers them.

uniform sampler2D InSampler;
uniform sampler2D DistSampler;

layout(std140) uniform HeldOutlineConfig {
    vec4 OutlineColor; // rgb + alpha
    vec4 FillColor;    // rgb + alpha
    vec4 Params;       // x = radius in pixels, y = outline on, z = fill on, w = test view
    vec4 SegInfo;      // x = connector count, y = mode
    vec4 SegBox;       // connectors' bounding box in window pixels (min x, min y, max x, max y)
    vec4 Seg[128];     // connector i: (x0, y0, x1, y1)
};

in vec2 texCoord;

out vec4 fragColor;

// Euclidean distance (squared, 1e9 = none) from p to the region whose per-row distances are in the given channel.
float ringDistance(ivec2 p, ivec2 size, int r, int channel) {
    float best = 1.0e9;
    for (int dy = -r; dy <= r; dy++) {
        int y = p.y + dy;
        if (y < 0 || y >= size.y) {
            continue;
        }
        vec4 t = texelFetch(DistSampler, ivec2(p.x, y), 0);
        float dx = floor((channel == 0 ? t.r : t.b) * 255.0 + 0.5);
        if (dx < 254.5) {
            best = min(best, dx * dx + float(dy * dy));
        }
    }
    return best;
}

float ringCoverage(float best, float radius) {
    return best < 1.0e8 ? clamp(radius + 1.0 - sqrt(best), 0.0, 1.0) : 0.0;
}

void main() {
    vec4 base = texture(InSampler, texCoord);
    if (Params.w > 0.5 && Params.w < 2.5) {
        // Test view (HeldOutlineRenderer.debugView): 1 = the pass itself (whole frame magenta), 2 = the silhouette.
        vec2 d = texelFetch(DistSampler, ivec2(gl_FragCoord.xy), 0).rg;
        fragColor = Params.w < 1.5 ? vec4(1.0, 0.0, 1.0, 1.0) : vec4(vec3(d.g), 1.0);
        return;
    }
    ivec2 size = textureSize(DistSampler, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 here = texelFetch(DistSampler, p, 0);
    bool inWhole = here.g > 0.5;
    bool inNear = here.a > 0.5;
    int mode = int(SegInfo.y + 0.5);
    vec3 col = base.rgb;
    if (inWhole && Params.z > 0.5) {
        col = mix(col, FillColor.rgb, FillColor.a);
    }
    if (Params.y > 0.5) {
        float radius = Params.x;
        int r = int(ceil(radius));
        float coverage = 0.0;
        if (mode == 0) {
            if (!inWhole) {
                coverage = ringCoverage(ringDistance(p, size, r, 0), radius);
            }
        } else if (!inNear) {
            float nearBest = ringDistance(p, size, r, 0);
            if (nearBest > 0.5) {
                coverage = ringCoverage(nearBest, radius);
            }
            if (mode == 2) {
                float farBest = ringDistance(p, size, r, 2);
                if (farBest > 0.5) {
                    coverage = max(coverage, ringCoverage(farBest, radius));
                }
                vec2 f = gl_FragCoord.xy;
                if (f.x >= SegBox.x && f.y >= SegBox.y && f.x <= SegBox.z && f.y <= SegBox.w) {
                    float halfW = max(0.5, radius * 0.5);
                    int n = int(SegInfo.x + 0.5);
                    for (int i = 0; i < n; i++) {
                        vec2 a = Seg[i].xy;
                        vec2 b = Seg[i].zw;
                        vec2 ab = b - a;
                        float len2 = dot(ab, ab);
                        float t = len2 > 1.0e-6 ? clamp(dot(f - a, ab) / len2, 0.0, 1.0) : 0.0;
                        float d = length(f - (a + t * ab));
                        coverage = max(coverage, clamp(halfW + 0.5 - d, 0.0, 1.0));
                    }
                }
            }
        }
        col = mix(col, OutlineColor.rgb, OutlineColor.a * coverage);
    }
    fragColor = vec4(col, base.a);
}
