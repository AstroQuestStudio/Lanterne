package fr.clubcitrouille.lanterne.report;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.TickBudget;

/**
 * Le banc de mesure : la même charge, deux fois, avec et sans.
 *
 * <h2>Le protocole, et pourquoi il compte plus que le résultat</h2>
 *
 * <p>Comparer deux mesures prises à des moments différents ne prouve rien : entre les deux, un
 * orage a pu éclater, un chunk se générer, le ramasse-miettes passer. Sur une instance à vingt-deux
 * mods d'optimisation, le bruit dépasse largement l'effet qu'on cherche à mesurer.
 *
 * <p>Le banc impose donc trois règles.
 *
 * <ol>
 *   <li><b>Une phase de chauffe.</b> Les premières secondes après un changement mesurent le
 *       compilateur à la volée en train de recompiler, pas le code. On les jette.</li>
 *   <li><b>Deux phases de durée égale</b>, l'une après l'autre, sur le même monde et la même
 *       charge — seul l'interrupteur change.</li>
 *   <li><b>La médiane, et non la moyenne.</b> Une sauvegarde automatique au milieu d'une phase
 *       ajoute deux cents millisecondes à un tick ; la moyenne en sort ruinée, la médiane n'en
 *       sait rien. On ne cherche pas le tick moyen, on cherche le tick <em>typique</em>.</li>
 * </ol>
 *
 * <p>Le résultat est un rapport entre deux nombres mesurés dans les mêmes conditions. S'il vaut un,
 * le mod ne sert à rien dans cette situation — et le banc le dira aussi clairement que l'inverse.
 */
public final class Bench {
    /**
     * Ticks jetés après chaque bascule, le temps que la machine virtuelle se restabilise.
     *
     * <h2>Pourquoi soixante ne suffisaient pas</h2>
     *
     * <p>Un banc lancé avec le mod <em>désactivé des deux côtés</em> a rendu quatorze millisecondes
     * pour la première phase et onze pour la seconde. Les deux mesuraient rigoureusement la même
     * chose : l'écart de vingt-sept pour cent était entièrement dû au compilateur à la volée, qui
     * continuait d'optimiser pendant la première.
     *
     * <p>Autrement dit, le protocole avantageait systématiquement la seconde phase — celle du
     * témoin. Le mod était donc sous-évalué, ce qui est la bonne direction pour se tromper, mais
     * une erreur reste une erreur. Deux cents ticks laissent au compilateur le temps de se fixer.
     */
    private static final int WARMUP = 200;
    /** Ticks mesurés par phase. Cinq cents ticks valent vingt-cinq secondes — si le serveur tient. */
    private static final int SAMPLE = 500;

    /**
     * Budget de temps réel par phase, en nanosecondes.
     *
     * <h2>Un banc qui n'aurait jamais rendu son verdict</h2>
     *
     * <p>Le protocole demandait cinq cents ticks par phase. Cela suppose, sans le dire, qu'un tick dure
     * à peu près cinquante millisecondes.
     *
     * <p>La charge « huit mille objets au sol » a détruit cette hypothèse. Phase témoin, mod éteint :
     * <b>cent trente-cinq secondes par tick</b>. Cinq cents de ces ticks font dix-huit heures. Le banc
     * ne mesurait pas mal — il ne finissait pas, ce qui est pire : aucun chiffre, aucun verdict, et une
     * nuit de mesure perdue à attendre.
     *
     * <p>Une phase s'arrête donc à l'échéance, même si elle n'a pas son compte de relevés. Quatre-vingt
     * -dix secondes suffisent : quand un tick dure deux minutes, trente relevés donnent une médiane
     * aussi solide que cinq cents, parce que le bruit relatif d'un tick de deux minutes est minuscule.
     *
     * <p>Le rapport dit alors <b>combien de relevés ont servi</b>. Un verdict tiré de trente ticks n'est
     * pas moins vrai qu'un verdict tiré de cinq cents, mais le lecteur a le droit de le savoir.
     */
    private static final long BUDGET_NANOS = 90_000_000_000L;

