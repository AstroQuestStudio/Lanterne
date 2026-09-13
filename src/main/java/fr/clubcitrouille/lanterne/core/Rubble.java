package fr.clubcitrouille.lanterne.core;

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
 * * <p>Le premier poste est le seul que ce module traite, et c.est la mesure qui l.a voulu ainsi : le
 * court-circuit de visibilité, écrit pour le deuxième, ne s.est <b>jamais armé une seule fois</b> sur
 * six mille charges — il exigeait que la ligne du centre à l.entité ne traverse que des sections
 * vides, or une explosion a toujours du sol sous elle. Il a été retiré comme le module « vide », et
 * pour la même raison. Le troisième attend qu.un profil le désigne.
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
    /** Recherches d'état de bloc évitées pendant le tracé des rayons. */
    private static long lookupsSkipped;

    /** Explosions traitées, et blocs qu'elles ont désignés — comptés quel que soit l'état du module. */
    private static long explosions;
    private static long blocksTouched;

    private Rubble() {}

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

    public static long lookupsSkipped() {
        return lookupsSkipped;
    }

    public static void reset() {
        lookupsSkipped = 0;
        explosions = 0;
        blocksTouched = 0;
    }
}
