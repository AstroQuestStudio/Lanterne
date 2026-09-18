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
// cas (local dans [0,1] a chaque sommet), cette fonction retombe exactement sur texCoord0.
//
// Une fois des faces fusionnees (repeat > 1, local pouvant depasser 1 entre les coins du quad
// fusionne), fract() fait boucler la lecture a l'interieur du seul rectangle du sprite d'origine —
// au lieu d'etirer une seule tuile sur toute la largeur fusionnee (le defaut documente dans
// notes/rendu-terrain-vs-sodium.md qui a bloque les passes precedentes).
//
// <h2>Le bandes grises trouvees par le premier vrai test de fusion — et pourquoi</h2>
//
// Verifie a l'oeil (Snap) : un sol de neige fusionne se rendait en bandes GRISES, pas blanches.
// Cause, lue dans texture_sampling.glsl (assets vanilla extraits du jar) : sampleNearest/sampleRGSS
// calculent dFdx(uv)/dFdy(uv) sur l'UV DEJA enroule par fract(). A chaque bord de tuile a l'interieur
// d'un quad fusionne, fract() saute instantanement de ~1 a ~0 — une discontinuite que le materiel
// interprete comme "ce texel est minifie a l'extreme", et qui selectionne le mip le plus haut (une
// moyenne floue de tout l'atlas, grise) exactement sur la colonne de pixels du bord de tuile.
//
// Le correctif : calculer les derivees ecran (dFdx/dFdy) sur la coordonnee CONTINUE (avant fract()),
// jamais sur le resultat enroule — local = (uv-min)/size est lineaire sur tout le quad fusionne, donc
// sans discontinuite, meme si sa valeur depasse 1. dFdx(local)*size vaut EXACTEMENT dFdx(uv) quand
// aucun repli n'a lieu (min/size sont des constantes par primitive) : ce chemin est donc aussi correct
// pour un quad non fusionne, pas seulement un correctif pour les gros quads.
//
// RGSS (l'anticrenelage par supersampling) n'expose aucune variante a derivees explicites — la
// toucher aurait exige de modifier texture_sampling.glsl, un fichier partage par bien plus que le
// terrain. Le terrain patche utilise donc sampleNearest avec derivees explicites dans tous les cas :
// une regression mineure de qualite (pas de supersampling sur le terrain fusionnable), documentee
// plutot que cachee, contre un bogue reel et deja vu a l'oeil.
//
// Absent (pipelines OIT, qui n'ont pas LANTERNE_TERRAIN_EXT — voir terrain.vsh) : identite pure,
// aucun changement de comportement par rapport a avant cette passe.
#ifdef LANTERNE_TERRAIN_EXT
vec4 lanterneSample(sampler2D source, vec2 uv, vec2 pixelSize) {
    if (lanterneSpriteSize.x <= 0.0 || lanterneSpriteSize.y <= 0.0) {
        return sampleNearest(source, uv, pixelSize);
    }
    vec2 local = (uv - lanterneSpriteMin) / lanterneSpriteSize;
    vec2 du = dFdx(local) * lanterneSpriteSize;
    vec2 dv = dFdy(local) * lanterneSpriteSize;
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    vec2 sampleUv = lanterneSpriteMin + fract(local) * lanterneSpriteSize;
    return sampleNearest(source, sampleUv, pixelSize, du, dv, texelScreenSize);
}
#endif

void main() {
    #ifdef LANTERNE_TERRAIN_EXT
    vec4 color = lanterneSample(Sampler0, texCoord0, 1.0f / TextureSize) * vertexColor;
    #else
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    #endif
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
