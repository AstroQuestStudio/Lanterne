package fr.clubcitrouille.lanterne.core;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.management.ObjectName;

import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Ce qui occupe réellement le tas, une fois le serveur stabilisé.
 *
 * <h2>Pourquoi cet instrument devait exister</h2>
 *
 * <p>{@link Ballast} a établi la distinction qui commande tout ce dossier : la mémoire
 * <b>allouée</b> est un débit, la mémoire <b>retenue</b> est un stock. Lanterne divise le premier par
 * douze et ne touche pas au second. Or c'est le second qui décide combien de mods tiennent dans
 * quatre gigaoctets.
 *
 * <p>Ballast a aussi montré la limite de son propre raisonnement : il chiffrait les tables de
 * voisinage des états de bloc <em>par le calcul</em>, parce que c'est là que la littérature dit de
 * regarder. La réponse fut 4,8 Mo sur 225 — la littérature regardait au mauvais endroit. Le verdict
 * final n'est venu que d'un histogramme de tas pris à la main, hors du jeu, avec un outil que
 * personne n'a sur un hébergement mutualisé.
 *
 * <p>Cet inventaire ramène cette mesure <b>dans le mod</b>, pour qu'un administrateur puisse la
 * prendre sur sa propre machine, sur son propre modpack, sans JDK ni profileur. C'est la seule façon
 * d'ordonner les chantiers par ce que la machine dit, et non par ce que les mods d'optimisation
 * annoncent.
 *
 * <h2>Deux mesures, parce qu'aucune ne suffit seule</h2>
 *
 * <p>La première est l'<b>histogramme du tas</b>, obtenu de la machine virtuelle elle-même. Il dit la
 * vérité — c'est un comptage, pas une estimation — mais il la dit en classes Java. Apprendre que
 * {@code long[]} pèse deux cent quarante mégaoctets ne dit pas <em>à qui</em> ils appartiennent.
 *
 * <p>La seconde est le <b>relevé par domaine de jeu</b> : on parcourt les chunks chargés et l'on
 * additionne ce que chaque poste occupe, avec l'arithmétique exacte des tableaux que le jeu a dû
 * allouer. Il dit à qui appartiennent les octets, mais seulement pour ce qu'on a pensé à compter.
 *
 * <p>Les deux ensemble ferment la boucle : si le relevé par domaine explique l'essentiel de
 * l'histogramme, l'inventaire est complet ; s'il reste un écart béant, c'est qu'un poste nous
 * échappe, et c'est <em>là</em> qu'il faut aller voir.
 *
 * <h2>Pourquoi l'histogramme passe par une interface d'administration</h2>
 *
 * <p>{@code jcmd} et {@code jmap} sont des outils du JDK. Un serveur hébergé tourne le plus souvent
 * sur un JRE, dans un conteneur sans accès au système de fichiers de l'hôte : la mesure y serait
 * impossible, c'est-à-dire qu'elle ne serait jamais prise.
 *
 * <p>La machine virtuelle expose pourtant les mêmes commandes de diagnostic par un bean
 * d'administration, dans le module {@code jdk.management} que tout JRE moderne embarque. On lui parle
 * donc de l'intérieur, sans processus fils, sans binaire externe et sans droits particuliers.
 *
 * <p>La commande {@code GC.class_histogram} déclenche un ramassage complet avant de compter. C'est
 * exactement ce qu'il faut ici : ce qui survit à un ramassage complet <b>est</b> la mémoire retenue.
 * Compter sans cela mesurerait les déchets en attente, c'est-à-dire l'erreur que Ballast a été écrit
 * pour dénoncer.
 *
 * <h2>Ce que cet inventaire ne fait pas</h2>
 *
 * <p>Il ne modifie rien et ne décide de rien. Il coûte un ramassage complet — une pause bien réelle,
 * de l'ordre de la seconde sur un petit tas — et c'est pourquoi il n'est pris que sur commande,
 * jamais en boucle. Un instrument qui se déclenche tout seul dans un tick est un défaut, pas une
 * mesure.
 */
public final class Inventaire {

    /** En-tête d'un tableau Java sur une machine virtuelle à références compressées. */
    private static final int ARRAY_HEADER = 16;

    /** Blocs par section de terrain. */
    private static final int CELLS = 4096;

    /** Cases de biome par section : une pour quatre blocs dans chaque axe. */
    private static final int BIOME_CELLS = 64;

    /** Octets d'une couche de lumière pleinement allouée. */
    private static final int LIGHT_BYTES = 2048;

