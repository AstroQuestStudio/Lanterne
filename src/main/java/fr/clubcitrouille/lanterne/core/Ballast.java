package fr.clubcitrouille.lanterne.core;

import java.util.Locale;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Ce que les états de bloc occupent en mémoire, avant d'essayer d'y toucher.
 *
 * <h2>Le domaine que ce mod ne couvrait pas</h2>
 *
 * <p>Lanterne divise par douze la mémoire <b>allouée</b> — le débit d'octets demandés par seconde,
 * qui commande la fréquence des ramassages et donc les à-coups. Il ne change en revanche rien à la
 * mémoire <b>retenue</b> : 225,4 Mo contre 227,8 sur la charge du village, soit l'égalité.
 *
 * <p>Ce sont deux grandeurs différentes et la seconde décide d'autre chose : combien de mods on peut
 * charger avant que le serveur ne meure. Sur un hébergement à deux gigaoctets, c'est elle qui fixe la
 * limite d'un modpack.
 *
 * <h2>Ce que Mojang a déjà fait</h2>
 *
 * <p>Le mod de référence sur ce terrain — FerriteCore — attaque surtout la façon dont un
 * {@code BlockState} retient ses propriétés. Les anciennes versions du jeu employaient un
 * {@code ImmutableMap} et une {@code Table} de Guava, deux structures généreuses en objets.
 *
 * <p>En 26.1, {@code StateHolder} déclare ceci :
 *
 * <pre>
 * private final Property&lt;?&gt;[] propertyKeys;
 * private final Comparable&lt;?&gt;[] propertyValues;
 * private S[][] neighbors;
 * </pre>
 *
 * <p>Des tableaux. L'optimisation principale est donc <b>déjà dans le jeu</b> — c'est le quatrième
 * cas de la journée, après Noisium, Starlight et Lithium. Ce projet a pris l'habitude de le vérifier
 * avant d'écrire, et cette habitude lui a évité quatre modules inutiles.
 *
 * <h2>Ce qui reste, et ce que ce compteur mesure</h2>
 *
 * <p>{@code neighbors} reste un tableau à deux dimensions <b>par état</b> : une entrée par propriété,
 * et dans chacune une référence par valeur possible. Un bloc à cinq propriétés de quatre valeurs
 * retient vingt références par état, et il a lui-même mille vingt-quatre états.
 *
 * <p>Ce compteur additionne ce que cela représente, sans réflexion et sans supposition : il lit les
 * propriétés par l'interface publique et calcule la taille exacte des tableaux que le jeu a dû
 * allouer. Si le total est de quelques mégaoctets, il n'y a rien à prendre et l'on ira voir ailleurs.
 * S'il se compte en dizaines, la déduplication vaut d'être écrite.
 */

/**
 * ADDENDUM — ce que la mesure a répondu, et pourquoi les postes 1 à 4 sont clos.
 *
 * <pre>
 * 29 873 états de bloc, 131 856 propriétés
 * tables de voisinage : 161 338 tableaux, 607 442 références   →   4,8 Mo
 * </pre>
 *
 * <p>Quatre virgule huit mégaoctets sur un tas de deux cent vingt-cinq. La déduplication des états de
 * bloc ne vaut donc <b>rien</b> en vanilla — elle ne prendrait de l.importance que sur un modpack qui
 * multiplie les états par dix ou vingt, ce qu.on ne peut pas mesurer sans l.installer.
 *
 * <p>L.histogramme d.un tas en cours de partie a répondu pour les trois autres postes :
 *
 * <pre>
 * [J (tableaux de long)                 242 Mo      ← les BitStorage des palettes
 * [B (tableaux d.octets)                212 Mo
 * PalettedContainer + Data               19 Mo
 * </pre>
 *
 * <p>Les objets de palette pèsent dix-neuf mégaoctets ; ce qu.ils <em>contiennent</em> en pèse deux
 * cent quarante-deux. Autrement dit, la mémoire retenue d.un serveur Minecraft, c.est <b>le terrain
 * lui-même</b> — et il est incompressible sans changer le format de stockage.
 *
 * <p>Le seul levier qui resterait est de retenir <b>moins de chunks</b>, ce qui est un tout autre
 * sujet et un tout autre risque : décharger trop tôt, c.est recharger, et recharger coûte plus cher
 * que garder. Les postes 1 à 4 sont donc clos sur un constat, et non sur un module.
 */

/**
 * SECOND ADDENDUM — l'histogramme ci-dessus ne décrivait pas un serveur.
 *
 * <p>Les chiffres de l'addendum précédent — 242 Mo de {@code long[]}, 212 Mo de {@code byte[]} — ont
 * été relevés à la main, hors du mod, avec un outil du JDK. {@link Inventaire} prend désormais la
 * même mesure <b>de l'intérieur</b>, sur un serveur dédié, et elle ne leur ressemble pas :
 *
 * <pre>
 * serveur 26.2, une doublure, 625 chunks, après ramassage complet
 *   tas retenu   220,0 Mo
 *   [B            55,3 Mo
 *   [J            19,9 Mo
 * </pre>
 *
 * <p>Un facteur douze d'écart sur les {@code long[]}. L'ancien relevé décrivait donc autre chose
 * qu'un serveur — un client, ou un monde bien plus grand — et la conclusion qu'il portait, « la
 * mémoire retenue d'un serveur Minecraft, c'est le terrain lui-même », était juste par accident.
 *
 * <p>Sur un serveur, le terrain ne pèse que <b>25,3 Mo sur 220</b>, soit onze et demi pour cent. Le
 * premier poste réel est un socle d'environ quarante-sept mégaoctets de tableaux d'octets qui est
 * <b>déjà là avant le premier chunk</b> et ne bouge plus ensuite. Ce que ce socle contient n'est pas
 * établi : un histogramme de classes ne donne pas les chemins de références.
 *
 * <p>Le raisonnement de cette classe reste entier — allouée et retenue sont deux grandeurs, et c'est
 * la seconde qui fixe la limite d'un modpack. Seul le classement des postes était faux, et il l'était
 * parce qu'un chiffre pris à la main n'avait pas dit sur quoi il avait été pris. Voir
 * {@code notes/memoire-retenue.md} pour l'inventaire complet.
 */
public final class Ballast {
    /** En-tête d'un tableau Java, et taille d'une référence compressée. */
    private static final int ARRAY_HEADER = 16;
    private static final int REFERENCE = 4;

    private Ballast() {}

    /** Chiffre le poids des tables de voisinage, et le dit au journal. */
    public static void appraise() {
        long states = 0L;
        long properties = 0L;
        long references = 0L;
        long arrays = 0L;

        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            states++;
            int here = 0;
            for (Property<?> property : state.getProperties()) {
                here++;
                // Une entrée de neighbors par propriété : un tableau d'autant de références que la
                // propriété a de valeurs possibles.
                references += property.getPossibleValues().size();
                arrays++;
            }
            properties += here;
            // Et le tableau extérieur, qui tient une entrée par propriété.
            if (here > 0) {
                references += here;
                arrays++;
            }
        }

        long bytes = arrays * ARRAY_HEADER + references * REFERENCE;
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[LEST] %d état(s) de bloc, %d propriété(s) au total · tables de voisinage : "
                + "%d tableau(x), %d référence(s), soit environ %.1f Mo retenus.",
                states, properties, arrays, references, bytes / 1048576d));
    }
}
