package fr.clubcitrouille.lanterne.client;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.neoforged.fml.loading.FMLPaths;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La parade G1 : fait passer l'instance CurseForge du joueur sur le ramasse-miettes ZGC.
 *
 * <h2>Le plantage qu'elle évite</h2>
 *
 * <p>Le 25/09/2026, la Boîte noire a remonté trois plantages de trois joueurs différents, tous
 * identiques : {@code Internal Error (g1HeapRegionManager.cpp:55) guarantee(...) failed: master
 * free list MT safety protocol at a safepoint}, pile entièrement dans {@code jvm.dll}, sous le Java
 * 25.0.1 que le lanceur Minecraft fournit. C'est un bug du ramasse-miettes G1 de la JVM elle-même,
 * signalé ailleurs (microsoft/openjdk#677, PaperMC#14123, encore présent en 25.0.3) et sans
 * correctif publié : aucun code de mod ne peut l'empêcher tant que G1 est actif. ZGC (générationnel
 * par défaut en Java 25) n'a pas ce chemin de code et convient très bien à Minecraft.
 *
 * <h2>Ce qu'elle fait, et seulement ça</h2>
 *
 * <p>Si le jeu tourne sur G1 en Java 25+, et que le dossier de jeu contient le
 * {@code minecraftinstance.json} de CurseForge, on ajoute {@code -XX:+UseZGC} à son champ
 * {@code javaArgsOverride} (les arguments Java propres à l'instance), en conservant ce que le joueur
 * y avait mis et en retirant un {@code -XX:+UseG1GC} explicite qui l'annulerait. Effet au prochain
 * lancement. Écriture atomique (fichier temporaire puis remplacement). Tout autre lanceur, ou un
 * fichier illisible : on ne touche à rien et on le dit une fois dans le journal.
 */
public final class ParadeG1 {
    private static final String CHAMP = "javaArgsOverride";
    private static final String ZGC = "-XX:+UseZGC";

    private ParadeG1() {}

    public static void install() {
        try {
            if (Runtime.version().feature() < 25 || !surG1()) {
                return;
            }
            Path instance = FMLPaths.GAMEDIR.get().resolve("minecraftinstance.json");
            if (!Files.isRegularFile(instance)) {
                Lanterne.LOG.info("[PARADE-G1] G1 actif sous Java {} mais pas d'instance CurseForge ici :"
                        + " ajoute {} aux arguments Java de ton lanceur pour éviter le plantage G1 connu.",
                        Runtime.version(), ZGC);
                return;
            }
            JsonObject racine = JsonParser.parseString(
                    Files.readString(instance, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement actuel = racine.get(CHAMP);
            String args = actuel == null || actuel.isJsonNull() ? "" : actuel.getAsString().trim();
            if (args.contains("UseZGC")) {
                Lanterne.LOG.warn("[PARADE-G1] {} est déjà dans l'instance mais la JVM tourne encore sur G1 :"
                        + " relance le jeu depuis CurseForge (ou vérifie les arguments Java).", ZGC);
                return;
            }
            if (args.contains("UseShenandoahGC") || args.contains("UseParallelGC") || args.contains("UseSerialGC")) {
                return; // un choix de ramasse-miettes explicite du joueur : on le respecte
            }
            args = (args.replace("-XX:+UseG1GC", "").trim() + " " + ZGC).trim();
            racine.addProperty(CHAMP, args);

            Path temp = instance.resolveSibling("minecraftinstance.json.lanterne-tmp");
            Files.writeString(temp, new GsonBuilder().disableHtmlEscaping().create().toJson(racine),
                    StandardCharsets.UTF_8);
            Files.move(temp, instance, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Lanterne.LOG.info("[PARADE-G1] Instance CurseForge passée sur ZGC ({} = \"{}\") : effectif au"
                    + " prochain lancement du jeu.", CHAMP, args);
        } catch (Exception | LinkageError probleme) {
            Lanterne.LOG.warn("[PARADE-G1] impossible de régler l'instance : {}", probleme.toString());
        }
    }

    private static boolean surG1() {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getName().startsWith("G1")) {
                return true;
            }
        }
        return false;
    }
}
