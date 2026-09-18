#version 410
// Sans accent, ASCII pur -- meme raison que fsr_easu.fsh : certains pilotes refusent les octets
// hors ASCII dans un commentaire GLSL source.

// Le quad du prototype de portail : echantillonnage direct de la texture de destination, sans
// mise en forme -- voir portail_quad.vsh pour le contexte complet.

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(Sampler0, texCoord0);
}
