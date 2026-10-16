#version 330

// A smooth radial glow (killer560smod mainmenu/SceneShaders): the stepped glow's nested ellipses averaged into one linear
// falloff (flat for the first few percent, zero at the box's ellipse), its alpha dithered with the blue-noise tile in
// Sampler0 (rows 0-63) so a faint falloff over a dark picture shows no rings.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float f = min(1.0, 1.15 * (1.0 - length(texCoord0)));
    if (f <= 0.0) {
        discard;
    }
    float n = texelFetch(Sampler0, ivec2(gl_FragCoord.xy) & 63, 0).r;
    float a = vertexColor.a * f + (n - 0.5) * (1.5 / 255.0) * min(1.0, f * 20.0);
    if (a <= 0.0) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, min(a, 1.0)) * ColorModulator;
}