    /**
     * Relevés minimaux avant d'accepter d'arrêter une phase à l'échéance.
     *
     * <p>En dessous, la médiane ne veut rien dire et l'on préfère dépasser le budget. Mieux vaut un banc
     * qui déborde qu'un chiffre tiré de trois mesures.
     */
    private static final int LEAST = 15;

    private enum Phase { IDLE, WARM_ON, MEASURE_ON, WARM_OFF, MEASURE_OFF, DONE }

    private static Phase phase = Phase.IDLE;
    private static int left;
    private static CommandSourceStack listener;
    /** Vrai quand le banc tourne sans personne : le verdict part alors au journal. */
    private static boolean headless;
    /** Arrêter le serveur en fin de banc — seulement en auto-test. */
    private static net.minecraft.server.MinecraftServer stopAfter;

    private static final long[] WITH = new long[SAMPLE];
    private static final long[] WITHOUT = new long[SAMPLE];
    private static int filled;

    /** Relevés réellement obtenus dans chaque phase : ils peuvent différer si l'échéance a tranché. */
    private static int keptWith;
    private static int keptWithout;
    /** Instant d'ouverture de la phase de mesure, pour savoir quand l'échéance tombe. */
    private static long phaseOpened;

    private static long tickStart;
    /** Entités que l'épreuve a demandées, pour que le contrôle sache quoi vérifier. */
    private static int expected;

    public static void expect(int entities) {
        expected = entities;
    }

    /**
     * Mémoire allouée pendant chaque phase, en octets.
     *
     * <h2>Pourquoi mesurer l'allocation et non l'occupation</h2>
     *
     * <p>« Combien de mémoire le serveur occupe-t-il ? » est une question sans réponse stable : le
     * tas monte jusqu'au prochain ramassage, puis retombe. Deux relevés pris à deux instants
     * différents du même cycle donnent des chiffres opposés, et aucun n'est faux.
     *
     * <p>Ce qui compte est le <b>débit d'allocation</b> : combien d'octets par seconde le serveur
     * demande. C'est lui qui décide de la fréquence des ramassages, et donc des à-coups — sur une
     * machine à un seul cœur, un ramassage ne s'exécute pas « en parallèle », il fige le serveur.
     *
     * <p>Moins allouer, c'est ramasser moins souvent. C'est la seule façon d'agir sur la mémoire
     * dont un mod comme celui-ci soit capable, et elle se mesure.
     */
    private static long allocWith;
    private static long allocWithout;
    private static long allocMark;
    private static long gcWith;
    private static long gcWithout;
    private static long gcMark;

    /**
     * Paquets que le serveur a voulu envoyer pendant chaque phase.
     *
     * <p>La seule mesure honnête de l'effet réseau d'un mod : ce sur quoi il peut agir est le
     * <b>nombre de paquets décidés</b>, pas le débit réel, qui dépend de la ligne du joueur.
     */
    private static long packetsWith;
    private static long packetsWithout;

    /**
     * La répartition des cadences, relevée pendant la phase où le mod agit.
     *
     * <h2>Un rapport qui décrivait la phase témoin</h2>
     *
     * <p>Le verdict s'écrit à la fin de la <b>seconde</b> phase, celle où le mod est éteint. Il lisait
     * donc les compteurs de cadence dans l'état où la phase témoin les avait laissés : mod inactif,
     * aucune dégradation, tout en pleine simulation.
     *
     * <p>Le premier banc de l'enclos l'a rendu visible. Il annonçait « pleine simulation : 967 » et
     * « travail évité : 4,5 % » pour une charge de mille vaches dont le gain mesuré était de ×2,48.
     * Les deux chiffres étaient incohérents entre eux, et c'est le second qui mentait.
     *
     * <p>La conclusion qu'on en aurait tirée était la mauvaise : « la dégradation par densité ne
     * s'applique pas, il faut la brancher ». Elle est branchée, et elle fonctionne. C'est le
     * <em>rapport</em> qui regardait au mauvais endroit — et c'est le troisième chiffre faux que ce
     * banc produit avec assurance.
     *
     * <p>On relève donc la répartition à la fin de la phase active, et on l'étiquette comme telle.
     */
    private static String repartitionWith = "";
    private static double avoidedWith;
    private static int jammedWith;

