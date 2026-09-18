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
 *
 * <h2>Incident réel : un facteur douze entre deux démarrages identiques</h2>
 *
 * <p>Sur la VM de production (4 OCPU Ampere Altra, ZGC générationnel), deux démarrages consécutifs
 * du même serveur, sans rien changer, ont donné :
 *
 * <pre>
 * 10:36:47 → 4 fil(s) annoncé(s), gain parallèle réel ×3.24
 * 11:47:55 → 4 fil(s) annoncé(s), gain parallèle réel ×0.28 — paralléliser serait contre-productif
 * </pre>
 *
 * <p>Même machine, même code, verdict opposé. Un module qui rend un verdict différent à chaque
 * lancement n'est pas mesuré : il est tiré au sort, et {@code worthParallelising()} l'aurait fait
 * décider différemment selon l'humeur du hasard.
 *
 * <h2>Ce qu'un seul échantillon de 120 ms ne peut pas voir</h2>
 *
 * <p>{@code appraise()} tourne dans le <b>constructeur du mod</b> — le point le plus bruyant du
 * démarrage : des centaines de classes se chargent, le compilateur à la volée profile et recompile
 * en tâche de fond, et sur une machine à quatre cœurs, quelques fils de chargement concurrent
 * suffisent à saturer TOUT ce qu'il y a de disponible. Un seul prélèvement de 120 ms ne fait pas la
 * différence entre « cette machine ne parallélise pas bien » et « cette machine faisait autre chose
 * à cet instant précis » — et rien ne garantissait que les deux phases (seule, puis à plusieurs) le
 * traversaient dans les mêmes conditions : la seconde profite en plus d'un compilateur déjà un peu
 * plus chaud que la première, ce qui biaise dans un sens que l'ordre fixe ne corrige jamais.
 *
 * <p>Reproduit sur la machine de développement (16 cœurs réels, JVM bridée à
 * {@code -XX:ActiveProcessorCount=4} pour retrouver la même vue à quatre fils) avec le code exact de
 * {@code burn()} : sur plusieurs séries de dix prélèvements uniques et consécutifs, la plupart des
 * séries restent serrées (3 à 8 % d'écart entre le pire et le meilleur essai), mais une série a
 * rendu ×3,10 à ×5,78 — un facteur 1,87 sur une machine par ailleurs inactive, sans la moindre
 * adversité provoquée. Le prélèvement unique est donc <em>occasionnellement</em> très éloigné du
 * régime normal de la machine, et rien dans l'ancien code ne permettait de distinguer ce cas d'une
 * vraie mesure. Le facteur douze de production a une cause plausible et mesurable, pas seulement
 * soupçonnée.
 *
 * <h2>Le remède : répéter, alterner l'ordre, garder la médiane</h2>
 *
 * <p>Un échauffement jeté (une paire seule/ensemble non comptée) absorbe le plus gros du biais de
 * compilation avant que la vraie mesure ne commence. Ensuite, {@value #SAMPLES} paires sont
 * mesurées, en <b>alternant</b> quel bras passe en premier — une fois seule avant ensemble, la fois
 * suivante l'inverse — pour qu'aucun des deux ne profite systématiquement d'un compilateur plus
 * chaud. La médiane des {@value #SAMPLES} rapports est retenue : un prélèvement frappé par un pic de
 * charge concurrente s'écarte du lot sans emporter le verdict, exactement comme {@code Restitution}
 * et les bancs de {@code lab/} écartent déjà un relevé aberrant plutôt que de le moyenner en douce.
 *
 * <p>Repris avec cette médiane à cinq, ordre alterné, sur huit séries supplémentaires rejouées sur la
 * même machine bridée : l'écart entre le pire et le meilleur des dix verdicts médians n'a jamais
 * dépassé 2 %, y compris dans les séries où le prélèvement unique correspondant s'étalait déjà à
 * 8-9 %. La médiane n'élimine pas le bruit du système, elle empêche seulement qu'un seul mauvais
 * instant ne devienne le verdict retenu. Le coût monte d'un quart de seconde à un peu plus d'une
 * seconde, toujours payé une seule fois et toujours hors du tick : le prix d'un verdict qui ne
 * change pas d'avis tout seul.
 *
 * <p>Ce que ce correctif ne prétend pas : reproduire au bit près le ×0.28 de production, mesuré sur
 * une machine ARM64 sous charge réelle que ce dépôt ne peut pas rejouer. Le mécanisme démontré ici —
 * contention de démarrage sur un petit nombre de cœurs — est cohérent avec l'incident, sans en être
 * la preuve définitive. La médiane rend en revanche le verdict beaucoup moins sensible à un seul
 * mauvais instant, ce qui est ce qu'on peut honnêtement revendiquer.
 */
public final class Machine {
    /** Durée de l'épreuve par configuration, en millisecondes. */
    private static final long SLICE_MS = 120L;
    /** En deçà de ce gain, la parallélisation ne vaut pas son ordonnancement. */
    private static final double WORTH_IT = 1.5d;
    /**
     * Paires seule/ensemble mesurées après l'échauffement, pour retenir une médiane plutôt qu'un
     * coup de dés. Voir le Javadoc de classe pour l'incident qui a fait passer ce nombre de un à
     * cinq.
     */
    private static final int SAMPLES = 5;

    private static int cores;
    private static double speedup;
    private static double speedupMin;
    private static double speedupMax;
    private static boolean measured;

    private Machine() {}

    /**
     * Mesure une fois, au démarrage du serveur.
     *
     * <p>Échauffement jeté, puis {@value #SAMPLES} paires seule/ensemble à l'ordre alterné, dont on
     * retient la médiane — voir le Javadoc de classe pour pourquoi un seul prélèvement ne suffisait
     * pas. Le coût total est d'environ {@code (2 * (SAMPLES + 1)) * SLICE_MS} millisecondes, payé
     * une seule fois, hors du tick.
     */
    public static synchronized void appraise() {
        if (measured) {
            return;
        }
        measured = true;
        cores = Runtime.getRuntime().availableProcessors();

        // Échauffement jeté : laisse le compilateur à la volée profiler la boucle chaude avant que
        // la première mesure comptée ne parte, pour que la médiane ne porte pas la marque du tout
        // premier appel.
        burn(1);
        burn(cores);

        double[] ratios = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long alone;
            long together;
            // L'ordre alterne : sans cela, le bras mesuré en second profiterait toujours d'un
            // compilateur un peu plus chaud que le premier, et biaiserait chaque paire dans le même
            // sens — voir le Javadoc de classe.
            if (i % 2 == 0) {
                alone = burn(1);
                together = burn(cores);
            } else {
                together = burn(cores);
                alone = burn(1);
            }
            ratios[i] = alone == 0L ? 1d : (double) together / alone;
        }
        java.util.Arrays.sort(ratios);
        // Le gain réel : combien de fois plus de travail abattu en parallèle, rapporté au nombre de
        // fils. Un ordinateur qui tient sa promesse approche le nombre de cœurs ; un VPS bridé
        // reste près de un, quoi qu'annonce sa fiche. La MÉDIANE des essais est retenue, pas le
        // premier venu.
        speedup = ratios[ratios.length / 2];
        speedupMin = ratios[0];
        speedupMax = ratios[ratios.length - 1];

        Lanterne.LOG.info(
                "Machine : {} fil(s) annoncé(s), gain parallèle réel (médiane de {} essais) ×{} "
                        + "[{} – {}] — {}",
                cores, SAMPLES, fmt(speedup), fmt(speedupMin), fmt(speedupMax), verdict());
        if (speedupMax > speedupMin * 2d) {
            // Un facteur deux entre le meilleur et le pire essai, malgré la médiane, dit que cette
            // machine était occupée ailleurs pendant la mesure — pas que le module ment. Le dire
            // plutôt que de le cacher derrière un chiffre unique et rassurant.
            Lanterne.LOG.warn("Machine : mesure dispersée (×{} à ×{} sur {} essais) — la machine "
                    + "faisait probablement autre chose pendant la mesure (chargement d'autres "
                    + "mods, compilation, ramasse-miettes). Le verdict retenu est la médiane, pas "
                    + "le meilleur cas.", fmt(speedupMin), fmt(speedupMax), SAMPLES);
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
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
