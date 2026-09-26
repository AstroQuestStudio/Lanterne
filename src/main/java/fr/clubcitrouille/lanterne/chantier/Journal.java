package fr.clubcitrouille.lanterne.chantier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le journal du chantier, sur disque, dans {@code <monde>/lanterne-chantier/} :
 *
 * <ul>
 *   <li>{@code operations.jsonl} — une ligne par requête qui a écrit ou tenté d'écrire : qui, quoi,
 *       où, combien. Ajout seul : c'est la trace, on n'y réécrit jamais.</li>
 *   <li>{@code annulation/<id>.nbt} — l'état AVANT de chaque bloc touché par l'opération {@code id}
 *       ({@link Releve}) : ce que {@code annuler} restaure.</li>
 *   <li>{@code cliches/<nom>.nbt} — les clichés pris à la demande.</li>
 * </ul>
 *
 * <p>Tout ce fichier s'exécute hors du thread serveur : écrire un relevé d'un million de blocs prend
 * une centaine de millisecondes, qu'un tick ne doit pas payer.
 */
final class Journal {
    private static final DateTimeFormatter HORODATE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Pattern NOM_SUR = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private Journal() {}

    /** Identifiant triable, unique pour la vie du serveur et au-delà (horodatage + compteur). */
    static String nouvelId() {
        return LocalDateTime.now().format(HORODATE) + "-" + String.format("%03d", SEQUENCE.incrementAndGet() % 1000);
    }

    static String nomSur(String nom) {
        if (!NOM_SUR.matcher(nom).matches()) {
            throw new Refus(400, "nom invalide « " + nom + " » (lettres, chiffres, _ . -, 64 au plus)");
        }
        return nom;
    }

    private static Path dossier(String sous) throws IOException {
        Path d = Chantier.racine().resolve(sous);
        Files.createDirectories(d);
        return d;
    }

    static void ecrireAnnulation(String id, Releve r, CompoundTag meta) throws IOException {
        NbtIo.writeCompressed(r.versNbt(meta), dossier("annulation").resolve(nomSur(id) + ".nbt"));
    }

    static Releve lireAnnulation(String id) {
        return lire("annulation", id, "aucun journal d'annulation pour « " + id + " »");
    }

    static void ecrireCliche(String nom, Releve r, CompoundTag meta) throws IOException {
        NbtIo.writeCompressed(r.versNbt(meta), dossier("cliches").resolve(nomSur(nom) + ".nbt"));
    }

    static Releve lireCliche(String nom) {
        return lire("cliches", nom, "aucun cliché « " + nom + " »");
    }

    static CompoundTag metaCliche(String nom) {
        try {
            Path f = dossier("cliches").resolve(nomSur(nom) + ".nbt");
            if (!Files.exists(f)) {
                throw new Refus(404, "aucun cliché « " + nom + " »");
            }
            return NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("meta");
        } catch (IOException e) {
            throw new Refus(500, "cliché illisible : " + e.getMessage());
        }
    }

    private static Releve lire(String sous, String nom, String absent) {
        try {
            Path f = dossier(sous).resolve(nomSur(nom) + ".nbt");
            if (!Files.exists(f)) {
                throw new Refus(404, absent);
            }
            return Releve.depuisNbt(NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap()));
        } catch (IOException e) {
            throw new Refus(500, "fichier illisible : " + e.getMessage());
        }
    }

    /** Ajoute une ligne au journal des opérations. Ne lève jamais : la trace ne doit pas faire échouer l'ouvrage. */
    static synchronized void tracer(JsonObject ligne) {
        try {
            ligne.addProperty("t", LocalDateTime.now().toString());
            Files.writeString(dossier("").resolve("operations.jsonl"), ligne + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            Lanterne.LOG.warn("[CHANTIER] journal des opérations non écrit : {}", e.toString());
        }
    }

    /** Les {@code n} dernières opérations, annulations repliées sur l'opération qu'elles annulent. */
    static synchronized List<JsonObject> dernieres(int n) {
        Map<String, JsonObject> ops = new LinkedHashMap<>();
        try {
            Path f = dossier("").resolve("operations.jsonl");
            if (!Files.exists(f)) {
                return List.of();
            }
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (l.isBlank()) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(l).getAsJsonObject();
                String id = o.has("id") ? o.get("id").getAsString() : null;
                if (id != null) {
                    ops.put(id, o);
                }
                if (o.has("annule") && ops.containsKey(o.get("annule").getAsString())) {
                    ops.get(o.get("annule").getAsString()).addProperty("annulee_par", id);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new Refus(500, "journal illisible : " + e.getMessage());
        }
        List<JsonObject> tout = new ArrayList<>(ops.values());
        return tout.subList(Math.max(0, tout.size() - n), tout.size());
    }

    static List<String> cliches() {
        try (var s = Files.list(dossier("cliches"))) {
            return s.map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(".nbt"))
                    .map(f -> f.substring(0, f.length() - 4))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
