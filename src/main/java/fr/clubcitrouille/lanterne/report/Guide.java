package fr.clubcitrouille.lanterne.report;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Le sommaire : toutes les commandes du mod, en une page.
 *
 * <h2>Pourquoi ce n'est pas un luxe</h2>
 *
 * <p>Lanterne a fini par porter cinq racines de commandes — {@code /lanterne}, {@code /boutique},
 * {@code /banque}, {@code /tableau}, {@code /disque} — et plusieurs dizaines de sous-commandes. La
 * complétion de Brigadier les montre une par une, à condition de savoir par quelle lettre commencer.
 * Quand on ne le sait pas, on ne trouve rien.
 *
 * <p>Le retour du joueur était net : « sinon c'est paumé ».
 *
 * <h2>Cliquable, et c'est là tout l'intérêt</h2>
 *
 * <p>Une aide qui se contente d'énumérer du texte oblige à tout retaper. Chaque ligne d'ici est donc
 * <b>cliquable</b> : le clic écrit la commande dans la zone de saisie sans l'exécuter
 * ({@code SuggestCommand}), ce qui laisse le loisir de compléter les arguments ou de reculer. On ne
 * lance rien dans le dos de qui lit une aide — {@code RunCommand} aurait exécuté au clic, et
 * {@code /lanterne clear} n'est pas une commande qu'on déclenche par mégarde.
 *
 * <p>Le survol donne une phrase d'explication, ce qui évite d'encombrer la ligne elle-même.
 *
 * <h2>Ce qui est montré</h2>
 *
 * <p>Seulement ce que la source a le droit d'employer. Un joueur ordinaire ne voit pas les commandes
 * d'administration : une aide qui promet ce qu'elle refuse ensuite est pire que pas d'aide du tout.
 */
public final class Guide {
    private Guide() {}

    /** Écrit le sommaire pour cette source. */
    public static int show(CommandSourceStack source) {
        // Le meme controle que celui qui garde reellement les commandes d'administration : en
        // 26.1 une permission n'est plus un entier mais un PermissionCheck, et Commands.hasPermission
        // en fait un predicat applicable a la source.
        boolean admin = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);

        say(source, Component.literal("🎃 Lanterne")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("  — clique une commande pour l'écrire")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)
                                .withBold(false))));

        heading(source, "Le mod");
        line(source, "/lanterne", "L'état du mod : ce qui est allumé, et ce que ça évite.");
        if (admin) {
            line(source, "/lanterne on", "Rallume toutes les optimisations.");
            line(source, "/lanterne off", "Éteint tout — utile pour comparer à vue d'œil.");
        }

        heading(source, "Le monde");
        line(source, "/lanterne maree", "Distances de vue et de simulation : où elles en sont.");
        if (admin) {
            line(source, "/lanterne maree on", "La marée suit à nouveau la charge.");
            line(source, "/lanterne maree off", "Fige les distances où elles sont.");
            line(source, "/lanterne pregen", "Pré-génère la carte. Une carte déjà générée se relit "
                    + "cinq fois plus vite qu'elle ne se crée.");
            line(source, "/lanterne pregen-stop", "Interrompt la pré-génération en cours.");
            line(source, "/lanterne clear", "Le balai : efface les objets au sol. Éteint par défaut.");
        }

        heading(source, "Les repères");
        line(source, "/lanterne wp liste", "Tes repères posés.");
        line(source, "/lanterne wp add", "Pose un repère ici, sous le nom que tu donnes.");
        line(source, "/lanterne wp remove", "Retire un repère.");
        line(source, "/lanterne wp share", "Partage un repère avec les autres joueurs.");

        heading(source, "La boutique");
        line(source, "/boutique", "Ouvre l'écran : acheter, vendre.");
        line(source, "/boutique cours", "Le cours d'un objet, son prix d'ancrage, le volume échangé.");
        line(source, "/banque", "Ton solde.");
        line(source, "/banque payer", "Un virement à un autre joueur.");
        line(source, "/banque classement", "Les plus fortunés du serveur.");
        if (admin) {
            line(source, "/boutique poser", "Inscrit ou corrige un article au catalogue.");
            line(source, "/boutique recharger", "Relit le catalogue depuis le fichier, à chaud.");
            line(source, "/boutique generer", "Réengendre le catalogue depuis les recettes du jeu.");
            line(source, "/banque donner", "Crédite un joueur.");
            line(source, "/banque masse", "La masse monétaire du serveur.");
        }

        heading(source, "L'atelier");
        line(source, "/tableau liste", "Les images de ta bibliothèque.");
        line(source, "/disque liste", "Les morceaux gravés et les sillons libres.");
        if (admin) {
            line(source, "/disque liberer", "Libère un sillon. Le disque correspondant devient muet.");
            line(source, "/tableau heritage", "Reprend les tableaux d'Immersive Paintings.");
        }

        if (admin) {
            heading(source, "Le laboratoire");
            line(source, "/lanterne bench", "Le banc : la même charge deux fois, avec et sans.");
            line(source, "/lanterne charge", "Pose une charge d'entités reproductible.");
            line(source, "/lanterne demo", "Une démonstration sur une charge donnée.");
            line(source, "/lanterne test", "Un troupeau d'épreuve.");
            line(source, "/lanterne palette", "Relève la largeur des palettes de terrain.");
            line(source, "/lanterne nbt", "Inspecte les données d'un bloc-entité.");
        }

        if (!admin) {
            say(source, Component.literal("Les commandes d'administration ne sont pas affichées.")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return 1;
    }

    private static void heading(CommandSourceStack source, String title) {
        say(source, Component.literal("▍ " + title).withStyle(ChatFormatting.YELLOW));
    }

    /**
     * Une ligne cliquable.
     *
     * <p>{@code SuggestCommand} écrit la commande dans la zone de saisie sans la lancer. C'est
     * délibéré : voir la note de classe.
     */
    private static void line(CommandSourceStack source, String command, String what) {
        MutableComponent name = Component.literal("  " + command)
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.SuggestCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal(what).withStyle(ChatFormatting.WHITE))));
        say(source, name.append(Component.literal("  " + what)
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
    }

    private static void say(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }
}
