package fr.clubcitrouille.lanterne.lab;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La pré-génération : le seul levier massif qui tienne sur un cœur unique.
 *
 * <h2>Quinze fois, mesuré deux fois sur la même machine</h2>
 *
 * <p>L'épreuve « essaim » a mesuré, par accident d'abord puis à dessein, les deux façons dont un
 * serveur peut fournir un chunk à un joueur qui avance :
 *
 * <pre>
 * le fabriquer devant lui   :  13 à 17 chunk(s) par seconde
 * le servir depuis le disque : 155 à 209 chunk(s) par seconde
 * </pre>
 *
 * <p>Le second chiffre a d'abord été pris pour un triomphe de parallélisme — c'était une exécution
 * qui rejouait les coordonnées de la précédente. L'erreur corrigée, il reste ce qu'il est : la mesure
 * du coût de <b>servir</b> plutôt que de <b>fabriquer</b>. Quinze fois moins cher.
 *
 * <h2>Pourquoi c'est le bon outil pour une machine à un cœur</h2>
 *
 * <p>Les mods de référence qui attaquent la génération — C2ME, VMP — la <b>répartissent</b> sur
 * plusieurs fils. Sur un hébergement à un cœur, du genre qu'on loue pour jouer entre amis, il n'y a
 * rien à répartir : distribuer du travail sur quinze fils qui n'existent pas ne rend rien, et fait
 * changer de contexte.
 *
 * <p>La pré-génération ne répartit rien. Elle <b>supprime</b> le travail du moment où il gêne pour le
 * faire à un moment où il ne gêne personne. C'est la seule optimisation de génération dont le gain ne
 * dépende pas du nombre de cœurs.
 *
 * <h2>La soumission bloquait le fil principal, et c'était le vrai coût</h2>
 *
 * <p>Ce fichier appelait {@code ServerChunkCache.getChunkFuture(...)}. Le nom ment sur un point
 * décisif, et le mensonge n'apparaît qu'en lisant la source du jeu :
 *
 * <pre>
 * if (Thread.currentThread() == this.mainThread) {
 *     serverFuture = this.getChunkFutureMainThread(x, z, targetStatus, loadOrGenerate);
 *     this.mainThreadProcessor.managedBlock(serverFuture::isDone);   // &lt;-- ici
 * }
 * </pre>
 *
 * <p>Appelée depuis le fil principal — et un gestionnaire de tick l'est toujours — cette méthode
 * <b>ne rend la main qu'une fois le chunk entièrement fabriqué</b>. Trois conséquences, qui
 * expliquent à elles seules les chiffres relevés sur la machine cible :
 *
 * <ol>
 *   <li><b>Aucun chunk n'était jamais « en vol ».</b> La liste d'attente ne contenait que des
 *       promesses <em>déjà tenues</em> : le plafond de vingt-quatre n'a jamais rien plafonné, et la
 *       génération était strictement sérielle, un chunk à la fois.</li>
 *   <li><b>Le budget par tick ne pouvait pas être tenu.</b> Une seule soumission coûtait le prix
 *       entier d'un chunk. À 11,2 chunks par seconde, c'est 89 ms — le double d'un tick. Régler
 *       « budget_ms_par_tick » de 1 à 45 ne changeait donc rigoureusement rien : la boucle sortait
 *       toujours après exactement un chunk. Le débit mesuré, 11,2 chunks/s, est exactement l'inverse
 *       de la durée d'un tick ainsi allongé : un chunk par tick, et le tick dure ce que coûte le
 *       chunk.</li>
 *   <li><b>Le serveur était en retard en permanence</b>, et c'est le plus cher. {@code haveTime()}
 *       était faux du début à la fin du tick suivant, or c'est lui qui autorise
 *       {@code ChunkMap.processUnloads} à décharger et {@code saveChunksEagerly} à écrire. Le
 *       déchargement ne repartait qu'au-delà de <b>deux mille</b> chunks en attente — la soupape de
 *       secours de vanilla, pas son régime normal. On accumulait donc des milliers de chunks
 *       résidents, et chaque soumission payait ce tas : {@code ChunkMap.promoteChunkMap()} recopie
 *       <em>toute</em> la table des chunks à chaque promotion. Le coût par chunk croissait avec le
 *       retard accumulé.</li>
 * </ol>
 *
 * <p>La soumission passe donc désormais par {@code addTicketAndLoadWithRadius}, la voie asynchrone
 * que le jeu utilise lui-même pour charger un chunk sans se bloquer (voir
 * {@code PlayerSpawnFinder}). Le travail engendré est <b>identique au chunk près</b> : les deux
 * chemins finissent sur le même {@code scheduleChunkGenerationTask(ChunkStatus.FULL, …)} et sur le
 * même niveau de ticket, 33. Seul le blocage disparaît.
 *
 * <h2>Compatible avec un monde déjà joué, sans rien lui demander</h2>
 *
 * <p>Un monde vanilla existant contient déjà des chunks, et les regénérer serait au mieux du temps
 * perdu, au pire une perte de constructions. On saute donc ce qui existe — mais sans le charger, car
 * charger pour vérifier coûterait plus cher que de générer.
 *
 * <p>Le format des fichiers de région rend la chose triviale, et c'est pour cela qu'on le lit
 * directement plutôt que de passer par le jeu : les <b>quatre premiers kilo-octets</b> d'un
 * {@code r.x.z.mca} sont une table de mille vingt-quatre entrées de quatre octets, une par chunk. Une
 * entrée nulle veut dire « jamais écrit ». Quatre kilo-octets lus disent donc l'état de mille
 * vingt-quatre chunks, sans ouvrir une seule fois le générateur ni le décompresseur.
 *
 * <p>Conséquence heureuse : la <b>reprise est gratuite</b>. Une pré-génération interrompue et relancée
 * saute d'elle-même tout ce qu'elle avait déjà fait, sans fichier d'état à tenir, à corrompre ou à
 * oublier.
 *
 * <h2>Ce qu'elle ne fait pas</h2>
 *
 * <p>Elle ne bloque pas le serveur. Elle travaille dans le temps qu'un tick laisse libre, sous un
 * budget explicite, et s'arrête dès qu'elle l'a dépassé. Des joueurs peuvent jouer pendant ce
 * temps — plus lentement, puisqu'ils partagent le cœur, mais sans déconnexion.
 *
 * <p>Elle ne laisse rien chargé derrière elle non plus, et ce n'est pas un effort de notre part :
 * voir {@link #submit(long)} pour la raison, qui tient entièrement dans le choix du type de ticket.
 */
public final class Pregen {
    /** Octets de l'en-tête d'un fichier de région : mille vingt-quatre entrées de quatre. */
    private static final int HEADER_BYTES = 4096;

    /** Chunks par côté d'une région. */
    private static final int REGION_SIDE = 32;

    /** Ce que {@link #nextSpot()} renvoie quand le carré est couvert. */
    private static final long NO_SPOT = Long.MIN_VALUE;

    /**
     * Toutes les combien de positions examinées on consulte l'horloge.
     *
     * <h2>Sur une reprise, c'est l'horloge qui coûte, pas le saut</h2>
     *
     * <p>Sauter un chunk déjà écrit, c'est une avancée de curseur et une recherche dans un ensemble
     * d'entiers : une vingtaine de nanosecondes. {@code System.nanoTime()} en coûte autant à lui
     * seul. Une reprise sur un monde déjà largement pré-généré passait donc la moitié de son budget
     * à regarder l'heure. Soixante-quatre positions entre deux lectures ne peut pas faire déborder
     * le budget de façon perceptible — soixante-quatre sauts, c'est quelques microsecondes — et
     * double le débit de balayage.
     */
    private static final int CLOCK_EVERY = 64;

    /**
     * Part d'un tick qu'on s'autorise à consommer, en millisecondes.
     *
     * <p>Relue à chaque tick plutôt que retenue au lancement : une pré-génération dure des heures,
     * et l'administrateur qui s'aperçoit qu'elle gêne doit pouvoir la brider sans tout reprendre.
     *
     * <p>Depuis que la soumission ne bloque plus, ce budget a changé de nature : une soumission
     * asynchrone coûte quelques microsecondes, et c'est le nombre de chunks en vol qui règle
     * réellement l'allure. Le budget reste un garde-fou — il borne le temps passé à <em>balayer</em>
     * les positions lors d'une reprise, et il borne la voie synchrone si quelqu'un la rallume.
     */
    private static long budgetMs() {
        return fr.clubcitrouille.lanterne.core.Config.PREGEN_BUDGET_MS.get();
    }

    /**
     * Combien de chunks on accepte de tenir en vol.
     *
     * <h2>Pourquoi ce n'est plus vingt-quatre</h2>
     *
     * <p>L'ancien commentaire justifiait vingt-quatre par « le débit plafonne bien avant
     * trente-deux ». Le réglage n'avait en réalité jamais servi à rien, la soumission étant
     * bloquante : le nombre de chunks réellement en vol valait un, toujours (voir la javadoc de
     * classe). Le chiffre à choisir est donc à choisir pour la première fois.
     *
     * <p>Sur un cœur unique, augmenter le nombre de chunks en vol <b>ne peut pas</b> augmenter le
     * débit : le travail est irréductiblement le même, 86,6 % de bruit et de biomes, et il n'y a
     * qu'un cœur pour le faire. La seule chose que le parallélisme achète ici, c'est de ne jamais
     * laisser le cœur inoccupé pendant qu'un chunk passe d'une étape à la suivante — un réveil de
     * fil, un passage par le fil principal. Ces trous se comptent en dizaines de microsecondes,
     * face à des dizaines de millisecondes de calcul par chunk : <b>deux ou trois chunks en vol
     * suffisent déjà à les boucher</b>.
     *
     * <p>Tout ce qui est au-delà s'achète, en revanche, et se paie :
     *
     * <ul>
     *   <li>chaque chunk en vol retient son cône de dépendances — les voisins qu'un chunk au statut
     *       {@code FULL} exige à des statuts intermédiaires ;</li>
     *   <li>{@code ChunkMap.promoteChunkMap()} recopie toute la table des chunks à chaque
     *       promotion : plus il y a de résidents, plus <em>chaque</em> soumission coûte cher ;</li>
     *   <li>18 Mo alloués par chunk, mesurés — quatre giga-octets de tas et un ramasse-miettes
     *       sériel n'aiment pas qu'on multiplie les vivants.</li>
     * </ul>
     *
     * <p>Huit par défaut : largement de quoi couvrir les trous d'ordonnancement, assez peu pour que
     * les cônes de dépendances des chunks en vol — qui sont voisins dans le parcours, donc
     * largement communs — restent un voisinage et non une région. Le réglage est exposé pour que ce
     * raisonnement puisse être démenti par la mesure plutôt que cru sur parole.
     */
    private static int inFlightCap() {
        return Math.max(1, fr.clubcitrouille.lanterne.core.Config.PREGEN_IN_FLIGHT.get());
    }

    private static boolean running;

    /**
     * Instant d'entrée dans la pause, ou zéro si l'on travaille.
     *
     * <p>Voir {@link #activeNanos()} : ce qui est mesuré ici finit par être <b>retiré</b> du
     * dénominateur du débit.
     */
    private static long pausedAt;

    /** Temps déjà passé en pause, hors pause en cours. */
    private static long pausedNanos;
    private static ServerLevel level;

    /**
     * Le numéro de l'exécution en cours.
     *
     * <h2>Une promesse ne sait pas qu'on l'a abandonnée</h2>
     *
     * <p>Les chunks en vol au moment d'un {@link #halt()} aboutissent — c'est voulu, annuler
     * perdrait du travail déjà payé. Mais leur rappel arrive <em>après</em>, et si une nouvelle
     * pré-génération a démarré entretemps, il viendrait décrémenter ses compteurs à elle. Le rappel
     * porte donc le numéro de l'exécution qui l'a émis, et se tait si ce n'est plus la bonne.
     *
     * <p>{@code volatile} parce que le rappel peut être exécuté par un fil de travail.
     */
    private static volatile int epoch;

    /**
     * Chunks demandés dont la promesse n'est pas encore tenue.
     *
     * <p>Atomique, et non simple {@code int} : le rappel qui décrémente s'exécute sur le fil qui
     * achève la promesse, qui n'est pas forcément le fil principal. On aurait pu le renvoyer sur le
     * fil principal — c'est ce que fait {@code PlayerSpawnFinder} — mais une tâche de tick peut
     * attendre jusqu'à trois ticks avant d'être exécutée, et ce compteur est précisément celui qui
     * décide si l'on a le droit de soumettre. Le retarder, c'est sous-alimenter la chaîne.
     */
    private static final AtomicInteger AIRBORNE = new AtomicInteger();

    /** Chunks effectivement fabriqués. Atomique pour la même raison qu'{@link #AIRBORNE}. */
    private static final AtomicLong FINISHED = new AtomicLong();

    /** Chunks dont la demande a échoué. Voir {@link #settle(int, Throwable)}. */
    private static final AtomicLong FAILED = new AtomicLong();

    private static final LongOpenHashSet ALREADY = new LongOpenHashSet();
    private static long wanted;
    private static long issued;
    private static long skipped;
    private static int ring;
    private static int radiusChunks;
    private static int cursorInRing;

    /** Position dans le parcours en serpentin. Voir {@link #nextSerpentine()}. */
    private static long serpentCursor;

    /**
     * Le parcours retenu pour cette exécution, figé au lancement.
     *
     * <p>Figé, et non relu à chaque chunk comme le budget : les deux parcours n'ont pas le même
     * curseur, et basculer en cours de route recommencerait le carré depuis un autre coin. Ce qui
     * aurait déjà été fait serait sauté — la reprise est gratuite — mais le temps restant annoncé
     * et la barre deviendraient faux, et l'opérateur croirait à une régression.
     */
    private static boolean serpentineRun;

    /** La voie de soumission retenue pour cette exécution, figée au lancement. */
    private static boolean asyncRun;

    /** Vrai quand {@link #nextSpot()} a épuisé le carré : on n'attend plus que les retardataires. */
    private static boolean exhausted;

    private static long startedAt;
    private static long lastReport;

    /**
     * La barre de progression, visible des seuls opérateurs.
     *
     * <h2>Pourquoi elle n'est pas un ornement</h2>
     *
     * <p>Une pré-génération dure des heures. Sans retour visible, celui qui l'a lancée n'a aucun moyen
     * de savoir si elle avance, si elle a fini, ou si elle s'est arrêtée sur une erreur. Le journal le
     * dit — encore faut-il y avoir accès, ce qui n'est pas le cas sur la plupart des hébergements
     * partagés, et encore faut-il y penser.
     *
     * <p>Elle affiche aussi le <b>débit</b> et le <b>temps restant estimé</b>, parce que « 34 % » ne
     * répond pas à la seule question qui intéresse vraiment : est-ce que je peux aller me coucher, ou
     * est-ce que ça va finir dans dix minutes ?
     *
     * <h2>Réservée à ceux qui peuvent la commander</h2>
     *
     * <p>Un joueur ordinaire n'a pas à subir une barre d'administration en travers de son écran
     * pendant qu'il joue. Seuls les opérateurs la voient, et la liste est révisée à chaque rapport —
     * un joueur promu en cours de route la reçoit, un joueur parti ne la garde pas.
     */
    private static ServerBossEvent bar;

    private Pregen() {}

    public static boolean running() {
        return running;
    }

    /**
     * Ouvre une pré-génération autour de l'origine du monde.
     *
     * @param radius rayon en chunks
     */
    public static void begin(ServerLevel target, int radius) {
        // Une exécution qui en recouvre une autre invaliderait les compteurs de la première ; le
        // numéro d'exécution avancé ici fait taire tous ses rappels encore en route.
        epoch++;
        level = target;
        radiusChunks = Math.max(1, radius);
        running = true;
        AIRBORNE.set(0);
        FINISHED.set(0L);
        FAILED.set(0L);
        issued = 0L;
        skipped = 0L;
        ring = 0;
        cursorInRing = 0;
        serpentCursor = 0L;
        exhausted = false;
        serpentineRun = fr.clubcitrouille.lanterne.core.Config.PREGEN_SERPENTINE.get();
        asyncRun = fr.clubcitrouille.lanterne.core.Config.PREGEN_ASYNC.get();
        startedAt = System.nanoTime();
        lastReport = startedAt;
        lastLogged = startedAt;
        pausedAt = 0L;
        pausedNanos = 0L;
        hideBar();
        int side = radiusChunks * 2 + 1;
        wanted = (long) side * side;

        ALREADY.clear();
        long known = readExistingChunks(target);
        Lanterne.LOG.info("[PRÉGÉN] {} chunk(s) visés autour de l'origine (rayon {}), dont {} déjà "
                + "écrits sur disque et qui seront sautés. Parcours {}, {} chunk(s) en vol, "
                + "soumission {}. Budget de balayage : {} ms par tick.",
                wanted, radiusChunks, known,
                serpentineRun ? "en serpentin" : "en anneaux",
                inFlightCap(),
                asyncRun ? "asynchrone" : "synchrone (ancienne voie, bloquante)",
                budgetMs());

        // Dit tout de suite ce qui va se passer, plutôt que de laisser l'opérateur constater qu'il
        // ne se passe rien. Celui qui lance la commande DEPUIS LE JEU est lui-même un joueur
        // connecté : sans cette ligne, il verrait une pré-génération qui ne démarre jamais et
        // conclurait à une panne. C'est le seul piège de ce réglage, et il se désamorce en le
        // disant.
        int present = target.getServer().getPlayerList().getPlayerCount();
        if (present > 0 && fr.clubcitrouille.lanterne.core.Config.PREGEN_PAUSE_ON_JOIN.get()) {
            Lanterne.LOG.info("[PRÉGÉN] en attente : {} joueur(s) connecté(s), et "
                    + "« pause_a_la_connexion » est active. Elle démarrera au départ du dernier. "
                    + "Pour générer pendant que vous jouez, mettre ce réglage à false dans "
                    + "config/lanterne-server.toml.", present);
        }
    }

    /**
     * Relève, depuis les en-têtes des fichiers de région, tout ce qui existe déjà.
     *
     * <p>Une lecture de quatre kilo-octets par région, soit par tranche de mille vingt-quatre chunks.
     * Sur un monde de cent mille chunks, cela fait une centaine de lectures de quatre kilo-octets —
     * quelques dizaines de millisecondes, une fois.
     *
     * <h2>Les régions hors du carré visé ne sont ni lues ni retenues</h2>
     *
     * <p>On parcourt toujours le dossier plutôt que d'énumérer les noms attendus : un rayon de vingt
     * mille chunks représente un million et demi de noms de fichiers, dont presque aucun n'existe, et
     * les demander un par un au système coûterait plus cher que de lire ce qui est là. Mais on écarte
     * sur le nom, avant d'ouvrir, toute région qui ne touche pas le carré — et on n'enregistre que
     * les chunks qui sont dedans.
     *
     * <p>Ce n'est pas une coquetterie : l'ensemble retenu était auparavant celui du <b>monde
     * entier</b>. Sur une carte déjà explorée sur des millions de chunks, c'est plusieurs dizaines de
     * méga-octets d'entiers longs gardés vivants pendant toute la pré-génération, sur un tas de
     * quatre giga-octets qui a déjà fort à faire.
     */
    private static long readExistingChunks(ServerLevel target) {
        Path regions = regionFolder(target);
        if (!Files.isDirectory(regions)) {
            Lanterne.LOG.info("[PRÉGÉN] aucun dossier de régions à {} — le monde est neuf.", regions);
            return 0L;
        }
        // Bornes du carré visé, en coordonnées de région. Un décalage arithmétique de cinq divise
        // par trente-deux en arrondissant vers le bas, y compris pour les négatifs — ce que la
        // division entière de Java ne fait pas.
        int firstRegion = (-radiusChunks) >> 5;
        int lastRegion = radiusChunks >> 5;
        long found = 0L;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(regions, "r.*.mca")) {
            for (Path file : files) {
                found += readOneRegion(file, firstRegion, lastRegion);
            }
        } catch (IOException unreadable) {
            Lanterne.LOG.warn("[PRÉGÉN] lecture des régions impossible ({}) — on générera peut-être "
                    + "des chunks qui existaient déjà, ce qui est une perte de temps et non une perte "
                    + "de données.", unreadable.toString());
        }
        return found;
    }

    /**
     * Le dossier des régions d'une dimension.
     *
     * <h2>Une disposition de sauvegarde qui a changé sous nos pieds</h2>
     *
     * <p>Ce code fabriquait le chemin à la main et traitait le Nether et l'End comme {@code DIM-1}
     * et {@code DIM1}. C'était vrai jusqu'à la 26 ; ça ne l'est plus : {@code getDimensionPath}
     * renvoie désormais {@code dimensions/<espace>/<nom>} pour <b>toutes</b> les dimensions, le
     * monde principal compris. Le repli générique sauvait donc le monde principal par accident,
     * pendant que les deux cas particuliers, eux, désignaient des dossiers inexistants : une
     * pré-génération du Nether ne voyait rien de ce qui existait et le refabriquait en entier.
     *
     * <p>On appelle maintenant exactement la méthode dont le jeu se sert pour ouvrir ses propres
     * fichiers. Si la disposition rechange, elle rechangera des deux côtés à la fois.
     */
    private static Path regionFolder(ServerLevel target) {
        Path root = target.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(target.dimension(), root).resolve("region");
    }

    private static long readOneRegion(Path file, int firstRegion, int lastRegion) {
        String name = file.getFileName().toString();
        String[] parts = name.split("\\.");
        if (parts.length != 4) {
            return 0L;
        }
        int regionX;
        int regionZ;
        try {
            regionX = Integer.parseInt(parts[1]);
            regionZ = Integer.parseInt(parts[2]);
        } catch (NumberFormatException malformed) {
            return 0L;
        }
        // Hors du carré : ni ouverture, ni lecture, ni place en mémoire.
        if (regionX < firstRegion || regionX > lastRegion
                || regionZ < firstRegion || regionZ > lastRegion) {
            return 0L;
        }

        byte[] header = new byte[HEADER_BYTES];
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r")) {
            if (handle.length() < HEADER_BYTES) {
                return 0L;
            }
            handle.readFully(header);
        } catch (IOException unreadable) {
            return 0L;
        }

        long found = 0L;
        for (int slot = 0; slot < REGION_SIDE * REGION_SIDE; slot++) {
            int offset = slot * 4;
            // Trois octets d'adresse, un de longueur. Tout à zéro : le chunk n'a jamais été écrit.
            int packed = ((header[offset] & 0xFF) << 16)
                    | ((header[offset + 1] & 0xFF) << 8)
                    | (header[offset + 2] & 0xFF);
            int sectors = header[offset + 3] & 0xFF;
            if (packed != 0 && sectors != 0) {
                int chunkX = regionX * REGION_SIDE + (slot & 31);
                int chunkZ = regionZ * REGION_SIDE + (slot >> 5);
                // Une région de bordure déborde du carré : on ne retient que le dedans.
                if (chunkX < -radiusChunks || chunkX > radiusChunks
                        || chunkZ < -radiusChunks || chunkZ > radiusChunks) {
                    continue;
                }
                ALREADY.add(ChunkPos.pack(chunkX, chunkZ));
                found++;
            }
        }
        return found;
    }

    /**
     * Avance d'un tick.
     *
     * <h2>Ce qui limite, et ce qui ne limite plus</h2>
     *
     * <p>Tant que la soumission bloquait, le budget était la seule limite — et il était toujours
     * dépassé, d'un chunk entier. Maintenant que soumettre coûte quelques microsecondes, c'est le
     * nombre de chunks en vol qui règle l'allure, et le budget ne borne plus que le balayage des
     * positions déjà écrites lors d'une reprise.
     *
     * <p>Le reste du travail se fait là où le jeu l'a prévu : {@code MinecraftServer.pollTaskInternal}
     * appelle déjà {@code ServerChunkCache.pollTask()} sur chaque monde, pendant tout le temps qui
     * reste avant le tick suivant. Rien ne sert de l'appeler nous-mêmes depuis ici — ce serait
     * avancer de quelques millisecondes, dans le même tick, un travail que le serveur fera de toute
     * façon, en le prenant aux fils qui fabriquent le terrain.
     */
    public static void tick(MinecraftServer server) {
        if (!running) {
            return;
        }
        if (suspended(server)) {
            return;
        }
        long deadline = System.nanoTime() + budgetMs() * 1_000_000L;
        int cap = inFlightCap();
        int examined = 0;

        while (!exhausted && AIRBORNE.get() < cap) {
            if (examined++ % CLOCK_EVERY == 0 && System.nanoTime() >= deadline) {
                break;
            }
            long next = nextSpot();
            if (next == NO_SPOT) {
                exhausted = true;
                break;
            }
            if (ALREADY.contains(next)) {
                skipped++;
                continue;
            }
            if (!submit(next)) {
                return;
            }
            // La voie synchrone fabrique le chunk sur place : sans cette relecture de l'horloge,
            // elle repartirait pour un tour et le tick durerait deux chunks au lieu d'un.
            if (System.nanoTime() >= deadline) {
                break;
            }
        }

        // Le carré est couvert mais des chunks sont encore en vol : on continue de rendre compte,
        // sans quoi la barre se figerait pendant que la fin se joue et se lirait comme une panne.
        if (exhausted && AIRBORNE.get() == 0) {
            conclude();
            return;
        }
        report();
    }

    /**
     * Demande un chunk, sans attendre qu'il soit prêt.
     *
     * <h2>Le type de ticket est tout le raisonnement</h2>
     *
     * <p>{@code addTicketAndLoadWithRadius} exige un ticket qui charge et qui ne puisse pas expirer
     * pendant le chargement. {@code SPAWN_SEARCH} est celui-là, et c'est celui dont vanilla se sert
     * pour exactement ce besoin — charger un chunk au statut {@code FULL} sans bloquer, puis le
     * lâcher. Ses quatre propriétés répondent chacune à une question qu'on se serait posée :
     *
     * <ul>
     *   <li>il <b>charge</b> mais ne <b>simule</b> pas : le ticket vaut le niveau 33, donc
     *       {@code FullChunkStatus.FULL} et pas {@code BLOCK_TICKING}. Aucun tick aléatoire, aucune
     *       apparition de créature, aucune entité mise à jour sur les chunks pré-générés — ce qui
     *       était déjà le cas avec l'ancienne voie, et qu'il fallait ne pas perdre ;</li>
     *   <li>il n'est <b>pas persistant</b> : rien n'atterrit dans {@code chunk_tickets} sur le
     *       disque, donc rien ne survit à un redémarrage ;</li>
     *   <li>il ne <b>garde pas la dimension active</b> ;</li>
     *   <li>il a un délai d'une seule tick, mais {@code TicketStorage.canTicketExpire} refuse de le
     *       purger tant que {@code isReadyForSaving()} est faux — c'est-à-dire tant que la
     *       génération tient encore le chunk. Il expire donc tout seul <b>juste après</b> que le
     *       chunk est fabriqué et sauvé, et pas avant. La pré-génération ne laisse rien chargé
     *       derrière elle sans avoir une ligne de code à écrire pour cela, ni un ticket à retirer
     *       qu'on pourrait oublier de retirer.</li>
     * </ul>
     *
     * <p>Ce que cela pourrait casser : un outil de diagnostic qui compte les tickets par type verra
     * des {@code spawn_search} là où il n'y a aucune recherche de point d'apparition. C'est le seul
     * inconvénient, et il est préférable à l'enregistrement d'un type de ticket à nous, qui
     * demanderait de toucher à l'enregistrement du mod.
     *
     * @return {@code false} si la demande a échoué et que la pré-génération vient d'être arrêtée
     */
    private static boolean submit(long packed) {
        ChunkPos spot = ChunkPos.unpack(packed);
        ServerChunkCache source = level.getChunkSource();
        final int era = epoch;
        AIRBORNE.incrementAndGet();
        issued++;
        try {
            CompletableFuture<?> promise;
            if (asyncRun) {
                promise = source.addTicketAndLoadWithRadius(TicketType.SPAWN_SEARCH, spot, 0);
            } else {
                // L'ancienne voie, gardée pour qu'on puisse mesurer l'écart plutôt que le croire.
                // Elle ne rend la main qu'une fois le chunk fabriqué : la promesse rendue est déjà
                // tenue, et le rappel ci-dessous s'exécute sur place.
                promise = source.getChunkFuture(spot.x(), spot.z(), ChunkStatus.FULL, true);
            }
            promise.whenComplete((outcome, failure) -> settle(era, outcome, failure));
            return true;
        } catch (RuntimeException refused) {
            // Le jeu refuse la demande — un monde en cours de fermeture, un générateur en panne. On
            // s'arrête plutôt que de rejouer l'erreur des milliers de fois : ce qui est écrit reste
            // écrit, et la reprise est gratuite.
            AIRBORNE.decrementAndGet();
            Lanterne.LOG.error("[PRÉGÉN] demande refusée sur le chunk {} — arrêt. Ce qui est déjà "
                    + "écrit est conservé, relancer reprendra là où l'on en est.", spot, refused);
            halt();
            return false;
        }
    }

    /**
     * Solde une promesse tenue.
     *
     * <h2>Un échec de génération ne lève pas d'exception</h2>
     *
     * <p>Le piège : le jeu signale « ce chunk n'a pas pu être fourni » en tenant sa promesse
     * <b>normalement</b>, avec un {@link ChunkResult} en échec pour valeur. Ne regarder que la
     * cause d'exception laisserait donc passer en silence exactement le cas qui compte, et la
     * pré-génération annoncerait un carré plein là où il y a des trous — trous qui ne se verraient
     * que le jour où un joueur y arrive. On inspecte donc la valeur rendue, pas seulement l'échec.
     *
     * <p>Une demande qui échoue est comptée comme achevée malgré tout : sans cela, le compteur des
     * chunks en vol ne redescendrait jamais à zéro et la pré-génération ne se conclurait pas. Les
     * échecs sont comptés à part et rappelés à la fin.
     */
    private static void settle(int era, Object outcome, Throwable failure) {
        if (era != epoch) {
            return;
        }
        AIRBORNE.decrementAndGet();
        FINISHED.incrementAndGet();
        String trouble = null;
        if (failure != null) {
            trouble = failure.toString();
        } else if (outcome instanceof ChunkResult<?> verdict && !verdict.isSuccess()) {
            trouble = verdict.getError();
        }
        if (trouble != null && FAILED.incrementAndGet() == 1L) {
            Lanterne.LOG.warn("[PRÉGÉN] au moins un chunk n'a pas pu être fabriqué ({}). Le compte "
                    + "des échecs sera rappelé à la fin.", trouble);
        }
    }

    /**
     * Faut-il se taire parce qu'un joueur est là ?
     *
     * <h2>Un joueur et une pré-génération se disputent le même cœur</h2>
     *
     * <p>Sur un hébergement à un cœur — celui qu'on loue pour jouer entre amis — il n'y a rien à
     * partager : les trente millisecondes que la pré-génération prend dans le tick sont trente
     * millisecondes que le joueur n'a pas. Le rendre entier au joueur dès qu'il arrive vaut mieux
     * que de lui servir un serveur à moitié occupé, d'autant que le travail en question n'est
     * <b>jamais</b> urgent : c'est précisément son intérêt de pouvoir être fait plus tard.
     *
     * <h2>Ce qui est en vol n'est pas abandonné</h2>
     *
     * <p>On cesse de <em>soumettre</em>, on n'annule rien. Les chunks déjà demandés aboutissent et
     * sont écrits ; leurs rappels les compteront. Annuler aurait perdu du travail déjà payé.
     *
     * <h2>Et le temps de pause sort du dénominateur</h2>
     *
     * <p>C'est le détail qui décide si le chiffre final veut dire quelque chose. Une pré-génération
     * de dix minutes dont huit passées en pause n'a pas un débit six fois moindre : elle a le même
     * débit, pendant deux minutes. Laisser la pause dans le calcul reviendrait à mesurer surtout le
     * temps où l'on n'a rien fait — un dénominateur faux rend tous les numérateurs inutiles.
     */
    private static boolean suspended(MinecraftServer server) {
        if (!fr.clubcitrouille.lanterne.core.Config.PREGEN_PAUSE_ON_JOIN.get()
                || server.getPlayerList().getPlayers().isEmpty()) {
            resume();
            return false;
        }
        if (pausedAt == 0L) {
            pausedAt = System.nanoTime();
            Lanterne.LOG.info("[PRÉGÉN] suspendue — {} joueur(s) connecté(s). Le serveur leur est "
                    + "rendu en entier, ce qui est en vol aboutit, et la reprise est automatique au "
                    + "départ du dernier. Le temps de pause ne comptera pas dans le débit annoncé.",
                    server.getPlayerList().getPlayerCount());
        }
        long now = System.nanoTime();
        if (now - lastReport >= 1_000_000_000L) {
            lastReport = now;
            showPaused(server);
        }
        return true;
    }

    /** Sort de la pause, s'il y en avait une, et verse son temps au compteur. */
    private static void resume() {
        if (pausedAt == 0L) {
            return;
        }
        long waited = System.nanoTime() - pausedAt;
        pausedNanos += waited;
        pausedAt = 0L;
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[PRÉGÉN] reprise après %s de pause — le serveur est de nouveau vide.",
                humanDuration(waited / 1.0E9d)));
    }

    /**
     * Le temps réellement passé à travailler, pause déduite.
     *
     * <p>La pause <b>en cours</b> est déduite elle aussi : sans quoi le débit affiché s'effondrerait
     * seconde après seconde sous les yeux d'un opérateur qui vient d'arriver, et il en conclurait
     * que la pré-génération est en train de ralentir alors qu'elle est simplement à l'arrêt.
     */
    private static long activeNanos() {
        long active = System.nanoTime() - startedAt - pausedNanos;
        if (pausedAt != 0L) {
            active -= System.nanoTime() - pausedAt;
        }
        return Math.max(1L, active);
    }

    /** Le débit, calculé sur le temps de travail et non sur le temps écoulé. */
    private static double rate() {
        return FINISHED.get() / (activeNanos() / 1.0E9d);
    }

    /** La prochaine position à examiner, empaquetée, ou {@link #NO_SPOT} si le carré est couvert. */
    private static long nextSpot() {
        return serpentineRun ? nextSerpentine() : nextRing();
    }

    /**
     * Le prochain chunk de la spirale.
     *
     * <p>En anneaux concentriques depuis l'origine : ce qui est proche du point d'apparition est
     * généré d'abord. Si l'on interrompt la pré-génération à mi-chemin, ce qui a été fait est ce dont
     * les joueurs se serviront en premier. C'est la <b>seule</b> qualité que le serpentin ne sait pas
     * imiter, et elle n'est pas mince : une pré-génération de nuit qu'on arrête au matin a couvert un
     * disque autour du bourg, et non une bande au nord.
     *
     * <p>Les positions successives restent voisines, y compris au passage d'un anneau au suivant :
     * l'anneau de rayon {@code r} se termine en {@code (-r, -r+1)}, et l'anneau {@code r+1} commence
     * en {@code (-r-1, -r-1)}, à un pas en diagonale. La spirale n'a donc pas le défaut qu'on lui
     * prête parfois de sauter d'un bout à l'autre de la carte.
     */
    private static long nextRing() {
        while (ring <= radiusChunks) {
            int side = ring * 2 + 1;
            int perimeter = ring == 0 ? 1 : side * 4 - 4;
            if (cursorInRing >= perimeter) {
                ring++;
                cursorInRing = 0;
                continue;
            }
            int index = cursorInRing++;
            if (ring == 0) {
                return ChunkPos.pack(0, 0);
            }
            int edge = side - 1;
            int x;
            int z;
            if (index < edge) {
                x = -ring + index;
                z = -ring;
            } else if (index < edge * 2) {
                x = ring;
                z = -ring + (index - edge);
            } else if (index < edge * 3) {
                x = ring - (index - edge * 2);
                z = ring;
            } else {
                x = -ring;
                z = ring - (index - edge * 3);
            }
            return ChunkPos.pack(x, z);
        }
        return NO_SPOT;
    }

    /**
     * Le prochain chunk du serpentin : ligne par ligne, une ligne sur deux à l'envers.
     *
     * <h2>Ce qu'il gagne, et ce qu'il perd</h2>
     *
     * <p>Un chunk au statut {@code FULL} exige de ses voisins des statuts intermédiaires, sur un
     * rayon de plusieurs chunks. Les deux parcours enchaînent des positions voisines, et réutilisent
     * donc à plein le voisinage <em>dans le sens de la marche</em>. La différence est ailleurs :
     * elle tient à la <b>longueur du front</b>. Pour couvrir un carré de côté {@code S}, le
     * serpentin avance un front d'une ligne, {@code S} chunks ; la spirale avance un anneau, dont le
     * périmètre vaut {@code 4S}. À couverture égale, la spirale garde donc <b>quatre fois plus</b>
     * de voisinage à portée pour espérer le réutiliser — et comme rien ne peut tout garder, elle
     * relit davantage depuis le disque.
     *
     * <p>Cela compte d'autant plus que {@code ChunkMap.promoteChunkMap()} recopie toute la table des
     * chunks à chaque promotion : un front long se paie deux fois, en relectures et en recopies.
     *
     * <p>En face, le serpentin perd la reprise « utile » : arrêté à mi-course, il a fabriqué une
     * bande et non un disque. C'est pourquoi il est proposé et non imposé, et pourquoi la spirale
     * reste le défaut. Le serpentin est le bon choix pour une pré-génération qu'on sait mener à son
     * terme, d'un coup, sur un monde neuf ; la spirale pour tout le reste.
     *
     * <p>Aucune des deux ne change le terrain : l'ordre dans lequel on demande des chunks n'entre
     * dans aucune graine et dans aucun bruit.
     */
    private static long nextSerpentine() {
        long side = radiusChunks * 2L + 1L;
        if (serpentCursor >= side * side) {
            return NO_SPOT;
        }
        long row = serpentCursor / side;
        long col = serpentCursor % side;
        serpentCursor++;
        // Une ligne sur deux est parcourue à l'envers : la fin d'une ligne touche le début de la
        // suivante. Sans cela, chaque changement de ligne traverserait le carré de part en part et
        // perdrait tout le voisinage chaud, ce qui est exactement ce qu'on cherchait à éviter.
        if ((row & 1L) == 1L) {
            col = side - 1L - col;
        }
        return ChunkPos.pack((int) (col - radiusChunks), (int) (row - radiusChunks));
    }

    private static void report() {
        long now = System.nanoTime();
        if (now - lastReport < 1_000_000_000L) {
            return;
        }
        lastReport = now;
        double rate = rate();
        long seen = issued + skipped;
        float share = (float) Math.min(1d, seen / (double) Math.max(1L, wanted));

        showBar(share, seen, rate);

        // Le journal reste plus espacé que la barre : cinq secondes suffisent à suivre, et une ligne
        // par seconde sur une pré-génération de plusieurs heures rendrait le fichier illisible.
        if (now - lastLogged < 5_000_000_000L) {
            return;
        }
        lastLogged = now;
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[PRÉGÉN] %d / %d (%.1f %%) · %d généré(s), %d sauté(s), %d en vol · "
                + "%.1f chunk(s) par seconde",
                seen, wanted, share * 100d, FINISHED.get(), skipped, AIRBORNE.get(), rate));
    }

    private static long lastLogged;

    /**
     * Met la barre à jour et la montre aux opérateurs connectés.
     *
     * <p>Le temps restant se calcule sur les chunks <em>à générer</em>, et non sur les chunks à
     * parcourir : sauter un chunk déjà écrit coûte une recherche dans un ensemble d'entiers, et le
     * compter comme du travail donnerait une estimation ridiculement pessimiste sur un monde déjà
     * largement exploré.
     */
    private static void showBar(float share, long seen, double rate) {
        if (level == null) {
            return;
        }
        if (bar == null) {
            bar = new ServerBossEvent(java.util.UUID.randomUUID(),
                    Component.literal("Pré-génération"),
                    BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        }

        long remainingToMake = Math.max(0L, wanted - seen);
        // Sur ce qui reste, la proportion de chunks neufs devrait ressembler à celle déjà observée.
        double madeShare = seen == 0L ? 1d : FINISHED.get() / (double) Math.max(1L, seen);
        double secondsLeft = rate <= 0.01d ? -1d : remainingToMake * madeShare / rate;

        bar.setName(Component.literal(String.format(Locale.ROOT,
                "Pré-génération  %.1f %%  ·  %d / %d chunks  ·  %.1f/s  ·  %s",
                share * 100d, seen, wanted, rate, humanTime(secondsLeft)))
                .withStyle(ChatFormatting.AQUA));
        bar.setProgress(share);
        bar.setColor(BossEvent.BossBarColor.GREEN);
        refreshViewers();
    }

    /**
     * La barre pendant la pause.
     *
     * <h2>Une barre figée se lit comme une panne</h2>
     *
     * <p>Sans cet affichage, l'opérateur qui vient d'arriver voit une barre qui ne bouge plus, et
     * conclut que la pré-génération a planté. Elle a fait exactement ce qu'on lui a demandé — et
     * c'est son arrivée à lui qui l'a provoqué. La barre doit donc le dire, et changer de couleur
     * pour qu'on n'ait pas à la lire pour le comprendre.
     */
    private static void showPaused(MinecraftServer server) {
        if (level == null) {
            return;
        }
        if (bar == null) {
            bar = new ServerBossEvent(java.util.UUID.randomUUID(),
                    Component.literal("Pré-génération"),
                    BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
        }
        long seen = issued + skipped;
        float share = (float) Math.min(1d, seen / (double) Math.max(1L, wanted));
        int players = server.getPlayerList().getPlayerCount();
        bar.setName(Component.literal(String.format(Locale.ROOT,
                "Pré-génération EN PAUSE  ·  %d / %d chunks  ·  %d joueur(s) connecté(s)",
                seen, wanted, players))
                .withStyle(ChatFormatting.YELLOW));
        bar.setProgress(share);
        bar.setColor(BossEvent.BossBarColor.YELLOW);
        refreshViewers();
    }

    /**
     * Révise qui voit la barre.
     *
     * <p>À chaque passage, et non une fois pour toutes : un opérateur promu en cours de route la
     * reçoit, un joueur déconnecté cesse d'y figurer, et un joueur dépromu ne la voit plus.
     */
    private static void refreshViewers() {
        for (ServerPlayer viewer : level.getServer().getPlayerList().getPlayers()) {
            boolean allowed = level.getServer().getPlayerList().isOp(viewer.nameAndId());
            if (allowed) {
                bar.addPlayer(viewer);
            } else {
                bar.removePlayer(viewer);
            }
        }
    }

    private static String humanTime(double seconds) {
        if (seconds < 0d) {
            return "estimation en cours";
        }
        long total = (long) seconds;
        if (total < 60L) {
            return total + " s restantes";
        }
        if (total < 3600L) {
            return (total / 60L) + " min restantes";
        }
        return String.format(Locale.ROOT, "%d h %02d restantes", total / 3600L, (total % 3600L) / 60L);
    }

    /** Une durée <b>écoulée</b>, en clair. Voir {@link #humanTime} pour une durée qui reste. */
    private static String humanDuration(double seconds) {
        long total = (long) Math.max(0d, seconds);
        if (total < 60L) {
            return total + " s";
        }
        if (total < 3600L) {
            return String.format(Locale.ROOT, "%d min %02d s", total / 60L, total % 60L);
        }
        return String.format(Locale.ROOT, "%d h %02d", total / 3600L, (total % 3600L) / 60L);
    }

    private static void hideBar() {
        if (bar != null) {
            bar.removeAllPlayers();
            bar.setVisible(false);
            bar = null;
        }
    }

    /**
     * Rend la table des chunks déjà écrits.
     *
     * <p>Elle a servi, elle ne servira plus jusqu'au prochain lancement, et sur un monde déjà
     * exploré elle pèse plusieurs méga-octets. {@code clear()} seul ne rend rien à fastutil : la
     * table reste dimensionnée pour ce qu'elle a contenu au plus fort. Il faut la retailler.
     */
    private static void releaseKnownChunks() {
        ALREADY.clear();
        ALREADY.trim();
    }

    private static void conclude() {
        double worked = activeNanos() / 1.0E9d;
        double rate = rate();
        long made = FINISHED.get();
        long lost = FAILED.get();
        running = false;
        hideBar();
        releaseKnownChunks();
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[PRÉGÉN] terminé — %d chunk(s) générés et %d sautés en %s de travail, soit "
                + "%.1f par seconde. Ces chunks seront désormais servis depuis le disque, ce qui "
                + "coûte une quinzaine de fois moins que de les fabriquer devant le joueur.",
                made, skipped, humanDuration(worked), rate));
        if (lost > 0L) {
            Lanterne.LOG.warn("[PRÉGÉN] dont {} chunk(s) que le jeu a refusé de fabriquer. Ils "
                    + "seront fabriqués devant le joueur le jour où il y passera. Relancer la même "
                    + "commande tentera de les reprendre, et sautera tout le reste.", lost);
        }
        // La pause n'est dite QUE si elle a eu lieu, et le temps total est rappelé à côté du temps
        // de travail : sans cela, quelqu'un qui a regardé l'horloge trouverait le chiffre trop beau
        // et aurait raison de s'en méfier.
        double idle = pausedNanos / 1.0E9d;
        if (idle >= 1d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[PRÉGÉN] dont %s de pause, joueurs connectés — soit %s d'horloge en tout. "
                    + "Le débit ci-dessus est celui du travail réel, pas celui de l'attente.",
                    humanDuration(idle), humanDuration(worked + idle)));
        }
    }

    /** Arrête proprement, en laissant aboutir ce qui est en vol. */
    public static void halt() {
        if (running) {
            // On verse la pause en cours au compteur sans passer par resume(), qui annoncerait une
            // reprise : l'arrêt n'est pas une reprise, et le journal ne doit pas dire le contraire.
            if (pausedAt != 0L) {
                pausedNanos += System.nanoTime() - pausedAt;
                pausedAt = 0L;
            }
            running = false;
            hideBar();
            Lanterne.LOG.info("[PRÉGÉN] interrompu — {} chunk(s) générés, {} sautés, {} encore en "
                    + "vol qui aboutiront et seront écrits. La reprise est gratuite : relancer "
                    + "sautera d'emblée tout ce qui vient d'être écrit.",
                    FINISHED.get(), skipped, AIRBORNE.get());
            // Le numéro d'exécution avance : les rappels encore en route se tairont, et la table
            // des chunks connus peut être rendue sans risque de la voir relue.
            epoch++;
            releaseKnownChunks();
        }
    }
}
