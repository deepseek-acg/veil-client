#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GrayscaleConfig {
    float Amount;
};

layout(location = 0) out vec4 fragColor;

// Rec. 601 luma: the weighted brightness the eye reads as "how light is this colour", so a red
// flower and a grey block of the same brightness come out the same shade.
const vec3 Luma = vec3(0.299, 0.587, 0.114);

void main(){
    vec4 diffuseColor = texture(InSampler, texCoord);
    float luma = dot(diffuseColor.rgb, Luma);
    fragColor = vec4(mix(diffuseColor.rgb, vec3(luma), Amount), 1.0);
}
