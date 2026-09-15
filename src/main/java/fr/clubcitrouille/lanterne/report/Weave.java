package fr.clubcitrouille.lanterne.report;

import java.util.Locale;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Combien de bits chaque section de terrain paie, et combien elle en aurait besoin.
 *
 * <h2>Une section à deux blocs distincts paie quatre bits</h2>
 *
 * <p>Le terrain d'un monde chargé est, de loin, le premier poste de mémoire <b>retenue</b> du
 * serveur — deux cent quarante-deux mégaoctets de {@code BitStorage} relevés sur ce projet. Ce
 * projet avait conclu que cette mémoire était incompressible : c'est le terrain lui-même, et l'on
 * ne peut pas l'effacer.
 *
 * <p>La conclusion était trop rapide. Voici comment le jeu choisit la largeur de ses cases :
 *
 * <pre>
 * case 0       -&gt; ZERO_BITS;           // une seule valeur : aucun stockage
 * case 1, 2, 3, 4 -&gt; FOUR_BITS_LINEAR; // &lt;-- ici
 * case 5       -&gt; FIVE_BITS_HASHMAP;
 * </pre>
 *
 * <p>Une section qui ne contient que <b>deux</b> états distincts — de la pierre et de l'air, le
 * sous-sol ordinaire — aurait besoin d'un seul bit par bloc. Elle en reçoit quatre. Une section à
 * trois ou quatre états en aurait besoin de deux, et reçoit quatre. Le gaspillage va du simple au
 * quadruple, et il porte sur 4096 blocs par section.
 *
 * <h2>Ce que ce relevé fait, et ce qu'il ne décide pas</h2>
 *
 * <p>Le gain dépend entièrement d'une chose qu'on ne sait pas : <b>combien</b> de sections ont peu
 * d'états distincts. S'il s'agit d'un tiers du terrain, le gain se compte en dizaines de mégaoctets.
 * S'il s'agit de deux pour cent, il n'y a rien à prendre et l'idée se jette.
 *
 * <p>Cet outil compte. Il ne modifie rien, n'écrit rien, et ne décide de rien — c'est le chiffre
 * qu'il rend qui décidera. C'est la règle de ce projet : un module dont le gain n'est pas prouvé
 * n'est pas écrit.
 *
 * <h2>Pourquoi la porte de sortie est propre</h2>
 *
 * <p>Si le chiffre est bon, le changement n'a pas besoin de toucher au format du disque. L'interface
 * {@code Configuration} sépare déjà les deux :
 *
 * <pre>
 * int bitsInMemory();
 * int bitsInStorage();
 * </pre>
 *
 * <p>On peut donc tenir <b>un bit en mémoire et quatre sur le disque</b>. Le monde reste lisible par
 * un serveur vanilla, et seule la mémoire vive baisse. Les constantes nécessaires existent déjà dans
 * le jeu — {@code ONE_BIT_LINEAR}, {@code TWO_BITS_LINEAR}, {@code THREE_BITS_LINEAR} — où elles
 * servent aux biomes, jamais aux blocs.
 */

