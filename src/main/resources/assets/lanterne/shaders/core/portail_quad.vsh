#version 410
// 410, sans inclusion -- meme raison et meme choix que post/gbuffer_normal.fsh : layout(location)
// standardise a partir de 410 seulement, et aucun #moj_import/#include a resoudre ici. Ce nuanceur
// aurait pu reutiliser core/position_tex.vsh de vanilla (meme format DefaultVertexFormat.POSITION_TEX,
// meme usage) mais celui-ci depend de #include <minecraft:dynamictransforms.glsl> et
// <minecraft:projection.glsl> -- une resolution d'inclusion que ce mod n'a pas verifiee pour son
// propre chargeur de nuanceurs (voir Gbuffer.SHADER_SOURCE, dont getInclude() leve une erreur
// deliberee : aucun #moj_import n'y est jamais attendu). Dupliquer les ~10 lignes utiles plutot que
// de parier sur une resolution d'inclusion non verifiee.

// -----------------------------------------------------------------------------
// Le quad du prototype de portail -- un rectangle statique dans le monde, texture avec la vue
// rendue depuis la camera de destination (voir PortailPrototype.java).
//
// Position deja RELATIVE A LA CAMERA REELLE (position - cameraState.pos, calcule cote Java avant
// l'upload du sommet) : ce moteur rend a origine flottante, comme le confirme la matrice de
// terrain de LevelRenderer.render (lecture du bytecode/source decompilee, voir
// notes/immersive-portals-faisabilite.md) -- ViewRotation ci-dessous ne porte donc AUCUNE
// translation, seulement la rotation de la camera reelle au moment du composite.
// -----------------------------------------------------------------------------

layout(std140) uniform CameraMatrices {
    mat4 ViewRotation;
    mat4 Projection;
    mat4 InverseProjection; // non utilise ici -- disposition gardee identique a CameraUniforms.upload
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

layout(location = 0) out vec2 texCoord0;

void main() {
    gl_Position = Projection * ViewRotation * vec4(Position, 1.0);
    texCoord0 = UV0;
}
