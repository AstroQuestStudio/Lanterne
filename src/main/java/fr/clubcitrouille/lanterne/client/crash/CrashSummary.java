package fr.clubcitrouille.lanterne.client.crash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Résume et anonymise un fichier de plantage — {@code crash-*.txt} (Minecraft/NeoForge) ou
 * {@code hs_err_pid*.log} (la machine virtuelle elle-même) — en un texte tenant sous
 * {@link fr.clubcitrouille.lanterne.core.network.BoiteNoireTranche#MAX_TOTAL_BYTES}.
 *
 * <h2>Pourquoi un extrait, et pas le fichier entier</h2>
 *
 * <p>Un crash-report complet fait souvent plusieurs centaines de kilo-octets : la section « System
 * Details » liste chaque mod chargé, chaque option graphique, parfois le détail complet du tas Java.
 * Aucune de ces lignes n'aide à diagnostiquer un « Connection reset by peer » — ce qui aide, c'est LA
 * cause (l'exception et son message), OÙ elle a eu lieu (les premières lignes de pile), et QUELS mods
 * NeoForge soupçonne lui-même (« Suspected Mods »). Le reste est coupé, pas parce qu'il est secret,
 * mais parce qu'il noierait l'essentiel dans un journal d'admin qui doit rester lisible.
 *
 * <h2>Anonymisation</h2>
 *
 * <p>Un chemin Windows contient le nom de session du joueur — {@code C:\Users\Régis\...} — que ce
 * module n'a aucune raison de transmettre : il ne sert à rien pour comprendre un plantage. Remplacé
 * par {@code ~}, comme un chemin Unix abrégé.
 */
public final class CrashSummary {
    /** Voir la Javadoc de classe : ce qui reste après troncature tient largement sous ce plafond. */
    private static final int BUDGET_BYTES = 48 * 1024;

    private static final Pattern CHEMIN_WINDOWS =
            Pattern.compile("[A-Za-z]:\\\\Users\\\\[^\\\\/:*?\"<>|\\r\\n]+");
    private static final Pattern CHEMIN_WINDOWS_SLASH =
            Pattern.compile("[A-Za-z]:/Users/[^/\\r\\n]+");
    private static final Pattern CHEMIN_UNIX = Pattern.compile("/home/[^/\\s]+");

    private CrashSummary() {}

    /** L'extrait tronqué et anonymisé d'un {@code crash-*.txt} de Minecraft/NeoForge. */
    public static String summarizeCrashReport(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();

        // L'en-tête : "---- Minecraft Crash Report ----", l'horodatage, "Description: ...".
        appendUntilBlank(lines, 0, 10, out);

        // La cause : la ligne "Description:" et l'exception qui suit, jusqu'à "-- Head --" ou "A
        // detailed walkthrough".
        int description = indexOf(lines, "Description:");
        if (description >= 0) {
            appendSection(lines, description, 6, out);
        }

        // Ce que NeoForge soupçonne lui-même — la meilleure piste possible, quand elle existe.
        int suspects = indexOf(lines, "Suspected Mods:");
        if (suspects >= 0) {
            appendSection(lines, suspects, 12, out);
        }

        // Les premières lignes de la pile d'appel : "-- Head --" puis "Stacktrace:".
        int head = indexOf(lines, "-- Head --");
        if (head >= 0) {
            appendSection(lines, head, 24, out);
        } else {
            int stack = indexOf(lines, "Stacktrace:");
            if (stack >= 0) {
                appendSection(lines, stack, 24, out);
            }
        }

        return anonymize(cap(out.toString()));
    }

    /** L'extrait tronqué et anonymisé d'un {@code hs_err_pid*.log} de la JVM elle-même. */
    public static String summarizeHsErr(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();

        // L'en-tête : "A fatal error has been detected...", le motif, la JVM, le cadre fautif.
        appendUntilBlank(lines, 0, 20, out);

        // Les lignes qui disent QUI plantait et DANS QUEL contexte — c'est ce qui manquait aux
        // rapports du 25/09 (trois plantages G1 identiques, fil fautif inconnu). Les lignes de hs_err
        // commencent souvent par "# " : on cherche par contenu, pas par début de ligne.
        for (String key : new String[] {"Command Line:", "Time:", "Host:", "VM_Operation",
                "VM Operation", "Current CompileTask"}) {
            int at = indexOfContaining(lines, key);
            if (at >= 0) {
                appendSection(lines, at, key.startsWith("Current") ? 3 : 1, out);
            }
        }
        int thread = indexOfContaining(lines, "Current thread (");
        if (thread >= 0) {
            // La ligne du fil (nom, état) puis sa pile native et Java, jusqu'à 60 lignes.
            appendUntilBlankLine(lines, thread, 60, out);
            int java = indexOfContaining(lines, "Java frames:");
            if (java > thread) {
                appendUntilBlankLine(lines, java, 40, out);
            }
        }
        int heap = indexOfContaining(lines, "Heap:");
        if (heap >= 0) {
            appendSection(lines, heap, 8, out);
        }

        return anonymize(cap(out.toString()));
    }

    private static int indexOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    /** Recopie à partir de {@code from} jusqu'à la première ligne vide, au plus {@code max} lignes. */
    private static void appendUntilBlankLine(List<String> lines, int from, int max, StringBuilder out) {
        int end = Math.min(lines.size(), from + max);
        for (int i = from; i < end; i++) {
            if (i > from && lines.get(i).isBlank()) {
                break;
            }
            out.append(lines.get(i)).append('\n');
        }
        out.append('\n');
    }

    private static void appendUntilBlank(List<String> lines, int from, int max, StringBuilder out) {
        int end = Math.min(lines.size(), from + max);
        for (int i = from; i < end; i++) {
            out.append(lines.get(i)).append('\n');
        }
        out.append('\n');
    }

    private static void appendSection(List<String> lines, int from, int max, StringBuilder out) {
        int end = Math.min(lines.size(), from + max);
        for (int i = from; i < end; i++) {
            out.append(lines.get(i)).append('\n');
        }
        out.append('\n');
    }

    private static String cap(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= BUDGET_BYTES) {
            return text;
        }
        // Coupe au budget, puis revient au dernier retour à la ligne complet : jamais un octet UTF-8
        // tronqué au milieu d'un caractère multi-octet.
        int cut = BUDGET_BYTES;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8) + "\n[... tronqué ...]\n";
    }

    /** Remplace les chemins qui portent un nom de compte par {@code ~}. */
    static String anonymize(String text) {
        String result = CHEMIN_WINDOWS.matcher(text).replaceAll("~");
        result = CHEMIN_WINDOWS_SLASH.matcher(result).replaceAll("~");
        result = CHEMIN_UNIX.matcher(result).replaceAll("~");
        return result;
    }
}