/**
 * ADDENDUM — ce que le relevé a répondu, et pourquoi le module n'a pas été écrit.
 *
 * <h2>Le chiffre</h2>
 *
 * <pre>
 * 5 834 sections pesées, 9 166 vides ou à valeur unique (déjà gratuites)
 * payé 11,92 Mo · nécessaire 10,74 Mo · gaspillé 1,18 Mo, soit 9,9 %
 *
 *   4 bits : 4 920 sections, 1,18 Mo gaspillés
 *   5 bits :   756 sections, 0,00 Mo
 *   6 bits :   158 sections, 0,00 Mo
 * </pre>
 *
 * <p>Neuf virgule neuf pour cent, et non « du simple au quadruple ». La note de classe ci-dessus
 * raisonnait sur la section de sous-sol ordinaire — pierre et air, deux états, un bit suffirait — et
 * elle avait raison sur ce cas-là. Ce qu'elle ignorait, c'est <b>combien</b> de sections lui
 * ressemblent. La réponse est : peu. Une section de terrain réel porte de la pierre, de la terre, de
 * l'herbe, de l'eau, du gravier, trois minerais et de l'air ; elle a donc entre neuf et seize états
 * distincts, et elle paie quatre bits parce qu'elle en a <b>besoin</b> de quatre.
 *
 * <p>Les sections vraiment pauvres en états, elles, sont déjà gratuites : le jeu les range en palette
 * à valeur unique, à zéro bit. Elles sont 9 166 sur 15 000 — soixante et un pour cent du terrain ne
 * coûte déjà rien. Le gaspillage cherché se trouvait dans la tranche étroite qui reste.
 *
 * <h2>La porte de sortie n'était pas propre</h2>
 *
 * <p>La note annonçait que « le paquet réseau reste celui que le client attend ». C'est <b>faux</b>,
 * et la lecture de {@code PalettedContainer.Data.write} le dit en une ligne :
 *
 * <pre>
 * buffer.writeByte(this.storage.getBits());   // les bits EN MÉMOIRE, pas ceux du disque
 * buffer.writeFixedSizeLongArray(this.storage.getRaw());
 * </pre>
 *
 * <p>Un serveur qui tiendrait un bit en mémoire annoncerait donc « un bit » au client. Un client
 * vanilla appellerait {@code getConfigurationForBitCount(1)}, qui rend {@code FOUR_BITS_LINEAR}, et
 * allouerait un tableau de deux cent cinquante-six {@code long} pour en lire soixante-quatre. Le
 * reste du paquet serait décalé, et la connexion tomberait au premier chunk.
 *
 * <p>Le disque, lui, était bien protégé : {@code pack()} ré-encode toujours vers
 * {@code bitsInStorage()}. C'est le réseau qui ne l'était pas — et l'écart entre les deux ne se voit
 * qu'en lisant le code, jamais en lisant l'interface.
 *
 * <p>Il resterait à ré-encoder chaque section à l'envoi. Sur un cœur unique, payer un ré-encodage de
 * 4 096 cases par section envoyée pour récupérer 1,18 Mo sur 220 est un marché qu'on refuse.
 *
 * <h2>Le poste est clos</h2>
 *
 * <p>Un virgule un huit mégaoctet sur un tas retenu de 220 Mo, soit un demi pour cent, au prix d'une
 * rupture de protocole et d'un ré-encodage dans le chemin d'envoi. L'outil reste : c'est lui qui a
 * permis de refuser, et il refusera de nouveau sur un modpack qui changerait la donne.
 */
public final class Weave {
    /** Rayon du relevé, en chunks. */
    private static final int REACH = 12;

    /** Blocs par section. */
    private static final int CELLS = 4096;

    private Weave() {}

    /**
     * Parcourt les sections chargées et dit ce que la largeur des cases coûte.
     */
    public static void survey(CommandSourceStack source) {
        int centreX = SectionPos.blockToSectionCoord((int) source.getPosition().x);
        int centreZ = SectionPos.blockToSectionCoord((int) source.getPosition().z);
        survey(source.getLevel(), centreX, centreZ,
                line -> source.sendSuccess(() -> Component.literal(line), false));
    }

