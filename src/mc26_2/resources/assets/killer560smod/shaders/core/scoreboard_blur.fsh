#version 330

// Custom Scoreboard background blur (killer560smod). Sampler0 is a copy of the main render target taken right before
// the GUI is drawn; this samples it around the fragment with a 9x9 gaussian kernel. The blur radius in framebuffer
// pixels is packed into the vertex colour's red channel; alpha 0 means "no copy yet" and draws nothing.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (vertexColor.a == 0.0) {
        discard;
    }
    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));
    vec2 uv = gl_FragCoord.xy * texel;
    float radius = max(1.0, vertexColor.r * 255.0);
    float spacing = radius / 4.0;
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int x = -4; x <= 4; x++) {
        for (int y = -4; y <= 4; y++) {
            float weight = exp(-float(x * x + y * y) / 8.0);
            sum += texture(Sampler0, uv + vec2(float(x), float(y)) * spacing * texel).rgb * weight;
            total += weight;
        }
    }
    fragColor = vec4(sum / total, 1.0);
}