    /**
     * Ce que coûte un détecteur de concurrence, verrous compris.
     *
     * <p>Chaque {@code PalettedContainer} en porte un, et chaque section de terrain porte deux
     * conteneurs — celui des états et celui des biomes. Le détecteur n'est pas un simple drapeau :
     *
     * <pre>
     * ThreadingDetector  en-tête 16 + 5 références    =  40 o
     *   Semaphore        en-tête 16 + 1 référence     =  24 o
     *     NonfairSync    en-tête 16 + état + 3 réf.   =  32 o
     *   ReentrantLock    en-tête 16 + 1 référence     =  24 o
     *     NonfairSync    en-tête 16 + état + 3 réf.   =  32 o
     * </pre>
     *
     * <p>Cent cinquante-deux octets par conteneur, trois cent quatre par section — et cela vaut aussi
     * pour les sections d'air pur, que le relevé compte par ailleurs comme « gratuites ». Elles ne le
     * sont pas : elles ne paient pas de stockage, elles paient de la serrure.
     */
    private static final int DETECTOR_BYTES = 152;

    /** Rayon du relevé par domaine, en chunks. Le même que celui de {@code Weave}, pour comparer. */
    private static final int REACH = 12;

    /** Combien de classes de l'histogramme on montre. Au-delà, la queue ne pèse plus rien. */
    private static final int TOP = 14;

    private Inventaire() {}

    // ------------------------------------------------------------------ public

    /**
     * L'inventaire complet, ligne à ligne.
     *
     * <p>Le destinataire est un {@link Consumer} et non une source de commande : la même mesure doit
     * pouvoir partir au journal depuis une épreuve sans joueur connecté. Un instrument qui exige un
     * humain n'est pas un instrument — c'est la leçon que {@code Weave} avait déjà tirée, après être
     * resté une session entière sans être lancé une seule fois.
     */
    public static void survey(MinecraftServer server, Consumer<String> say) {
        say.accept("── Inventaire de la mémoire RETENUE ──");
        heap(say);
        say.accept("");
        domains(server, say);
    }

    // ------------------------------------------------------------- l'histogramme

    /**
     * Ce que la machine virtuelle elle-même dit du tas, après un ramassage complet.
     */
    private static void heap(Consumer<String> say) {
        String raw = classHistogram();
        if (raw == null) {
            say.accept("Histogramme indisponible : la machine virtuelle n'expose pas "
                    + "GC.class_histogram. Le relevé par domaine ci-dessous reste valable.");
            return;
        }

        // Le ramassage complet a eu lieu à l'intérieur de GC.class_histogram : ce qu'on lit
        // maintenant est le jeu vivant, et non les déchets en attente.
        long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long max = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        say.accept(String.format(Locale.ROOT,
                "Tas retenu après ramassage complet : %.1f Mo sur %.0f Mo de plafond (%.0f %%).",
                used / 1048576d, max / 1048576d, used * 100d / Math.max(1L, max)));

        List<Line> lines = parse(raw);
        if (lines.isEmpty()) {
            say.accept("Histogramme illisible — format inattendu.");
            return;
        }

        long total = 0L;
        for (Line line : lines) {
            total += line.bytes;
        }

        say.accept(String.format(Locale.ROOT, "Les %d premières classes, sur %.1f Mo comptés :",
                Math.min(TOP, lines.size()), total / 1048576d));
        for (int i = 0; i < Math.min(TOP, lines.size()); i++) {
            Line line = lines.get(i);
            say.accept(String.format(Locale.ROOT, "  %7.1f Mo  %5.1f %%  %s",
                    line.bytes / 1048576d, line.bytes * 100d / Math.max(1L, total), line.name));
        }
    }

