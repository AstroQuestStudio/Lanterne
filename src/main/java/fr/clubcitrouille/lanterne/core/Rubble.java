package fr.clubcitrouille.lanterne.core;

import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * L'enlèvement de matière : ce que coûte réellement une explosion.
 *
 * <h2>Le prix d'une seule TNT, poste par poste</h2>
 *
 * <p>Une charge qui explose fait quatre choses, et trois d'entre elles sont bien plus chères que ce
 * que leur description laisse croire :
 *
 * <ol>
 *   <li><b>tracer les rayons</b> — mille trois cent cinquante-deux rayons partent du centre, un par
 *       case de la surface d'un cube de seize, et avancent par pas de trois dixièmes de bloc ;</li>
 *   <li><b>blesser les entités</b> — chaque entité à portée est vue à travers un échantillonnage de sa
 *       boîte, chaque échantillon étant un lancer de rayon complet ;</li>
 *   <li><b>ramasser les objets</b> — chaque bloc détruit produit son butin, et chaque butin est
 *       comparé à tous ceux déjà collectés ;</li>
 *   <li><b>poser les objets</b> — un {@code ItemEntity} par pile.</li>
 * </ol>
 *
 * <p>Le premier poste est celui qu'on voit venir. Le deuxième est <b>quadratique</b> en nombre de
 * charges, et c'est lui qui met un serveur à genoux quand un joueur en fait sauter vingt mille. Le
 * troisième l'est aussi, en nombre de piles.
 *
 * <h2>Ce que ce module ne fait pas</h2>
 *
 * <p>Il ne réduit ni le nombre de rayons, ni le nombre d'échantillons, ni la portée. Une explosion
 * détruit exactement les mêmes blocs, lâche exactement le même butin et pousse les entités
 * exactement de la même façon. Tout ce qui suit est du travail <b>supprimé parce qu'il était déjà
 * connu</b>, ou du travail fait autrement.
 *
 * <p>C'est la condition pour qu'un mod d'optimisation soit installable sur un serveur où les gens
 * construisent des machines : une explosion qui détruit un bloc de moins casse des fermes.
 */
public final class Rubble {
    /**
     * Au-delà, on renonce au court-circuit de visibilité.
     *
     * <p>Le raccourci examine les sections traversées par le segment. Pour une explosion ordinaire
     * — portée quatre, donc segments de huit blocs au plus — cela fait une à quatre colonnes de
     * chunk. Une explosion de portée démesurée, produite par un mod, ferait un examen plus cher que
     * les lancers qu'il remplace : on le laisse passer.
     */
    private static final int MOST_SECTIONS = 32;

    /** Lancers de rayon évités parce que la ligne ne traversait que du vide. */
    private static long raysSkipped;

    /** Recherches d'état de bloc évitées pendant le tracé des rayons. */
    private static long lookupsSkipped;

    /** Explosions traitées, et blocs qu'elles ont désignés — comptés quel que soit l'état du module. */
    private static long explosions;
    private static long blocksTouched;

    private Rubble() {}

