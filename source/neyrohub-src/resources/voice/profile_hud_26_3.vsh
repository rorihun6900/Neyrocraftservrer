#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

#ifdef IS_GUI
const vec3 PROFILE_COLORS[7] = vec3[](
    vec3(1.0), vec3(0.96, 0.98, 1.0), vec3(1.0, 0.42, 0.47),
    vec3(1.0, 0.77, 0.28), vec3(0.45, 0.76, 1.0),
    vec3(0.77, 0.54, 1.0), vec3(0.44, 0.89, 0.58)
);
#endif

void main() {
    vec4 position = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * position;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif

#ifdef IS_GUI
    ivec3 rgb = ivec3(round(Color.rgb * 255.0));
    int profileColor = rgb.b - 201;
    if (rgb.r == 9 && rgb.g == 109 && profileColor >= 0 && profileColor < 7) {
        // The client draws the action bar at (floor(width/2), height-68) with baseline offset -4.
        float mid = floor(1.0 / abs(ProjMat[0][0]) + 0.001);
        float bottom = round(2.0 / abs(ProjMat[1][1]));
        vec2 origin = vec2(mid, bottom - 68.0);
        vec2 local = position.xy - origin;
        if (local.x >= -1.0 && local.x <= 144.0 && local.y >= -6.0 && local.y <= 42.0) {
            position.xy = local + vec2(8.0, 12.0);
            gl_Position = ProjMat * position;
            vertexColor.rgb = PROFILE_COLORS[profileColor];
        }
    }
#endif

    texCoord0 = UV0;
}
