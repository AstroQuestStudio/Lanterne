#version 330

// -----------------------------------------------------------------------------
// Anticrenelage de contour, applique AVANT la remontee d'echelle.
//
// POURQUOI CETTE PASSE EXISTE
//
// La documentation d'AMD pose une condition d'emploi a FSR 1.0, sous le titre
// « Expected input », et elle est sans ambiguite :
//
//     "Image should already be well anti-aliased by a technique like TAA,
//      MSAA etc."
//
// Ce n'est pas une recommandation, c'est une precondition. EASU est un filtre
// de remontee qui suppose des bords propres : nourri de marches d'escalier, il
// ne les lisse pas, il les AGRANDIT -- puis RCAS, qui raffermit les contours,
// raffermit aussi les marches. Or Minecraft n'a aucun anticrenelage par defaut.
// Sans cette passe, on donne donc a FSR exactement ce que son auteur dit de ne
// pas lui donner, et le defaut se voit d'autant plus que le facteur est fort.
//
// ORIGINE
//
// L'idee -- detecter un contour par le contraste de luminance, en deduire une
// direction, et fondre le long de cette direction -- est celle de FXAA, publiee
// par Timothy Lottes (NVIDIA). AUCUNE LIGNE N'EN EST COPIEE, et ce n'est pas un
// scrupule de style : le fichier fxaa3_11.h ne porte pas de licence de
// redistribution, seulement un avis « all rights reserved » suivi d'un
// disclaimer de garantie. On ne peut donc pas le reprendre. Un algorithme ne se
// protege pas, une ecriture si -- alors celle-ci est la notre. Voir NOTICE.md.
//
// CE QU'ELLE COUTE
//
// Une passe plein ecran A LA RESOLUTION REDUITE, c'est-a-dire sur 45 % des
// pixels au prereglage Qualite. Onze echantillons au pire, trois au mieux :
// les zones plates sortent au premier test et ne paient que cinq lectures.
//
// ASCII seulement : voir l'en-tete de fsr_easu.fsh.
// -----------------------------------------------------------------------------

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Luminance percue. On garde les trois canaux ici, a la difference d'EASU qui
// se contente du vert : EASU pese douze echantillons par pixel et l'economie s'y
// justifie, alors qu'ici un contour bleu sur fond noir doit etre vu comme un
// contour -- sinon on ne lisse pas ce qui se voit le plus dans une grotte.
float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 texel = 1.0 / InSize;

    vec3 rgbM = texture(InSampler, texCoord).rgb;
    float lumaM = luma(rgbM);

    // Les quatre diagonales. On les prefere aux quatre orthogonales parce que
    // ce sont elles qui donnent directement les deux differences croisees dont
    // la direction du contour se deduit, sans second jeu de lectures.
    float lumaNW = luma(texture(InSampler, texCoord + vec2(-1.0, -1.0) * texel).rgb);
    float lumaNE = luma(texture(InSampler, texCoord + vec2( 1.0, -1.0) * texel).rgb);
    float lumaSW = luma(texture(InSampler, texCoord + vec2(-1.0,  1.0) * texel).rgb);
    float lumaSE = luma(texture(InSampler, texCoord + vec2( 1.0,  1.0) * texel).rgb);

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));
    float range = lumaMax - lumaMin;

    // Deux seuils et non un. Le seuil ABSOLU laisse passer le bruit de codage
    // dans les zones sombres, ou l'oeil ne distingue rien et ou lisser ne
    // ferait que ternir. Le seuil RELATIF adapte la sensibilite a la luminosite
    // locale : un ecart de 0,05 est un contour franc sur un sol d'ardoise et
    // n'est rien du tout dans un ciel de midi.
    if (range < max(0.0312, lumaMax * 0.125)) {
        fragColor = vec4(rgbM, 1.0);
        return;
    }

    // Direction PERPENDICULAIRE au contour, deduite des differences croisees.
    // Le signe de x est inverse pour que le vecteur pointe le long du contour
    // et non a travers : fondre a travers un contour l'effacerait au lieu de
    // l'adoucir.
    vec2 dir;
    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));

    // Sans ce terme, une direction presque nulle -- un contour presque parallele
    // a un axe -- se normaliserait en un vecteur enorme, et l'on irait chercher
    // des couleurs a dix pixels de la. Le terme est proportionnel a la
    // luminance locale, pour que le garde-fou suive le contraste.
    float reduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * 0.03125, 0.0078125);
    float rcpMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * rcpMin, vec2(-8.0), vec2(8.0)) * texel;

    // Deux paires d'echantillons. La paire serree (A) est toujours sure ; la
    // paire large (B) lisse mieux les contours peu inclines mais peut mordre
    // sur un voisin qui n'appartient pas au meme bord.
    vec3 rgbA = 0.5 * (texture(InSampler, texCoord + dir * (1.0 / 3.0 - 0.5)).rgb
                     + texture(InSampler, texCoord + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 rgbB = rgbA * 0.5 + 0.25 * (texture(InSampler, texCoord + dir * -0.5).rgb
                                   + texture(InSampler, texCoord + dir *  0.5).rgb);

    // Le garde-fou qui fait toute la difference entre « lisse » et « bave » :
    // si la paire large sort de l'intervalle de luminance du voisinage, c'est
    // qu'elle est allee chercher une couleur etrangere au contour. On retombe
    // alors sur la paire serree, qui ne peut pas mentir.
    float lumaB = luma(rgbB);
    fragColor = vec4((lumaB < lumaMin || lumaB > lumaMax) ? rgbA : rgbB, 1.0);
}