    /**
     * Le relevé, sans personne aux commandes.
     *
     * <p>La première version n'existait qu'en commande, donc ne pouvait être lancée que par un joueur
     * connecté — c'est-à-dire à la main, c'est-à-dire rarement. Elle n'a jamais été lancée une seule
     * fois, et le module qu'elle devait trancher est resté en suspens pendant toute une session.
     *
     * <p>Un instrument qui demande un humain n'est pas un instrument : c'est une intention.
     */
    public static void survey(ServerLevel level, int centreX, int centreZ,
                              java.util.function.Consumer<String> say) {

        // Index = nombre de bits réellement payés. Vanilla ne dépasse guère 8 hors palette globale.
        long[] sectionsAt = new long[33];
        long[] wastedAt = new long[33];
        long billed = 0L;
        long ideal = 0L;
        long sections = 0L;
        long empty = 0L;

        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                // On force le chargement : l'épreuve de compression a montré qu'un relevé lancé
                // tôt après le démarrage ne trouve aucun chunk chargé, et conclut « rien à peser »
                // sur un monde entier disponible à portée d'un appel.
                LevelChunk chunk = level.getChunk(centreX + dx, centreZ + dz);
                for (LevelChunkSection section : chunk.getSections()) {
                    if (section == null || section.hasOnlyAir()) {
                        empty++;
                        continue;
                    }
                    int bits = section.getStates().bitsPerEntry();
                    if (bits == 0) {
                        // Palette à valeur unique : déjà gratuit, rien à prendre.
                        empty++;
                        continue;
                    }
                    int distinct = distinctStates(section);
                    int needed = Math.max(1, ceilLog2(distinct));

                    sections++;
                    sectionsAt[Math.min(bits, 32)]++;
                    long paid = (long) CELLS * bits / 8L;
                    long want = (long) CELLS * needed / 8L;
                    billed += paid;
                    ideal += want;
                    wastedAt[Math.min(bits, 32)] += paid - want;
                }
            }
        }

        if (sections == 0L) {
            say.accept("Aucune section chargée à peser.");
            return;
        }

        final long paidTotal = billed;
        final long wantTotal = ideal;
        final long seen = sections;
        final long none = empty;
        say.accept(String.format(Locale.ROOT,
                "%d section(s) pesée(s), %d vide(s) ou à valeur unique (déjà gratuites).",
                seen, none));
        say.accept(String.format(Locale.ROOT,
                "Payé %.2f Mo · nécessaire %.2f Mo · gaspillé %.2f Mo, soit %.1f %%.",
                paidTotal / 1048576d, wantTotal / 1048576d,
                (paidTotal - wantTotal) / 1048576d,
                (paidTotal - wantTotal) * 100d / Math.max(1L, paidTotal)));

        for (int bits = 1; bits < sectionsAt.length; bits++) {
            if (sectionsAt[bits] == 0L) {
                continue;
            }
            final int width = bits;
            final long howMany = sectionsAt[bits];
            final long wasted = wastedAt[bits];
            say.accept(String.format(Locale.ROOT,
                    "  %2d bit(s) : %6d section(s), %7.2f Mo gaspillé(s)",
                    width, howMany, wasted / 1048576d));
        }

        Lanterne.LOG.info("[PALETTES] {} section(s) · payé {} o · nécessaire {} o · gaspillé {} o",
                sections, billed, ideal, billed - ideal);
    }

    /**
     * Le nombre d'états distincts réellement présents dans la section.
     *
     * <h2>Pourquoi on compte, au lieu de lire la taille de la palette</h2>
     *
     * <p>La palette d'une section peut contenir des entrées <b>mortes</b> : un bloc posé puis cassé y
     * laisse son état, et le jeu ne resserre la palette qu'au moment de l'écriture sur le disque.
     * Lire sa taille répondrait donc « combien d'états ont un jour existé ici », et non « combien il y
     * en a ».
     *
     * <p>Or c'est exactement la différence qui décide du gain : une section où il ne reste que de la
     * pierre et de l'air, mais dont la palette porte encore huit fantômes, est précisément celle qu'on
     * veut compter comme candidate. Compter coûte un parcours de 4096 cases ; c'est le prix d'une
     * réponse juste, payé une fois, sur commande.
     */
    private static int distinctStates(LevelChunkSection section) {
        int[] tally = {0};
        section.getStates().count((state, howMany) -> tally[0]++);
        return Math.max(1, tally[0]);
    }

    private static int ceilLog2(int value) {
        if (value <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }
}
