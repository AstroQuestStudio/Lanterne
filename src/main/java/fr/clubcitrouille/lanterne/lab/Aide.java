package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.report.Guide;

/**
 * L'épreuve de l'aide : {@code /lanterne help} promet-il des commandes qui existent ?
 *
 * <h2>Le défaut que cette épreuve existe pour rendre impossible</h2>
 *
 * <p>Chaque ligne de l'aide est <b>cliquable</b> : le clic écrit la commande dans la zone de saisie
 * du joueur. Une ligne qui nomme une commande inexistante lui fait donc taper un texte que le mod
 * vient de lui proposer, pour se voir répondre par un refus de Brigadier. C'est pire que pas d'aide
 * du tout — l'aide devient une source d'erreurs au lieu d'en être le remède.
 *
 * <p>C'est arrivé deux fois. {@code /lanterne wp liste} n'a jamais existé : la sous-commande de
 * liste, c'est {@code /lanterne wp} tout court. Et {@code /lanterne status} vit en réalité sous
 * {@code clear} — c'est l'état du balai, pas celui du mod.
 *
 * <p>Les deux fois, la faute a été la même, et elle n'est pas de l'inattention : l'aide a été
 * <b>relue</b> au lieu d'être <b>confrontée</b>. Relire une page de soixante lignes contre un arbre
 * de commandes réparti sur sept fichiers est un exercice que personne ne réussit deux fois de suite.
 *
 * <h2>Ce qu'on demande, et à qui</h2>
 *
 * <p>On ne relit rien : on demande au <b>vrai dispatcher</b>, celui-là même qui répondra au joueur,
 * de résoudre chaque ligne. Brigadier sait dire si un chemin de nœuds existe, et c'est exactement la
 * question posée.
 *
 * <p>Une ligne de l'aide n'est pas une commande complète : {@code /lanterne wp add} attend un nom
 * derrière. Une analyse stricte échouerait donc sur des lignes parfaitement justes. Ce qu'on vérifie
 * est plus précis et plus utile : <b>chaque mot de la ligne correspond-il à un nœud littéral qui
 * existe à cet endroit de l'arbre ?</b> Un argument manquant est normal ; un littéral inconnu ne
 * l'est jamais.
 *
 * <h2>Pourquoi une source d'administrateur</h2>
 *
 * <p>L'aide cache ce que la source n'a pas le droit d'employer. Éprouvée avec les droits d'un joueur
 * ordinaire, elle ne montrerait que la moitié de ses lignes — et la moitié cachée est précisément
 * celle que personne ne relit jamais.
 */
public final class Aide {
    private Aide() {}

    /** Confronte l'aide au dispatcher. Rend vrai si tout ce qu'elle promet existe. */
    public static boolean run(MinecraftServer server) {
        CommandSourceStack source = server.createCommandSourceStack();

        // Afficher l'aide EST la façon de la relever : elle retient ses lignes au passage. On la
        // fait donc parler à la console du serveur, ce qui a l'avantage secondaire de prouver que
        // l'affichage lui-même ne lève pas.
        Guide.show(source);
        List<String> promises = Guide.promises();

        if (promises.isEmpty()) {
            Lanterne.LOG.error("[AIDE] ÉPREUVE INVALIDE : l'aide n'a promis aucune commande. Soit "
                    + "elle ne s'est pas affichée, soit elle ne retient plus ses lignes — dans les "
                    + "deux cas, rien n'a été éprouvé.");
            return false;
        }

        // Les lignes declarees facultatives — celles qu'un serveur dedie n'a pas, parce qu'elles
        // ouvrent un ecran — sont enumerees mais ne font pas echouer. On les affiche quand meme :
        // une ligne absente parce qu'elle est cote client et une ligne absente parce qu'on a fait
        // une faute de frappe se ressemblent trop pour qu'on laisse la seconde passer en silence.
        java.util.Set<String> optional = Guide.optional();

        List<String> broken = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (String promise : promises) {
            String faulty = firstUnknownWord(server, source, promise);
            if (faulty == null) {
                continue;
            }
            if (optional.contains(promise)) {
                absent.add(promise);
            } else {
                broken.add(promise + "   (« " + faulty + " » n'existe pas à cet endroit)");
            }
        }

        Lanterne.LOG.info("[AIDE] {} ligne(s) promise(s) par /lanterne help.", promises.size());
        for (String line : absent) {
            Lanterne.LOG.info("[AIDE]   · {} — absente ici, et c'est déclaré : commande d'écran, "
                    + "un serveur dédié n'en a pas.", line);
        }
        if (broken.isEmpty()) {
            Lanterne.LOG.info("[AIDE] CONFORME : chaque ligne de l'aide désigne une commande qui "
                    + "existe. Rappel : cela ne dit rien de la justesse des descriptions.");
            return true;
        }
        Lanterne.LOG.error("[AIDE] NON CONFORME : {} ligne(s) promettent une commande inexistante. "
                + "Chacune est un clic qui écrit au joueur un texte que le jeu refusera.",
                broken.size());
        for (String line : broken) {
            Lanterne.LOG.error("[AIDE]   ✗ {}", line);
        }
        return false;
    }

    /**
     * Le premier mot de cette ligne qui ne correspond à aucun nœud littéral.
     *
     * <p>On descend l'arbre mot à mot. Dès qu'un mot ne nomme ni un littéral connu, ni ne peut être
     * un argument attendu là, on le rend — c'est celui qu'il faut corriger, et le dire vaut mieux
     * que de dire « la ligne est fausse ».
     *
     * @return le mot fautif, ou {@code null} si la ligne descend entièrement dans l'arbre.
     */
    private static String firstUnknownWord(MinecraftServer server, CommandSourceStack source,
            String promise) {
        String[] words = promise.startsWith("/") ? promise.substring(1).split("\\s+")
                : promise.split("\\s+");
        CommandNode<CommandSourceStack> node = server.getCommands().getDispatcher().getRoot();
        for (String word : words) {
            CommandNode<CommandSourceStack> next = node.getChild(word);
            if (next == null) {
                // Un argument attendu ici ? Alors le mot n'est pas cense etre un litteral, et
                // l'aide s'arrete legitimement avant lui. Mais l'aide n'ecrit jamais d'arguments,
                // donc ce cas ne devrait pas se presenter — on le signale quand meme.
                return word;
            }
            node = next;
        }
        return null;
    }

    /**
     * Une variante silencieuse, pour l'autotest.
     *
     * <p>Exposée à part parce que l'affichage de l'aide envoie soixante lignes à la console, ce qui
     * est utile quand on éprouve l'aide et encombrant quand on éprouve autre chose.
     */
    public static ParseResults<CommandSourceStack> parse(MinecraftServer server, String command) {
        return server.getCommands().getDispatcher()
                .parse(command, server.createCommandSourceStack());
    }
}
