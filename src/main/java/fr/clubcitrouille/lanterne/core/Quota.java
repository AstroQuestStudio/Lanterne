package fr.clubcitrouille.lanterne.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Combien de cœurs cette machine promet vraiment, pas combien elle en affiche.
 *
 * <h2>Le piège de l'hébergement mutualisé</h2>
 *
 * <p>{@code Runtime.getRuntime().availableProcessors()} répond au nom du <b>matériel</b>, pas du
 * <b>quota</b>. Un conteneur facturé pour un seul cœur, posé sur un Xeon qui en compte vingt-six,
 * peut très bien voir passer ce nombre-là si l'hébergeur ne confine pas correctement le conteneur
 * par cgroup — et la JVM elle-même, qui sait pourtant lire les cgroups depuis longtemps, ne peut
 * rien lire s'il n'y a rien à lire. Un module qui ouvrirait alors vingt-cinq fils pour « profiter »
 * de ce nombre ne profiterait de rien : il n'existe qu'un seul cœur de temps CPU réel derrière, et
 * vingt-cinq fils qui se le disputent ne font qu'ajouter de la contention à un travail qui tenait
 * déjà dans un seul.
 *
 * <h2>Trois sources, classées de la plus sûre à la plus trompeuse</h2>
 *
 * <ol>
 *   <li><b>Le réglage explicite</b> — {@code LANTERNE_CPU_LIMIT}, une variable d'environnement
 *       comme {@code LANTERNE_MODULES} ou {@code LANTERNE_BREAK_DIGUE} déjà présentes dans ce
 *       dépôt. Un administrateur qui sait ce que son hébergeur promet vraiment n'a besoin d'aucune
 *       détection : il le dit, et c'est ce qui compte, avant toute lecture de fichier.</li>
 *   <li><b>Le cgroup</b> — {@code cpu.max} (v2) ou {@code cpu.cfs_quota_us}/{@code cpu.cfs_period_us}
 *       (v1), croisé avec {@code cpuset.cpus.effective}/{@code cpuset.cpus} quand il existe : deux
 *       contraintes distinctes, l'une en temps (un quota par période), l'autre en identité (quels
 *       cœurs précis sont autorisés). On retient le plus bas des deux, jamais un seul en ignorant
 *       l'autre.</li>
 *   <li><b>Le repli JVM</b> — {@code availableProcessors()}, seulement quand rien de ce qui précède
 *       n'a rien donné. C'est le cas piégeux nommé par le joueur : un hébergeur qui virtualise sans
 *       exposer de vraie limite au conteneur. Rien ne peut corriger ce cas depuis l'intérieur de la
 *       JVM — seul le réglage explicite du premier point le peut — mais on le dit tout haut au
 *       démarrage plutôt que de laisser un chiffre trompeur passer pour une mesure.</li>
 * </ol>
 *
 * <h2>La règle dans le doute</h2>
 *
 * <p>À chaque étage, un signal absent ou illisible ne vaut jamais « illisible donc illimité » — il
 * vaut « passer à l'étage suivant, plus prudent ». Et le résultat final est toujours arrondi vers le
 * bas : un quota d'un cœur et demi rend un, jamais deux. La même règle que la marge de {@link Foule}
 * ou l'arrondi de {@link Digue} — se tromper en dessous coûte un fil de moins que ce qu'on aurait pu
 * se permettre ; se tromper au-dessus coûte de la contention sur une machine qui n'a déjà presque
 * rien.
 *
 * <h2>Ce que ce module ne fait pas</h2>
 *
 * <p>Pas de calibrage empirique au démarrage — lancer des tâches occupées en parallèle pour sentir
 * si elles avancent vraiment de concert aurait pu affiner le repli JVM, mais rien de simple et rapide
 * ne distingue de façon fiable une contention de cgroup (temps CPU restreint) d'une contention de
 * machine partagée mais non confinée (d'autres locataires actifs au même instant) ; un mauvais
 * calibrage serait pire qu'aucun calibrage, parce qu'il se ferait passer pour une mesure. Cette piste
 * reste ouverte, pas fermée.
 */
public final class Quota {
    /** Cœurs de repli si absolument aucune source n'a rien donné — ne devrait jamais arriver. */
    private static final int FLOOR = 1;

    /** Plafond de bon sens, pour ne jamais dimensionner un bassin sur une lecture aberrante. */
    private static final int CEILING = 256;

    private static final String ENV_OVERRIDE = "LANTERNE_CPU_LIMIT";

    private static final Path CGROUP2_CPU_MAX = Path.of("/sys/fs/cgroup/cpu.max");
    private static final Path CGROUP2_CPUSET = Path.of("/sys/fs/cgroup/cpuset.cpus.effective");
    private static final Path CGROUP1_QUOTA = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_quota_us");
    private static final Path CGROUP1_PERIOD = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_period_us");
    private static final Path CGROUP1_CPUSET = Path.of("/sys/fs/cgroup/cpuset/cpuset.cpus");

    private static final int CORES;
    private static final String SOURCE;

    static {
        Detected detected = detect();
        CORES = detected.cores;
        SOURCE = detected.source;
        Lanterne.LOG.info("[QUOTA] {} cœur(s) réellement disponible(s) retenu(s), source : {}.",
                CORES, SOURCE);
        if (detected.uncertain) {
            Lanterne.LOG.warn("[QUOTA] Aucune limite de cgroup lisible : ce chiffre vient du "
                    + "materiel entier ({} tel que rendu par la JVM), pas d'un quota confirme. Sur "
                    + "un hebergement mutualise qui ne confine pas correctement le conteneur, ce "
                    + "nombre peut etre trompeur — voir la variable {} pour le corriger a la main.",
                    Runtime.getRuntime().availableProcessors(), ENV_OVERRIDE);
        }
    }

    private Quota() {}

    /**
     * Cœurs réellement utilisables, au jugé le plus prudent possible — jamais sous {@code 1}.
     *
     * <p>Calculé une seule fois, au chargement de cette classe : le quota d'un conteneur ne change
     * pas en cours de partie, et relire ces fichiers à chaque appel ne rendrait jamais une réponse
     * différente.
     */
    public static int cores() {
        return CORES;
    }

    /** D'où vient {@link #cores()} — pour le journal et le diagnostic, jamais pour décider. */
    public static String source() {
        return SOURCE;
    }

    private record Detected(int cores, String source, boolean uncertain) {}

    private static Detected detect() {
        Integer override = fromEnvironment();
        if (override != null) {
            return new Detected(clamp(override), "réglage explicite (" + ENV_OVERRIDE + ")", false);
        }

        Integer v2 = fromCgroupV2();
        if (v2 != null) {
            return new Detected(clamp(v2), "cgroup v2", false);
        }

        Integer v1 = fromCgroupV1();
        if (v1 != null) {
            return new Detected(clamp(v1), "cgroup v1", false);
        }

        // Dernier repli : ce que la JVM affiche, potentiellement le materiel entier plutot que le
        // quota promis. On le dit sans detour dans le journal plutot que de le faire passer pour une
        // mesure — voir le Javadoc de classe.
        return new Detected(clamp(Runtime.getRuntime().availableProcessors()), "JVM (repli)", true);
    }

    private static Integer fromEnvironment() {
        String raw = System.getenv(ENV_OVERRIDE);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException malformed) {
            Lanterne.LOG.warn("[QUOTA] {} vaut « {} », illisible comme un entier positif — ignoré.",
                    ENV_OVERRIDE, raw);
            return null;
        }
    }

    /**
     * cgroup v2 : {@code cpu.max} donne un quota en temps, {@code cpuset.cpus.effective} donne une
     * identité de cœurs. Deux contraintes différentes ; on retient la plus basse des deux qui a
     * répondu, {@code null} si aucune des deux ne donne de limite exploitable.
     */
    private static Integer fromCgroupV2() {
        Integer timeQuota = readCpuMax(CGROUP2_CPU_MAX);
        Integer coreIdentity = readCpuset(CGROUP2_CPUSET);
        return lowerOf(timeQuota, coreIdentity);
    }

    /** cgroup v1 : même principe, deux fichiers séparés pour le quota au lieu d'un seul. */
    private static Integer fromCgroupV1() {
        Integer timeQuota = readCfsQuota(CGROUP1_QUOTA, CGROUP1_PERIOD);
        Integer coreIdentity = readCpuset(CGROUP1_CPUSET);
        return lowerOf(timeQuota, coreIdentity);
    }

    /** {@code cpu.max} (v2) : « <quota> <période> », ou « max <période> » pour dire « pas de quota ». */
    private static Integer readCpuMax(Path path) {
        String line = readFirstLine(path);
        if (line == null) {
            return null;
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2 || "max".equals(parts[0])) {
            return null; // pas de limite exprimée ici — on ne la suppose jamais infinie pour autant
        }
        return divideDown(parts[0], parts[1]);
    }

    /** cgroup v1 : le quota et la période vivent dans deux fichiers séparés. -1 veut dire illimité. */
    private static Integer readCfsQuota(Path quotaPath, Path periodPath) {
        String quota = readFirstLine(quotaPath);
        String period = readFirstLine(periodPath);
        if (quota == null || period == null) {
            return null;
        }
        quota = quota.trim();
        if ("-1".equals(quota)) {
            return null;
        }
        return divideDown(quota, period.trim());
    }

    private static Integer divideDown(String quotaText, String periodText) {
        try {
            long quota = Long.parseLong(quotaText);
            long period = Long.parseLong(periodText);
            if (quota <= 0L || period <= 0L) {
                return null;
            }
            // Arrondi vers le bas, delibere : un quota d'un coeur et demi ne rend jamais deux fils.
            return (int) Math.min(CEILING, quota / period);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /**
     * Une liste de cœurs façon {@code cpuset} — « 0-3,8,10-11 » — comptée en nombre d'identifiants,
     * pas interprétée autrement. Un fichier vide ou absent ne donne aucune contrainte de ce type.
     */
    private static Integer readCpuset(Path path) {
        String line = readFirstLine(path);
        if (line == null || line.isBlank()) {
            return null;
        }
        int count = 0;
        for (String piece : line.trim().split(",")) {
            if (piece.isBlank()) {
                continue;
            }
            int dash = piece.indexOf('-');
            try {
                if (dash < 0) {
                    Integer.parseInt(piece.trim());
                    count += 1;
                } else {
                    int from = Integer.parseInt(piece.substring(0, dash).trim());
                    int to = Integer.parseInt(piece.substring(dash + 1).trim());
                    if (to >= from) {
                        count += to - from + 1;
                    }
                }
            } catch (NumberFormatException malformed) {
                return null; // format inattendu : on ne devine pas, on renonce a cette source
            }
        }
        return count > 0 ? count : null;
    }

    private static Integer lowerOf(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    private static String readFirstLine(Path path) {
        try {
            if (!Files.isReadable(path)) {
                return null;
            }
            List<String> lines = Files.readAllLines(path);
            return lines.isEmpty() ? null : lines.get(0);
        } catch (Exception unreadable) {
            // N'importe quel echec de lecture — permission refusee, chemin qui n'existe pas sur ce
            // systeme d'exploitation, fichier qui disparait entre les deux appels — vaut « rien a en
            // tirer », jamais une exception qui empecherait le mod de demarrer.
            return null;
        }
    }

    private static int clamp(int value) {
        return Math.max(FLOOR, Math.min(CEILING, value));
    }
}
