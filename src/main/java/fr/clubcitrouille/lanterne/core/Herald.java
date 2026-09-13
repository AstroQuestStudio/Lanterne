package fr.clubcitrouille.lanterne.core;

import java.util.Locale;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le mot d'accueil : dire ce qu'on a mesuré, pas ce qu'on promet.
 *
 * <h2>Ce qu'une bannière devrait contenir</h2>
 *
 * <p>La plupart des mods annoncent leur nom, leur version et un remerciement. C'est joli et cela
 * n'apprend rien. Un administrateur qui lit sa console veut savoir <b>ce qui va se passer sur sa
 * machine</b>, et il veut le savoir avant que les joueurs n'arrivent.
 *
 * <p>Celle-ci dit donc trois choses, toutes mesurées à l'instant :
 *
 * <ul>
 *   <li><b>ce que vaut la machine</b> — le gain parallèle réel, mesuré et non déclaré, avec le
 *       verdict qui en découle ;</li>
 *   <li><b>ce que le mod va faire</b> — la liste des modules actifs, pour qu'un réglage oublié se
 *       voie au premier coup d'œil ;</li>
 *   <li><b>ce qu'il ne fera jamais</b> — les garanties, parce qu'un administrateur qui installe un
 *       mod d'optimisation a le droit de savoir ce qui ne sera pas dégradé.</li>
 * </ul>
 *
 * <p>Le temps de démarrage est affiché parce qu'il est la première chose qu'on remarque, et la
 * dernière qu'on mesure. Il est donné tel quel, sans comparaison flatteuse : ce mod ne prétend pas
 * accélérer le démarrage, et le dire vaut mieux que de laisser croire le contraire.
 */
public final class Herald {
    private static final String LINE = "─".repeat(66);

    private Herald() {}

    /** Écrit le mot d'accueil. Appelé une fois, quand le serveur est prêt. */
    public static void welcome(long bootMillis, int mods) {
        say("");
        say("┌" + LINE + "┐");
        say(pad("  🎃  L A N T E R N E   ·   la citrouille qui éclaire sans brûler"));
        say("├" + LINE + "┤");

        say(pad(String.format(Locale.ROOT, "  Machine     %d fil(s) · gain parallèle réel ×%.2f",
                Machine.cores(), Machine.speedup())));
        say(pad("              " + Machine.verdict()));
        say(pad(String.format(Locale.ROOT, "  Démarrage   %.2f s · %d mod(s) chargé(s)",
                bootMillis / 1000d, mods)));
        // La liste des modules peut dépasser la largeur du cadre. On la replie plutôt que de la
        // tronquer : un module actif qu'on ne verrait pas est précisément ce qu'une bannière doit
        // éviter.
        for (String line : wrap(Settings.describe(), LINE.length() - 16)) {
            say(pad("  Modules     " + line));
        }
        if (Hush.silenced() > 0) {
            say(pad("  Journal     " + Hush.silenced() + " message(s) inutile(s) écarté(s)"));
        }

        say("├" + LINE + "┤");
        say(pad("  Ce qui ne sera jamais dégradé :"));
        say(pad("    · rien à moins de 24 blocs d'un joueur"));
        say(pad("    · ce qui tombe, les projectiles, la TNT amorcée"));
        say(pad("    · les villageois : jamais sous un tick sur quatre"));
        say(pad("    · les fours : exacts au tick près"));
        say("├" + LINE + "┤");
        say(pad("  /lanterne  ·  ce que le mod économise, en direct et chiffré"));
        say("└" + LINE + "┘");
        say("");
    }

    /** Replie un texte long en lignes qui tiennent dans le cadre, sans couper un mot. */
    private static java.util.List<String> wrap(String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width && line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines.isEmpty() ? java.util.List.of("aucun") : lines;
    }

    /**
     * Complète une ligne pour que le cadre reste droit.
     *
     * <p>Les émojis comptent pour deux colonnes dans la plupart des terminaux mais pour un seul
     * caractère en Java. Sans cette correction, la seule ligne qui en contient dépasse du cadre — un
     * détail, mais un cadre de travers donne à tout le reste un air d'à-peu-près.
     */
    private static String pad(String text) {
        int visible = text.codePointCount(0, text.length());
        for (int i = 0; i < text.length(); i++) {
            if (Character.isSurrogate(text.charAt(i)) || text.charAt(i) > 0x2500) {
                visible++;
            }
        }
        int fill = Math.max(0, LINE.length() - visible);
        return "│" + text + " ".repeat(fill) + "│";
    }

    private static void say(String line) {
        Lanterne.LOG.info(line);
    }
}
