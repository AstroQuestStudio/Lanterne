package fr.clubcitrouille.lanterne.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.world.Difficulty;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.mixin.DedicatedServerAccessor;
import fr.clubcitrouille.lanterne.mixin.DedicatedServerSettingsAccessor;

/**
 * La difficulté choisie en jeu survit au redémarrage d'un serveur dédié.
 *
 * <h2>Le défaut vanilla</h2>
 *
 * <p>Au démarrage, {@code DedicatedServer.forceDifficulty} réapplique la ligne {@code difficulty=} de
 * {@code server.properties} au monde. Or {@code /difficulty} ne passe que par
 * {@code MinecraftServer.setDifficulty(Difficulty, boolean)}, qui change le monde et jamais le
 * fichier : un administrateur qui passe en difficile un soir retrouve le lendemain la difficulté du
 * fichier, sans un mot.
 *
 * <h2>Ce qu'on fait</h2>
 *
 * <p>Quand la difficulté du monde change et diffère de celle des réglages, on :
 *
 * <ol>
 * <li>met à jour la copie <b>en mémoire</b> des réglages (sans l'écrire) — sinon le prochain
 * enregistrement complet par vanilla (liste blanche activée, par exemple) réécrirait l'ancienne
 * valeur ;</li>
 * <li>réécrit la seule ligne {@code difficulty=} du fichier : lecture ligne à ligne, remplacement de
 * cette ligne, écriture dans un fichier temporaire du même dossier puis déplacement atomique. Le reste
 * — commentaires, ordre, fins de ligne — est recopié octet pour octet (lecture et écriture en
 * ISO-8859-1, qui fait l'aller-retour de n'importe quels octets).</li>
 * </ol>
 *
 * <h2>Pourquoi pas {@code DifficultyChangeEvent}</h2>
 *
 * <p>C'était le crochet demandé, et il existe bien dans NeoForge 26.3.0.7-beta — mais rien ne le
 * déclenche côté serveur : {@code CommonHooks.onDifficultyChange}, seul émetteur, n'est appelé par
 * aucune classe du serveur patché ni de NeoForge (vérifié par recherche dans tous les jars de
 * {@code libraries/net/neoforged}). D'où {@code DifficultyMixin}, en fin de
 * {@code MinecraftServer.setDifficulty}.
 *
 * <p>Au démarrage, {@code forceDifficulty} passe par la même méthode avec la valeur du fichier :
 * monde et réglages sont alors égaux, et l'on ne touche à rien.
 */
public final class DifficultePersistante {
    private DifficultePersistante() {}

    private static final Pattern LIGNE = Pattern.compile("(?m)^(difficulty\\s*[=:]\\s*)([^\\r\\n]*)$");

    public static void noter(MinecraftServer server, Difficulty monde) {
        if (!Settings.difficultePersistante() || !server.isDedicatedServer()
                || !(server instanceof DedicatedServer dedie)) {
            return;
        }
        DedicatedServerSettings reglages = ((DedicatedServerAccessor) dedie).lanterne$settings();
        DedicatedServerProperties proprietes = reglages.getProperties();
        Difficulty fichier = proprietes.difficulty.get();
        if (fichier == monde) {
            return;
        }
        DedicatedServerSettingsAccessor acces = (DedicatedServerSettingsAccessor) reglages;
        acces.lanterne$setProperties(proprietes.difficulty.update(server.registryAccess(), monde));
        Path source = acces.lanterne$source();
        try {
            if (reecrire(source, monde.getSerializedName())) {
                Lanterne.LOG.info("[DIFFICULTÉ] {} -> {} : ligne difficulty= de {} réécrite, la difficulté"
                        + " survivra au redémarrage.", fichier.getSerializedName(),
                        monde.getSerializedName(), source.toAbsolutePath());
            } else {
                Lanterne.LOG.warn("[DIFFICULTÉ] {} -> {} : aucune ligne difficulty= dans {} — fichier"
                        + " laissé tel quel ; la difficulté du fichier reviendra au redémarrage.",
                        fichier.getSerializedName(), monde.getSerializedName(), source.toAbsolutePath());
            }
        } catch (IOException | RuntimeException e) {
            Lanterne.LOG.warn("[DIFFICULTÉ] impossible de réécrire {} : {}", source.toAbsolutePath(),
                    e.toString());
        }
    }

    /** Remplace la valeur de la ligne {@code difficulty=}. Faux si la ligne n'existe pas. */
    static boolean reecrire(Path fichier, String valeur) throws IOException {
        if (!Files.isRegularFile(fichier)) {
            return false;
        }
        String texte = Files.readString(fichier, StandardCharsets.ISO_8859_1);
        Matcher m = LIGNE.matcher(texte);
        if (!m.find()) {
            return false;
        }
        if (m.group(2).trim().equals(valeur)) {
            return true;
        }
        String nouveau = texte.substring(0, m.start(2)) + valeur + texte.substring(m.end(2));
        Path dossier = fichier.toAbsolutePath().getParent();
        Path temporaire = Files.createTempFile(dossier, "server.properties.", ".tmp");
        try {
            Files.writeString(temporaire, nouveau, StandardCharsets.ISO_8859_1);
            try {
                Files.move(temporaire, fichier, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaire, fichier, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaire);
        }
        return true;
    }
}
