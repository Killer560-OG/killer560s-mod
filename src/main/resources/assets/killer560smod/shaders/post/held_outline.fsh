#version 330

// Held Item Outline, pass 2 of 2: combines pass 1's per-row distances into the exact Euclidean distance to the held
// item's silhouette within the outline radius, then blends the outline outside the silhouette (solid out to the
// width, fading out over the next pixel) and the fill inside it over the frame.

uniform sampler2D InSampler;
uniform sampler2D DistSampler;

layout(std140) uniform HeldOutlineConfig {
    vec4 OutlineColor; // rgb + alpha
    vec4 FillColor;    // rgb + alpha
    vec4 Params;       // x = radius in pixels, y = outline on, z = fill on
};

in vec2 texCoord;

out vec4 fragColor;

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
    vec2 here = texelFetch(DistSampler, p, 0).rg;
    vec3 col = base.rgb;
    if (here.g > 0.5) {
        if (Params.z > 0.5) {
            col = mix(col, FillColor.rgb, FillColor.a);
        }
    } else if (Params.y > 0.5) {
        float radius = Params.x;
        int r = int(ceil(radius));
        float best = 1.0e9;
        for (int dy = -r; dy <= r; dy++) {
            int y = p.y + dy;
            if (y < 0 || y >= size.y) {
                continue;
            }
            float dx = floor(texelFetch(DistSampler, ivec2(p.x, y), 0).r * 255.0 + 0.5);
            if (dx < 254.5) {
                best = min(best, dx * dx + float(dy * dy));
            }
        }
        if (best < 1.0e8) {
            float coverage = clamp(radius + 1.0 - sqrt(best), 0.0, 1.0);
            col = mix(col, OutlineColor.rgb, OutlineColor.a * coverage);
        }
    }
    fragColor = vec4(col, base.a);
}
