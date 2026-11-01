#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;

layout(std140) uniform MotionBlurConfig {
    vec4 BlendParams;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 current = texture(InSampler, texCoord).rgb;
    vec3 previous = texture(PrevSampler, texCoord).rgb;

    float blend = clamp(BlendParams.x, 0.0, 0.99);
    vec3 delta = current - previous;
    vec3 stepAmount = abs(delta) * (1.0 - blend);

    // 8-bit targets: always move at least one code value toward the current frame so trails fully fade
    stepAmount = max(stepAmount, min(abs(delta), vec3(1.0 / 255.0)));

    fragColor = vec4(previous + sign(delta) * stepAmount, 1.0);
}
