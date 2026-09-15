package fr.clubcitrouille.lanterne.lab;

import java.util.Arrays;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La carrière : combien coûte un chunk qu'il faut créer, et combien coûte le même chunk qu'il faut
 * seulement relire ?
 *
 * <h2>Deux régimes que ce mod n'avait jamais distingués</h2>
 *
 * <p>Aucune épreuve de ce projet ne mesurait jusqu'ici l'axe chunk. Or ce coût recouvre deux
 * opérations sans rapport, que le brief impose de séparer plutôt que de moyenner :
 *
 * <ul>
 *   <li><b>Génération</b> : un chunk qui n'existe encore nulle part, à faire naître du bruit —
 *       structures, biomes, bruit de densité, surface, carvers, features, lumière, apparitions. Le
 *       cas d'un joueur qui explore un territoire neuf.</li>
 *   <li><b>Chargement</b> : un chunk déjà généré et sauvegardé, à relire depuis le disque —
 *       décompression, parsing NBT, reconstruction des sections. Le cas, largement majoritaire, d'un
 *       serveur établi où la carte a déjà été explorée une première fois.</li>
 * </ul>
 *
 * <p>Un chiffre unique qui moyennerait les deux ne décrirait ni l'un ni l'autre : un serveur avec un
 * an d'ancienneté charge presque exclusivement, un serveur tout neuf ne fait que générer. Ce banc
 * rend donc deux verdicts, jamais un seul.
 *
 * <h2>Le forçage synchrone, vérifié dans {@code ServerChunkCache}</h2>
 *
 * <p>{@code ServerChunkCache.getChunk(int, int, ChunkStatus, boolean)} (vérifié,
 * {@code ServerChunkCache.java} ligne 146) est la méthode que ce banc appelle pour chaque chunk. Le
 * booléen {@code loadOrGenerate} n'est pas un simple filtre de lecture : à {@code true}, il inscrit un
 * ticket ({@code TicketType.UNKNOWN}, niveau du statut cible) puis bloque le thread appelant — via
 * {@code mainThreadProcessor.managedBlock(...)} suivi d'un {@code join()}, corps de méthode vérifié —
 * jusqu'à ce que le chunk atteigne réellement le statut demandé. Un seul appel, un seul chunk,
 * intégralement généré <em>ou</em> chargé avant que l'appel ne rende la main : c'est ce qui permet de
 * chronométrer un chunk à la fois, en nanosecondes, sans deviner où s'arrête le travail asynchrone.
 *
 * <p>Le statut cible est {@code ChunkStatus.FULL}, le dernier des douze de la chaîne vérifiée dans
 * {@code ChunkStatus.java} (lignes 21-32) : {@code EMPTY → STRUCTURE_STARTS → STRUCTURE_REFERENCES →
 * BIOMES → NOISE → SURFACE → CARVERS → FEATURES → INITIALIZE_LIGHT → LIGHT → SPAWN → FULL}. C'est le
 * seul statut qui corresponde à un chunk réellement jouable, et donc la seule mesure qui vaille : un
 * banc qui s'arrêterait à {@code NOISE} sous-évaluerait la génération de tout ce qui vient après.
 *
 * <p>Le rapport {@code notes/recherche-chunks.md} (§1.1, vérifié indépendamment ici dans
 * {@code ChunkPyramid.java} lignes 9 et 41) documente que ce même statut cible est atteint par deux
 * chemins distincts selon l'état du chunk : {@code ChunkPyramid.GENERATION_PYRAMID} s'il n'existe pas
 * encore, {@code ChunkPyramid.LOADING_PYRAMID} s'il existe déjà sur disque. Le second traverse
 * {@code ChunkMap.scheduleChunkLoad} (vérifié, ligne 557) : lecture région ({@code readChunk}, ligne
 * 920), désérialisation NBT, puis {@code SerializableChunkData.parse}. Le premier ne lit jamais le
 * disque. Ce sont ces deux chemins, et non deux variantes d'un même travail, que ce banc chronomètre
 * séparément.
 *
 * <h2>Trois régions, jamais deux mesures sur le même terrain</h2>
 *
 * <p>Un chunk déjà généré ne se régénère pas : la moitié « mod éteint » du régime génération exige
 * donc une région vierge <b>différente</b> de celle du mod actif. Deux régions distinctes ne
 * produisent pas exactement le même relief, donc pas exactement le même coût — {@link #verdict} le
 * rappelle à chaque publication plutôt que de laisser croire à une comparaison parfaite.
 *
 * <p>Le régime chargement échappe à cette contrainte : relire un chunk déjà écrit est une opération
 * déterministe, reproductible autant de fois qu'on le souhaite sur les <b>mêmes</b> chunks. Une seule
 * région de chargement sert donc aux deux moitiés (mod actif, puis mod éteint), ce qui rend cette
 * comparaison strictement plus fiable que celle de la génération — et c'est dit dans le verdict.
 *
 * <p>Les trois régions vivent à cent mille blocs du centre, dans trois cadrans différents, à
 * distance les unes des autres et de tout ce qu'un joueur aurait pu visiter :
 *
 * <table>
 *   <caption>Régions du banc, en coordonnées de chunk</caption>
 *   <tr><th>Région</th><th>Origine (chunk)</th><th>Rôle</th></tr>
 *   <tr><td>Génération, mod actif</td><td>(6250, 6250)</td><td>chronométrée, jamais visitée</td></tr>
 *   <tr><td>Génération, mod éteint</td><td>(6250, -6250)</td><td>chronométrée, jamais visitée</td></tr>
 *   <tr><td>Chargement (les deux moitiés)</td><td>(-6250, 6250)</td><td>générée une fois hors mesure,
 *       relue deux fois</td></tr>
 * </table>
 *
 * <p>Cent mille blocs tient large sous la borne théorique {@code WorldBorder.MAX_CENTER_COORDINATE}
 * (vérifié, {@code WorldBorder.java} ligne 24, environ trente millions de blocs) — mais ce mod ne
 * suppose pas qu'un serveur donné garde la bordure par défaut. {@link #begin} interroge donc la
 * bordure <b>réelle</b> du monde en cours via {@code WorldBorder.isWithinBounds(ChunkPos)} (vérifié,
 * {@code WorldBorder.java} lignes 49 et 57) avant de commencer, et refuse de mesurer si elle a été
 * resserrée au point d'exclure une des trois régions — dans l'esprit de {@code Preflight}, sans
 * réutiliser cette classe : ses contrôles portent sur les joueurs et les entités, hors sujet ici,
 * exactement comme {@code Boom} l'explique pour son propre cas.
 *
 * <h2>Le piège du chargement : prouver qu'on lit vraiment le disque</h2>
 *
 * <p>C'est l'endroit où un banc trop sûr de lui publierait un chiffre qui ne mesure qu'un cache
 * mémoire. Trois garanties, chacune vérifiée dans les sources, s'enchaînent avant qu'un seul chunk de
 * la région de chargement ne soit rechronométré :
 *
 * <ol>
 *   <li><b>Sauvegarde forcée</b> — {@code ServerChunkCache.save(true)} (vérifié, ligne 307) appelle
 *       {@code ChunkMap.saveAllChunks(true)} (ligne 413), qui écrit tout chunk modifiable puis bloque
 *       sur {@code this.synchronize(true).join()} : les écritures en attente sont garanties posées sur
 *       le système de fichiers avant que l'appel ne rende la main.</li>
 *   <li><b>Éviction mémoire confirmée, pas supposée</b> — le ticket posé par {@code getChunk(...,
 *       true)} est du type {@code TicketType.UNKNOWN} (vérifié, {@code TicketType.java} ligne 29 :
 *       délai d'une tick, drapeau {@code FLAG_CAN_EXPIRE_IF_UNLOADED}). {@code Ticket.java} (vérifié)
 *       montre qu'il expire après deux appels à {@code TicketStorage.purgeStaleTickets} — deux ticks —
 *       après quoi {@code ChunkMap.processUnloads} (ligne 472) retire le chunk de
 *       {@code updatingChunkMap} puis programme sa sauvegarde et son détachement
 *       ({@code scheduleUnload}, ligne 513). Ce banc ne suppose pas ce délai : il interroge
 *       {@code ServerChunkCache.getChunkNow(int, int)} (vérifié, ligne 182 — rend {@code null} si
 *       aucun {@code ChunkHolder} n'est présent) à chaque tick, sur les {@value #GRID_CHUNKS} chunks
 *       de la région, jusqu'à ce que tous répondent {@code null}.</li>
 *   <li><b>Une marge après le premier {@code null}, pas une confiance aveugle en lui</b> — lecture
 *       serrée de {@code ChunkMap.updateChunkScheduling} (vérifié, lignes 380-386) : un chunk qui
 *       vient d'être retiré de {@code updatingChunkMap} peut encore traîner dans
 *       {@code pendingUnloads} le temps que sa sauvegarde asynchrone se termine, et si un rechargement
 *       est demandé <em>pendant</em> cette fenêtre, le code le récupère directement
 *       (« {@code chunk = this.pendingUnloads.remove(node)} ») sans jamais toucher le disque — un
 *       succès silencieux qui ne prouverait rien. Aucune méthode publique n'expose l'état de
 *       {@code pendingUnloads} pour fermer cette fenêtre avec certitude absolue ; ce banc ajoute donc,
 *       après le premier tick où les {@value #GRID_CHUNKS} chunks répondent {@code null}, une marge
 *       supplémentaire de {@value #SETTLE_AFTER_EVICTION_TICKS} ticks pendant laquelle il revérifie
 *       que rien ne redevient résident. Ce n'est pas une preuve mathématique — c'est une marge
 *       documentée, choisie très au-dessus du délai observé nécessaire (quelques ticks), et le dire
 *       est plus honnête que de prétendre à une certitude que l'API publique ne permet pas d'établir.</li>
 * </ol>
 *
 * <p><b>Ce qui reste hors de contrôle, et qu'aucune API Java ne referme</b> : même une fois le
 * {@code ChunkHolder} authentiquement disparu de la JVM, la lecture du fichier {@code .mca} passe par
 * l'appel système du système d'exploitation, qui garde ses propres pages en cache. Rien, depuis ce
 * mod, ne peut vider ce cache-là — le ferait-on qu'il faudrait des privilèges système et sortirait du
 * périmètre d'un mixin de contenu. Le chiffre de chargement publié ici mesure donc « relecture depuis
 * la JVM vide, à travers le cache disque du système d'exploitation », pas « lecture physique du
 * plateau » — c'est la limite du protocole la plus honnête à admettre, et non un défaut caché.
 *
 * <p><b>Seconde limite, tout aussi réelle</b> : ce banc ne peut pas vérifier depuis l'API publique que
 * les régions de génération n'ont jamais été touchées lors d'une exécution précédente sur la même
 * sauvegarde — aucune méthode de {@code ServerChunkCache} ne permet de sonder l'existence d'un fichier
 * sans déclencher, justement, un chargement ou une génération. {@link #countSuspectlyFast} signale
 * les chunks « générés » en moins de {@value #GEN_SUSPECT_MS} ms — un temps que la génération complète
 * d'un chunk (des dizaines de milliers d'évaluations de bruit par chunk, voir
 * {@code recherche-chunks.md} §1.3) n'atteint normalement jamais — mais ce n'est qu'un signal
 * d'alarme, pas une preuve d'absence. Relancer ce banc sur une sauvegarde déjà utilisée par lui
 * invaliderait silencieusement l'axe génération si ce signal n'apparaissait pas non plus : à changer
 * les coordonnées entre deux exécutions sur le même monde, par prudence.
 *
 * <h2>Chauffe, médiane, marge</h2>
 *
 * <p>La première rangée de chaque grille ({@value #WARMUP_CHUNKS} chunks sur {@value #GRID_CHUNKS})
 * est jetée avant de commencer à mesurer. Ce n'est pas la chauffe du compilateur à la volée décrite
 * dans {@code Bench} — un seul chunk exécute déjà des dizaines de milliers d'évaluations de bruit,
 * largement de quoi déclencher la compilation. C'est un coût différent : le premier chunk d'une
 * dimension paie des initialisations paresseuses (caches de {@code StructureCheck}, premiers
 * remplissages des marqueurs {@code DensityFunction} décrits dans {@code recherche-chunks.md} §4) que
 * les chunks suivants n'ont plus à payer. Ne pas le jeter ferait passer un coût d'amorçage pour un
 * coût récurrent.
 *
 * <p>Comme partout dans ce mod, le verdict retient la <b>médiane</b>, jamais la moyenne — un chunk
 * dont la génération croise une sauvegarde automatique ne doit pas peser sur le chiffre publié. Le
 * total (somme réelle des mesures utiles, pas médiane × N) est publié à côté : c'est lui qui dit
 * combien de temps machine {@value #MEASURED} chunks ont réellement coûté.
 */
public final class Quarry {
    /** Côté de la grille carrée de chunks par région : {@value #SIDE} × {@value #SIDE}. */
    private static final int SIDE = 8;
    /** Chunks par région, chauffe comprise. */
    private static final int GRID_CHUNKS = SIDE * SIDE;
    /** Première rangée de la grille, jetée avant de mesurer — voir le Javadoc de classe. */
    private static final int WARMUP_CHUNKS = SIDE;
    /** Chunks réellement retenus dans chaque médiane. */
    private static final int MEASURED = GRID_CHUNKS - WARMUP_CHUNKS;

    /** Origine (en coordonnées de chunk) de la région de génération, mod actif. */
    private static final int GEN_ON_CX = 13750;
    private static final int GEN_ON_CZ = 13750;
    /** Origine de la région de génération témoin — DIFFÉRENTE, un chunk généré ne se régénère pas. */
    private static final int GEN_OFF_CX = 6250;
    private static final int GEN_OFF_CZ = -6250;
    /** Origine de la région de chargement, partagée par les deux moitiés (opération déterministe). */
    private static final int LOAD_CX = -6250;
    private static final int LOAD_CZ = 6250;

    /**
     * Ticks tolérés en attente d'une éviction mémoire confirmée avant de refuser de mesurer.
     *
     * <p>Le mécanisme vérifié (expiration du ticket en deux ticks, puis retrait au tick suivant)
     * suggère un délai réel de l'ordre de quelques ticks. Cette valeur est délibérément très au-dessus
     * de cette estimation : un dépassement signale une vraie anomalie, pas une marge trop courte.
     */
    private static final int MAX_EVICT_WAIT = 400;
    /**
     * Marge appliquée après le premier tick où les {@value #GRID_CHUNKS} chunks répondent absents —
     * voir le Javadoc de classe sur la fenêtre {@code pendingUnloads} qu'aucune API publique ne ferme
     * avec certitude.
     */
    private static final int SETTLE_AFTER_EVICTION_TICKS = 20;

    /** Seuil, en nanosecondes, en deçà duquel une « génération » est signalée comme suspecte. */
    private static final long GEN_SUSPECT_NS = 500_000L;
    private static final double GEN_SUSPECT_MS = GEN_SUSPECT_NS / 1e6;

    private enum Step {
        OFF, GEN_PAIRED, LOAD_SEED, LOAD_SAVE_1, LOAD_WAIT_1, LOAD_ON, LOAD_SAVE_2, LOAD_WAIT_2,
        LOAD_OFF, DONE
    }

    /**
     * Nombre de mesures par côté, la grille étant partagée en deux par alternance.
     *
     * <h2>Deux régions différentes ne se comparent pas</h2>
     *
     * <p>Le protocole d'origine générait une région avec le mod, une autre sans, à des coordonnées
     * éloignées. C'était la seule façon apparente de contourner un fait têtu : <b>un chunk généré ne se
     * régénère pas</b>.
     *
     * <p>La première exécution a rendu « PERTE ×0,83 » — le mod plus cher que le témoin. Un résultat que
     * le banc lui-même refusait d'endosser, et à juste titre : 43,8 ms contre 36,2, sur deux reliefs
     * différents. Une montagne coûte plus cher qu'une plaine, et rien dans ce chiffre ne disait laquelle
     * on avait tirée.
     *
     * <p>On alterne donc <b>à l'intérieur d'une même grille</b> : un chunk avec le mod, le suivant sans,
     * et ainsi de suite. Les chunks voisins partagent leur relief, leurs structures et leur biome ; ce
     * qui les distingue encore se répartit également entre les deux moitiés au lieu de s'accumuler d'un
     * seul côté.
     *
     * <p>Le prix de cet appariement est une bascule du mod à chaque chunk. C'est acceptable ici — la
     * génération dure des dizaines de millisecondes, contre quelques nanosecondes pour changer un
     * booléen — alors que ce serait absurde sur un tick d'entité.
     */
    private static final int PAIRED = MEASURED / 2;

    private static Step step = Step.OFF;
    /** Index du chunk courant dans la grille en cours, de 0 à {@link #GRID_CHUNKS} exclu. */
    private static int shot;
    /** Ticks passés à attendre une éviction qui ne vient pas encore. */
    private static int evictWait;
    /** Ticks restants de la marge de confirmation après la première éviction observée. */
    private static int settleLeft;
    private static MinecraftServer host;

    /** Faux si l'éviction n'a jamais pu être garantie — la moitié correspondante n'est pas publiée. */
    private static boolean loadOnAvailable = true;
    private static boolean loadOffAvailable = true;

    private static final long[] genOn = new long[PAIRED];
    private static final long[] genOff = new long[PAIRED];
    /** Combien de mesures rangées de chaque côté : l'alternance ne les remplit pas au même rythme. */
    private static int genOnFilled;
    private static int genOffFilled;
    private static final long[] loadOn = new long[MEASURED];
    private static final long[] loadOff = new long[MEASURED];

    /** Octets alloués, tous fils confondus, au moment où la génération commence. Voir {@code cps}. */
    private static long allocationMark;
    /** Chunks réellement générés, chauffe comprise : le dénominateur des octets par chunk. */
    private static int genChunksDone;
    /** Les trois relevés figés à la fin de la génération appariée — voir {@code measurePairedChunk}. */
    private static long allocatedDuringGeneration;
    private static int genChunksCounted;
    private static String genStages = "";
    /**
     * Le total de {@code minecraft:noise} figé au même instant que la ventilation.
     *
     * <p>Dénominateur de {@code lab/Filon}. Le relever au rapport plutôt qu'ici y mêlerait
     * l'amorçage et la relecture de la région de chargement, et les pourcentages seraient faux —
     * même raison que pour les octets par chunk.
     */
    private static long genNoiseNanos;

    private Quarry() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    /**
     * Ouvre l'épreuve, après un unique contrôle : les trois régions tiennent-elles dans la bordure du
     * monde ? Rien d'autre ne peut être vérifié à l'avance depuis l'API publique — voir le Javadoc de
     * classe sur la limite acceptée concernant la virginité réelle des régions de génération.
     */
    public static void begin(MinecraftServer server) {
        host = server;
        ServerLevel level = server.overworld();

        if (!regionWithinBorder(level, GEN_ON_CX, GEN_ON_CZ)
                || !regionWithinBorder(level, GEN_OFF_CX, GEN_OFF_CZ)
                || !regionWithinBorder(level, LOAD_CX, LOAD_CZ)) {
            Lanterne.LOG.error(
                    "[CARRIÈRE] MESURE REFUSÉE : au moins une des trois régions de {}×{} chunks déborde "
                            + "de la bordure du monde (WorldBorder.isWithinBounds). Rapprocher les "
                            + "coordonnées du centre avant de relancer.",
                    SIDE, SIDE);
            step = Step.OFF;
            host.halt(false);
            return;
        }

        step = Step.GEN_PAIRED;
        // Le profileur ne connaissait que le banc de vitesse. La génération d'un chunk coûte trente-sept
        // millisecondes et personne n'avait jamais regardé où elles passent — le forçage synchrone fait
        // tourner une bonne part du travail sur ce fil, donc un profil y est lisible.
        fr.clubcitrouille.lanterne.lab.Sampler.start(Thread.currentThread());
        shot = 0;
        evictWait = 0;
        settleLeft = 0;
        loadOnAvailable = true;
        loadOffAvailable = true;
        genChunksDone = 0;
        // Le relevé d'allocation et celui des étapes sont pris ICI et non au démarrage du serveur :
        // charger un monde alloue des centaines de mégaoctets, et les compter dans le coût d'un chunk
        // rendrait le chiffre faux d'un ordre de grandeur. C'est la troisième règle d'instrument de ce
        // dépôt — un pourcentage n'a de sens que rapporté à ce qui a été totalisé.
        allocationMark = allocatedByAllThreads();
        fr.clubcitrouille.lanterne.core.Forge.reset();
        Filon.reset();
        Settings.setEnabled(true);

        Lanterne.LOG.info(
                "[CARRIÈRE] Épreuve lancée — {} chunks par région ({} de chauffe jetés par région, {} "
                        + "mesures utiles). Génération/actif en ({},{}), génération/témoin en ({},{}), "
                        + "chargement en ({},{}). Chunks déjà résidents en mémoire au départ : {}.",
                GRID_CHUNKS, WARMUP_CHUNKS, MEASURED, GEN_ON_CX, GEN_ON_CZ, GEN_OFF_CX, GEN_OFF_CZ,
                LOAD_CX, LOAD_CZ, level.getChunkSource().getLoadedChunksCount());
    }

    /** Fait avancer l'épreuve d'un pas. Appelé à chaque tick du serveur, comme {@code Boom.tick}. */
    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        ServerChunkCache chunkSource = level.getChunkSource();

        switch (step) {
            case GEN_PAIRED -> measurePairedChunk(chunkSource);
            case LOAD_SEED -> seedLoadRegion(chunkSource);
            case LOAD_SAVE_1 -> {
                chunkSource.save(true);
                Lanterne.LOG.info("[CARRIÈRE] Sauvegarde forcée de la région de chargement — attente de "
                        + "l'éviction mémoire avant la moitié « mod actif ».");
                step = Step.LOAD_WAIT_1;
            }
            case LOAD_WAIT_1 -> waitEviction(chunkSource, true);
            case LOAD_ON -> measureChunk(chunkSource, LOAD_CX, LOAD_CZ, loadOn, () -> {
                Settings.setEnabled(false);
                step = Step.LOAD_SAVE_2;
            });
            case LOAD_SAVE_2 -> {
                chunkSource.save(true);
                Lanterne.LOG.info("[CARRIÈRE] Sauvegarde forcée de la région de chargement — attente de "
                        + "l'éviction mémoire avant la moitié « mod éteint ».");
                step = Step.LOAD_WAIT_2;
            }
            case LOAD_WAIT_2 -> waitEviction(chunkSource, false);
            case LOAD_OFF -> measureChunk(chunkSource, LOAD_CX, LOAD_CZ, loadOff, () -> {
                Settings.setEnabled(true);
                finish();
            });
            default -> { }
        }
    }

    /**
     * Chronomètre un chunk de la grille courante, puis avance à la case suivante ou change de phase.
     *
     * <p>Un seul chunk par tick, jamais {@value #GRID_CHUNKS} d'affilée : {@code getChunk(...,
     * true)} bloque déjà le thread principal le temps d'un chunk entier, souvent plusieurs
     * millisecondes en génération — en enchaîner plusieurs dans le même tick reviendrait à fabriquer
     * artificiellement le pic de charge que {@code Rationing} est censé absorber, exactement le piège
     * que {@code Boom} évite pour la même raison.
     */
    /**
     * Mesure un chunk, en alternant l'état du mod d'un chunk au suivant.
     *
     * <p>La bascule a lieu <b>avant</b> le chronomètre : ce qu'on mesure est la génération, pas le
     * changement d'un booléen.
     */
    private static void measurePairedChunk(ServerChunkCache chunkSource) {
        ChunkPos pos = chunkPosAt(GEN_ON_CX, GEN_ON_CZ, shot);

        // <h2>L'appariement avait son propre biais, et il fallait deux exécutions pour le voir</h2>
        //
        // La première version donnait les chunks pairs au mod et les impairs au témoin. Le verdict est
        // tombé à « PERTE ×0,91 » — et le régime chargement, mesuré juste après sur un protocole
        // séquentiel, à ×0,84. Or <b>aucun module de ce mod ne touche au chargement</b> : les deux
        // pertes avaient donc une cause commune, et cette cause ne pouvait pas être le mod.
        //
        // C'était l'ordre. Dans une paire de chunks voisins, le premier paie ce que le second réutilise
        // — structures partagées, caches de bruit, chemins de code que le compilateur vient tout juste
        // de traiter. En donnant systématiquement le rang impair au témoin, on lui offrait un avantage
        // à chaque paire.
        //
        // On inverse donc la parité à la moitié de la grille : le mod passe premier sur la première
        // moitié, second sur la seconde. Le biais d'ordre existe toujours, mais il tombe autant d'un
        // côté que de l'autre — ce qui est la seule façon de s'en débarrasser sans le comprendre
        // entièrement.
        boolean firstHalf = shot < GRID_CHUNKS / 2;
        boolean active = ((shot & 1) == 0) == firstHalf;
        Settings.setEnabled(active);

        long start = System.nanoTime();
        chunkSource.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, true);
        long elapsed = System.nanoTime() - start;
        genChunksDone++;

        if (shot >= WARMUP_CHUNKS) {
            if (active) {
                if (genOnFilled < genOn.length) {
                    genOn[genOnFilled++] = elapsed;
                }
            } else if (genOffFilled < genOff.length) {
                genOff[genOffFilled++] = elapsed;
            }
        }
        shot++;

        if (shot >= GRID_CHUNKS) {
            shot = 0;
            Settings.setEnabled(true);
            // Les deux relevés du débit sont figés ICI, et non au rapport : ce qui suit — amorçage
            // puis relecture de la région de chargement — est de la GÉNÉRATION et de la LECTURE qui
            // n'ont rien à faire dans un bilan de génération pure. Les laisser s'y ajouter aurait
            // gonflé les octets par chunk et noyé la ventilation par étape.
            allocatedDuringGeneration = allocatedByAllThreads() - allocationMark;
            genChunksCounted = genChunksDone;
            genStages = fr.clubcitrouille.lanterne.core.Forge.describe();
            genNoiseNanos = fr.clubcitrouille.lanterne.core.Forge.nanos("minecraft:noise");
            Lanterne.LOG.info("[CARRIÈRE] Génération appariée terminée — {} mesures avec le mod, {} sans, "
                    + "sur la même grille. Amorçage, hors mesure, de la région de chargement.",
                    genOnFilled, genOffFilled);
            step = Step.LOAD_SEED;
        }
    }

    private static void measureChunk(ServerChunkCache chunkSource, int originCx, int originCz,
            long[] samples, Runnable onGridDone) {
        ChunkPos pos = chunkPosAt(originCx, originCz, shot);
        long start = System.nanoTime();
        chunkSource.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, true);
        long elapsed = System.nanoTime() - start;

        if (shot >= WARMUP_CHUNKS) {
            samples[shot - WARMUP_CHUNKS] = elapsed;
        }
        shot++;

        if (shot >= GRID_CHUNKS) {
            shot = 0;
            onGridDone.run();
        }
    }

    /** Génère la région de chargement une fois, sans chronométrer : elle ne sert qu'à exister. */
    private static void seedLoadRegion(ServerChunkCache chunkSource) {
        ChunkPos pos = chunkPosAt(LOAD_CX, LOAD_CZ, shot);
        chunkSource.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, true);
        shot++;

        if (shot >= GRID_CHUNKS) {
            shot = 0;
            Lanterne.LOG.info("[CARRIÈRE] Région de chargement amorcée : {} chunks écrits sur disque, "
                    + "prêts à être déchargés puis relus.", GRID_CHUNKS);
            step = Step.LOAD_SAVE_1;
        }
    }

    /**
     * Attend une éviction mémoire confirmée de toute la région de chargement, avec la marge décrite
     * dans le Javadoc de classe. Ne mesure rien tant que la confirmation n'est pas acquise.
     */
    private static void waitEviction(ServerChunkCache chunkSource, boolean firstHalf) {
        boolean evicted = loadRegionEvicted(chunkSource);

        if (settleLeft > 0) {
            if (!evicted) {
                Lanterne.LOG.warn("[CARRIÈRE] Un chunk de la région de chargement est redevenu résident "
                        + "pendant la marge de confirmation — on recompte depuis zéro, par prudence.");
                settleLeft = 0;
            } else {
                settleLeft--;
                if (settleLeft == 0) {
                    Lanterne.LOG.info("[CARRIÈRE] Éviction confirmée puis stable {} ticks de plus — "
                                    + "aucun des {} chunks n'est résident en mémoire.",
                            SETTLE_AFTER_EVICTION_TICKS, GRID_CHUNKS);
                    evictWait = 0;
                    shot = 0;
                    step = firstHalf ? Step.LOAD_ON : Step.LOAD_OFF;
                }
                return;
            }
        }

        if (evicted) {
            settleLeft = SETTLE_AFTER_EVICTION_TICKS;
            return;
        }

        evictWait++;
        if (evictWait > MAX_EVICT_WAIT) {
            Lanterne.LOG.error(
                    "[CARRIÈRE] MESURE REFUSÉE : après {} ticks, au moins un chunk de la région de "
                            + "chargement reste résident en mémoire. Publier un chiffre maintenant "
                            + "mesurerait un accès en cache, pas une lecture disque — cette moitié est "
                            + "abandonnée plutôt que faussée.",
                    MAX_EVICT_WAIT);
            evictWait = 0;
            settleLeft = 0;
            if (firstHalf) {
                loadOnAvailable = false;
                Settings.setEnabled(false);
                step = Step.LOAD_SAVE_2;
            } else {
                loadOffAvailable = false;
                Settings.setEnabled(true);
                finish();
            }
        }
    }

    /** Vrai si aucun chunk de la région de chargement n'a de {@code ChunkHolder} résident. */
    private static boolean loadRegionEvicted(ServerChunkCache chunkSource) {
        for (int i = 0; i < GRID_CHUNKS; i++) {
            ChunkPos pos = chunkPosAt(LOAD_CX, LOAD_CZ, i);
            if (chunkSource.getChunkNow(pos.x(), pos.z()) != null) {
                return false;
            }
        }
        return true;
    }

    private static ChunkPos chunkPosAt(int originCx, int originCz, int index) {
        int dx = index % SIDE;
        int dz = index / SIDE;
        return new ChunkPos(originCx + dx, originCz + dz);
    }

    private static boolean regionWithinBorder(ServerLevel level, int originCx, int originCz) {
        WorldBorder border = level.getWorldBorder();
        ChunkPos near = new ChunkPos(originCx, originCz);
        ChunkPos far = new ChunkPos(originCx + SIDE - 1, originCz + SIDE - 1);
        return border.isWithinBounds(near) && border.isWithinBounds(far);
    }

    private static void finish() {
        step = Step.DONE;
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    /**
     * Les deux verdicts, rendus séparément — voir le Javadoc de classe sur la raison de ne jamais les
     * moyenner.
     */
    private static void report() {
        fr.clubcitrouille.lanterne.lab.Sampler.stop();
        Lanterne.LOG.info("[CARRIÈRE] ── Où passent les millisecondes d'une génération ──");
        fr.clubcitrouille.lanterne.lab.Sampler.report(26);
        double genOnMedian = median(genOn);
        double genOffMedian = median(genOff);
        long genOnTotal = total(genOn);
        long genOffTotal = total(genOff);
        double genRatio = genOnMedian <= 0d ? 0d : genOffMedian / genOnMedian;

        Lanterne.LOG.info("[CARRIÈRE] ── Régime GÉNÉRATION — grille unique, chunks appariés par "
                + "alternance ({} avec le mod, {} sans) ──", genOnFilled, genOffFilled);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[CARRIÈRE] Mod actif  : médiane %.3f ms/chunk · total %.1f ms",
                genOnMedian / 1e6, genOnTotal / 1e6));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[CARRIÈRE] Mod éteint : médiane %.3f ms/chunk · total %.1f ms",
                genOffMedian / 1e6, genOffTotal / 1e6));

        int genOnSuspect = countSuspectlyFast(genOn);
        int genOffSuspect = countSuspectlyFast(genOff);
        if (genOnSuspect > 0 || genOffSuspect > 0) {
            Lanterne.LOG.warn(String.format(Locale.ROOT,
                    "[CARRIÈRE] ATTENTION : %d (actif) + %d (éteint) chunk(s) « générés » en moins de "
                            + "%.1f ms — anormalement rapide pour une génération complète ; la région "
                            + "n'était peut-être pas vierge (rejeu du banc sur la même sauvegarde ?). "
                            + "Chiffres de génération à considérer avec prudence.",
                    genOnSuspect, genOffSuspect, GEN_SUSPECT_MS));
        }
        // Les trois nombres du cadastre, et pas seulement le plus flatteur.
        //
        // La règle que ce projet s'est donnée après la chute libre — taux, épargne, appels — vaut ici
        // en entier : un index qui ne voit aucune structure ne peut rien rapporter, et un index qui
        // renonce souvent non plus. Sans ces lignes, un verdict « aucun effet » serait
        // indéchiffrable : on ne saurait pas si le module a échoué ou s'il n'a simplement jamais
        // rencontré de village.
        Lanterne.LOG.info("[CARRIÈRE] Cadastre : {}",
                fr.clubcitrouille.lanterne.core.Cadastre.describe());
        Lanterne.LOG.info("[CARRIÈRE] Calque : {}",
                fr.clubcitrouille.lanterne.core.Calque.describe());

        Lanterne.LOG.info(
                "[CARRIÈRE] VERDICT génération : {} — chunks appariés dans la MÊME grille, un sur deux "
                        + "de chaque côté : le relief se répartit également au lieu de s'accumuler d'un "
                        + "seul côté. Un seul module de ce mod touche à la génération — le cadastre ; "
                        + "hors de lui, tout écart notable est un signal sur le protocole, pas sur le "
                        + "mod.",
                verdict(genRatio));

        Lanterne.LOG.info(
                "[CARRIÈRE] ── Régime CHARGEMENT — mêmes {} chunks, relus depuis le disque après "
                        + "éviction mémoire confirmée ──",
                GRID_CHUNKS);
        Double loadOnMedian = null;
        Double loadOffMedian = null;
        if (loadOnAvailable) {
            loadOnMedian = median(loadOn);
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[CARRIÈRE] Mod actif  : médiane %.3f ms/chunk · total %.1f ms (région chunk %d,%d)",
                    loadOnMedian / 1e6, total(loadOn) / 1e6, LOAD_CX, LOAD_CZ));
        } else {
            Lanterne.LOG.info("[CARRIÈRE] Mod actif  : NON MESURÉ — éviction mémoire non garantie, voir "
                    + "l'erreur ci-dessus.");
        }
        if (loadOffAvailable) {
            loadOffMedian = median(loadOff);
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[CARRIÈRE] Mod éteint : médiane %.3f ms/chunk · total %.1f ms (région chunk %d,%d)",
                    loadOffMedian / 1e6, total(loadOff) / 1e6, LOAD_CX, LOAD_CZ));
        } else {
            Lanterne.LOG.info("[CARRIÈRE] Mod éteint : NON MESURÉ — éviction mémoire non garantie, voir "
                    + "l'erreur ci-dessus.");
        }
        if (loadOnMedian != null && loadOffMedian != null) {
            double loadRatio = loadOnMedian <= 0d ? 0d : loadOffMedian / loadOnMedian;
            // <h2>Un verdict que la première exécution a suffi à disqualifier</h2>
            //
            // Ce protocole était présumé « plus fiable que celui de la génération », puisqu'il réutilise
            // la même région. La première exécution a rendu <b>×30,41 en faveur du mod</b> : 0,231 ms
            // contre 7,029.
            //
            // C'est impossible. Aucun module de ce mod ne touche au chargement d'un chunk depuis le
            // disque. Un facteur trente ne peut donc venir que du protocole — très probablement de la
            // fenêtre que l'on savait ne pas pouvoir fermer : un chunk retiré de la carte des chunks
            // peut encore traîner dans la file de déchargement et être rendu tel quel, sans lecture.
            // La confirmation d'éviction et les vingt ticks de marge n'ont pas suffi.
            //
            // On refuse donc de publier ce rapport. Un chiffre impossible reste impossible même quand
            // il flatte le mod — et c'est précisément quand il flatte qu'il faut le rejeter.
            boolean credible = loadRatio > 0.8d && loadRatio < 1.25d;
            if (credible) {
                Lanterne.LOG.info("[CARRIÈRE] VERDICT chargement : {} — conforme à l'attendu, aucun "
                        + "module de ce mod ne touchant au chargement.", verdict(loadRatio));
            } else {
                Lanterne.LOG.warn(String.format(Locale.ROOT,
                        "[CARRIÈRE] VERDICT chargement : REJETÉ (rapport ×%.2f). Aucun module de ce "
                        + "mod ne touche au chargement d'un chunk : un écart de cette ampleur ne peut "
                        + "venir que du protocole. L'éviction mémoire n'est pas garantie — un chunk "
                        + "peut être rendu depuis la file de déchargement sans lecture disque. "
                        + "Chiffre à ne pas citer.", loadRatio));
            }
        }

        cps(genOnMedian, genOffMedian, genOnSuspect + genOffSuspect);

        if (loadOnMedian != null) {
            double crossRatio = loadOnMedian <= 0d ? 0d : genOnMedian / loadOnMedian;
            Lanterne.LOG.info("[CARRIÈRE] ── Le point du banc : génération contre chargement (mod "
                    + "actif) ──");
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[CARRIÈRE] Générer un chunk coûte environ %.1f fois ce que coûte le relire une fois "
                            + "qu'il existe déjà (médiane %.3f ms contre %.3f ms). Un serveur établi "
                            + "charge bien plus souvent qu'il ne génère : c'est ce second chiffre qui "
                            + "domine le coût réel d'un serveur en production, pas le premier.",
                    crossRatio, genOnMedian / 1e6, loadOnMedian / 1e6));
        }
    }

    /**
     * Le débit en chunks par seconde, et les quatre choses sans lesquelles il ne veut rien dire.
     *
     * <h2>Pourquoi ce chiffre a besoin d'autant de précautions</h2>
     *
     * <p>Un débit de génération est la grandeur la plus facile à falsifier de tout ce dépôt, et ce
     * banc s'est déjà fait prendre : une exécution qui rejouait les coordonnées de la précédente a
     * annoncé <b>209 chunks par seconde</b> au lieu de 13,7 — quinze fois, pour la seule raison
     * qu'elle relisait le disque au lieu de générer. Voir {@code lab/Swarm}, qui porte le récit.
     *
     * <p>D'où le refus qui ouvre cette méthode. Un chunk « généré » en moins d'une demi-milliseconde
     * n'a pas été généré : il existait. S'il y en a plus d'un sur dix, la région n'était pas vierge et
     * <b>aucun débit n'est publié</b> — pas un débit prudent, pas un débit annoté : aucun.
     *
     * <p>Les trois autres précautions sont des <em>conditions</em> qu'il faut lire à côté du chiffre,
     * parce qu'un débit sans elles n'est comparable à rien :
     *
     * <ul>
     *   <li><b>Le nombre de fils.</b> La cible de ce projet est un cœur unique. Un débit relevé sur
     *       huit cœurs n'y sera pas reproduit, et c'est le cas mono-fil qui décide.</li>
     *   <li><b>Les octets alloués par chunk.</b> Sur quatre gigaoctets et un cœur, le ramasse-miettes
     *       ne travaille pas <em>à côté</em> du tick : il le lui prend. Un débit payé en allocations
     *       n'est pas un débit.</li>
     *   <li><b>La ventilation par étape.</b> Un débit dit <em>combien</em>, jamais <em>où</em>. C'est
     *       la seule ligne de ce rapport qui dise par quel bout prendre le problème.</li>
     * </ul>
     */
    private static void cps(double genOnMedian, double genOffMedian, int suspect) {
        Lanterne.LOG.info("[CPS] ── Débit de génération pure ──");

        int cpus = Runtime.getRuntime().availableProcessors();
        Lanterne.LOG.info("[CPS] Conditions : {} processeur(s) annoncé(s) à la machine virtuelle. {}",
                cpus, fr.clubcitrouille.lanterne.core.Threads.explain(
                        Math.max(1, Math.min(cpus - 1, 255))));

        if (allocatedDuringGeneration > 0L && genChunksCounted > 0) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[CPS] Mémoire allouée pendant la génération : %.1f Mo pour %d chunk(s), soit "
                            + "%.2f Mo par chunk, tous fils confondus.",
                    allocatedDuringGeneration / 1048576d, genChunksCounted,
                    allocatedDuringGeneration / 1048576d / genChunksCounted));
        } else {
            Lanterne.LOG.info("[CPS] Mémoire allouée : NON MESURÉE — cette machine virtuelle "
                    + "n'expose pas le compteur d'allocation par fil.");
        }

        if (suspect > GRID_CHUNKS / 10) {
            Lanterne.LOG.error(String.format(Locale.ROOT,
                    "[CPS] REFUS DE PUBLIER UN DÉBIT : %d chunk(s) sur %d ont été « générés » en "
                            + "moins de %.1f ms. La région n'était pas vierge — ce banc a déjà "
                            + "annoncé 209 chunks/s au lieu de 13,7 pour cette raison exacte. "
                            + "Efface le monde de ce banc et relance.",
                    suspect, GRID_CHUNKS, GEN_SUSPECT_MS));
            return;
        }

        if (genOnMedian <= 0d || genOffMedian <= 0d) {
            Lanterne.LOG.warn("[CPS] REFUS DE PUBLIER UN DÉBIT : médiane nulle d'un côté.");
            return;
        }

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[CPS] Mod actif  : %.2f chunk(s) par seconde  (médiane %.3f ms/chunk)",
                1000d / (genOnMedian / 1e6), genOnMedian / 1e6));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[CPS] Mod éteint : %.2f chunk(s) par seconde  (médiane %.3f ms/chunk)",
                1000d / (genOffMedian / 1e6), genOffMedian / 1e6));
        Lanterne.LOG.info("[CPS] Ce débit est celui d'un chunk demandé SEUL et attendu : c'est le "
                + "régime d'un joueur qui explore, et le seul qui ait un sens sur un cœur unique. "
                + "Un pré-générateur qui sature le bassin de fils obtient davantage sur une machine "
                + "qui a les cœurs — voir lab/Swarm, qui mesure cet autre régime.");

        Lanterne.LOG.info("[CPS] ── Où va le temps d'un chunk, étape par étape ──{}", genStages);

        // <h2>Et à l'intérieur de l'étape du bruit, qui pèse à elle seule les deux tiers</h2>
        //
        // Dire « le bruit, 64 % » ne désigne pas un endroit où creuser : c'est une région, et elle
        // contient trois boucles sans rapport. La ventilation ci-dessous les sépare — voir lab/Filon
        // pour la lecture de source qui les identifie et pour ce que la mesure coûte.
        Lanterne.LOG.info("[FILON] ── Le dedans de l'étape du bruit ──{}",
                Filon.ventilation(genNoiseNanos));
        Lanterne.LOG.info("[FILON] ── Les postes, au détail ──{}", Filon.describe(genChunksCounted));
    }

    /**
     * Octets alloués par <b>tous</b> les fils depuis le démarrage.
     *
     * <p>Et non par le fil courant, contrairement à {@code report/Bench} : la génération de terrain
     * s'exécute sur le pool de travail, et un compteur de fil courant y rendrait presque zéro tout en
     * ayant l'air de fonctionner. C'est le genre de mesure juste appliquée au mauvais objet qui a
     * déjà coûté deux verdicts à ce dépôt.
     */
    private static long allocatedByAllThreads() {
        try {
            java.lang.management.ThreadMXBean bean =
                    java.lang.management.ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean sun) {
                return sun.getTotalThreadAllocatedBytes();
            }
        } catch (Throwable unsupported) {
            // Machine virtuelle sans cette extension : on renonce à la mesure, pas au banc.
        }
        return 0L;
    }

    /** Même seuil et même formulation que {@code Boom.report} : un écart sous cinq pour cent n'est
     * pas un gain, c'est du bruit, et le dire est la seule façon de rester crédible le jour où le
     * chiffre est bon. */
    private static String verdict(double ratio) {
        if (ratio > 1.05d) {
            return String.format(Locale.ROOT, "gain ×%.2f (%.0f %% de temps en moins)",
                    ratio, (1d - 1d / ratio) * 100d);
        } else if (ratio < 0.95d) {
            return String.format(Locale.ROOT, "PERTE ×%.2f — plus cher avec le mod", ratio);
        } else {
            return "aucun effet mesurable (écart sous le bruit de fond)";
        }
    }

    /** Chunks dont le temps mesuré tombe sous {@link #GEN_SUSPECT_NS} — voir le Javadoc de classe. */
    private static int countSuspectlyFast(long[] samples) {
        int count = 0;
        for (long value : samples) {
            if (value < GEN_SUSPECT_NS) {
                count++;
            }
        }
        return count;
    }

    /** La médiane d'un échantillon — même raisonnement que {@code Bench.median}. */
    private static double median(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0
                ? (copy[middle - 1] + copy[middle]) / 2d
                : copy[middle];
    }

    private static long total(long[] values) {
        long sum = 0L;
        for (long value : values) {
            sum += value;
        }
        return sum;
    }
}
