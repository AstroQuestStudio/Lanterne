package fr.clubcitrouille.lanterne.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Ce que vaut vraiment cette machine, mesuré et non déclaré.
 *
 * <h2>Pourquoi ne pas se fier au nombre de cœurs</h2>
 *
 * <p>{@code Runtime.availableProcessors()} rend un nombre, et ce nombre ment souvent. Un VPS annoncé
 * à quatre cœurs peut n'en avoir qu'un de réellement disponible, partagé avec d'autres locataires.
 * Un processeur à seize fils logiques n'en a que huit physiques, et deux fils d'un même cœur se
 * disputent les mêmes unités de calcul. Un conteneur peut être bridé par un quota que la machine
 * virtuelle ne voit pas.
 *
 * <p>La seule réponse fiable est de <b>mesurer</b> : lancer le même travail sur un fil, puis sur
 * plusieurs, et comparer. Ce que l'on obtient alors n'est pas un nombre de cœurs, c'est le gain réel
 * que la parallélisation apporterait ici et maintenant.
 *
 * <h2>Pourquoi cette mesure décide de quelque chose</h2>
 *
 * <p>La documentation de Folia — la référence en matière de serveur Minecraft multifil — recommande
 * <b>seize cœurs physiques au minimum</b>, et des essais indépendants montrent qu'en deçà d'un
 * certain nombre de régions actives, l'ordonnancement coûte plus qu'il ne rapporte. Autrement dit :
 * <b>paralléliser sur une petite machine la ralentit</b>.
 *
 * <p>Or la cible de ce mod est précisément la petite machine. Un réglage par défaut unique serait
 * donc mauvais dans un cas ou dans l'autre ; on mesure, et l'on décide.
 *
 * <h2>Ce que la mesure ne dit pas</h2>
 *
 * <p>Elle dit ce que la <em>machine</em> permet, pas ce que le <em>jeu</em> permet. Le tick de
 * Minecraft n'est pas parallélisable sans une architecture de régions étanches — c'est tout le
 * travail de Folia, et il a demandé des années. Ce module rend donc un verdict et une
 * recommandation, il n'active rien de lui-même.
 *
 * <p>Le dire ainsi est plus utile que de prétendre le contraire : un serveur qui sait qu'il ne
 * gagnerait rien à paralléliser sait aussi qu'il doit chercher ailleurs.
 */
public final class Machine {
    /** Durée de l'épreuve par configuration, en millisecondes. */
    private static final long SLICE_MS = 120L;
    /** En deçà de ce gain, la parallélisation ne vaut pas son ordonnancement. */
    private static final double WORTH_IT = 1.5d;

    private static int cores;
    private static double speedup;
    private static boolean measured;

    private Machine() {}

    /**
     * Mesure une fois, au démarrage du serveur.
     *
     * <p>Le coût est d'un quart de seconde, payé une seule fois, hors du tick. C'est le prix d'une
     * décision juste contre une supposition.
     */
    public static synchronized void appraise() {
        if (measured) {
            return;
        }
        measured = true;
        cores = Runtime.getRuntime().availableProcessors();

        long alone = burn(1);
        long together = burn(cores);
        // Le gain réel : combien de fois plus de travail abattu en parallèle, rapporté au nombre de
        // fils. Un ordinateur qui tient sa promesse approche le nombre de cœurs ; un VPS bridé
        // reste près de un, quoi qu'annonce sa fiche.
        speedup = alone == 0L ? 1d : (double) together / alone;

        Lanterne.LOG.info("Machine : {} fil(s) annoncé(s), gain parallèle réel ×{} — {}",
                cores, String.format(java.util.Locale.ROOT, "%.2f", speedup), verdict());
    }

    /**
     * Fait tourner un calcul borné dans le temps, sur le nombre de fils demandé.
     *
     * <p>Le calcul est volontairement arithmétique et sans mémoire partagée : on mesure la capacité
     * de calcul disponible, pas la bande passante mémoire ni la contention d'un verrou. Chaque fil
     * accumule dans sa propre variable locale, et ne publie son total qu'à la fin.
     */
    private static long burn(int threads) {
        AtomicLong total = new AtomicLong();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        // L'échéance se fixe une fois tout le monde en place, jamais avant.
        //
        // La première version la calculait au lancement, puis créait les fils. Créer un fil coûte
        // quelques millisecondes ; avec deux fils, chacun démarrait plus tard et travaillait donc
        // moins longtemps dans une fenêtre qui, elle, ne bougeait pas. Le résultat était un gain
        // annoncé de ×0,08 sur une machine à deux cœurs — c'est-à-dire douze fois pire que
        // séquentiel, ce qu'aucune machine ne fait.
        //
        // Le verdict restait juste par accident ; le chiffre, lui, était faux. Un outil de mesure
        // qui se trompe d'un facteur dix ne sert qu'à donner confiance à tort.
        final long[] deadline = new long[1];

        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    done.countDown();
                    return;
                }
                long rounds = 0L;
                long noise = 1L;
                while (System.nanoTime() < deadline[0]) {
                    // Mille tours entre deux lectures d'horloge : lire le temps est un appel
                    // système déguisé, et le mesurer lui-même fausserait l'épreuve.
                    for (int step = 0; step < 1000; step++) {
                        noise = noise * 6364136223846793005L + 1442695040888963407L;
                        noise ^= noise >>> 29;
                    }
                    rounds++;
                }
                // On publie le bruit accumulé pour que le compilateur ne supprime pas la boucle
                // comme un calcul sans effet — il en a parfaitement le droit, sans cela.
                total.addAndGet(rounds + (noise & 1L));
                done.countDown();
            }, "lanterne-appraise-" + i);
            worker.setDaemon(true);
            worker.start();
        }

        try {
            ready.await();
            // Tout le monde est prêt : on ouvre la fenêtre et on la referme au même instant pour
            // tous. C'est la seule façon de comparer des débits plutôt que des temps de démarrage.
            deadline[0] = System.nanoTime() + SLICE_MS * 1_000_000L;
            go.countDown();
            done.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 0L;
        }
        return total.get();
    }

    /** La parallélisation vaudrait-elle son ordonnancement sur cette machine ? */
    public static boolean worthParallelising() {
        return measured && speedup >= WORTH_IT;
    }

    public static double speedup() {
        return speedup;
    }

    public static int cores() {
        return cores;
    }

    /** Le verdict en clair, pour le journal et le rapport. */
    public static String verdict() {
        if (!measured) {
            return "non mesurée";
        }
        if (speedup >= 4d) {
            return "paralléliser vaudrait le coup (grosse machine)";
        }
        if (speedup >= WORTH_IT) {
            return "paralléliser apporterait peu";
        }
        return "paralléliser serait contre-productif — faire moins, pas en parallèle";
    }
}
