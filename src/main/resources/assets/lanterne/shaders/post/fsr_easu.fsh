#version 330

// -----------------------------------------------------------------------------
// FSR 1.0 -- EASU (Edge Adaptive Spatial Upsampling)
//
// Remonte la toile reduite a la resolution de l'ecran. Douze echantillons, un
// noyau de Lanczos etire le long du contour local : c'est ce qui distingue FSR
// d'un simple etirement bilineaire, qui rendrait les aretes floues.
//
// Origine : algorithme publie par AMD (ffx_fsr1.h, licence MIT). Voir NOTICE.md.
// Ce portage est ecrit d'apres l'algorithme publie, pas copie ligne a ligne.
//
// Pourquoi ce fichier n'a pas un seul accent : la specification GLSL limite le
// jeu de caracteres source a l'ASCII, commentaires compris. Certains pilotes le
// tolerent, d'autres refusent de compiler -- et un shader qui ne compile pas
// chez un joueur sur dix est pire qu'un shader sans accents.
// -----------------------------------------------------------------------------

uniform sampler2D InSampler;

// Fourni d'office par PostPass : taille de la cible de sortie, puis taille de
// chaque entree, dans l'ordre de declaration du JSON.
layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// FSR prend le canal vert pour luminance. Ce n'est pas une approximation
// grossiere : le vert porte l'essentiel de la luminance percue, et l'economie
// de deux multiplications par echantillon se paie douze fois par pixel.
float chroma(vec3 c) {
    return c.g;
}

// Direction et anisotropie du contour local, mesurees autour d'un des quatre
// pixels voisins et ponderees par sa part bilineaire.
//
// lA..lE sont les luminances en croix autour du pixel central du quadrant :
// haut, gauche, centre, droite, bas.
void gauge(inout vec2 dir, inout float len, float w,
        float lA, float lB, float lC, float lD, float lE) {
    float dc = lD - lC;
    float cb = lC - lB;
    float spanX = max(max(abs(dc), abs(cb)), 1.0 / 32768.0);
    float dirX = lD - lB;
    dir.x += dirX * w;
    float lenX = clamp(abs(dirX) / spanX, 0.0, 1.0);
    len += lenX * lenX * w;

    float ec = lE - lC;
    float ca = lC - lA;
    float spanY = max(max(abs(ec), abs(ca)), 1.0 / 32768.0);
    float dirY = lE - lA;
    dir.y += dirY * w;
    float lenY = clamp(abs(dirY) / spanY, 0.0, 1.0);
    len += lenY * lenY * w;
}

// Le noyau : deux lobes, le second negatif, evalue dans le repere tourne du
// contour. Poids 1 au centre, 0 exactement a la coupure -- c'est cette paire
// d'egalites qui rend le filtre neutre quand le contour est plat.
void tap(inout vec3 sum, inout float weight,
        vec2 off, vec2 dir, vec2 stretch, float lobe, float clip, vec3 colour) {
    vec2 v = vec2(dot(off, dir), dot(off, vec2(-dir.y, dir.x))) * stretch;
    float d2 = min(dot(v, v), clip);
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lobe * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 1.5625 * wB - 0.5625;
    float w = wB * wA;
    sum += colour * w;
    weight += w;
}

