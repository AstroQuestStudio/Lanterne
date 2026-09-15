package fr.clubcitrouille.lanterne.content.disc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le détour : {@code ffmpeg}, s'il se trouve être là.
 *
 * <h2>Une porte de sortie, et surtout pas une dépendance</h2>
 *
 * <p>Ce fichier est ce qui reste de l'ancienne presse, et il faut dire pourquoi il reste. Pendant
 * longtemps, tout le chemin audio passait par {@code ffmpeg} : c'était la seule façon d'obtenir du
 * Vorbis, et c'était une erreur de conception — {@code ffmpeg} n'est installé que chez les gens qui
 * l'ont installé exprès, c'est-à-dire presque personne, et la fonction ne marchait donc presque
 * jamais. Voir {@link Press} pour ce qui l'a remplacé.
 *
 * <p>Il reste parce que l'argument « il n'est pas là » ne dit rien du cas où il <b>est</b> là. Un
 * joueur qui a {@code ffmpeg} dans son chemin système peut graver un {@code .m4a}, un {@code .opus}
 * ou un {@code .wma} sans que cela ne coûte une ligne de plus à personne. La règle qui compte est
 * ailleurs, et elle est absolue : <b>aucun format courant ne passe par ici</b>. OGG, MP3 et WAV sont
 * lus par le mod lui-même, sur toutes les machines, installées ou non.
 *
 * <h2>Pourquoi passer par des fichiers temporaires</h2>
 *
 * <p>{@code ffmpeg} est un programme séparé : il lit un fichier et en écrit un autre. Lui parler par
 * tubes serait possible et fragile — certains formats exigent de pouvoir revenir en arrière dans le
 * flux d'entrée, ce qu'un tube ne permet pas, et {@code ffmpeg} échoue alors avec un message obscur.
 * Deux fichiers temporaires coûtent quelques millisecondes et marchent avec tout.
 *
 * <p>Ils sont effacés dans tous les cas, y compris en cas d'échec. Un dossier temporaire qui enfle à
 * chaque tentative ratée est le genre de fuite qu'on ne remarque qu'au bout de six mois.
 */
public final class Detour {
    /** Mémorisé une fois : chercher {@code ffmpeg} coûte la création d'un processus. */
    private static Boolean present;

    private Detour() {}

    /** {@code ffmpeg} est-il joignable ? Testé une seule fois par session. */
    public static boolean available() {
        if (present == null) {
            present = run(List.of("ffmpeg", "-version"), 10);
        }
        return present;
    }

    /**
     * Convertit des octets quelconques en OGG/Vorbis.
     *
     * <p>{@code -q:a 5} vise environ 160 kbit/s en débit variable : au-dessus, la différence ne
     * s'entend plus à travers le moteur sonore de Minecraft, qui rééchantillonne et applique son
     * atténuation ; en dessous, les cymbales sifflent. {@code -vn} jette la pochette d'album qu'un
     * fichier transporte souvent, et qui n'aurait aucun sens dans un flux audio.
     *
     * @throws IOException si la conversion échoue. Le message est destiné à un humain.
     */
    public static byte[] toVorbis(byte[] raw) throws IOException {
        Path work = Files.createTempFile("lanterne-", ".src");
        Path target = null;
        try {
            Files.write(work, raw);
            target = Files.createTempFile("lanterne-", ".ogg");
            // Le fichier temporaire existe déjà et ffmpeg refuserait d'écrire par-dessus sans le
            // « -y » qu'il porte déjà. On le supprime quand même : certaines versions s'arrêtent sur
            // un fichier de taille nulle qu'elles prennent pour un flux cassé.
            Files.deleteIfExists(target);
            boolean ok = run(List.of("ffmpeg", "-y", "-loglevel", "error",
                    "-i", work.toAbsolutePath().toString(),
                    "-vn", "-c:a", "libvorbis", "-q:a", "5",
                    target.toAbsolutePath().toString()), 600);
            if (!ok || !Files.isRegularFile(target)) {
                Lanterne.LOG.warn("[ATELIER] conversion par ffmpeg échouée.");
                throw new IOException("ffmpeg n'a pas su convertir ce fichier");
            }
            return Files.readAllBytes(target);
        } finally {
            Files.deleteIfExists(work);
            if (target != null) {
                Files.deleteIfExists(target);
            }
        }
    }

    /**
     * Lance un processus et attend sa fin.
     *
     * <p>La sortie est redirigée vers le néant : {@code ffmpeg} est bavard, et recopier son journal
     * dans celui du jeu noierait tout le reste. En cas d'échec, c'est le code de retour qui parle.
     *
     * <p>Le délai n'est pas une politesse : un processus qui ne rend jamais la main bloquerait le fil
     * qui attend, et ce fil est celui qui prépare les morceaux. Au-delà du délai, on tue.
     */
    private static boolean run(List<String> command, int seconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException missing) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }
}
