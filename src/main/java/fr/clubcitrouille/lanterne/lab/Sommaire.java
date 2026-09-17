package fr.clubcitrouille.lanterne.lab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve du sommaire : un paquet zip interrogé deux cents fois, avec et sans index.
 *
 * <h2>Pourquoi le banc de vitesse ne peut pas mesurer ce module</h2>
 *
 * <p>Deux raisons, et chacune suffirait. La lecture des paquets a lieu <b>au chargement</b>, avant
 * que le premier tick n'existe : le chronomètre du banc n'est pas encore ouvert. Et le banc mesure
 * des millisecondes par tick, alors que ce module en supprime au <em>démarrage</em> — deux grandeurs
 * qui n'ont aucun rapport.
 *
 * <p>C'est la même raison qui a fait écrire {@link Zip} pour la compression des paquets réseau.
 *
 * <h2>La scène est bâtie, et non trouvée</h2>
 *
 * <p>On fabrique un zip : {@link #ENTRIES} entrées réparties sur {@link #NAMESPACES} espaces de noms
 * et {@link #DIRECTORIES} répertoires. Le fabriquer plutôt que chercher un vrai paquet a une raison
 * qui compte : une épreuve qui dépend de ce qui traîne sur le disque de celui qui la lance rend un
 * chiffre différent chez chacun, et donc aucun chiffre.
 *
 * <p>Le nombre d'entrées est un ordre de grandeur réaliste : l'auteur de {@code quick-pack} mesure
 * son cas sur un paquet à soixante mille entrées.
 *
 * <h2>Ce qu'elle vérifie avant de chronométrer</h2>
 *
 * <p>Que les deux chemins rendent <b>exactement les mêmes ressources</b>. Un index qui va vite en
 * oubliant la moitié d'un répertoire ne serait pas une optimisation, ce serait un paquet de
 * ressources amputé — et rien ne planterait. C'est la panne la plus difficile à voir de tout ce
 * dépôt : elle ne produit ni erreur, ni lenteur, seulement une texture manquante ou une recette
 * absente, des semaines plus tard.
 *
 * <p>{@code LANTERNE_BREAK_SOMMAIRE=1} retire la borne supérieure de la recherche. L'épreuve
 * <b>doit</b> alors annoncer une divergence.
 */
public final class Sommaire {
    /** Entrées du paquet fabriqué. */
    private static final int ENTRIES = 60_000;

    /** Espaces de noms, comme dans un gros assemblage de mods. */
    private static final int NAMESPACES = 12;

    /** Répertoires par espace de noms : recettes, tables de butin, étiquettes… */
    private static final int DIRECTORIES = 8;

    /** Interrogations par passe. L'ordre de grandeur d'un rechargement de datapacks. */
    private static final int QUERIES = NAMESPACES * DIRECTORIES;

    /** Passes de chauffe, jetées. */
    private static final int WARMUP = 2;

    /** Passes mesurées, alternées. */
    private static final int PASSES = 8;

    private Sommaire() {}

    public static void run(MinecraftServer server) {
        Path archive;
        try {
            archive = build();
        } catch (IOException broken) {
            Lanterne.LOG.error("[SOMMAIRE] Impossible de fabriquer le paquet d'essai — "
                    + "rien à mesurer.", broken);
            return;
        }

        PackLocationInfo where = new PackLocationInfo("lanterne-banc",
                Component.literal("Banc du sommaire"), PackSource.BUILT_IN, Optional.empty());
        // openPrimary() a disparu en 26.3 : openResources() rend maintenant un Stream (un paquet
        // avec overlays en rendrait plusieurs), et ce banc n'en a jamais eu — le premier suffit.
        Pack.Metadata metadata = new Pack.Metadata(Component.literal("Banc du sommaire"),
                PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), false);
        PackResources pack = new FilePackResources.FileResourcesSupplier(archive)
                .openResources(where, metadata)
                .findFirst()
                .orElseThrow();

        Lanterne.LOG.info("[SOMMAIRE] Paquet fabriqué : {} entrées, {} espaces de noms, {} "
                + "répertoires · {} interrogations par passe.",
                ENTRIES, NAMESPACES, DIRECTORIES, QUERIES);

        // ------------------------------------------------------------------ la conformité d'abord
        //
        // Avant toute vitesse : les deux chemins rendent-ils la même chose ? Un chiffre sur un
        // paquet amputé ne vaudrait rien.
        Settings.setSommaire(false);
        TreeSet<String> vanilla = collect(pack);
        Settings.setSommaire(true);
        TreeSet<String> indexed = collect(pack);

        if (vanilla.isEmpty()) {
            Lanterne.LOG.error("[SOMMAIRE] REFUS : le chemin vanilla n'a rendu AUCUNE ressource. "
                    + "Le paquet fabriqué n'est pas lisible — rien n'a été éprouvé.");
            close(pack, archive);
            return;
        }

        boolean same = vanilla.equals(indexed);
        if (same) {
            Lanterne.LOG.info("[SOMMAIRE] CONFORMITÉ : {} ressource(s) listée(s) des deux côtés, "
                    + "ensembles identiques.", vanilla.size());
        } else {
            TreeSet<String> missing = new TreeSet<>(vanilla);
            missing.removeAll(indexed);
            TreeSet<String> extra = new TreeSet<>(indexed);
            extra.removeAll(vanilla);
            Lanterne.LOG.error("[SOMMAIRE] NON CONFORME : {} ressource(s) manquante(s), {} de trop, "
                    + "sur {} attendues. L'INDEX NE REND PAS CE QUE LE PAQUET CONTIENT — ne rien "
                    + "publier, et ne pas livrer ce module.",
                    missing.size(), extra.size(), vanilla.size());
            if (!missing.isEmpty()) {
                Lanterne.LOG.error("[SOMMAIRE] Première manquante : {}", missing.first());
            }
            if (!extra.isEmpty()) {
                Lanterne.LOG.error("[SOMMAIRE] Première de trop : {}", extra.first());
            }
        }
        if (fr.clubcitrouille.lanterne.core.Sommaire.broken()) {
            Lanterne.LOG.info("[SOMMAIRE] (LANTERNE_BREAK_SOMMAIRE=1 était posé : cette épreuve "
                    + "DEVAIT annoncer NON CONFORME.)");
        }

        // ------------------------------------------------------------------ puis la vitesse
        for (int pass = 0; pass < WARMUP; pass++) {
            timeOne(pack, true);
            timeOne(pack, false);
        }

        fr.clubcitrouille.lanterne.core.Sommaire.reset();
        List<Long> withIndex = new ArrayList<>();
        List<Long> without = new ArrayList<>();
        for (int pass = 0; pass < PASSES; pass++) {
            // Alterné, et l'ordre inversé une passe sur deux : une mesure séquentielle
            // avantagerait systématiquement le second bras, que le compilateur trouve déjà chaud.
            if (pass % 2 == 0) {
                withIndex.add(timeOne(pack, true));
                without.add(timeOne(pack, false));
            } else {
                without.add(timeOne(pack, false));
                withIndex.add(timeOne(pack, true));
            }
        }
        Settings.setSommaire(true);

        double indexedMedian = median(withIndex);
        double vanillaMedian = median(without);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[SOMMAIRE] Sans sommaire : %.2f ms pour %d interrogations",
                vanillaMedian / 1e6, QUERIES));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[SOMMAIRE] Avec sommaire : %.2f ms pour %d interrogations",
                indexedMedian / 1e6, QUERIES));
        Lanterne.LOG.info("[SOMMAIRE] Compteurs : {}",
                fr.clubcitrouille.lanterne.core.Sommaire.describe());

        long served = fr.clubcitrouille.lanterne.core.Sommaire.served();
        if (served == 0L) {
            Lanterne.LOG.error("[SOMMAIRE] REFUS DE CONCLURE : zéro demande servie par l'index. "
                    + "Les deux bras ont mesuré le même code.");
        } else if (!same) {
            Lanterne.LOG.error("[SOMMAIRE] REFUS DE CONCLURE : la conformité a échoué. Un gain sur "
                    + "un paquet amputé n'est pas un gain.");
        } else if (indexedMedian <= 0d || vanillaMedian <= 0d) {
            Lanterne.LOG.error("[SOMMAIRE] REFUS DE CONCLURE : médiane nulle d'un côté.");
        } else {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[SOMMAIRE] GAIN : ×%.1f sur la lecture d'un paquet zip.",
                    vanillaMedian / indexedMedian));
            Lanterne.LOG.info("[SOMMAIRE] Rappel de portée : un serveur dédié ne charge AUCUN "
                    + "paquet de ressources, et un datapack rangé en dossier passe par "
                    + "PathPackResources, qui n'a pas ce défaut. Ce gain est celui du client au "
                    + "démarrage, et celui d'un serveur dont les datapacks sont zippés.");
        }

        close(pack, archive);
    }

    /** Une passe : toutes les interrogations, chronométrées. */
    private static long timeOne(PackResources pack, boolean indexed) {
        Settings.setSommaire(indexed);
        long started = System.nanoTime();
        int seen = 0;
        for (int ns = 0; ns < NAMESPACES; ns++) {
            for (int dir = 0; dir < DIRECTORIES; dir++) {
                Counter counter = new Counter();
                pack.listResources(PackType.SERVER_DATA, namespaceOf(ns), directoryOf(dir),
                        counter);
                seen += counter.count;
            }
        }
        long elapsed = System.nanoTime() - started;
        if (seen == 0) {
            Lanterne.LOG.warn("[SOMMAIRE] Une passe n'a rien listé.");
        }
        return elapsed;
    }

    private static TreeSet<String> collect(PackResources pack) {
        TreeSet<String> found = new TreeSet<>();
        for (int ns = 0; ns < NAMESPACES; ns++) {
            for (int dir = 0; dir < DIRECTORIES; dir++) {
                pack.listResources(PackType.SERVER_DATA, namespaceOf(ns), directoryOf(dir),
                        (id, supplier) -> found.add(id.toString()));
            }
        }
        return found;
    }

    private static final class Counter implements PackResources.ResourceOutput {
        private int count;

        @Override
        public void accept(Identifier id, net.minecraft.server.packs.resources.IoSupplier
                <java.io.InputStream> supplier) {
            this.count++;
        }
    }

    /**
     * Fabrique le paquet.
     *
     * <p>Les noms sont choisis pour que la recherche par intervalle soit réellement éprouvée : des
     * répertoires dont les noms se préfixent les uns les autres — {@code recipe} et
     * {@code recipe_book} — sont exactement ce qu'une borne supérieure bâclée confond. Un jeu de
     * noms bien séparés aurait laissé passer le défaut que
     * {@code LANTERNE_BREAK_SOMMAIRE=1} introduit.
     */
    private static Path build() throws IOException {
        Path archive = Files.createTempFile("lanterne-sommaire", ".zip");
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("pack.mcmeta"));
            out.write(payload);
            out.closeEntry();
            int perSlot = ENTRIES / (NAMESPACES * DIRECTORIES);
            for (int ns = 0; ns < NAMESPACES; ns++) {
                for (int dir = 0; dir < DIRECTORIES; dir++) {
                    for (int i = 0; i < perSlot; i++) {
                        out.putNextEntry(new ZipEntry(String.format(Locale.ROOT,
                                "data/%s/%s/piece_%05d.json", namespaceOf(ns), directoryOf(dir),
                                i)));
                        out.write(payload);
                        out.closeEntry();
                    }
                }
            }
        }
        return archive;
    }

    private static String namespaceOf(int index) {
        return String.format(Locale.ROOT, "mod%02d", index);
    }

    /** Huit répertoires, dont plusieurs se préfixent l'un l'autre. C'est délibéré. */
    private static String directoryOf(int index) {
        String[] names = {"recipe", "recipe_book", "loot_table", "loot_table_extra", "tags",
            "tags_extra", "advancement", "advancement_root"};
        return names[index % names.length];
    }

    private static void close(PackResources pack, Path archive) {
        pack.close();
        try {
            Files.deleteIfExists(archive);
        } catch (IOException ignored) {
            Lanterne.LOG.debug("[SOMMAIRE] Le paquet d'essai n'a pas pu être effacé : {}", archive);
        }
    }

    private static double median(List<Long> values) {
        if (values.isEmpty()) {
            return 0d;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2d
                : sorted.get(middle);
    }
}
