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
 * un serveur vanilla, le paquet réseau reste celui que le client attend, et seule la mémoire vive
 * baisse. Les constantes nécessaires existent déjà dans le jeu — {@code ONE_BIT_LINEAR},
 * {@code TWO_BITS_LINEAR}, {@code THREE_BITS_LINEAR} — où elles servent aux biomes, jamais aux blocs.
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
