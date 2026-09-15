package fr.clubcitrouille.lanterne.core;

import java.util.Locale;

import com.mojang.brigadier.ParseResults;

import net.minecraft.commands.CommandSourceStack;

import net.neoforged.neoforge.event.CommandEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La réclame : les messages publicitaires que l'hébergeur injecte dans la console.
 *
 * <h2>Ce que c'est, exactement</h2>
 *
 * <p>Un hébergement gratuit se paie autrement, et la façon la plus courante est celle-ci : le
 * démon qui lance le serveur écrit de temps à autre une commande directement sur son entrée
 * standard, comme si un administrateur l'avait tapée.
 *
 * <pre>
 * tellraw @a [{"text":"[MyBoxFree]", ... "clickEvent":{"action":"open_url",
 *              "value":"https://minestrator.com"} ... }]
 * </pre>
 *
 * <p>Le serveur ne peut pas faire la différence : pour lui, c'est la console qui parle, et la
 * console a tous les droits. Le message part donc à <b>tous les joueurs</b>, plusieurs fois par
 * heure, y compris au milieu d'une partie.
 *
 * <h2>Ce que cela coûte, et ce que cela ne coûte pas</h2>
 *
 * <p>Il faut être exact, parce que la raison qu'on donne à un correctif décide de l'endroit où on
 * le met : <b>cela ne coûte rien en performances</b>. Un {@code tellraw} toutes les deux minutes
 * est indétectable, et quiconque prétend le contraire n'a pas mesuré.
 *
 * <p>Ce que cela coûte est ailleurs, et c'est réel : c'est un message que le joueur n'a pas
 * demandé, au milieu de ce qu'il lit. Le motif est suffisant, à condition de ne pas se raconter
 * qu'on optimise quelque chose.
 *
 * <h2>Pourquoi ici, et pas dans le filtre de journal</h2>
 *
 * <p>Le premier réflexe a été de le faire taire avec {@link Hush}. C'était impossible, et pour une
 * raison qui valait d'être vérifiée plutôt que supposée : <b>la commande n'apparaît pas dans
 * {@code latest.log}</b>. Ce qu'on voit dans la console du panneau est l'écho de l'entrée standard
 * par le démon — un texte qui n'a jamais traversé le journal du serveur.
 *
 * <p>Il n'y avait donc rien à filtrer côté journal. Le seul point où ce message existe pour nous
 * est l'instant où le serveur exécute la commande — et NeoForge y publie justement un évènement
 * annulable, ce qui évite un mixin.
 *
 * <h2>La règle, et pourquoi elle est étroite</h2>
 *
 * <p>On n'annule que si <b>les deux</b> conditions tiennent :
 *
 * <ol>
 *   <li>la commande ne vient <b>pas d'un joueur</b> — donc de la console, du démon ou de RCON ;</li>
 *   <li>elle nomme le site d'un hébergeur connu.</li>
 * </ol>
 *
 * <p>La première condition est celle qui rend la chose sûre. Un administrateur connecté en jeu qui
 * écrit lui-même un message nommant son hébergeur le verra partir : c'est <b>sa</b> décision, et ce
 * module n'a pas à la lui reprendre. Seul ce que personne n'a demandé est retiré.
 *
 * <p>Et l'on ne filtre pas sur « publicité » en général — ce serait indécidable, et un mod
 * d'optimisation qui se met à juger le contenu des messages d'un serveur a dépassé son rôle. On
 * filtre sur une liste <b>nommée</b>, que l'administrateur peut lire.
 *
 * <h2>Ce que l'administrateur doit savoir avant de s'en servir</h2>
 *
 * <p>C'est la contrepartie d'un hébergement gratuit. La retirer peut contrevenir aux conditions de
 * l'hébergeur, et c'est à l'administrateur d'en décider — d'où un réglage, et non un comportement
 * imposé. Le module le dit une fois au premier blocage, pour que personne ne l'apprenne trop tard.
 */
public final class Reclame {
    /**
     * Les hébergeurs dont on connaît le message.
     *
     * <p>Une liste nommée plutôt qu'une heuristique : on doit pouvoir dire <b>exactement</b> ce qui
     * est retiré, et un administrateur doit pouvoir vérifier que rien d'autre ne l'est.
     */
    private static final String[] HOSTS = {
        "minestrator.com",
        "aternos.org",
        "falixnodes.net",
        "falix.gg",
        "exaroton.com",
        "serveur-minecraft.net",
        "mc-host24.de",
        "bloom.host",
        "ggservers.com",
        "apexminecrafthosting.com",
    };

    private Reclame() {}

    /** Réclames retirées depuis le démarrage. */
    private static long blocked;

    /** Vrai une fois l'avertissement donné. Voir la note sur les conditions de l'hébergeur. */
    private static boolean warned;

    /**
     * Examine une commande sur le point d'être exécutée.
     *
     * <p>Branchée sur {@code CommandEvent}, qui n'est publié qu'à l'exécution d'une commande : ce
     * n'est pas un chemin chaud, et le coût de l'examen est sans objet.
     */
    public static void onCommand(CommandEvent event) {
        if (!Settings.reclame()) {
            return;
        }
        ParseResults<CommandSourceStack> parsed = event.getParseResults();
        CommandSourceStack source = parsed.getContext().getSource();

        // Un joueur reste maître de ce qu'il écrit, même si cela nomme son hébergeur.
        if (source.getPlayer() != null) {
            return;
        }

        String raw = parsed.getReader().getString();
        String host = hostNamedIn(raw);
        if (host == null) {
            return;
        }

        event.setCanceled(true);
        blocked++;
        if (!warned) {
            warned = true;
            Lanterne.LOG.info("[RÉCLAME] message publicitaire de « {} » retiré : la console en a "
                    + "envoyé un à tous les joueurs. C'est la contrepartie d'un hébergement gratuit, "
                    + "et la retirer peut contrevenir aux conditions de l'hébergeur — à vous de "
                    + "voir. Pour laisser passer : reclame = false dans "
                    + "config/lanterne-server.toml. Les prochains seront retirés en silence.", host);
        }
    }

    /** Le premier hébergeur nommé dans cette commande, ou {@code null}. */
    private static String hostNamedIn(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        for (String host : HOSTS) {
            if (lower.contains(host)) {
                return host;
            }
        }
        return null;
    }

    /** Nombre de réclames retirées depuis le démarrage. */
    public static long blocked() {
        return blocked;
    }
}
