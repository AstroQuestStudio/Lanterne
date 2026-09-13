package fr.clubcitrouille.lanterne.core;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Faire taire ce qui n'apprend rien à personne.
 *
 * <h2>Le bruit qui coûte</h2>
 *
 * <p>Une console de serveur devrait se lire. Celle d'un serveur moddé se parcourt en diagonale, parce
 * qu'elle est noyée sous des avertissements que personne ne peut ni corriger ni éviter.
 *
 * <p>Le cas le plus net, relevé sur un serveur réel, tient en une trace de quarante lignes,
 * <b>répétée à chaque erreur journalisée</b> :
 *
 * <pre>
 * ERROR An exception occurred processing Appender DebugFile
 * java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native
 * Caused by: java.lang.IllegalStateException: Only supported on OSX/BSD
 * </pre>
 *
 * <p>Netty essaie de charger son transport réseau spécifique à macOS, ne le trouve pas sur Linux —
 * <b>ce qui est parfaitement normal</b> — et le journal en fait une erreur complète avec sa pile
 * d'appel. Le serveur, lui, retombe silencieusement sur le transport correct et continue.
 *
 * <p>Ce n'est pas un incident : c'est un choix de plateforme, journalisé comme une panne. Et il
 * n'existe aucun réglage pour l'éteindre.
 *
 * <h2>Ce qu'on fait taire, et ce qu'on ne fait jamais taire</h2>
 *
 * <p>La règle tient en une phrase : <b>on ne masque que ce dont on peut démontrer l'innocuité</b>.
 * Chaque motif filtré ci-dessous correspond à une situation identifiée, normale, et sur laquelle
 * l'administrateur ne peut rien.
 *
 * <p>Tout le reste passe. Un mod d'optimisation qui masquerait une vraie erreur pour paraître propre
 * aurait échangé un problème visible contre un problème invisible — ce qui est toujours un mauvais
 * marché.
 */
public final class Hush {
    /**
     * Ce qu'on fait taire, et pourquoi.
     *
     * <p>Chaque entrée est un fragment de message et sa justification. La justification n'est pas
     * décorative : si l'on ne sait pas dire pourquoi un message est inutile, c'est qu'on n'a pas le
     * droit de le masquer.
     */
    private static final String[][] NOISE = {
        {"Could not initialize class io.netty.channel.kqueue",
         "transport macOS absent sur Linux — le serveur retombe sur epoll, c'est normal"},
        {"Only supported on OSX/BSD",
         "même cause, l'autre bout de la même trace"},
        {"An exception occurred processing Appender DebugFile",
         "conséquence de la précédente : le journal se plaint de ne pas pouvoir journaliser"},
        {"Reference map 'lanterne.refmap.json'",
         "absente en développement, sans objet en production — NeoForge n'obfusque plus"},
    };

    private static boolean installed;
    private static long silenced;

    private Hush() {}

    /** Pose le filtre sur le journal. Sans effet si le réglage est éteint. */
    public static void install() {
        if (installed || !Settings.hush()) {
            return;
        }
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.getConfiguration().getRootLogger().addFilter(new NoiseFilter());
            for (Logger logger : context.getLoggers()) {
                logger.addFilter(new NoiseFilter());
            }
            context.updateLoggers();
            installed = true;
        } catch (Throwable refused) {
            // Un journal qu'on n'arrive pas à filtrer reste un journal qui fonctionne. On ne fait
            // pas échouer un serveur pour une question de propreté.
            Lanterne.LOG.debug("Filtre de journal non posé : {}", refused.toString());
        }
    }

    public static long silenced() {
        return silenced;
    }

    /**
     * Le filtre lui-même.
     *
     * <p>Il ne regarde que les messages de niveau avertissement ou pire — un message d'information
     * ne coûte rien et peut servir. Et il ne compare que des fragments connus : aucune heuristique,
     * aucune expression régulière, rien qui puisse attraper un message qu'on n'aurait pas prévu.
     */
    private static final class NoiseFilter extends AbstractFilter {
        /**
         * Laisser passer par défaut, des deux côtés.
         *
         * <h2>Un filtre qui a fait taire son propre mod</h2>
         *
         * <p>Le constructeur sans argument de {@code AbstractFilter} prend {@code onMatch = NEUTRAL}
         * mais <b>{@code onMismatch = DENY}</b>. Or cette classe ne redéfinit que trois des
         * nombreuses surcharges de {@code filter(...)} ; toutes les autres rendent alors la valeur
         * par défaut — c'est-à-dire <b>refusent le message</b>.
         *
         * <p>Le résultat a été immédiat et total : le mot d'accueil n'est jamais apparu, ni la ligne
         * d'appréciation de la machine, ni rien de ce que le mod journalise. Un filtre destiné à
         * écarter quatre messages en avait supprimé la totalité.
         *
         * <p>On déclare donc explicitement les deux issues à {@code NEUTRAL} : ce filtre ne peut
         * qu'écarter ce qu'il reconnaît, et n'a aucun avis sur le reste.
         */
        NoiseFilter() {
            super(Result.NEUTRAL, Result.NEUTRAL);
        }

        @Override
        public Result filter(Logger logger, Level level, Marker marker, String message,
                             Object... params) {
            return judge(level, message);
        }

        @Override
        public Result filter(Logger logger, Level level, Marker marker, Object message,
                             Throwable error) {
            return judge(level, message == null ? null : message.toString());
        }

        @Override
        public Result filter(Logger logger, Level level, Marker marker, Message message,
                             Throwable error) {
            return judge(level, message == null ? null : message.getFormattedMessage());
        }

        private Result judge(Level level, String text) {
            if (text == null || level == null || !level.isMoreSpecificThan(Level.WARN)) {
                return Result.NEUTRAL;
            }
            for (String[] entry : NOISE) {
                if (text.contains(entry[0])) {
                    silenced++;
                    return Result.DENY;
                }
            }
            return Result.NEUTRAL;
        }
    }
}