    /**
     * Demande l'histogramme à la machine virtuelle, sans processus fils ni outil du JDK.
     *
     * <p>Rend {@code null} plutôt que de lever : un inventaire qui échoue doit dégrader ce qu'il
     * montre, pas faire tomber la commande qui l'a demandé.
     */
    private static String classHistogram() {
        try {
            Object answer = ManagementFactory.getPlatformMBeanServer().invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "gcClassHistogram",
                    new Object[] {new String[0]},
                    new String[] {String[].class.getName()});
            return answer == null ? null : answer.toString();
        } catch (Exception unavailable) {
            Lanterne.LOG.warn("Histogramme du tas indisponible : {}", unavailable.toString());
            return null;
        }
    }

    /** Une ligne de l'histogramme, réduite à ce qui nous intéresse. */
    private record Line(long bytes, String name) {}

    /**
     * Découpe la sortie de {@code GC.class_histogram}.
     *
     * <p>Le format est {@code rang: instances octets nom}, précédé d'un en-tête et suivi d'un total.
     * On ne se fie donc ni au nombre de lignes ni à leur ordre : toute ligne dont les deux premiers
     * champs numériques ne se lisent pas est simplement écartée.
     */
    private static List<Line> parse(String raw) {
        List<Line> lines = new ArrayList<>();
        for (String row : raw.split("\\R")) {
            String[] parts = row.trim().split("\\s+");
            if (parts.length < 4 || !parts[0].endsWith(":")) {
                continue;
            }
            try {
                long bytes = Long.parseLong(parts[2]);
                lines.add(new Line(bytes, parts[3]));
            } catch (NumberFormatException header) {
                // En-tête ou séparateur : rien à prendre, on continue.
            }
        }
        return lines;
    }

    // ---------------------------------------------------------- le relevé de jeu

    /**
     * Ce que le terrain chargé occupe, poste par poste.
     *
     * <p>On ne force aucun chargement : {@code getChunkNow} rend {@code null} pour un chunk absent.
     * Forcer fausserait précisément ce qu'on mesure — on veut savoir ce que le serveur retient, pas
     * ce qu'il retiendrait si on l'y obligeait.
     */
    private static void domains(MinecraftServer server, Consumer<String> say) {
        long chunks = 0L;
        long sections = 0L;
        long allSections = 0L;
        long freeSections = 0L;
        long statesBytes = 0L;
        long statesNeeded = 0L;
        long biomeBytes = 0L;
        long heightBytes = 0L;
        long blockEntities = 0L;
        long entities = 0L;

        long lightAbsent = 0L;
        long lightFree = 0L;
        long lightPaid = 0L;
        long lightUniform = 0L;

        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity ignored : level.getAllEntities()) {
                entities++;
            }

            // On se place là où le serveur retient vraiment quelque chose : autour des joueurs. Sans
            // joueur — un banc, un serveur au repos — on retombe sur l'origine, qui est le centre
            // qu'emploie déjà le relevé des palettes, pour que les deux chiffres se comparent.
            List<net.minecraft.world.level.ChunkPos> centres = new ArrayList<>();
            for (var player : level.players()) {
                centres.add(player.chunkPosition());
            }
            if (centres.isEmpty()) {
                centres.add(new net.minecraft.world.level.ChunkPos(0, 0));
            }

            // Deux joueurs voisins partagent des chunks ; les compter deux fois gonflerait
            // l'inventaire d'autant, et c'est exactement le genre d'erreur qu'on ne verrait pas.
            java.util.Set<Long> seen = new java.util.HashSet<>();

            for (var centre : centres) {
                for (int dx = -REACH; dx <= REACH; dx++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    int cx = centre.x() + dx;
                    int cz = centre.z() + dz;
                    if (!seen.add((long) cx << 32 | (cz & 0xFFFFFFFFL))) {
                        continue;
                    }
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
                    chunks++;
                    blockEntities += chunk.getBlockEntities().size();

                    for (var entry : chunk.getHeightmaps()) {
                        // Une carte de hauteur est un BitStorage de 256 cases ; sa largeur dépend de
                        // la hauteur du monde et ne varie donc pas d'un chunk à l'autre.
                        heightBytes += ARRAY_HEADER
                                + (long) entry.getValue().getRawData().length * Long.BYTES;
                    }

                    for (LevelChunkSection section : chunk.getSections()) {
                        if (section == null) {
                            continue;
                        }
                        // Deux conteneurs par section, qu'elle porte du terrain ou de l'air pur :
                        // celui des états et celui des biomes. Chacun sa serrure.
                        allSections++;

                        int bits = section.getStates().bitsPerEntry();
                        if (section.hasOnlyAir() || bits == 0) {
                            freeSections++;
                        } else {
                            sections++;
                            statesBytes += ARRAY_HEADER + (long) CELLS * bits / 8L;
                            statesNeeded += (long) CELLS * Math.max(1, ceilLog2(distinct(section))) / 8L;
                        }
                        int biomeBits = section.getBiomes().bitsPerEntry();
                        if (biomeBits > 0) {
                            biomeBytes += ARRAY_HEADER + (long) BIOME_CELLS * biomeBits / 8L;
                        }
                    }

                    // La lumière se compte sur le moteur, pas sur le chunk : elle vit dans une carte
                    // à part, indexée par section, et déborde d'une section au-dessus et au-dessous
                    // du monde.
                    var engine = level.getLightEngine();
                    for (int y = engine.getMinLightSection(); y < engine.getMaxLightSection(); y++) {
                        for (LightLayer layer : LightLayer.values()) {
                            DataLayer data = engine.getLayerListener(layer)
                                    .getDataLayerData(SectionPos.of(chunk.getPos(), y));
                            if (data == null) {
                                lightAbsent++;
                            } else if (data.isDefinitelyHomogenous()) {
                                // Le jeu sait déjà ne rien allouer pour une couche uniforme.
                                lightFree++;
                            } else {
                                lightPaid++;
                                if (uniform(data)) {
                                    lightUniform++;
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        if (chunks == 0L) {
            // La règle de la maison : une épreuve sans scène refuse de conclure. Cinq épreuves de ce
            // dépôt ont un jour rendu « conforme » sur un monde vide.
            say.accept("Aucun chunk chargé autour des joueurs ni de l'origine — rien à "
                    + "inventorier. Relancez une fois un joueur connecté, ou après une "
                    + "pré-génération.");
            return;
        }

        long lightBytes = lightPaid * (ARRAY_HEADER + LIGHT_BYTES);
        long lightRecoverable = lightUniform * (ARRAY_HEADER + LIGHT_BYTES);
        long detectorBytes = allSections * 2L * DETECTOR_BYTES;
        long terrain = statesBytes + biomeBytes + heightBytes + lightBytes + detectorBytes;

        say.accept(String.format(Locale.ROOT,
                "Relevé par domaine · %d chunk(s) chargé(s) dans un rayon de %d, %d section(s) "
                + "occupée(s), %d déjà gratuite(s).", chunks, REACH, sections, freeSections));
        say.accept(String.format(Locale.ROOT,
                "  états de bloc     %8.2f Mo   (nécessaire %.2f Mo, gaspillé %.1f %%)",
                statesBytes / 1048576d, statesNeeded / 1048576d,
                (statesBytes - statesNeeded) * 100d / Math.max(1L, statesBytes)));
        say.accept(String.format(Locale.ROOT,
                "  lumière           %8.2f Mo   (%d couche(s) payée(s), %d libre(s), %d absente(s))",
                lightBytes / 1048576d, lightPaid, lightFree, lightAbsent));
        say.accept(String.format(Locale.ROOT,
                "      dont uniformes mais allouées : %d couche(s), soit %.2f Mo récupérables",
                lightUniform, lightRecoverable / 1048576d));
        say.accept(String.format(Locale.ROOT,
                "  cartes de hauteur %8.2f Mo", heightBytes / 1048576d));
        say.accept(String.format(Locale.ROOT,
                "  biomes            %8.2f Mo", biomeBytes / 1048576d));
        say.accept(String.format(Locale.ROOT,
                "  détecteurs de fil %8.2f Mo   (%d conteneur(s), verrous compris)",
                detectorBytes / 1048576d, allSections * 2L));
        say.accept(String.format(Locale.ROOT,
                "  ───────────────── %8.2f Mo de terrain, pour %d bloc-entité(s) et %d entité(s).",
                terrain / 1048576d, blockEntities, entities));
        say.accept(String.format(Locale.ROOT,
                "  soit %.1f Ko par chunk chargé.", terrain / 1024d / chunks));

        Lanterne.LOG.info("[INVENTAIRE] chunks={} sections={}/{} etats={}o lumiere={}o "
                + "(uniformes={}o) hauteurs={}o biomes={}o serrures={}o blocEntites={} entites={}",
                chunks, sections, allSections, statesBytes, lightBytes, lightRecoverable,
                heightBytes, biomeBytes, detectorBytes, blockEntities, entities);
    }

    /**
     * Une couche de lumière allouée porte-t-elle partout la même valeur ?
     *
     * <p>Chaque octet loge deux valeurs de quatre bits. Une couche uniforme est donc une couche dont
     * tous les octets sont égaux <b>et</b> dont les deux quartets de cet octet le sont aussi — sans
     * quoi deux blocs voisins auraient des luminosités différentes.
     *
     * <p>L'appel à {@code getData()} n'est fait qu'après avoir vérifié que la couche n'est pas
     * homogène : sur une couche homogène, il <em>allouerait</em> les deux mille octets qu'on cherche
     * justement à éviter. Mesurer en créant ce qu'on mesure est le piège classique de ce genre
     * d'outil.
     */
    private static boolean uniform(DataLayer data) {
        byte[] bytes = data.getData();
        byte first = bytes[0];
        if ((first & 0x0F) != ((first >> 4) & 0x0F)) {
            return false;
        }
        for (byte value : bytes) {
            if (value != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * Le nombre d'états réellement présents dans la section.
     *
     * <p>On compte au lieu de lire la taille de la palette, parce qu'une palette garde ses entrées
     * mortes jusqu'à la prochaine écriture sur le disque. Lire sa taille répondrait « combien d'états
     * ont un jour existé ici », et c'est précisément la mauvaise question.
     */
    private static int distinct(LevelChunkSection section) {
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