    /**
     * Mémoire réellement retenue à la fin de chaque phase, en octets.
     *
     * <h2>Pourquoi « 26,9 Go » n'était probablement pas vrai</h2>
     *
     * <p>Ce projet a publié un tableau annonçant vingt-six virgule neuf gigaoctets pour le jeu nu
     * contre deux virgule sept avec le mod. Le chiffre a été obtenu en lisant l'occupation du tas à un
     * instant donné — c'est-à-dire <b>juste avant un ramassage</b>, au sommet de la dent de scie.
     *
     * <p>Or ce sommet ne mesure pas ce que le serveur a besoin de retenir. Il mesure à quel point la
     * machine virtuelle a laissé le tas gonfler avant de se décider à nettoyer, ce qui dépend de la
     * taille maximale accordée et du ramasse-miettes choisi, et pas du tout du contenu du monde. Deux
     * relevés du même serveur peuvent différer d'un facteur dix sans que rien n'ait changé.
     *
     * <p>La question qui compte pour un administrateur est autre : <b>combien faut-il de mémoire pour
     * que ce serveur tienne ?</b> Et la réponse est l'ensemble vivant — ce qui reste occupé après un
     * ramassage complet. C'est ce qu'on relève ici, en provoquant le ramassage plutôt qu'en l'attendant.
     *
     * <p>Le débit d'allocation reste mesuré à côté : il commande la fréquence des à-coups, et c'est
     * une autre question, tout aussi réelle. Les deux chiffres disent des choses différentes, et les
     * confondre est ce qui a produit le premier.
     */
    private static long liveWith;
    private static long liveWithout;

    private Bench() {}

