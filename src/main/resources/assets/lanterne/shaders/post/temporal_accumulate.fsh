#version 410
// 410, pas 330 : "layout(location = N)" sur une entree/sortie de fragment shader n'est
// standardise qu'a partir de GLSL 410 (voir fsr_easu.fsh pour la preuve en conditions
// reelles de ce que 330 refuse).

// -----------------------------------------------------------------------------
// Accumulation temporelle -- premiere brique d'un remonteur facon FSR2/DLSS.
//
// Melange l'image courante (decalee par Jitter) avec l'historique reprojete par la
// matrice que Reproject calcule cote CPU.
//
// REJET DE DESOCCLUSION ET DE FLOU DE MOUVEMENT, verifie a l'oeil (captures Snap,
// camera en mouvement via Vertige, comparaison avec/sans cette passe, aux crans 220
// puis 90 deg/s) : la toute premiere version melangeait 90% d'historique sans aucune
// condition -- un panoramique rapide laissait un flou/une trainee nette sur les bords
// a fort contraste (contour des nuages), absente quand cette passe est desactivee
// (EASU/RCAS seuls). Deux causes distinctes, deux remedes ci-dessous : le serrage de
// voisinage rejette un historique carrement FAUX (desocclusion) ; le poids adaptatif
// au mouvement combat le flou d'un historique VALIDE mais reechantillonne trop de
// fois par filtrage bilineaire -- le premier ne suffisait pas seul, verifie a l'oeil.
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

    // Serrage de voisinage (neighborhood clamping) : la methode standard pour rejeter
    // un historique desocclu SANS tampon de vecteurs de mouvement (c'est ce que fait le
    // TAA d'Unreal en l'absence d'un rejet plus fin). On lit les huit voisins du pixel
    // courant dans SceneSampler, on en tire une boite [min;max] par canal incluant le
    // centre, et on y force l'historique AVANT de le meler. Un historique qui ne
    // correspond plus a rien de local (bord qui vient de se decouvrir, panoramique
    // rapide) est ramene vers ce que l'image courante montre a cet endroit, au lieu de
    // trainer pendant des dizaines d'images -- a poids 0.9, 0.9^n ne descend sous 5%
    // qu'a la 29e image, soit une demi-seconde a 60 Hz, ce qui se voyait.
    vec2 texel = 1.0 / vec2(textureSize(SceneSampler, 0));
    vec4 minC = courant;
    vec4 maxC = courant;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) {
                continue;
            }
            vec4 voisin = texture(SceneSampler, texCoord + vec2(dx, dy) * texel);
            minC = min(minC, voisin);
            maxC = max(maxC, voisin);
        }
    }
    historique = clamp(historique, minC, maxC);

    // Poids ADAPTATIF au mouvement apparent, et non plus fixe : le serrage ci-dessus
    // rejette un historique qui ne correspond plus a rien de local, mais meme un
    // historique VALIDE (dans la boite) reste un rejet repete par filtrage bilineaire
    // -- chaque reprojection reechantillonne HistorySampler a une position sous-
    // pixellaire differente, et cet adoucissement s'accumule image apres image. Verifie
    // a l'oeil : sur un panoramique rapide mais plausible (Vertige a 90 deg/s), les
    // bords a fort contraste (nuages) ressortaient nettement plus flous qu'en EASU/RCAS
    // seuls, MEME apres le serrage de voisinage -- ce n'est pas de la trainee fantome
    // (l'historique n'est pas FAUX, juste reechantillonne en trop), donc une boite
    // [min;max] ne peut rien y faire : seul reduire LE POIDS pendant le mouvement le
    // peut.
    //
    // On mesure donc le deplacement apparent, en pixels de la toile, entre le pixel
    // courant et son point reprojete, et on l'utilise pour attenuer la confiance en
    // l'historique. A l'arret (camera immobile, c'est le cas ou l'accumulation vaut le
    // plus : voir Jitter, dont le sous-decalage ne sert a rien SANS accumulation), le
    // poids reste a 0.9 et la super-resolution temporelle fait son travail en entier.
    // Des que la camera bouge, le poids retombe vite -- a 8 px de deplacement (environ
    // un tiers de degre de rotation ecran a la resolution testee), il est deja sous
    // 0.3, et l'image redevient presque entierement celle d'EASU/RCAS seuls.
    vec2 canvasSize = vec2(textureSize(SceneSampler, 0));
    float motionPixels = length((historyUv - texCoord) * canvasSize);
    float weight = 0.9 * exp2(-motionPixels * 0.35);

    fragColor = mix(courant, historique, weight);
}
