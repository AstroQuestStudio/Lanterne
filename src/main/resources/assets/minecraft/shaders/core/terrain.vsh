#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
// Lanterne — bornes de sprite dans l'atlas, ajoutees au binding 0 UNIQUEMENT sur les pipelines qui
// posent LANTERNE_TERRAIN_EXT (RenderPipelinesMixin, sur TERRAIN_SNIPPET/MULTIDRAW_TERRAIN_SNIPPET —
// jamais sur les pipelines OIT, qui partagent ce fichier mais gardent le format BLOCK d'origine).
// Un premier essai sans cette garde a casse la compilation des pipelines OIT au lancement client reel
// (ShaderCompileException : attrib UV3 sans element de tampon de sommet correspondant) — le define
// existe pour que CE fichier de shader reste valide pour les deux familles de pipelines a la fois.
//
// UV1 porte la TAILLE du sprite (u,v) mise a l'echelle sur un entier 16 bits signe (voir
// TerrainVertexFormat.packSpan) ; UV3 porte son coin MIN, en flottant exact. Tant que SectionCompiler
// n'emet que des quads non fusionnes (repeat implicite = 1), ces deux attributs ne changent RIEN au
// resultat final — voir terrain.fsh pour la formule et pourquoi.
#ifdef LANTERNE_TERRAIN_EXT
layout(location = 6) in ivec2 UV1;
layout(location = 7) in vec2 UV3;
#endif
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;
// Lanterne — voir plus haut. Passees telles quelles au fragment shader, qui fait la reconstruction.
#ifdef LANTERNE_TERRAIN_EXT
layout(location = 5) out vec2 lanterneSpriteMin;
layout(location = 6) out vec2 lanterneSpriteSize;
#endif

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    #ifndef OIT_ALPHA_ONLY
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    #else
    vertexColor = Color;
    #endif
    texCoord0 = UV0;
    #ifdef LANTERNE_TERRAIN_EXT
    lanterneSpriteMin = UV3;
    // 32767.0 : meme echelle que TerrainVertexFormat.packSpan, cote Java — les deux doivent rester
    // en phase, voir le Javadoc de cette methode pour pourquoi 32767 et pas 65535 (UV1 est signe).
    lanterneSpriteSize = vec2(float(UV1.x), float(UV1.y)) / 32767.0;
    #endif

    const float chunkFullyVisibleRange = 16.0;
    float dist = length(pos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
}
