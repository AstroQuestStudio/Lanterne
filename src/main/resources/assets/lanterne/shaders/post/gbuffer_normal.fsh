#version 410
// 410 : layout(location = N) sur une entree/sortie de fragment shader n'est standardise qu'a
// partir de GLSL 410 -- meme raison que temporal_accumulate.fsh et fsr_easu.fsh.

// -----------------------------------------------------------------------------
// Reconstruction de normale par fragment -- le premier vrai G-buffer de ce module.
//
// Nuancier n'accroche que l'etage FINAL de composition (voir son Javadoc de classe) : le monde
// est deja aplati en couleur+profondeur au moment ou un nuancier tiers peut agir. Ajouter une
// sortie de normale au VRAI passage de rendu terrain est un chantier distinct (et un autre fork
// y travaille peut-etre deja -- voir client/terrain/TerrainVertexFormat.java, qui n'ecrit meme
// pas la normale du sommet dans son format etendu aujourd'hui). Cette passe contourne la limite
// exactement comme le screen-space ambient occlusion "sans G-buffer" le fait depuis des annees
// dans les moteurs qui n'exposent pas de normale geometrique en post-effet : on la RECONSTRUIT
// depuis la seule profondeur, par derivee d'ecran.
//
// POURQUOI CA EXIGE LA VRAIE MATRICE DE PROJECTION, PAS UNE CONSTANTE DU JSON
//
// Reconstruire une position vue depuis la profondeur d'un pixel demande d'inverser la matrice
// de projection REELLE de la camera -- celle qui change a chaque fois que le joueur modifie son
// FOV, ou simplement respire (le FOV dynamique de la course/tir a l'arc). PostPass n'offre aucun
// moyen de faire varier un uniforme d'une image a l'autre (voir Accumulate.java, meme limite,
// meme solution) : InverseProjection vient donc de CameraUniforms.upload, un tampon transitoire
// reecrit chaque image, exactement comme Accumulate le fait pour sa matrice de reprojection.
//
// CE QUE CETTE PASSE PRODUIT, ET POURQUOI LE CANAL ALPHA PORTE LA PROFONDEUR
//
// RGB : la normale vue, en composantes SIGNEES (RGBA16_FLOAT n'a pas besoin du remappage
// *0.5+0.5 qu'un format UNORM imposerait). A : la profondeur brute lue ici meme, recopiee telle
// quelle. Un nuancier tiers qui veut la profondeur n'a donc JAMAIS besoin de "use_depth_buffer"
// sur lanterne:scene -- utile parce que ce nom ne pointe pas toujours vers une cible qui A une
// profondeur (Scene.accum, la sortie de l'accumulation temporelle quand elle est active -- reglage
// par defaut -- n'en a pas). lanterne:normal, lui, vient TOUJOURS de la vraie toile (voir
// Gbuffer.java / Scene.give), donc son canal alpha est fiable quel que soit le chemin
// EASU/RCAS/accumulation emprunte en aval.
//
// Sans accent, ASCII pur -- meme raison que fsr_easu.fsh : certains pilotes refusent les octets
// hors ASCII dans un commentaire GLSL source.
// -----------------------------------------------------------------------------

uniform sampler2D DepthSampler;

layout(std140) uniform CameraMatrices {
    mat4 ViewRotation;
    mat4 Projection;
    mat4 InverseProjection;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;

    // vec4(ndcX, ndcY, ndcZ, 1.0), profondeur INVERSEE lue telle quelle -- meme convention que
    // Reproject.matrix() documente pour l'inverse de cette meme matrice de projection (voir son
    // javadoc : "la coordonnee normalisee en z se lit donc telle quelle dans le tampon").
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 clip = vec4(ndc, depth, 1.0);
    vec4 viewPos4 = InverseProjection * clip;
    vec3 viewPos = viewPos4.xyz / viewPos4.w;

    // Normale plate par derivees d'ecran : le triangle implicite forme par ce fragment et ses
    // deux voisins immediats en X et en Y. Ne demande aucune normale de sommet -- ce que le
    // terrain n'ecrit justement pas aujourd'hui (voir TerrainVertexFormat.putQuad, qui LIT le
    // normal du quad mais ne l'ecrit dans aucun element du format).
    vec3 normal = normalize(cross(dFdx(viewPos), dFdy(viewPos)));

    // Un ciel (profondeur au plancher du buffer, donc tres proche de 0.0 en Z inverse) n'a pas de
    // surface reelle : la derivee y est instable (deux pixels de "lointain" n'ont pas de relation
    // geometrique). On l'ecrit tel quel plutot que de le masquer -- a charge du nuancier
    // consommateur de decider, via le canal alpha, s'il veut l'ignorer.
    fragColor = vec4(normal, depth);
}