    /**
     * La ligne de vue est-elle certainement dégagée ?
     *
     * <h2>Vingt-sept lancers de rayon par entité, par explosion</h2>
     *
     * <p>{@code getSeenPercent} échantillonne la boîte de l'entité et lance un rayon depuis chaque
     * échantillon vers le centre de l'explosion. Pour une charge de dynamite — boîte de quatre-vingt-
     * dix-huit centièmes — le pas d'échantillonnage vaut un tiers, ce qui donne trois points par axe,
     * soit <b>vingt-sept lancers</b>. Chacun descend dans {@code BlockGetter.clip}, qui parcourt les
     * blocs un par un et interroge la palette à chaque case.
     *
     * <p>Multiplié par le nombre d'entités à portée, lui-même proportionnel au nombre de charges, et
     * par le nombre d'explosions, également proportionnel au nombre de charges : le coût de cette
     * seule fonction croît comme le <b>carré</b> du nombre de TNT. C'est la raison pour laquelle un
     * serveur tient mille charges et s'effondre à vingt mille.
     *
     * <h2>La question à laquelle on peut répondre sans lancer un rayon</h2>
     *
     * <p>Les vingt-sept rayons partent de points distants de moins d'un bloc et visent tous le même
     * point. Ils traversent donc, à peu de chose près, le même volume — un volume que l'on peut
     * encadrer exactement : la boîte qui contient la boîte de l'entité et le centre de l'explosion.
     *
     * <p>Si <b>toutes les sections</b> que cette boîte recouvre ne contiennent que de l'air, alors
     * aucun rayon ne peut rencontrer quoi que ce soit, et les vingt-sept rendent {@code MISS}. La
     * fonction rend alors exactement un, et on le sait en lisant un booléen par section — une donnée
     * que le jeu tient déjà à jour pour son propre compte.
     *
     * <p>Ce n'est pas une approximation : c'est la même réponse, obtenue en trois lectures au lieu de
     * quatre cents.
     *
     * <h2>Pourquoi c'est presque toujours vrai là où ça compte</h2>
     *
     * <p>Une explosion se produit dans de l'air — sinon la charge serait dans un bloc. Et la deuxième
     * explosion d'une chaîne se produit dans le trou qu'a fait la première. Plus il y a de charges,
     * plus le volume est vide, et plus ce raccourci s'applique : il est le plus efficace très
     * exactement dans le cas où le serveur souffre le plus.
     *
     * <p>Un fluide, une feuille, une dalle suffisent en revanche à rendre la section non vide, et le
     * raccourci renonce alors sans rien tenter. Il ne se trompe pas ; il ne sert simplement à rien.
     */
    public static boolean clearBetween(Level level, Vec3 center, Entity entity) {
        AABB box = entity.getBoundingBox();

        // Les points d'échantillonnage débordent légèrement de la boîte — le décalage que vanilla
        // applique en x et en z vaut moins d'un bloc. On élargit d'un bloc entier plutôt que de
        // recalculer ce décalage : le raccourci doit être plus prudent que le code qu'il remplace,
        // jamais moins.
        int minX = Mth.floor(Math.min(box.minX, center.x)) - 1;
        int maxX = Mth.floor(Math.max(box.maxX, center.x)) + 1;
        int minY = Mth.floor(Math.min(box.minY, center.y)) - 1;
        int maxY = Mth.floor(Math.max(box.maxY, center.y)) + 1;
        int minZ = Mth.floor(Math.min(box.minZ, center.z)) - 1;
        int maxZ = Mth.floor(Math.max(box.maxZ, center.z)) + 1;

        int sx0 = SectionPos.blockToSectionCoord(minX);
        int sx1 = SectionPos.blockToSectionCoord(maxX);
        int sy0 = SectionPos.blockToSectionCoord(minY);
        int sy1 = SectionPos.blockToSectionCoord(maxY);
        int sz0 = SectionPos.blockToSectionCoord(minZ);
        int sz1 = SectionPos.blockToSectionCoord(maxZ);

        if ((sx1 - sx0 + 1) * (sy1 - sy0 + 1) * (sz1 - sz0 + 1) > MOST_SECTIONS) {
            return false;
        }

        // Hors des limites verticales du monde, le jeu rend de l'air — mais la table des sections
        // n'a pas de case correspondante. Plutôt que de traiter ce cas à part, on renonce.
        if (sy0 < level.getMinSectionY() || sy1 > level.getMaxSectionY()) {
            return false;
        }

        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                ChunkAccess chunk = level.getChunk(sx, sz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    return false; // chunk absent : on ne sait rien, donc on ne promet rien
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = sy0; sy <= sy1; sy++) {
                    int index = level.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= sections.length || !sections[index].hasOnlyAir()) {
                        return false;
                    }
                }
            }
        }

        raysSkipped++;
        return true;
    }

    /**
     * Le troisième poste, laissé en place faute de preuve.
     *
     * <p>{@code addOrAppendStack} est également quadratique : chaque butin produit visite toute la
     * liste des piles déjà collectées, et chaque visite compare les composants de deux piles d'objets
     * — y compris quand ce sont des objets différents, qui ne fusionneront jamais.
     *
     * <p>Le corriger demanderait un mixin sur une classe interne privée et une interface de
     * contrebande pour y accéder. Une explosion détruit deux ou trois cents blocs, qui donnent
     * quelques dizaines de piles : le carré de quelques dizaines ne justifie pas cette machinerie
     * tant qu'aucun profil ne l'a désigné.
     *
     * <p>La note est ici pour que la piste ne soit pas perdue, et l'ordre du projet est respecté :
     * on mesure avant d'écrire, pas l'inverse.
     */
    public static void noteLookupSkipped() {
        lookupsSkipped++;
    }

    /**
     * Le travail réellement accompli par les explosions, compté que le module soit actif ou non.
     *
     * <h2>Un témoin d'équité, et non une mesure de gain</h2>
     *
     * <p>Ces deux compteurs ne servent pas à vanter le module : ils servent à décider si le banc a le
     * droit de conclure. Le protocole compare deux phases en supposant qu'elles subissent la même
     * charge ; pour une scène qui détruit le monde, cette supposition est fausse, et elle l'a été
     * pendant tous les verdicts de dynamite déjà rendus.
     *
     * <p>Le comptage a lieu <b>avant</b> le test d'activation, dans les deux branches du mixin. Il
     * mesure donc le monde, jamais le mod. Si la première phase fait sauter deux mille charges et la
     * seconde douze cents, aucun rapport de temps n'a de sens, et le banc doit le dire au lieu de
     * publier un chiffre.
     */
    public static void noteExplosion(int blocks) {
        explosions++;
        blocksTouched += blocks;
    }

    public static long explosions() {
        return explosions;
    }

    public static long blocksTouched() {
        return blocksTouched;
    }

    public static long raysSkipped() {
        return raysSkipped;
    }

    public static long lookupsSkipped() {
        return lookupsSkipped;
    }

    public static void reset() {
        raysSkipped = 0;
        lookupsSkipped = 0;
        explosions = 0;
        blocksTouched = 0;
    }
}