void main() {
    vec2 source = max(InSize, vec2(1.0));
    vec2 invSource = 1.0 / source;

    // Position du pixel de sortie, exprimee en texels de la toile reduite.
    vec2 pp = texCoord * source - 0.5;
    vec2 fp = floor(pp);
    vec2 f = pp - fp;

    // Les douze echantillons, en croix elargie :
    //        b c
    //      e f g h
    //      i j k l
    //        n o
    // f, g, j, k encadrent le pixel demande ; les huit autres donnent au noyau
    // de quoi mesurer un contour sans deborder sur un troisieme rang.
    vec3 cb = texture(InSampler, (fp + vec2(0.5, -0.5)) * invSource).rgb;
    vec3 cc = texture(InSampler, (fp + vec2(1.5, -0.5)) * invSource).rgb;
    vec3 ce = texture(InSampler, (fp + vec2(-0.5, 0.5)) * invSource).rgb;
    vec4 cfFull = texture(InSampler, (fp + vec2(0.5, 0.5)) * invSource);
    vec3 cf = cfFull.rgb;
    vec3 cg = texture(InSampler, (fp + vec2(1.5, 0.5)) * invSource).rgb;
    vec3 ch = texture(InSampler, (fp + vec2(2.5, 0.5)) * invSource).rgb;
    vec3 ci = texture(InSampler, (fp + vec2(-0.5, 1.5)) * invSource).rgb;
    vec3 cj = texture(InSampler, (fp + vec2(0.5, 1.5)) * invSource).rgb;
    vec3 ck = texture(InSampler, (fp + vec2(1.5, 1.5)) * invSource).rgb;
    vec3 cl = texture(InSampler, (fp + vec2(2.5, 1.5)) * invSource).rgb;
    vec3 cn = texture(InSampler, (fp + vec2(0.5, 2.5)) * invSource).rgb;
    vec3 co = texture(InSampler, (fp + vec2(1.5, 2.5)) * invSource).rgb;

    float lb = chroma(cb);
    float lc = chroma(cc);
    float le = chroma(ce);
    float lf = chroma(cf);
    float lg = chroma(cg);
    float lh = chroma(ch);
    float li = chroma(ci);
    float lj = chroma(cj);
    float lk = chroma(ck);
    float ll = chroma(cl);
    float ln = chroma(cn);
    float lo = chroma(co);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    gauge(dir, len, (1.0 - f.x) * (1.0 - f.y), lb, le, lf, lg, lj);
    gauge(dir, len, f.x * (1.0 - f.y), lc, lf, lg, lh, lk);
    gauge(dir, len, (1.0 - f.x) * f.y, lf, li, lj, lk, ln);
    gauge(dir, len, f.x * f.y, lg, lj, lk, ll, lo);

    // Pas de contour mesurable : on retombe sur un noyau isotrope, c'est-a-dire
    // sur un Lanczos ordinaire. Sans ce garde-fou, la normalisation d'un vecteur
    // nul produit un NaN, et un NaN dans une cible de rendu se voit.
    float strength = dot(dir, dir);
    if (strength < 1.0 / 32768.0) {
        dir = vec2(1.0, 0.0);
    } else {
        dir *= inversesqrt(strength);
    }

    len *= 0.5;
    len *= len;

    float widen = 1.0 / max(max(abs(dir.x), abs(dir.y)), 1.0 / 32768.0);
    vec2 stretch = vec2(1.0 + (widen - 1.0) * len, 1.0 - 0.5 * len);
    float lobe = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clip = 1.0 / lobe;

    vec3 sum = vec3(0.0);
    float weight = 0.0;
    tap(sum, weight, vec2(0.0, -1.0) - f, dir, stretch, lobe, clip, cb);
    tap(sum, weight, vec2(1.0, -1.0) - f, dir, stretch, lobe, clip, cc);
    tap(sum, weight, vec2(-1.0, 1.0) - f, dir, stretch, lobe, clip, ci);
    tap(sum, weight, vec2(0.0, 1.0) - f, dir, stretch, lobe, clip, cj);
    tap(sum, weight, vec2(0.0, 0.0) - f, dir, stretch, lobe, clip, cf);
    tap(sum, weight, vec2(-1.0, 0.0) - f, dir, stretch, lobe, clip, ce);
    tap(sum, weight, vec2(1.0, 1.0) - f, dir, stretch, lobe, clip, ck);
    tap(sum, weight, vec2(2.0, 1.0) - f, dir, stretch, lobe, clip, cl);
    tap(sum, weight, vec2(2.0, 0.0) - f, dir, stretch, lobe, clip, ch);
    tap(sum, weight, vec2(1.0, 0.0) - f, dir, stretch, lobe, clip, cg);
    tap(sum, weight, vec2(1.0, 2.0) - f, dir, stretch, lobe, clip, co);
    tap(sum, weight, vec2(0.0, 2.0) - f, dir, stretch, lobe, clip, cn);

    // Repli bilineaire si la somme des poids s'annule. Ce cas ne devrait jamais
    // se presenter -- le poids central vaut un -- mais une division par zero
    // donnerait un pixel noir ou blanc franc, tres visible en mouvement.
    vec3 resolved = weight > 1.0e-4
            ? sum / weight
            : mix(mix(cf, cg, f.x), mix(cj, ck, f.x), f.y);

    // La borne du voisinage : le lobe negatif du noyau peut depasser la plage
    // des quatre voisins et creer un halo. Ramener le resultat dans leur
    // intervalle borne l'erreur -- y compris une erreur de constante dans ce
    // fichier, qui ne peut alors qu'adoucir ou durcir l'image, jamais la casser.
    vec3 low = min(min(cf, cg), min(cj, ck));
    vec3 high = max(max(cf, cg), max(cj, ck));

    fragColor = vec4(clamp(resolved, low, high), cfFull.a);
}
