package fr.clubcitrouille.lanterne.lab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le coffre : combien coûte l'écriture des chunks sur disque, et que paie-t-on en échange de la
 * vitesse choisie ?
 *
 * <h2>Deux chiffres, jamais l'un sans l'autre</h2>
 *
 * <p>{@code RegionFileVersion.java} (vérifié, lignes 26-45) propose quatre codecs pour un fichier de
 * région : gzip (id 1, historique, non sélectionnable depuis {@code server.properties}), deflate (id 2,
 * {@code "deflate"}, valeur par défaut), none (id 3, {@code "none"}, aucune compression) et lz4 (id 4,
 * {@code "lz4"}). Décompresser du lz4 coûte moins cher en cycles CPU que du deflate, ce qui accélère
 * la sauvegarde — mais lz4 vise la vitesse de (dé)compression, pas le taux, et produit donc des
 * fichiers plus gros. Annoncer le premier fait sans le second serait exactement le genre de chiffre
 * à moitié vrai que ce projet a appris à ne plus publier : ce banc rend donc systématiquement les deux,
 * dans le même rapport, jamais l'un sans l'autre.
 *
 * <h2>Quelle compression, et depuis quand</h2>
 *
 * <p>Le codec actif se lit avec {@code RegionFileVersion.getSelected()} (vérifié, ligne 90) — un champ
 * statique {@code volatile} (ligne 46) qui ne change qu'une fois par exécution du serveur : {@code
 * Main.main} (vérifié, ligne 117) appelle {@code RegionFileVersion.configure(settings.getProperties()
 * .regionFileComression)} — avec la faute de frappe vanilla dans le nom du champ, confirmée dans
 * {@code DedicatedServerProperties.java} ligne 110 — <b>avant</b> même de charger le monde, et rien
 * d'autre dans les sources parcourues n'appelle {@code configure} ensuite. Ce banc ne bascule donc
 * jamais le codec lui-même : il lit celui qui est actif pour cette exécution et le nomme dans son
 * verdict ({@link #compressionLabel}), par comparaison d'identité avec les cinq constantes publiques
 * de {@code RegionFileVersion} — la classe n'expose aucun accesseur public pour le nom textuel
 * (seul {@code getId()} est public). Comparer lz4 contre deflate exige d'éditer {@code
 * server.properties}, de relancer le serveur, puis de relancer ce banc une seconde fois : ce n'est
 * pas une limite de ce code, c'est celle de l'API vanilla elle-même.
 *
 * <h2>La rétrocompatibilité par chunk, vérifiée dans {@code RegionFile.java}</h2>
 *
 * <p>Le brief affirme qu'un monde écrit en deflate se relit sans problème après bascule en lz4.
 * <b>Vérifié, et exact.</b> {@code RegionFile.write} (via {@code ChunkBuffer}, vérifié ligne 387) grave
 * l'identifiant du codec <em>utilisé pour ce chunk</em> dans les cinq octets d'en-tête qui précèdent
 * son flux, avec {@code this.version.getId()} — un champ figé à la construction du {@code RegionFile}
 * (ligne 50, {@code RegionFileVersion.getSelected()} lu une seule fois par fichier ouvert). À la
 * lecture, {@code getChunkDataInputStream} (ligne 113) relit cet octet stocké ({@code byte versionId =
 * buffer.get()}, ligne 129) et appelle {@code RegionFileVersion.fromId(versionId)} (ligne 169) — jamais
 * {@code getSelected()}. Un chunk gravé en deflate reste donc lisible indéfiniment, quel que soit le
 * codec actif au moment de la lecture : c'est le format qui porte sa propre étiquette, chunk par
 * chunk, pas le fichier ni le serveur. La seule nuance : au sein d'une même exécution, un fichier de
 * région déjà ouvert garde le codec qu'il avait à son ouverture pour toute <em>nouvelle</em> écriture
 * (le champ {@code this.version} ne se relit jamais) — sans conséquence pratique ici, puisque {@code
 * configure} n'agit qu'avant le premier accès à un monde.
 *
 * <h2>Le choix de la méthode de sauvegarde</h2>
 *
 * <p>Trois candidats vérifiés : {@code ServerLevel.save(ProgressListener, boolean, boolean)} (ligne
 * 893, sauvegarde aussi {@code level.dat} et le dossier {@code entities}), {@code MinecraftServer
 * .saveEverything(boolean, boolean, boolean)} (ligne 632, en plus sauvegarde tous les joueurs et toutes
 * les dimensions) et {@code ServerChunkCache.save(boolean)} (ligne 307, appelle {@code ChunkMap
 * .saveAllChunks(flushStorage)} puis rend la main). On retient ce dernier : ce banc ne mesure que le
 * dossier {@code region} (voir {@code regionDir}), et les deux premiers y mêleraient le coût
 * d'écritures qui n'y vivent pas (données de niveau, entités, autres dimensions) — un chiffre de temps
 * qui ne correspondrait plus exactement au chiffre de taille rapporté à côté.
 *
 * <p>Avec {@code flushStorage = true}, {@code ChunkMap.saveAllChunks} (ligne 413) termine par {@code
 * this.synchronize(true).join()} ; {@code IOWorker.synchronize(true)} (vérifié, lignes 155-169) attend
 * la fin de toutes les écritures en attente puis appelle {@code RegionFileStorage.flush()}, qui force
 * {@code FileChannel.force(true)} sur chaque fichier de région ouvert. Le temps mesuré ici couvre donc
 * l'encodage <em>et</em> l'appel système qui pose réellement les octets sur le disque — pas seulement
 * leur mise en file d'attente.
 *
 * <h2>Le piège principal : une sauvegarde qui n'a rien à sauvegarder</h2>
 *
 * <p>{@code ChunkMap.save(ChunkAccess)} (vérifié, ligne 746) commence par {@code if (!chunk
 * .tryMarkSaved()) return false;} — et {@code tryMarkSaved()} ({@code ChunkAccess.java}, vérifié, lignes
 * 266-273) ne rend {@code true}, en repassant le drapeau à faux, que si {@code unsaved} valait déjà
 * {@code true}. Un chunk déjà sauvegardé une première fois ne l'est <b>jamais</b> une seconde tant que
 * rien ne l'a remarqué modifié : aucun encodage, aucune écriture, un relevé qui rendrait zéro seconde
 * après la première mesure. C'est le piège que ce banc doit éviter avant tout.
 *
 * <p>La parade retenue : {@code LevelChunk.markUnsaved()} (vérifié, ligne 178 — surcharge publique et
 * sans argument, qui appelle {@code ChunkAccess.markUnsaved()} puis notifie le
 * {@code UnsavedListener}). Il n'existe pas de {@code setUnsaved(boolean)} dans les sources parcourues.
 * Appelé sur les {@value #GRID_CHUNKS} chunks tenus par ce banc juste avant chaque relevé, il force
 * {@code unsaved = true} sans toucher un seul bloc — pas de cratère à reboucher, pas de contenu
 * synthétique qui fausserait le taux de compression réel. Le contenu réellement écrit à chaque relevé
 * est donc le terrain généré une fois en préparation, identique d'un relevé à l'autre : le poids sur
 * disque qui en résulte décrit fidèlement ce codec sur ce terrain, sans le bruit qu'introduirait un
 * contenu qui changerait à chaque passage.
 *
 * <h2>Des chunks forcés, pas de simples chunks visités</h2>
 *
 * <p>{@code ServerLevel.setChunkForced(int, int, boolean)} (vérifié, ligne 1520) pose un ticket
 * permanent — celui-là même que la commande {@code /forceload} utilise — puis, s'il vient d'être posé,
 * charge immédiatement le chunk. Un simple {@code getChunk(..., true)} sans ticket durable aurait
 * exposé ce banc au même risque que {@code Quarry} documente pour son propre régime de chargement :
 * un ticket {@code TicketType.UNKNOWN} qui expire en deux ticks, suivi d'un déchargement qui viderait
 * discrètement la grille pendant les {@value #GRID_CHUNKS} ticks que prend sa préparation. Un ticket
 * forcé ne s'éteint pas de lui-même : les {@value #GRID_CHUNKS} chunks restent résidents, chargés un
 * par tick pendant la préparation pour ne jamais fabriquer, en un seul tick, le pic de charge que
 * {@code Rationing} est censé absorber — même raisonnement que {@code Boom} et {@code Quarry} pour
 * leur propre travail étalé. {@link #finish} retire ces tickets un par un en fin d'épreuve : un
 * chunk forcé oublié resterait chargé pour toujours, y compris après un redémarrage du serveur.
 *
 * <h2>Où vit le dossier {@code region}, et pourquoi ce n'est plus la racine du monde</h2>
 *
 * <p>{@code ChunkMap} (vérifié, ligne 172) résout le dossier de région d'une dimension avec {@code
 * levelStorage.getDimensionPath(level.dimension()).resolve("region")}. {@code LevelStorageAccess
 * .getDimensionPath} (ligne 524, {@code LevelStorageSource.java}) délègue à {@code DimensionType
 * .getStorageFolder(ResourceKey, Path)} (ligne 143), qui résout <b>l'identifiant complet de la
 * dimension</b> — {@code minecraft:overworld} y compris — contre {@code baseFolder.resolve
 * ("dimensions")}. Aucun cas particulier pour l'overworld n'apparaît dans les sources parcourues : ce
 * n'est plus, dans cette version, le dossier {@code <monde>/region} historique, mais {@code
 * <monde>/dimensions/minecraft/overworld/region}. {@code storageSource} étant un champ protégé de
 * {@code MinecraftServer} inaccessible depuis ce paquet, ce banc reconstruit le même chemin avec deux
 * appels publics : {@code server.getWorldPath(LevelResource.ROOT)} (ligne 2058, équivalent public de
 * {@code storageSource.getLevelPath(LevelResource.ROOT)}) puis {@code DimensionType.getStorageFolder}
 * directement, la même méthode statique publique que {@code ChunkMap} appelle en interne.
 *
 * <h2>Ce que « avant / après » mesure vraiment</h2>
 *
 * <p>Le brief demande la taille <b>totale</b> du dossier {@code region}, pas seulement celle du fichier
 * qui contient la grille de ce banc — et c'est ce qui est rendu ici. Le calcul suppose qu'aucune autre
 * activité (joueur, autre épreuve, sauvegarde automatique du jeu) n'écrit dans ce dossier entre la
 * mesure « avant » et la mesure « après » : une hypothèse raisonnable pour une épreuve headless isolée
 * comme celle-ci, mais une hypothèse, pas une garantie — exactement le genre de condition que {@code
 * Preflight} vérifierait si son objet était les joueurs et les entités plutôt que le disque ; il ne
 * l'est pas, et ce banc porte donc son propre contrôle, dans le même esprit.
 *
 * <p>Second risque, celui-là vérifiable et vérifié ici : si ce banc a déjà tourné sur ce même monde,
 * le fichier {@code .mca} qui couvre sa grille existe déjà avant même que {@link #begin} ne touche un
 * seul chunk, et le delta de taille ne dira plus rien du coût d'une première écriture — seulement
 * celui, généralement nul, d'une réécriture à l'identique. {@link #begin} teste l'existence de ce
 * fichier précis ({@code r.<rx>.<rz>.mca}, coordonnées lues via {@code ChunkPos.getRegionX()/
 * getRegionZ()}, vérifié) avant de commencer, et le verdict le dit plutôt que de laisser un delta
 * proche de zéro passer pour « cette compression n'occupe presque rien ».
 *
 * <h2>Chauffe : un coût de premier accès, pas un compilateur à réchauffer</h2>
 *
 * <p>{@code RegionFileStorage.getRegionFile} (vérifié, lignes 32-48) garde en cache jusqu'à 256
 * fichiers de région ouverts ; le tout premier accès à un fichier donné paie la création du dossier,
 * l'ouverture du canal et l'analyse de son en-tête de 8 Ko (constructeur de {@code RegionFile}, ligne
 * 49), un coût que les accès suivants n'ont plus à payer. Ce n'est pas la chauffe du compilateur à la
 * volée que {@code Bench} rejette pour d'autres raisons — c'est un coût d'amorçage disque, réel une
 * seule fois. {@value #WARMUP} relevés le jettent avant que la médiane ne commence.
 *
 * <h2>Limites assumées</h2>
 *
 * <ul>
 *   <li>Le temps mesuré inclut l'appel système de synchronisation ({@code FileChannel.force(true)}) :
 *       il dépend donc, en partie, du disque physique de la machine qui exécute ce banc, pas seulement
 *       du codec. C'est une propriété du protocole de sauvegarde vanilla lui-même, pas un artefact de
 *       ce banc.</li>
 *   <li>Rien, depuis l'API publique parcourue, ne permet de vérifier directement que {@code ChunkHolder
 *       .wasAccessibleSinceLastSave} (le filtre de {@code ChunkMap.saveAllChunks}, ligne 418) est vrai
 *       pour un chunk forcé sans lire du code interne non exposé. Ce banc s'appuie sur le fait, vérifié
 *       dans {@code ChunkHolder.refreshAccessibility} (ligne 333), que ce drapeau suit le niveau de
 *       ticket du chunk — et qu'un ticket forcé, comme un ticket de joueur, maintient ce niveau à
 *       {@code FULL}. Si un relevé rendait pourtant zéro seconde après la chauffe, ce serait le premier
 *       endroit à revérifier.</li>
 *   <li>Ce banc ne compare pas « avec Lanterne » et « sans Lanterne » : aucun module de ce mod ne touche
 *       à la sauvegarde des chunks, et rien ici n'affirme le contraire. Il mesure un mécanisme vanilla,
 *       pour ce que ce mécanisme coûte réellement sur cette machine, avec le codec réellement actif.</li>
 * </ul>
 */
public final class Vault {
    /**
     * Origine (en coordonnées de chunk) de la grille mesurée : le seul des quatre cadrans à cent mille
     * blocs du centre que {@code Quarry} n'utilise pas ({@code (6250,6250)}, {@code (6250,-6250)} et
     * {@code (-6250,6250)} y sont déjà pris) — loin de tout ce qu'un joueur aurait pu visiter, et sans
     * risque de recouper une grille d'une autre épreuve du même monde.
     */
    private static final int CHUNK_ORIGIN_X = -6250;
    private static final int CHUNK_ORIGIN_Z = -6250;

    /** Côté de la grille carrée de chunks : {@value #SIDE} × {@value #SIDE}. */
    private static final int SIDE = 8;
    /** Chunks tenus en permanence par ce banc, forcés du début à la fin de l'épreuve. */
    private static final int GRID_CHUNKS = SIDE * SIDE;

    /** Relevés par épreuve, chauffe comprise — voir le Javadoc de classe sur ce que paie le premier. */
    private static final int READINGS = 12;
    /** Parmi les {@link #READINGS} relevés, ceux qu'on jette en tête. */
    private static final int WARMUP = 2;
    /** Relevés effectivement retenus dans la médiane. */
    private static final int MEASURED = READINGS - WARMUP;

    private enum Step { OFF, PREP, RUN, DONE }

    private static Step step = Step.OFF;
    /** Prochain chunk à forcer pendant {@link Step#PREP}, de 0 à {@link #GRID_CHUNKS} exclu. */
    private static int prepIndex;
    /** Relevé courant pendant {@link Step#RUN}, de 0 à {@link #READINGS} exclu. */
    private static int shot;
    private static MinecraftServer host;

    /** Les chunks tenus par ce banc, dans l'ordre de {@link #chunkPosAt}. */
    private static final LevelChunk[] chunks = new LevelChunk[GRID_CHUNKS];

    /** Dossier {@code region} de l'overworld — voir le Javadoc de classe sur sa résolution. */
    private static Path regionDir;
    /** Vrai si le fichier {@code .mca} de la grille existait déjà avant cette épreuve — voir Javadoc. */
    private static boolean regionFilePreexisting;

    private static long sizeBeforeBytes;
    private static long sizeAfterBytes;

    private static final long[] samples = new long[MEASURED];

    private Vault() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    /**
     * Ouvre l'épreuve, après un unique contrôle : la grille tient-elle dans la bordure du monde ? Même
     * raisonnement que {@code Quarry.begin} — aucune autre condition ne peut être vérifiée à l'avance
     * depuis l'API publique.
     */
    public static void begin(MinecraftServer server) {
        host = server;
        ServerLevel level = server.overworld();

        if (!gridWithinBorder(level)) {
            Lanterne.LOG.error(
                    "[COFFRE] MESURE REFUSÉE : la grille de {}×{} chunks déborde de la bordure du "
                            + "monde (WorldBorder.isWithinBounds). Rapprocher l'origine du centre avant "
                            + "de relancer.",
                    SIDE, SIDE);
            step = Step.OFF;
            host.halt(false);
            return;
        }

        regionDir = regionDirectory(server, level);
        ChunkPos origin = chunkPosAt(0);
        Path expectedRegionFile = regionDir.resolve(
                "r." + origin.getRegionX() + "." + origin.getRegionZ() + ".mca");
        regionFilePreexisting = Files.exists(expectedRegionFile);

        step = Step.PREP;
        prepIndex = 0;
        shot = 0;

        Lanterne.LOG.info(
                "[COFFRE] Épreuve lancée — grille de {} chunks en ({},{}), compression active : {} · "
                        + "dossier region : {}{}",
                GRID_CHUNKS, CHUNK_ORIGIN_X, CHUNK_ORIGIN_Z,
                compressionLabel(RegionFileVersion.getSelected()), regionDir,
                regionFilePreexisting
                        ? " (ATTENTION : le fichier de région de cette grille existe déjà — voir le "
                                + "Javadoc de classe sur ce que cela retire à la mesure de taille)"
                        : " (fichier de région de cette grille absent : première écriture garantie)");
    }

    /** Fait avancer l'épreuve d'un pas. Appelé à chaque tick du serveur, comme {@code Boom.tick}. */
    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();

        switch (step) {
            case PREP -> prepStep(level);
            case RUN -> runStep(level);
            default -> { }
        }
    }

    /**
     * Force et charge un chunk de plus, un par tick — voir le Javadoc de classe sur le risque de
     * fabriquer un pic de charge en en chargeant {@value #GRID_CHUNKS} d'un coup.
     */
    private static void prepStep(ServerLevel level) {
        ChunkPos pos = chunkPosAt(prepIndex);
        level.setChunkForced(pos.x(), pos.z(), true);
        chunks[prepIndex] = level.getChunk(pos.x(), pos.z());
        prepIndex++;

        if (prepIndex >= GRID_CHUNKS) {
            sizeBeforeBytes = regionBytes(regionDir);
            step = Step.RUN;
            shot = 0;
            Lanterne.LOG.info(
                    "[COFFRE] Grille chargée et forcée — dossier region avant mesure : {}. Début des "
                            + "{} relevés ({} de chauffe jetés).",
                    formatBytes(sizeBeforeBytes), READINGS, WARMUP);
        }
    }

    /**
     * Un relevé : marquer la grille modifiée, chronométrer une sauvegarde complète et flushée, avancer.
     *
     * <p>{@link #chunks} sont remarqués avant chaque relevé, y compris le premier — voir le Javadoc de
     * classe sur {@code tryMarkSaved} : sans cela, seul ce tout premier relevé écrirait quoi que ce
     * soit sur disque, et les {@value #READINGS} moins un suivants mesureraient un aller-retour à vide.
     */
    private static void runStep(ServerLevel level) {
        for (LevelChunk chunk : chunks) {
            chunk.markUnsaved();
        }

        ServerChunkCache chunkSource = level.getChunkSource();
        long start = System.nanoTime();
        chunkSource.save(true);
        long elapsed = System.nanoTime() - start;

        if (shot >= WARMUP) {
            samples[shot - WARMUP] = elapsed;
        }
        shot++;

        if (shot >= READINGS) {
            sizeAfterBytes = regionBytes(regionDir);
            finish(level);
        }
    }

    /** Libère les tickets forcés, rend le verdict, arrête le serveur — voir Javadoc sur le nettoyage. */
    private static void finish(ServerLevel level) {
        for (int i = 0; i < GRID_CHUNKS; i++) {
            ChunkPos pos = chunkPosAt(i);
            level.setChunkForced(pos.x(), pos.z(), false);
        }

        step = Step.DONE;
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    /**
     * Le verdict : temps et taille, toujours ensemble — voir le Javadoc de classe sur la raison de ne
     * jamais publier l'un sans l'autre.
     */
    private static void report() {
        double medianNanos = median(samples);
        long totalNanos = total(samples);
        long deltaBytes = sizeAfterBytes - sizeBeforeBytes;
        RegionFileVersion active = RegionFileVersion.getSelected();

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COFFRE] ── Sauvegarde de %d chunks, compression « %s » (id %d) ──",
                GRID_CHUNKS, compressionLabel(active), active.getId()));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COFFRE] Temps de sauvegarde — médiane sur %d relevés utiles : %.2f ms · total : "
                        + "%.1f ms",
                MEASURED, medianNanos / 1e6, totalNanos / 1e6));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COFFRE] Dossier region — avant : %s · après : %s · delta : %s (%s/chunk)",
                formatBytes(sizeBeforeBytes), formatBytes(sizeAfterBytes), formatBytes(deltaBytes),
                formatBytes(deltaBytes / GRID_CHUNKS)));

        if (regionFilePreexisting) {
            Lanterne.LOG.warn(
                    "[COFFRE] ATTENTION : le fichier de région de cette grille existait déjà avant "
                            + "cette épreuve (rejeu sur le même monde ?) — le delta de taille ci-dessus "
                            + "sous-estime le coût d'une première écriture. Changer CHUNK_ORIGIN_X / "
                            + "CHUNK_ORIGIN_Z avant de relancer pour un chiffre de taille fiable.");
        }

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COFFRE] VERDICT : sous cette compression, %d chunks coûtent %.2f ms en médiane et "
                        + "occupent %s sur disque — vitesse et place sont deux faces de la même "
                        + "décision, ni l'une ni l'autre ne se lit seule.",
                GRID_CHUNKS, medianNanos / 1e6, formatBytes(deltaBytes)));
    }

    /** Position du chunk d'index {@code index} dans la grille, en balayant ligne par ligne. */
    private static ChunkPos chunkPosAt(int index) {
        int dx = index % SIDE;
        int dz = index / SIDE;
        return new ChunkPos(CHUNK_ORIGIN_X + dx, CHUNK_ORIGIN_Z + dz);
    }

    /** Vrai si les deux coins de la grille tiennent dans la bordure réelle du monde. */
    private static boolean gridWithinBorder(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        ChunkPos near = chunkPosAt(0);
        ChunkPos far = chunkPosAt(GRID_CHUNKS - 1);
        return border.isWithinBounds(near) && border.isWithinBounds(far);
    }

    /**
     * Reconstruit le chemin du dossier {@code region} de l'overworld avec deux appels publics — voir
     * le Javadoc de classe sur pourquoi {@code LevelStorageAccess.getDimensionPath} n'est pas
     * directement accessible depuis ce paquet.
     */
    private static Path regionDirectory(MinecraftServer server, ServerLevel level) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path dimensionPath = DimensionType.getStorageFolder(level.dimension(), root);
        return dimensionPath.resolve("region").normalize();
    }

    /** Taille totale, en octets, de tous les fichiers réguliers du dossier — zéro s'il n'existe pas. */
    private static long regionBytes(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).mapToLong(Vault::fileSize).sum();
        } catch (IOException failed) {
            Lanterne.LOG.warn("[COFFRE] Impossible de mesurer le dossier region : {}", failed.toString());
            return -1L;
        }
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException failed) {
            return 0L;
        }
    }

    /**
     * Le nom du codec actif, par comparaison d'identité avec les constantes publiques de {@code
     * RegionFileVersion} — voir le Javadoc de classe sur l'absence d'accesseur public pour ce nom.
     */
    private static String compressionLabel(RegionFileVersion version) {
        if (version == RegionFileVersion.VERSION_LZ4) {
            return "lz4";
        } else if (version == RegionFileVersion.VERSION_DEFLATE) {
            return "deflate";
        } else if (version == RegionFileVersion.VERSION_NONE) {
            return "none";
        } else if (version == RegionFileVersion.VERSION_GZIP) {
            return "gzip";
        } else if (version == RegionFileVersion.VERSION_CUSTOM) {
            return "custom";
        } else {
            return "inconnu (id=" + version.getId() + ")";
        }
    }

    /** Même mise en forme que {@code Bench.bytes} : Go, Mo ou Ko selon l'ordre de grandeur. */
    private static String formatBytes(long value) {
        long magnitude = Math.abs(value);
        if (magnitude > 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.2f Go", value / 1_073_741_824d);
        }
        if (magnitude > 1_048_576L) {
            return String.format(Locale.ROOT, "%.1f Mo", value / 1_048_576d);
        }
        return String.format(Locale.ROOT, "%.1f Ko", value / 1024d);
    }

    /** La médiane d'un échantillon — même raisonnement que {@code Boom.median}. */
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
