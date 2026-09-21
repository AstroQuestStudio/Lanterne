package fr.clubcitrouille.lanterne.client.bilan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import net.neoforged.fml.loading.FMLPaths;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Une ligne du bilan : {@code [BILAN] type=perf cle=valeur cle="valeur avec espaces" ...}.
 *
 * <p>Format stable, documenté dans {@code BILAN-FORMAT.md}. Une ligne = un enregistrement, sans
 * retour à la ligne ; toute valeur qui contient un espace, un signe égal ou un guillemet est mise
 * entre guillemets (les guillemets internes sont échappés). Chaque ligne est écrite dans le journal
 * du jeu ET dans {@code logs/lanterne-bilan.log} (un fichier qui ne contient que le bilan, plus
 * simple à lire pour un script).
 */
final class BilanLine {
    /** Préfixe fixe : {@code grep "\[BILAN\]"} suffit à tout retrouver. */
    static final String PREFIX = "[BILAN]";

    private final StringBuilder text = new StringBuilder(256);

    private BilanLine(String type) {
        text.append(PREFIX).append(" type=").append(type);
    }

    static BilanLine of(String type) {
        return new BilanLine(type);
    }

    BilanLine kv(String key, String value) {
        text.append(' ').append(key).append('=');
        String v = value == null ? "n/a" : value;
        if (v.isEmpty() || needsQuotes(v)) {
            text.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else {
            text.append(v);
        }
        return this;
    }

    BilanLine kv(String key, long value) {
        text.append(' ').append(key).append('=').append(value);
        return this;
    }

    BilanLine kv(String key, double value, int decimals) {
        text.append(' ').append(key).append('=')
                .append(String.format(Locale.ROOT, "%." + decimals + "f", value));
        return this;
    }

    BilanLine kv(String key, boolean value) {
        text.append(' ').append(key).append('=').append(value);
        return this;
    }

    private static boolean needsQuotes(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || c == '=' || c == '"' || c == '\n' || c == '\t') {
                return true;
            }
        }
        return false;
    }

    /** Écrit la ligne dans le journal du jeu et dans le fichier dédié. */
    void emit() {
        String line = text.toString();
        Lanterne.LOG.info(line);
        appendToFile(line);
    }

    private static Path file;
    private static boolean fileFailed;

    private static synchronized void appendToFile(String line) {
        if (fileFailed) {
            return;
        }
        try {
            if (file == null) {
                Path logs = FMLPaths.GAMEDIR.get().resolve("logs");
                Files.createDirectories(logs);
                file = logs.resolve("lanterne-bilan.log");
            }
            Files.writeString(file,
                    java.time.LocalDateTime.now().toString() + ' ' + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException unwritable) {
            fileFailed = true; // le journal du jeu reste la source ; jamais d'exception vers le rendu
        }
    }
}
