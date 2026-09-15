package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.biome.Climate;

import fr.clubcitrouille.lanterne.core.Climat;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les bornes d'un nœud de l'arbre climatique, mises à plat.
 *
 * <h2>Où l'on se pose, et pourquoi là</h2>
 *
 * <p>Le profil désigne {@code Climate$RTree$SubTree.search}, mais ce n'est pas là que le temps
 * passe : {@code search} ne fait que deux choses, boucler sur six enfants et demander à chacun sa
 * distance. C'est {@code Node.distance} qui travaille, et la machine virtuelle l'incorpore dans
 * {@code search} — d'où le nom que rend l'échantillonneur. On se pose donc sur {@code Node}, un cran
 * plus bas que ce que le relevé annonce.
 *
 * <p>{@code Climate.RTree.Node} est une classe interne, invisible du compilateur depuis ce paquet :
 * la cible est écrite en toutes lettres. {@code verifieMixins} la relit contre le jar, ce qu'aucun
 * compilateur ne fait.
 *
 * <p>Tout ce que ce mixin nomme en Java est en revanche public : {@code Climate.Parameter} est un
 * record public, {@code distance(long[])} ne prend et ne rend que des primitifs, et le constructeur
 * ne reçoit qu'une {@code List}. Aucun type inaccessible n'apparaît dans une signature — c'est ce
 * qui rend ce point d'accroche possible sans acrobatie.
 *
 * <h2>Pourquoi un remplacement et non une injection</h2>
 *
 * <p>{@code distance} rend un {@code long}. Un {@code @Inject} annulable exigerait un
 * {@code CallbackInfoReturnable<Long>} : une allocation <b>et un boxing</b> par nœud visité, soit des
 * dizaines de millions par grille de chunks. Ce serait l'exact contraire du but. Le corps tient en
 * quinze lignes et se remplace en entier, comme {@code RedstoneEvaluatorMixin} l'a fait avant lui, et
 * pour la même raison mesurée.
 *
 * <p>Conséquence à connaître : un autre mod qui remplacerait la même méthode serait en conflit
 * ouvert. Aucun ne le fait — ni Noisium ni FastNoise ne touchent à ce chemin, et rien dans Minecraft
 * ni dans NeoForge n'appelle {@code Climate.DistanceMetric} ailleurs que {@code ParameterList}
 * lui-même (vérifié par recherche sur l'ensemble des sources de la 26.2).
 *
 * <p>Seconde conséquence, et c'est un angle mort à connaître : {@code verifieMixins} ne contrôle que
 * les cibles qui nomment leur méthode dans l'annotation. Un {@code @Overwrite} n'en nomme aucune, il
 * se contente d'en porter la signature — donc <b>il passe sans être vérifié</b>. Et le contrôleur
 * lit le fichier au lieu du code compilé, si bien qu'écrire ici le motif qu'il cherche suffirait à
 * lui faire réclamer une méthode imaginaire. La signature visée a donc été relue à la main dans le
 * jar de la 26.2 —
 * {@code javap -p 'net.minecraft.world.level.biome.Climate$RTree$Node'} rend bien
 * {@code protected long distance(long[])} — et c'est à refaire à chaque montée de version.
 *
 * <h2>Quand le tableau plat est construit, et pourquoi pas à la demande</h2>
 *
 * <p>À la construction du nœud, donc une fois par nœud et par monde, sur le fil qui lit les données.
 * Le construire paresseusement au premier appel aurait demandé un champ {@code volatile} — sans quoi
 * un fil de génération pourrait voir la référence du tableau sans en voir le contenu, et rendre des
 * distances nulles, donc des biomes faux. Le construire ici évite la question : l'arbre est bâti
 * avant que le moindre chunk ne soit demandé, et le passage du travail au bassin de fils établit la
 * relation d'antériorité qui rend le tableau visible.
 */
@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree$Node")
public abstract class ClimatMixin {
    @Shadow
    @Final
    protected Climate.Parameter[] parameterSpace;

    /** Les quatorze bornes du nœud, contiguës. {@code null} si la forme n'était pas attendue. */
    @Unique
    private long @Nullable [] lanterne$bornes;

    /** La contribution de la septième dimension, constante tant que la cible y vaut zéro. */
    @Unique
    private long lanterne$carreDuDecalage;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lanterne$aplatir(List<Climate.Parameter> espace, CallbackInfo callback) {
        this.lanterne$bornes = Climat.aplatir(this.parameterSpace);
        this.lanterne$carreDuDecalage = Climat.carreDuDecalage(this.parameterSpace);
    }

    /**
     * La distance du point visé à la boîte de ce nœud.
     *
     * @author Lanterne
     * @reason Le corps est la boucle la plus chaude de l'étape des biomes — dizaines de nœuds par
     *         position, 1536 positions par chunk. Il ne calcule pas trop : il suit sept références
     *         vers sept objets dispersés là où deux {@code long} voisins suffisent. Un
     *         {@code @Inject} annulable y allouerait un {@code CallbackInfoReturnable<Long>} par
     *         appel. Voir la javadoc de {@code core.Climat} pour la mesure et pour la démonstration
     *         que le résultat est le même nombre, pas un nombre équivalent.
     */
    @Overwrite
    protected long distance(long[] cible) {
        long[] bornes = this.lanterne$bornes;
        if (bornes == null || !Settings.climat()) {
            return this.lanterne$distanceDeVanilla(cible);
        }

        long distance = 0L;
        for (int dimension = 0, borne = 0; dimension < 6; dimension++, borne += 2) {
            long visee = cible[dimension];
            // Copie mot pour mot de Climate.Parameter.distance : above = target - max,
            // below = min - target, puis le premier des deux qui soit positif, ou zéro.
            long dessus = visee - bornes[borne + 1];
            long dessous = bornes[borne] - visee;
            long ecart = dessus > 0L ? dessus : Math.max(dessous, 0L);
            distance += ecart * ecart;
        }

        // La septième dimension. Sa cible vaut toujours zéro — TargetPoint.toParameterArray l'écrit
        // en dur — donc sa contribution est une constante du nœud. On ne s'en sert que lorsque c'est
        // vrai, et le test qui le vérifie coûte moins que le calcul qu'il évite.
        if (cible[6] == 0L) {
            distance += this.lanterne$carreDuDecalage;
        } else {
            long visee = cible[6];
            long dessus = visee - bornes[13];
            long dessous = bornes[12] - visee;
            long ecart = dessus > 0L ? dessus : Math.max(dessous, 0L);
            distance += ecart * ecart;
        }

        if (Climat.AUDIT) {
            // Constante repliée quand la variable d'environnement est absente : ces trois lignes
            // n'existent alors pas dans le code compilé à la volée.
            Climat.check(distance, this.lanterne$distanceDeVanilla(cible));
        }
        return distance;
    }

    /**
     * Le corps d'origine, mot pour mot.
     *
     * <p>Il sert deux fois : quand le module est éteint, et comme <b>référence du contrôle</b> sous
     * {@code LANTERNE_CLIMAT_AUDIT=1}. C'est la même méthode dans les deux rôles, ce qui interdit
     * qu'un module éteint et un contrôle vert reposent sur deux lectures différentes de vanilla.
     */
    @Unique
    private long lanterne$distanceDeVanilla(long[] cible) {
        long distance = 0L;
        for (int dimension = 0; dimension < 7; dimension++) {
            long ecart = this.parameterSpace[dimension].distance(cible[dimension]);
            distance += ecart * ecart;
        }
        return distance;
    }
}
