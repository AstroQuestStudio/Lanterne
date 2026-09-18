#version 410
// 410, pas 330 : "layout(location = N)" sur une entree/sortie de fragment shader n'est
// standardise qu'a partir de GLSL 410 (voir fsr_easu.fsh pour la preuve en conditions
// reelles de ce que 330 refuse).

// -----------------------------------------------------------------------------
// Accumulation temporelle -- premiere brique d'un remonteur facon FSR2/DLSS.
//
// Melange l'image courante (decalee par Jitter) avec l'historique reprojete par la
// matrice que Reproject calcule cote CPU. Sans rejet de desocclusion pour cette
// premiere version : un pixel qui vient d'etre decouvert (jamais vu a l'image
// precedente) accumule quand meme un peu d'historique invalide -- traine fantome
// possible sur un mouvement rapide, connue et documentee plutot que masquee.
//
// Pourquoi ce fichier n'a pas un seul accent : voir fsr_easu.fsh, meme raison
// (limite ASCII de la specification GLSL source, tous pilotes ne tolerent pas les
// octets hors ASCII dans les commentaires).
// -----------------------------------------------------------------------------

uniform sampler2D SceneSampler;
uniform sampler2D HistorySampler;

layout(std140) uniform Reprojection {
    mat4 ReprojMatrix;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 courant = texture(SceneSampler, texCoord);

    // Coordonnee normalisee du pixel courant, reprojetee vers l'image precedente.
    // Reproject.matrix() est deja au format attendu : vec4(ndcX, ndcY, ndcZ, 1.0) en
    // entree, division par w en sortie -- voir le javadoc de Reproject.matrix().
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 reprojected = ReprojMatrix * vec4(ndc, 0.0, 1.0);
    vec2 historyNdc = reprojected.xy / reprojected.w;
    vec2 historyUv = historyNdc * 0.5 + 0.5;

    // Hors cadre : rien a accumuler ici, l'historique n'a jamais vu ce point (bord
    // d'ecran qui vient d'apparaitre, ou caméra qui a beaucoup tourne). Repli sur
    // l'image courante seule plutot que d'echantillonner en dehors de la texture.
    if (historyUv.x < 0.0 || historyUv.x > 1.0 || historyUv.y < 0.0 || historyUv.y > 1.0) {
        fragColor = courant;
        return;
    }

    vec4 historique = texture(HistorySampler, historyUv);

    // Melange simple, sans rejet de desocclusion (voir l'en-tete du fichier). Poids
    // fixe : accorder plus de confiance a l'historique donne plus de nettete au prix
    // de plus de trainee, et l'inverse. 0.9 est un point de depart raisonnable pour
    // juger le principe avant d'affiner.
    fragColor = mix(courant, historique, 0.9);
}
