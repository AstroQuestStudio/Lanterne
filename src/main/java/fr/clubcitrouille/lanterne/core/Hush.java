package fr.clubcitrouille.lanterne.core;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.Configurator;
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
 * <h2>Pourquoi le filtre ci-dessous ne suffit pas pour CE cas précis</h2>
 *
 * <p>Vérifié par lecture du bytecode réel, pas supposé : {@code KQueue.<clinit>} (Netty 4.2.16.Final)
 * capture l'échec et ne journalise qu'en <b>DEBUG</b> — {@code logger.debug("KQueue support is not
 * available: {}", cause.getMessage())}. {@link NoiseFilter} ne regarde jamais rien sous {@code WARN}
 * (voir sa Javadoc), donc cet évènement-source passe toujours, quel que soit {@link Settings#hush()}.
 *
 * <p>Et la ligne « An exception occurred processing Appender DebugFile » qui suit — celle que le
 * joueur voit vraiment — ne traverse de toute façon <b>jamais</b> {@link NoiseFilter} : lecture du
 * bytecode de {@code AppenderControl.handleAppenderError} (log4j-core 2.26.0) confirmée,
 * elle part par {@code Appender.getHandler().error(...)}, le canal interne de diagnostic de log4j
 * — le même genre de canal hors filtre que {@link Reclame} avait déjà dû contourner pour une tout
 * autre raison (la réclame de l'hébergeur, écrite sur l'entrée standard, ne traverse pas non plus le
 * journal). Un filtre posé sur la configuration des journaux ne peut rien contre un message qui ne
 * passe jamais par elle.
 *
 * <p>La seule prise possible est donc en amont, sur la source : voir {@link #preventKQueueCrash()},
 * qui coupe l'appel {@code debug()} avant qu'il ne parte — jamais émis, donc jamais rendu, donc jamais
 * l'occasion pour {@code NoClassDefFoundError} de retomber sur le même échec d'initialisation de
 * classe. Contrairement à {@link #install()}, ce geste-là n'est <b>pas</b> optionnel : ce n'est pas
 * une préférence de confort, c'est la correction d'un vrai plantage, et il ne dépend donc pas de
 * {@link Settings#hush()}.
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

    /**
     * Coupe à la source le plantage documenté dans la Javadoc de classe.
     *
     * <p>Relève le niveau des deux loggers Netty en cause à {@code INFO} : {@code isDebugEnabled()}
     * rend alors {@code false} pour eux, {@code KQueue.<clinit>} ne compose ni n'émet son message, et
     * rien n'atteint jamais un appender qui tenterait de le rendre — {@code io.netty.channel.kqueue}
     * ne sert de toute façon jamais à rien sur une machine qui n'est ni macOS ni BSD, et son silence
     * en DEBUG ne prive personne d'un diagnostic exploitable.
     *
     * <p>Sans effet sur {@code epoll} ni sur quoi que ce soit d'autre : deux noms de logger précis,
     * rien de plus. Appelé sans condition, avant que le réseau ne s'initialise — voir le point
     * d'appel dans {@code Lanterne}, juste à côté d'{@link #install()}.
     *
     * <p>{@code Configurator.setLevel} est l'API publique et stable de log4j-core pour ce geste ; pas
     * de réflexion, pas de champ privé forcé. Si l'appel échoue — implémentation de journal différente
     * de celle attendue — on continue sans lui : au pire, le message revient, ce qui est le
     * comportement d'aujourd'hui, pas une régression.
     */
    public static void preventKQueueCrash() {
        try {
            Configurator.setLevel("io.netty.channel.kqueue.KQueue", Level.INFO);
            Configurator.setLevel("io.netty.channel.kqueue.Native", Level.INFO);
        } catch (Throwable refused) {
            Lanterne.LOG.debug("Silence du logger kqueue non posé : {}", refused.toString());
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