    /**
     * Mémoire retenue après un ramassage complet.
     *
     * <p>Deux passages : le premier libère l'essentiel, le second ramasse ce que les finaliseurs du
     * premier ont rendu joignable. C'est la pratique habituelle, et elle suffit ici — on cherche un
     * ordre de grandeur comparable entre deux phases, pas une comptabilité à l'octet.
     *
     * <p>Le coût — quelques centaines de millisecondes de serveur figé — est payé <b>hors</b> de
     * toute fenêtre de mesure : en fin de phase, juste avant les deux cents ticks de chauffe qui sont
     * jetés de toute façon.
     */
    private static long liveBytes() {
        try {
            System.gc();
            Thread.sleep(120L);
            System.gc();
            Thread.sleep(80L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return java.lang.management.ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getUsed();
    }

    /**
     * L'échéance de la phase est-elle passée ?
     *
     * <p>On exige un minimum de relevés avant de laisser l'échéance trancher : une médiane sur trois
     * valeurs n'est pas une médiane, c'est une valeur prise au hasard parmi trois.
     */
    private static boolean overdue() {
        return filled >= LEAST && System.nanoTime() - phaseOpened > BUDGET_NANOS;
    }

    /**
     * La chauffe a-t-elle assez duré ?
     *
     * <h2>Le budget qui ne couvrait que la moitié du chemin</h2>
     *
     * <p>Borner la phase de mesure ne suffisait pas. Sur la charge témoin des objets au sol, un tick
     * durait cent trois secondes — et la <b>chauffe</b> en demandait deux cents. Cinq heures et demie
     * avant le premier relevé, donc avant que l'échéance de mesure n'ait la moindre chance de tomber.
     *
     * <p>Deux cents ticks de chauffe se justifient quand un tick dure cinquante millisecondes : dix
     * secondes, le temps que le compilateur se fixe. Quand un tick dure deux minutes, le compilateur a
     * tout compilé avant la fin du premier — et la phase précédente l'avait déjà fait.
     *
     * <p>La chauffe s'arrête donc elle aussi à l'échéance. Le tiers du budget de mesure : assez pour
     * laisser passer un pic, jamais assez pour perdre une nuit.
     */
    private static boolean warmEnough() {
        return System.nanoTime() - phaseOpened > BUDGET_NANOS / 3L;
    }

    /** La répartition des cadences en une ligne, pour pouvoir la retenir telle quelle. */
    private static String repartition() {
        StringBuilder text = new StringBuilder();
        for (var entry : fr.clubcitrouille.lanterne.core.Census.buckets().entrySet()) {
            if (!text.isEmpty()) {
                text.append(" · ");
            }
            text.append(entry.getKey()).append(" : ").append(entry.getValue()[0]);
        }
        return text.isEmpty() ? "aucune entité recensée" : text.toString();
    }

    public static boolean running() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    /** Lance la comparaison. Deux phases de vingt-cinq secondes, plus la chauffe. */
    /**
     * Lance le banc sans interlocuteur : le verdict ira au journal, puis le serveur s'arrête.
     *
     * <p>Rien ne démarre si le contrôle préalable échoue. Ce banc a rendu trois chiffres faux avec
     * assurance ; il rend désormais un refus explicite plutôt qu'un nombre plausible.
     */
    public static void startHeadless(net.minecraft.server.MinecraftServer server) {
        var verdict = fr.clubcitrouille.lanterne.lab.Preflight.check(
                server, server.overworld(), expected);
        if (!fr.clubcitrouille.lanterne.lab.Preflight.announce("banc de vitesse", verdict)) {
            server.halt(false);
            return;
        }
        headless = true;
        stopAfter = server;
        listener = null;
        // La scène est remise à neuf avant la première phase comme avant la seconde. Sans cela, la
        // phase active héritait des ticks de décantation écoulés depuis la construction — vingt de
        // plus que sa rivale — et traitait dix-huit pour cent d'explosions de plus. L'écart jouait
        // contre le mod, mais un banc ne doit pas se tromper, même en sa défaveur.
        fr.clubcitrouille.lanterne.lab.Scene.rearm(server.overworld());
        // Et les compteurs repartent de zéro avec elle. Ils couraient depuis le démarrage du serveur,
        // si bien que la phase active se voyait attribuer toutes les explosions du chargement, de la
        // décantation et du contrôle préalable. Le témoin valait 2554 à trois exécutions près d'une
        // unité ; la phase active, elle, oscillait entre 2951 et 3019 — non parce qu'elle travaillait
        // davantage, mais parce qu'elle héritait d'un préambule de durée variable.
        fr.clubcitrouille.lanterne.core.Rubble.reset();
        phase = Phase.WARM_ON;
        phaseOpened = System.nanoTime();
        left = WARMUP;
        filled = 0;
        Settings.setEnabled(true);
        fr.clubcitrouille.lanterne.Lanterne.LOG.info(
                "Banc lancé — deux phases de {} s.", SAMPLE / 20);
    }

    public static void start(CommandSourceStack source) {
        headless = false;
        stopAfter = null;
        listener = source;
        phase = Phase.WARM_ON;
        phaseOpened = System.nanoTime();
        left = WARMUP;
        filled = 0;
        Settings.setEnabled(true);

        source.sendSuccess(() -> Component.literal(
                        "Banc lancé — deux phases de " + (SAMPLE / 20) + " s. Ne bouge pas, et ne touche à rien.")
                .withStyle(ChatFormatting.GOLD), false);
    }

    /** Ouvre le chronomètre du tick. */
    public static void beginTick() {
        if (running()) {
            tickStart = System.nanoTime();
        }
    }

    /**
     * Ferme le chronomètre et fait avancer le protocole.
     *
     * <p>On mesure ici et non via {@link TickBudget} : celui-ci lisse et rejette les valeurs
     * extrêmes, ce qui est exactement ce qu'il faut pour piloter la sévérité, et exactement ce
     * qu'il ne faut pas pour mesurer.
     */
    public static void endTick(ServerLevel level) {
        if (!running()) {
            return;
        }
        // La charge de lumière agit pendant la mesure, et non avant elle : une lampe immobile ne
        // coûte rien, le moteur ne travaille que sur le changement. Elle est donc déclenchée avant
        // l'arrêt du chronomètre, pour que son prix entre dans le tick qu'on mesure. Les autres
        // charges ignorent cet appel.
        fr.clubcitrouille.lanterne.lab.Scene.stir(level);
        long elapsed = System.nanoTime() - tickStart;

        switch (phase) {
            case WARM_ON -> {
                if (--left <= 0 || warmEnough()) {
                    phase = Phase.MEASURE_ON;
                    filled = 0;
                    allocMark = allocatedBytes();
                    phaseOpened = System.nanoTime();
                    gcMark = gcCount();
                    fr.clubcitrouille.lanterne.lab.Understudy.resetPackets();
                    fr.clubcitrouille.lanterne.lab.Sampler.start(Thread.currentThread());
                    // Le profileur d'allocations ne s'ouvre que si on le demande : démarrer le
                    // magnétoscope charge ses propres classes, et ces octets-là entraient dans la
                    // mesure de mémoire de la première phase — faussant la comparaison en faveur de
                    // la seconde, qui les trouvait déjà chargées.
                    if ("1".equals(System.getenv("LANTERNE_ALLOC"))) {
                        fr.clubcitrouille.lanterne.lab.Allocations.start();
                    }
                }
            }
            case MEASURE_ON -> {
                WITH[filled++] = elapsed;
                if (filled >= SAMPLE || overdue()) {
                    keptWith = filled;
                    allocWith = allocatedBytes() - allocMark;
                    gcWith = gcCount() - gcMark;
                    packetsWith = fr.clubcitrouille.lanterne.lab.Understudy.packetsSent();
                    // Relevé ici, et non dans le verdict : dans deux phases d'ici le mod sera éteint
                    // et ces compteurs ne diront plus rien de lui.
                    repartitionWith = repartition();
                    avoidedWith = fr.clubcitrouille.lanterne.core.Census.workAvoided();
                    jammedWith = fr.clubcitrouille.lanterne.core.Jam.held();
                    liveWith = liveBytes();
                    explosionsWith = fr.clubcitrouille.lanterne.core.Rubble.explosions();
                    phase = Phase.WARM_OFF;
                    phaseOpened = System.nanoTime();
                    left = WARMUP;
                    Settings.setEnabled(false);
                    // Avant la chauffe témoin, et non pendant la mesure : rebâtir coûte cent mille
                    // poses de bloc, qui n'ont rien à faire dans une fenêtre chronométrée.
                    fr.clubcitrouille.lanterne.lab.Scene.rearm(level);
                    fr.clubcitrouille.lanterne.lab.Sampler.stop();
                    say("── Profil AVEC Lanterne ──");
                    fr.clubcitrouille.lanterne.lab.Sampler.report(24);
                    fr.clubcitrouille.lanterne.lab.Allocations.stopAndReport(20);
                    say("Phase 1 terminée (mod actif). Bascule — phase 2 sans le mod.");
                }
            }
            case WARM_OFF -> {
                if (--left <= 0 || warmEnough()) {
                    phase = Phase.MEASURE_OFF;
                    filled = 0;
                    allocMark = allocatedBytes();
                    phaseOpened = System.nanoTime();
                    gcMark = gcCount();
                    fr.clubcitrouille.lanterne.lab.Understudy.resetPackets();
                    // On profile la phase témoin, c'est-à-dire le jeu tel qu'il est. C'est cette
                    // répartition-là qu'il faut connaître : elle dit ce qui reste à attaquer.
                    fr.clubcitrouille.lanterne.lab.Sampler.start(Thread.currentThread());
                }
            }
            case MEASURE_OFF -> {
                WITHOUT[filled++] = elapsed;
                if (filled >= SAMPLE || overdue()) {
                    keptWithout = filled;
                    allocWithout = allocatedBytes() - allocMark;
                    gcWithout = gcCount() - gcMark;
                    packetsWithout = fr.clubcitrouille.lanterne.lab.Understudy.packetsSent();
                    liveWithout = liveBytes();
                    phase = Phase.DONE;
                    Settings.setEnabled(true);
                    fr.clubcitrouille.lanterne.lab.Sampler.stop();
                    conclude(level);
                    say("── Profil SANS Lanterne (le jeu tel quel) ──");
                    fr.clubcitrouille.lanterne.lab.Sampler.report(22);
                    if (stopAfter != null) {
                        stopAfter.halt(false);
                    }
                }
            }
            default -> { }
        }
    }

    private static void conclude(ServerLevel level) {
        double with = median(WITH, keptWith);
        double without = median(WITHOUT, keptWithout);
        double ratio = with <= 0d ? 0d : without / with;

        say("── Résultat ──");
        say(String.format(Locale.ROOT, "Sans Lanterne : %.2f ms par tick (médiane de %d relevés)",
                without / 1e6, keptWithout));
        say(String.format(Locale.ROOT, "Avec Lanterne : %.2f ms par tick (médiane de %d relevés)",
                with / 1e6, keptWith));
        if (keptWith < SAMPLE || keptWithout < SAMPLE) {
            say("Une phase au moins s'est arrêtée à l'échéance : la charge dépassait ce qu'un tick "
                    + "peut absorber. Le chiffre reste valable, il porte sur moins de ticks.");
        }
        say(String.format(Locale.ROOT, "Entités dans le monde : %d", count(level)));
        say(String.format(Locale.ROOT, "Modules actifs : %s", Settings.describe()));

        if (allocWithout > 0L) {
            double memoryRatio = (double) allocWithout / Math.max(1L, allocWith);
            say(String.format(Locale.ROOT,
                    "Mémoire allouée — sans : %s · avec : %s  (×%.2f moins)",
                    bytes(allocWithout), bytes(allocWith), memoryRatio));
            say(String.format(Locale.ROOT, "Ramassages — sans : %d · avec : %d",
                    gcWithout, gcWith));
        }
        if (liveWithout > 0L) {
            // L'ensemble vivant, et non le sommet de la dent de scie. C'est le chiffre qui dit
            // combien de mémoire il faut donner à ce serveur ; l'autre disait surtout à quel point
            // la machine virtuelle était patiente.
            say(String.format(Locale.ROOT,
                    "Mémoire retenue après ramassage — sans : %s · avec : %s  (%s)",
                    bytes(liveWithout), bytes(liveWith),
                    liveWith <= liveWithout
                            ? String.format(Locale.ROOT, "%s de moins",
                                    bytes(liveWithout - liveWith))
                            : String.format(Locale.ROOT, "%s de PLUS",
                                    bytes(liveWith - liveWithout))));
        }

        if (packetsWithout > 0L) {
            // <h2>Un ratio qui comparait deux durées différentes</h2>
            //
            // Ces compteurs étaient publiés en valeur absolue. À dix joueurs, le résultat a été
            // « paquets ×0,93 » — le mod en émettant <em>plus</em> que le témoin, ce qui n'a pas de sens
            // puisqu'il ne fait que les espacer.
            //
            // L'explication est dans la ligne du dessus : la phase témoin s'était arrêtée à l'échéance
            // au bout de 401 relevés, la phase active en avait fait 500. Le mod avait donc émis vingt-
            // cinq pour cent de paquets en plus parce qu'il avait vécu vingt-cinq pour cent de ticks en
            // plus — ce qui est précisément la preuve qu'il fonctionne, présentée comme un défaut.
            //
            // On divise donc par le nombre de ticks. C'est la seule grandeur comparable entre deux
            // phases qui n'ont pas duré le même nombre de ticks, et ce cas devient la règle dès que la
            // charge dépasse ce qu'un tick peut absorber.
            double perTickWithout = packetsWithout / (double) Math.max(1, keptWithout);
            double perTickWith = packetsWith / (double) Math.max(1, keptWith);
            say(String.format(Locale.ROOT,
                    "Paquets émis par tick — sans : %.0f · avec : %.0f  (×%.2f moins)",
                    perTickWithout, perTickWith,
                    perTickWithout / Math.max(0.001d, perTickWith)));
        }

        // Sans cette ventilation, un verdict « aucun effet » est indéchiffrable : on ne sait pas si
        // le mod n'a rien économisé, ou s'il n'a rien eu à économiser. Ce sont deux problèmes
        // opposés, et ils appellent des corrections opposées.
        say(String.format(Locale.ROOT, "Observateurs : %d · chunks recensés : %d",
                fr.clubcitrouille.lanterne.core.Census.probeCount(),
                fr.clubcitrouille.lanterne.core.Census.chunksSeen()));
        // Étiqueté « pendant la phase active », parce que ces compteurs ont déjà décrit la phase
        // témoin une fois, et que personne ne s'en est aperçu avant que deux chiffres du même
        // rapport ne se contredisent.
        say("Cadences pendant la phase active — " + repartitionWith);
        say(String.format(Locale.ROOT,
                "Travail évité pendant la phase active : %.1f %% · amas figés : %d",
                avoidedWith * 100d, jammedWith));
        // Cette ligne existe parce qu'un module a été mesuré « sans effet » alors qu'il ne s'était en
        // réalité jamais déclenché. Sans compteur d'activation, un gain nul et une optimisation morte
        // donnent le même chiffre — et appellent des corrections opposées.
        say(String.format(Locale.ROOT,
                "Recherches de collision évitées : %d · entités bloquantes : %d · "
                + "ticks de production rattrapés : %d",
                fr.clubcitrouille.lanterne.core.Solid.shortcuts(),
                fr.clubcitrouille.lanterne.core.Solid.blockers(),
                fr.clubcitrouille.lanterne.core.Produce.compensated()));

        long toggles = fr.clubcitrouille.lanterne.lab.Scene.lampToggles();
        if (toggles > 0L) {
            say(String.format(Locale.ROOT,
                    "Bascules de lampe pendant la phase témoin : %d (chacune efface puis repropage "
                    + "une sphère de quinze blocs de rayon)", toggles));
        }

        // Les deux phases ont-elles seulement subi la même charge ? Voir refuseUnequal.
        if (refuseUnequal()) {
            return;
        }

        // Le verdict, formulé pour être vérifiable et non pour flatter. Un gain sous cinq pour cent
        // n'est pas un gain : c'est du bruit, et le dire est la seule façon de rester crédible
        // quand le chiffre est bon.
        if (ratio > 1.05d) {
            say(String.format(Locale.ROOT, "Gain : ×%.2f (%.0f %% de temps en moins)",
                    ratio, (1d - with / without) * 100d));
        } else if (ratio < 0.95d) {
            say(String.format(Locale.ROOT,
                    "PERTE : ×%.2f — le mod coûte plus qu'il ne rapporte ici.", ratio));
        } else {
            say("Aucun effet mesurable dans cette situation (écart sous le bruit de fond).");
        }
    }

    /**
     * Refuse de conclure quand les deux phases n'ont pas abattu la même quantité de travail.
     *
     * <h2>Le défaut qui a produit quatre diagnostics faux d'affilée</h2>
     *
     * <p>Le protocole compare deux phases en supposant qu'elles subissent une charge identique. Pour
     * un troupeau ou un tas d'objets, c'est vrai : rien ne s'use. Pour la dynamite, c'est faux — la
     * première phase creuse le sol et la seconde explose dans le vide qu'elle a laissé.
     *
     * <p>Le banc a rendu quatre verdicts de « perte » sur cette charge. Quatre correctifs ont été
     * écrits pour expliquer une régression qui n'existait pas ; trois ont été démentis par la mesure
     * et retirés. Ce qui trahissait l'affaire était pourtant imprimé dans chacun de ces rapports :
     *
     * <pre>
     * Mémoire allouée · sans : 5,34 Go · avec : 11,34 Go
     * Paquets par tick · sans : 123    · avec : 443
     * </pre>
     *
     * <p>Un facteur trois sur le travail accompli. Je lisais ces lignes comme un symptôme du mod
     * — « il alloue trop » — alors qu'elles disaient l'inverse : la phase active <b>faisait trois fois
     * plus de choses</b>. Un rapport de temps entre deux charges différentes ne mesure rien.
     *
     * <p>La scène se reconstruit désormais entre les phases ({@code Scene.rearm}). Ce garde-fou reste
     * en second rideau, parce qu'une reconstruction peut être imparfaite et qu'une charge future sera
     * destructive sans qu'on y pense : il compte les explosions et les blocs qu'elles désignent,
     * indépendamment du mod, et se tait tant que les deux phases concordent.
     *
     * @return vrai si le verdict a été refusé et déjà expliqué
     */
    private static boolean refuseUnequal() {
        long explosions = fr.clubcitrouille.lanterne.core.Rubble.explosions();
        if (explosions == 0) {
            return false; // charge non destructive : rien à vérifier ici
        }
        long withCount = explosionsWith;
        long withoutCount = explosions - explosionsWith;
        if (withCount == 0 || withoutCount == 0) {
            return false;
        }
        double skew = (double) Math.max(withCount, withoutCount) / Math.min(withCount, withoutCount);
        if (skew <= 1.25d) {
            say(String.format(Locale.ROOT,
                    "Explosions traitées · avec : %d · sans : %d (écart %.0f %%, les deux phases "
                    + "sont comparables) · blocs désignés : %d · "
                    + "relectures de palette évitées : %d",
                    withCount, withoutCount, (skew - 1d) * 100d,
                    fr.clubcitrouille.lanterne.core.Rubble.blocksTouched(),
                    fr.clubcitrouille.lanterne.core.Rubble.lookupsSkipped()));
            return false;
        }
        say(String.format(Locale.ROOT,
                "VERDICT REFUSÉ · les deux phases n'ont pas subi la même charge : %d explosion(s) "
                + "avec le mod contre %d sans, soit un écart de ×%.2f. Un rapport de temps entre "
                + "deux charges différentes ne mesure pas le mod.", withCount, withoutCount, skew));
        return true;
    }

    /** Explosions comptées à la fin de la phase active, pour les distinguer de celles de la phase témoin. */
    private static long explosionsWith;

    /** Nombre d'entités réellement vivantes dans le monde — la vérification qui a tout débloqué. */
    public static int livingCount(ServerLevel level) {
        return count(level);
    }

    /**
     * Octets alloués par le thread courant depuis son démarrage.
     *
     * <p>La machine virtuelle tient ce compteur par thread, gratuitement. Il n'est pas exposé par
     * l'interface publique, d'où le détour par la classe d'implémentation — sans conséquence : en
     * cas d'absence, on rend zéro et le rapport se tait plutôt que de mentir.
     */
    private static long allocatedBytes() {
        try {
            java.lang.management.ThreadMXBean bean =
                    java.lang.management.ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean sun) {
                return sun.getCurrentThreadAllocatedBytes();
            }
        } catch (Throwable unsupported) {
            // Machine virtuelle sans cette extension : on renonce à la mesure, pas au banc.
        }
        return 0L;
    }

    /** Nombre total de ramassages depuis le démarrage, toutes générations confondues. */
    private static long gcCount() {
        long total = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0L) {
                total += count;
            }
        }
        return total;
    }

    private static String bytes(long value) {
        if (value > 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.2f Go", value / 1_073_741_824d);
        }
        if (value > 1_048_576L) {
            return String.format(Locale.ROOT, "%.1f Mo", value / 1_048_576d);
        }
        return String.format(Locale.ROOT, "%.1f Ko", value / 1024d);
    }

    private static int count(ServerLevel level) {
        int total = 0;
        for (var ignored : level.getAllEntities()) {
            total++;
        }
        return total;
    }

    /**
     * La médiane d'un échantillon.
     *
     * <p>Le tri coûte, mais il a lieu une fois en fin de banc : c'est exactement le genre d'endroit
     * où la simplicité vaut mieux que l'astuce.
     */
    private static double median(long[] values, int count) {
        // Ne trier que ce qui a été rempli. Depuis qu'une phase peut s'arrêter à l'échéance, le reste
        // du tableau contient des zéros : les inclure donnerait une médiane de zéro, donc un gain
        // infini, donc un verdict absurde présenté avec le même aplomb que les autres.
        if (count <= 0) {
            return 0d;
        }
        long[] copy = java.util.Arrays.copyOf(values, count);
        java.util.Arrays.sort(copy);
        int middle = count / 2;
        return count % 2 == 0
                ? (copy[middle - 1] + copy[middle]) / 2d
                : copy[middle];
    }

    private static void say(String text) {
        if (listener != null) {
            listener.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.WHITE), false);
        }
        if (headless) {
            fr.clubcitrouille.lanterne.Lanterne.LOG.info("[BANC] {}", text);
        }
    }
}
