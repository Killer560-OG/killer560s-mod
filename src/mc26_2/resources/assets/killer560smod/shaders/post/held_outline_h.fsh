#version 330

// Held Item Outline, pass 1 of 2. Per row: the distance (in pixels, 0..radius, 255 = none) to the nearest pixel of the
// region the main outline follows - the whole item for Outline Side: Far, its near face for Near and Both - and, for
// Both, to its far face; plus whether this pixel is the whole item and whether it is the near face.

uniform sampler2D MaskSampler;
uniform sampler2D MaskDepthSampler;
uniform sampler2D NearSampler;
uniform sampler2D FarSampler;

layout(std140) uniform HeldOutlineH {
    vec4 HParams; // x = outline radius in pixels (0 = no outline), y = the cleared (far) depth: 1 on 26.1.2, 0 on 26.2,
                  // z = Outline Side mode: 0 Far (whole item), 1 Near, 2 Both
};

in vec2 texCoord;

out vec4 fragColor;

const float THRESHOLD = 0.02;

bool covered(vec4 c) {
    return max(max(c.r, c.g), max(c.b, c.a)) > THRESHOLD;
}

// The whole item was drawn into a target cleared to transparent black and the far depth (HParams.y). Not every item
// pipeline writes alpha, so a texel belongs to the item if it took depth or got any colour or alpha at all.
bool whole(ivec2 q) {
    return abs(texelFetch(MaskDepthSampler, q, 0).r - HParams.y) > 1.0e-6 || covered(texelFetch(MaskSampler, q, 0));
}

// The face masks are colour only (they borrowed the whole mask's depth while drawn).
bool nearFace(ivec2 q) {
    return covered(texelFetch(NearSampler, q, 0));
}

bool farFace(ivec2 q) {
    return covered(texelFetch(FarSampler, q, 0));
}

bool main1(ivec2 q, int mode) {
    return mode == 0 ? whole(q) : nearFace(q);
}

void main() {
    ivec2 size = textureSize(MaskSampler, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    int mode = int(HParams.z + 0.5);
    int r = int(ceil(HParams.x));
    bool inWhole = whole(p);
    bool inNear = mode != 0 && nearFace(p);

    int best = 255;
    if (main1(p, mode)) {
        best = 0;
    } else {
        for (int dx = 1; dx <= r && best == 255; dx++) {
            int xl = p.x - dx;
            int xr = p.x + dx;
            if ((xl >= 0 && main1(ivec2(xl, p.y), mode)) || (xr < size.x && main1(ivec2(xr, p.y), mode))) {
                best = dx;
            }
        }
    }

    int bestFar = 255;
    if (mode == 2) {
        if (farFace(p)) {
            bestFar = 0;
        } else {
            for (int dx = 1; dx <= r && bestFar == 255; dx++) {
                int xl = p.x - dx;
                int xr = p.x + dx;
                if ((xl >= 0 && farFace(ivec2(xl, p.y))) || (xr < size.x && farFace(ivec2(xr, p.y)))) {
                    bestFar = dx;
                }
            }
        }
    }
    fragColor = vec4(float(best) / 255.0, inWhole ? 1.0 : 0.0, float(bestFar) / 255.0, inNear ? 1.0 : 0.0);
}
