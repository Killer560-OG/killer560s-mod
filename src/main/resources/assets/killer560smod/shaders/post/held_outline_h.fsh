#version 330

// Held Item Outline, pass 1 of 2: per row, the distance (in pixels, 0..radius) to the nearest pixel of the held
// item's silhouette, or 255 if there is none within the radius. Green carries the silhouette itself for the fill.

uniform sampler2D MaskSampler;
uniform sampler2D MaskDepthSampler;

layout(std140) uniform HeldOutlineH {
    vec4 HParams; // x = outline radius in pixels (0 = no outline), y = the cleared (far) depth: 1 on 26.1.2, 0 on 26.2
};

in vec2 texCoord;

out vec4 fragColor;

const float THRESHOLD = 0.02;

// The item was drawn into a target cleared to transparent black and the far depth (HParams.y). Not every item pipeline
// writes alpha, so a texel belongs to the item if it took depth or got any colour or alpha at all.
bool item(ivec2 q) {
    vec4 c = texelFetch(MaskSampler, q, 0);
    return abs(texelFetch(MaskDepthSampler, q, 0).r - HParams.y) > 1.0e-6 || max(max(c.r, c.g), max(c.b, c.a)) > THRESHOLD;
}

void main() {
    ivec2 size = textureSize(MaskSampler, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    bool centre = item(p);
    int r = int(ceil(HParams.x));
    int best = 255;
    if (centre) {
        best = 0;
    } else {
        for (int dx = 1; dx <= r && best == 255; dx++) {
            int xl = p.x - dx;
            int xr = p.x + dx;
            if ((xl >= 0 && item(ivec2(xl, p.y))) || (xr < size.x && item(ivec2(xr, p.y)))) {
                best = dx;
            }
        }
    }
    fragColor = vec4(float(best) / 255.0, centre ? 1.0 : 0.0, 0.0, 1.0);
}
