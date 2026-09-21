package fr.clubcitrouille.lanterne.perf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Interrupteurs des modules de performance intégrés (famille {@code lanterne.perf}).
 *
 * <p>Lu très tôt (le contrôleur de mixins l'interroge avant le chargement de NeoForge) : donc ni
 * {@code ModConfigSpec} ni API du jeu, seulement une propriété JVM ou un petit fichier.
 * Ordre : {@code -Dlanterne.perf.<id>=true|false}, puis {@code config/lanterne-perf.properties}
 * (clé {@code <id>=true|false}), puis le défaut : désactivé.
 *
 * <p>Tous les modules sont <b>désactivés par défaut</b> tant que leur gain et leur iso-qualité ne sont
 * pas mesurés sur le banc central (voir PERF-MODS-INTEGRES.md). Un module désactivé n'applique AUCUN
 * mixin : coût nul.
 */
public final class PerfConfig {
    private static Properties file;

    private PerfConfig() {}

    public static boolean enabled(String id) {
        String prop = System.getProperty("lanterne.perf." + id);
        if (prop != null) {
            return Boolean.parseBoolean(prop.trim());
        }
        String fromFile = load().getProperty(id);
        return fromFile != null && Boolean.parseBoolean(fromFile.trim());
    }

    private static synchronized Properties load() {
        if (file == null) {
            file = new Properties();
            Path path = Path.of("config", "lanterne-perf.properties");
            if (Files.isRegularFile(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    file.load(in);
                } catch (IOException unreadable) {
                    // Fichier illisible : on garde les défauts (tout désactivé).
                }
            }
        }
        return file;
    }
}
