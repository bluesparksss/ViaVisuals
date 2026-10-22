#version 150

#moj_import <light.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightColor;
flat out vec4 dye;
out vec2 texCoord0;
out vec2 texCoord1;
out vec4 normal;

// ViaVisuals: the raw dye colour reaches the fragment shader (armor trim keys), lighting is kept apart from it.
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = length((ModelViewMat * vec4(Position, 1.0)).xyz);
    vec4 light = texelFetch(Sampler2, UV2 / 16, 0);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color) * light;
    lightColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, vec4(1.0)) * light;
    dye = Color;
    texCoord0 = UV0;
    texCoord1 = UV1;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);
}
