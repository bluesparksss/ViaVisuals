#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightColor;
flat in vec4 dye;
in vec2 texCoord0;
in vec2 texCoord1;
in vec4 normal;

out vec4 fragColor;

// ViaVisuals armor trims: see scripts/generate_armor_trims.py for the layout of the leather armor textures.
const int BASES = 7;
const ivec3 MARKERS[7] = ivec3[7](ivec3(12, 12, 12), ivec3(15, 11, 2), ivec3(2, 12, 13), ivec3(3, 2, 3), ivec3(6, 6, 7), ivec3(4, 12, 3), ivec3(13, 6, 4));
const ivec2 REGION = ivec2(896, 256);
const int SCALE = 16;
const int NO_PATTERN = 31;
const int LEVEL_STEP = 28;

ivec2 tile(int index) {
    return REGION + ivec2((index % 2) * 64, (index / 2) * 32);
}

void main() {
    ivec2 size = textureSize(Sampler0, 0);
    if (size == ivec2(128, 64)) {
        // the leather overlay pass: plain leather draws its overlay in the base pass
        discard;
    }
    vec4 color;
    if (size == ivec2(64 * SCALE, 32 * SCALE)) {
        ivec3 c = ivec3(floor(dye.rgb * 255.0 + 0.5));
        ivec3 hi = c >> 4;
        ivec3 lo = c & 15;
        int base = -1;
        for (int i = 0; i < BASES; i++) {
            if (hi == MARKERS[i]) {
                base = i;
            }
        }
        int row = lo.r;
        int pattern = lo.g + ((lo.b & 1) << 4);
        int check = lo.b >> 1;
        bool keyed = base >= 0 && row <= 14 && (pattern < 18 || pattern == NO_PATTERN)
            && check == ((row + 3 * pattern + 5 * base) & 7);
        ivec2 px = ivec2(clamp(floor(texCoord0 * vec2(64.0, 32.0)), vec2(0.0), vec2(63.0, 31.0)));
        if (keyed) {
            vec4 tex = texelFetch(Sampler0, tile(base) + px, 0);
            if (pattern != NO_PATTERN) {
                float v = texelFetch(Sampler0, tile(8 + pattern / 3) + px, 0)[pattern % 3];
                int level = int(floor(v * 255.0 / float(LEVEL_STEP) + 0.5));
                if (level > 0) {
                    tex = vec4(texelFetch(Sampler0, tile(14) + ivec2(level - 1, row), 0).rgb, 1.0);
                }
            }
            color = tex * lightColor * ColorModulator;
        } else {
            color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
            vec4 overlay = texelFetch(Sampler0, tile(15) + px, 0);
            if (overlay.a > 0.5 && color.a > 0.1) {
                color = vec4(overlay.rgb * lightColor.rgb, color.a) * ColorModulator;
            }
        }
    } else {
        color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    }
    if (color.a < 0.1) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
