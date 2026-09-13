package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Qui a changé, et où : l'infrastructure qui rend le sommeil exact.
 *
 * <h2>Le problème que tout sommeil rencontre</h2>
 *
 * <p>Ce mod espace les ticks de ce qui est loin ou noyé dans le nombre, et il l'assume : on dégrade ce
 * qui ne se distingue pas. Mais il existe une autre catégorie, plus intéressante — <b>ce dont on peut
 * démontrer que rien ne se passera</b>.
 *
 * <p>Un objet posé sur de la pierre, immobile, hors de l'eau, va refaire vingt fois par seconde le
 * même calcul et obtenir vingt fois le même résultat. Ce n'est pas une dégradation acceptable qu'on
 * cherche ici, c'est une dépense <em>entièrement</em> gratuite qu'on supprime.
 *
 * <p>Sauf qu'elle n'est gratuite que <b>tant que rien ne change autour</b>. Si un joueur mine le bloc
 * qui porte l'objet, il doit tomber ; si de l'eau arrive, il doit flotter. Un sommeil qui ignorerait
 * cela ferait flotter les objets en l'air — le genre de défaut qu'un joueur voit en une seconde et
 * qu'il n'excuse pas.
 *
 * <h2>Le réveil par évènement plutôt que par réexamen</h2>
 *
 * <p>La solution facile serait de réexaminer périodiquement — une fois par seconde, disons. Elle
 * marche, et elle coûte le prix d'un objet qui flotte jusqu'à une seconde sous les yeux du joueur.
 * C'est inacceptable pour ce qui est proche, donc il faudrait interdire le sommeil à proximité, donc
 * renoncer au cas qui compte le plus : la base du joueur, là où les objets s'accumulent.
 *
 * <p>On préfère donc savoir. Chaque section de chunk — un cube de seize blocs de côté — tient un
 * compteur, incrémenté à chaque changement de bloc qu'elle subit. Un dormeur retient la valeur qu'il a
 * vue en s'endormant ; si elle a bougé, il se réveille, <b>dans le tick même</b>.
 *
 * <p>Le sommeil devient alors exact, et non approché. Il peut s'appliquer partout, y compris à trois
 * blocs du joueur, sans rien concéder — ce qui est la première optimisation de ce mod à ne demander
 * aucune concession du tout.
 *
 * <h2>Ce que cela coûte</h2>
 *
 * <p>Une addition dans une table d'entiers par changement de bloc. Un joueur en pose quelques-uns par
 * seconde ; une explosion en pose quelques milliers, une fois. En face, on supprime une requête de
 * collision par objet et par tick. Le rapport n'est pas serré.
 *
 * <h2>Une infrastructure, et non une optimisation</h2>
 *
 * <p>Ce compteur ne fait rien par lui-même. Il répond à une question — « quelque chose a-t-il changé
 * ici depuis tel moment ? » — que plusieurs postes du serveur se posent sans pouvoir y répondre. Les
 * objets au sol en sont le premier usage ; la mise en cache des états de bloc lus par les créatures
 * immobiles, que le profileur chiffre à seize pour cent du travail, en sera le second.
 */
public final class Churn {
    /**
     * Section vers nombre de modifications subies.
     *
     * <p>Une table d'entiers primitifs : pas d'objet créé, pas de boîte autour d'un {@code int}. Sur
     * un chemin emprunté par chaque pose de bloc, la différence n'est pas cosmétique.
     */
    private static final Long2IntOpenHashMap GENERATION = new Long2IntOpenHashMap();

    /**
     * Au-delà, on remet les compteurs à plat.
     *
     * <p>Un serveur qui explore finit par toucher des dizaines de milliers de sections, et une table
     * qui ne se vide jamais est une fuite qu'on se reprocherait — ce mod passe son temps à compter les
     * octets des autres.
     *
     * <p>La remise à plat est <b>sans danger</b>, et c'est ce qui autorise à la faire sans précaution :
     * un dormeur qui retrouve zéro là où il avait mémorisé sept se réveille. Perdre la mémoire fait
     * donc travailler davantage, jamais moins — l'erreur tombe toujours du bon côté.
     */
    private static final int TOO_MANY = 60_000;

    private Churn() {}

    /** Déclare qu'un bloc a changé. Appelé depuis le mixin de pose de bloc. */
    public static void touched(BlockPos pos) {
        if (GENERATION.size() > TOO_MANY) {
            GENERATION.clear();
        }
        GENERATION.addTo(SectionPos.asLong(pos), 1);
    }

    /**
     * L'état du voisinage d'un objet, en un seul nombre.
     *
     * <p>On surveille deux sections : celle où l'objet se trouve, et celle qui contient le bloc juste
     * en dessous. Elles ne diffèrent qu'au ras d'une frontière de section, mais c'est précisément là
     * qu'un objet posé sur le plafond d'une section se ferait oublier.
     *
     * <p>La somme suffit à détecter un changement, car aucun des deux compteurs ne décroît jamais :
     * si l'un bouge, la somme bouge. Deux lectures de table au lieu de deux comparaisons, et rien à
     * retenir de plus qu'un entier par objet.
     */
    public static int around(int blockX, int blockY, int blockZ) {
        // « asLong(int, int, int) » attend des coordonnées de section, pas de bloc : la surcharge qui
        // prend un BlockPos fait la conversion, celle-ci non. Les passer telles quelles désignerait
        // des sections situées à seize fois la bonne distance — le compteur consulté n'aurait aucun
        // rapport avec l'endroit surveillé, et le dormeur ne se réveillerait jamais.
        int sectionX = SectionPos.blockToSectionCoord(blockX);
        int sectionZ = SectionPos.blockToSectionCoord(blockZ);
        return GENERATION.get(SectionPos.asLong(
                        sectionX, SectionPos.blockToSectionCoord(blockY), sectionZ))
                + GENERATION.get(SectionPos.asLong(
                        sectionX, SectionPos.blockToSectionCoord(blockY - 1), sectionZ));
    }

    /** Nombre de sections suivies, pour le rapport. */
    public static int tracked() {
        return GENERATION.size();
    }

    public static void reset() {
        GENERATION.clear();
    }
}
