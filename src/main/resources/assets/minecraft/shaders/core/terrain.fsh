#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;
// Lanterne — voir terrain.vsh pour pourquoi ceci est conditionnel (pipelines OIT exclus).
// min/size du rectangle de sprite dans l'atlas, en coordonnees UV.
#ifdef LANTERNE_TERRAIN_EXT
layout(location = 5) in vec2 lanterneSpriteMin;
layout(location = 6) in vec2 lanterneSpriteSize;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

// Lanterne — reconstruit l'UV a echantillonner a partir des bornes du sprite d'origine, plutot que
// d'utiliser texCoord0 tel quel.
//
// Tant qu'aucune face n'est fusionnee, texCoord0 reste l'UV absolu d'origine (voir
// TerrainVertexFormat.putQuad : UV0 n'est jamais recalcule, seulement copie du BakedQuad). Dans ce
// cas (local dans [0,1] a chaque sommet), cette fonction retombe exactement sur texCoord0 — c'est le
// jalon verifie visuellement en premier : le format est etendu, mais RIEN ne change a l'image tant que
// la fusion elle-meme n'est pas ecrite.
//
// Une fois des faces fusionnees (repeat > 1, local pouvant depasser 1 entre les coins du quad
// fusionne), fract() fait boucler la lecture a l'interieur du seul rectangle du sprite d'origine —
// au lieu d'etirer une seule tuile sur toute la largeur fusionnee (le defaut documente dans
// notes/rendu-terrain-vs-sodium.md qui a bloque les passes precedentes).
// Absent (pipelines OIT, qui n'ont pas LANTERNE_TERRAIN_EXT — voir terrain.vsh) : identite pure,
// aucun changement de comportement par rapport a avant cette passe.
#ifdef LANTERNE_TERRAIN_EXT
vec2 lanterneWrappedUv(vec2 uv) {
    if (lanterneSpriteSize.x <= 0.0 || lanterneSpriteSize.y <= 0.0) {
        return uv;
    }
    vec2 local = (uv - lanterneSpriteMin) / lanterneSpriteSize;
    return lanterneSpriteMin + fract(local) * lanterneSpriteSize;
}
#endif

void main() {
    #ifdef LANTERNE_TERRAIN_EXT
    vec2 sampleUv = lanterneWrappedUv(texCoord0);
    #else
    vec2 sampleUv = texCoord0;
    #endif
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, sampleUv, 1.0f / TextureSize) : sampleNearest(Sampler0, sampleUv, 1.0f / TextureSize)) * vertexColor;
    #ifndef OIT_ALPHA_ONLY
    color = mix(FogColor * vec4(1, 1, 1, color.a), color, chunkVisibility);
    #endif
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
