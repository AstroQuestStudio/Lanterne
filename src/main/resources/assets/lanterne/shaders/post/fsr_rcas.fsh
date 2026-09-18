#version 330

// -----------------------------------------------------------------------------
// FSR 1.0 -- RCAS (Robust Contrast Adaptive Sharpening)
//
// Seconde moitie de FSR. EASU reconstruit les contours ; RCAS leur rend le
// mordant que toute remontee d'echelle enleve. Ce n'est pas un masque flou :
// le gain est calcule par pixel de facon a ne JAMAIS depasser la plage des
// quatre voisins, donc a ne jamais produire de halo ni faire deborder une
// couleur hors de [0, 1].
//
// Origine : algorithme publie par AMD (ffx_fsr1.h, licence MIT). Voir NOTICE.md.
// Ce portage est ecrit d'apres l'algorithme publie, pas copie ligne a ligne.
//
// ASCII seulement : voir l'en-tete de fsr_easu.fsh.
// -----------------------------------------------------------------------------

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform RcasConfig {
    // x : nettete, de 0 (aucun effet) a 1 (le maximum que RCAS s'autorise).
    //
    // Un vec4 pour un seul nombre utile : un bloc std140 est TOUJOURS declare
    // par le pilote comme un multiple de seize octets, alors que le tampon que
    // le jeu alloue fait exactement la taille des champs listes dans le JSON.
    // Un seul float donnerait un tampon de quatre octets pour un bloc de seize,
    // ce qu'une couche de validation Vulkan signale. Trois flottants de rembourrage
    // coutent zero et ferment la question.
    vec4 Tuning;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float chroma(vec3 c) {
    return c.g;
}

void main() {
    vec2 texel = 1.0 / max(InSize, vec2(1.0));

    //     b
    //   d e f
    //     h
    vec3 b = texture(InSampler, texCoord + vec2(0.0, -texel.y)).rgb;
    vec3 d = texture(InSampler, texCoord + vec2(-texel.x, 0.0)).rgb;
    vec4 eFull = texture(InSampler, texCoord);
    vec3 e = eFull.rgb;
    vec3 f = texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb;
    vec3 h = texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb;

    vec3 low = min(min(b, d), min(f, h));
    vec3 high = max(max(b, d), max(f, h));

    // Le gain admissible, canal par canal : celui qui, applique au pixel
    // central, ne le fera sortir ni sous le plus sombre des voisins ni
    // au-dessus du plus clair. C'est de la que vient le mot ROBUSTE.
    vec3 ceiling = max(high, vec3(1.0 / 32768.0));
    vec3 toFloor = low / (4.0 * ceiling);
    vec3 toCeiling = (1.0 - min(high, vec3(1.0 - 1.0 / 32768.0))) / (4.0 * min(high, vec3(1.0 - 1.0 / 32768.0)) - 4.0);
    vec3 room = max(-toFloor, toCeiling);

    // -0,1875 = 0,25 - 1/16 : la limite dure de RCAS. Au-dela, le filtre
    // amplifierait le bruit d'echantillonnage autant que le detail.
    float gain = max(-0.1875, min(max(room.r, max(room.g, room.b)), 0.0)) * clamp(Tuning.x, 0.0, 1.0);

    // Debruitage : un pixel isole au milieu d'une plage plate est du bruit, pas
    // un detail. On mesure son ecart a la moyenne de ses voisins, rapporte a
    // l'amplitude locale, et on retire d'autant de gain. Sans cette etape, RCAS
    // fait scintiller le ciel et le sable.
    float lb = chroma(b);
    float ld = chroma(d);
    float le = chroma(e);
    float lf = chroma(f);
    float lh = chroma(h);
    float spread = max(max(lb, ld), max(le, max(lf, lh)))
            - min(min(lb, ld), min(le, min(lf, lh)));
    float noise = clamp(abs(0.25 * (lb + ld + lf + lh) - le) / max(spread, 1.0 / 32768.0), 0.0, 1.0);
    gain *= 1.0 - 0.5 * noise;

    vec3 sharpened = (gain * (b + d + f + h) + e) / (4.0 * gain + 1.0);

    fragColor = vec4(clamp(sharpened, 0.0, 1.0), eFull.a);
}
