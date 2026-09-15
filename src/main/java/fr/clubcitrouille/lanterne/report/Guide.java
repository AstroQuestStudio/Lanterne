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
 * <p>Lanterne a fini par porter six racines de commandes — {@code /lanterne}, {@code /boutique},
 * {@code /banque}, {@code /tableau}, {@code /disque}, {@code /projection} — et plusieurs dizaines
 * de sous-commandes. La
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
    /**
     * Les commandes que cette page a promises, à l'ordre où elle les a écrites.
     *
     * <h2>Pourquoi une aide doit pouvoir être éprouvée</h2>
     *
     * <p>Une ligne d'ici est <b>cliquable</b> : elle écrit une commande dans la zone de saisie. Si
     * cette commande n'existe pas, le joueur obtient un refus de Brigadier sur un texte que le mod
     * vient de lui proposer lui-même. C'est pire que pas d'aide du tout, et cela s'est produit deux
     * fois : {@code /lanterne wp liste} n'a jamais existé, et {@code /lanterne status} vit en réalité
     * sous {@code clear}.
     *
     * <p>Les deux fois, la faute était la même : l'aide était <b>relue</b> au lieu d'être
     * <b>confrontée</b>. D'où cette liste, que {@code lab.Aide} parcourt en demandant au vrai
     * dispatcher de résoudre chaque ligne. Une promesse non tenue fait alors échouer l'épreuve, au
     * lieu d'attendre qu'un joueur la découvre.
     *
     * <p>Elle est remplie à chaque affichage et non une fois pour toutes : les lignes visibles
     * dépendent des droits de la source, et l'épreuve passe une source d'administrateur pour les
     * voir toutes.
     */
    private static final java.util.List<String> PROMISES = new java.util.ArrayList<>();

    /**
     * Les lignes dont l'absence est normale sur certains serveurs.
     *
     * <h2>Toutes les commandes du mod n'existent pas partout</h2>
     *
     * <p>{@code /lanterne guide} ouvre un écran : elle est donc enregistrée par du code client, et
     * <b>n'existe pas sur un serveur dédié</b>. L'annoncer là-bas serait promettre ce que ce serveur
     * ne peut pas faire ; ne jamais l'annoncer priverait de sa principale porte d'entrée le joueur
     * en solo, chez qui elle existe.
     *
     * <p>Ces lignes ne sont donc affichées <b>que si le dispatcheur les reconnaît</b>. Elles restent
     * inscrites parmi les promesses pour que {@code lab.Aide} puisse les énumérer : une ligne
     * absente parce qu'elle est côté client et une ligne absente parce qu'on a fait une faute de
     * frappe se ressemblent trop pour qu'on laisse la seconde passer en silence.
     */
    private static final java.util.Set<String> OPTIONAL = new java.util.LinkedHashSet<>();

    /** Les commandes promises par le dernier affichage. Réservé au laboratoire. */
    public static java.util.List<String> promises() {
        return java.util.List.copyOf(PROMISES);
    }

    /** Celles dont l'absence est normale. Réservé au laboratoire. Voir {@link #OPTIONAL}. */
    public static java.util.Set<String> optional() {
        return java.util.Set.copyOf(OPTIONAL);
    }

    private Guide() {}

    /** Écrit le sommaire pour cette source. */
    public static int show(CommandSourceStack source) {
        // Le meme controle que celui qui garde reellement les commandes d'administration : en
        // 26.1 une permission n'est plus un entier mais un PermissionCheck, et Commands.hasPermission
        // en fait un predicat applicable a la source.
        boolean admin = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
        PROMISES.clear();
        OPTIONAL.clear();

        say(source, Component.literal("🎃 Lanterne")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("  — clique une commande pour l'écrire")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)
                                .withBold(false))));

        heading(source, "Le mod");
        line(source, "/lanterne", "L'état du mod : ce qui est allumé, et ce que ça évite.");
        // Enregistrée côté client : elle ouvre un écran, et un serveur dédié n'en a pas. Voir
        // OPTIONAL — on l'annonce là où elle existe, et nulle part ailleurs.
        optionalLine(source, "/lanterne guide", "Le guide illustré : les mécanismes du mod, en "
                + "animation. Ou garde W enfoncé sur un objet, dans l'inventaire comme dans JEI.");
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
            line(source, "/lanterne clear status", "Quand le balai repasse, et ce qu'il a déjà "
                    + "effacé depuis le démarrage.");
        }

        heading(source, "Les repères");
        // « /lanterne wp » tout court LISTE ; il n'y a pas de sous-commande « liste », et l'aide en
        // annonçait une. Une aide cliquable qui écrit une commande refusée est pire que pas d'aide.
        line(source, "/lanterne wp", "Tes repères posés.");
        line(source, "/lanterne wp add", "Pose un repère ici, sous le nom que tu donnes.");
        line(source, "/lanterne wp remove", "Retire un repère.");
        line(source, "/lanterne wp share", "Partage un repère avec les autres joueurs.");

        heading(source, "Les portails");
        line(source, "/lanterne portail", "Tes portails et les portails publics : où ils sont, où "
                + "ils mènent.");
        line(source, "/lanterne portail lier", "Relie un de tes portails à une destination. "
                + "« <portail> <destination> », les noms entre guillemets s'ils ont un espace.");
        line(source, "/lanterne portail delier", "Ce portail ne mène plus nulle part.");
        line(source, "/lanterne portail public", "Ouvre un portail à tous, ou le referme.");
        line(source, "/lanterne portail oublier", "Éteint un portail et efface sa fiche.");
        say(source, Component.literal("  Un cadre d'obsidienne, un Cœur de repère à la place d'un "
                        + "de ses blocs, un briquet — et clic droit sur le cœur pour le régler.")
                .withStyle(ChatFormatting.DARK_GRAY));

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
        line(source, "/tableau prendre", "Grave une image de la bibliothèque sur le tableau visé. "
                + "« <image> <largeur> », la largeur en blocs.");
        line(source, "/disque liste", "Les morceaux gravés et les sillons libres.");
        line(source, "/disque prendre", "Grave un morceau du dossier sur un disque vierge.");
        line(source, "/disque etat", "Les formats lus et les sillons occupés.");
        if (admin) {
            line(source, "/disque liberer", "Libère un sillon. Le disque correspondant devient muet.");
            line(source, "/disque recharger", "Relit le dossier des morceaux, à chaud.");
            line(source, "/tableau recharger", "Relit le dossier des images, à chaud.");
            line(source, "/tableau heritage", "Reprend les tableaux d'Immersive Paintings.");
        }

        heading(source, "La projection");
        line(source, "/projection domaines", "L'état du filtrage. Ouvert par défaut : tout lien "
                + "http(s) public passe. Clic droit sur un écran pour le régler.");
        if (admin) {
            line(source, "/projection domaine ajouter", "Inscrit un domaine. Ne mord que si tu "
                    + "actives aussi liste_blanche.active dans la configuration.");
            line(source, "/projection domaine retirer", "Retire un domaine de la liste.");
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
    /**
     * Une ligne qui peut ne pas exister sur ce serveur.
     *
     * <p>Voir {@link #OPTIONAL}. On interroge le dispatcheur plutôt que de supposer : c'est lui qui
     * répondra au joueur, c'est donc lui qui a raison.
     */
    private static void optionalLine(CommandSourceStack source, String command, String what) {
        OPTIONAL.add(command);
        if (!resolves(source, command)) {
            PROMISES.add(command);
            return;
        }
        line(source, command, what);
    }

    /** Ce chemin de littéraux descend-il réellement dans l'arbre des commandes ? */
    private static boolean resolves(CommandSourceStack source, String command) {
        com.mojang.brigadier.tree.CommandNode<CommandSourceStack> node =
                source.getServer().getCommands().getDispatcher().getRoot();
        for (String word : command.substring(1).split(" ")) {
            node = node.getChild(word);
            if (node == null) {
                return false;
            }
        }
        return true;
    }

    private static void line(CommandSourceStack source, String command, String what) {
        // Chaque ligne est retenue au passage. Voir « promises » : c'est ce qui permet de
        // CONFRONTER l'aide au vrai dispatcher plutôt que de la relire.
        PROMISES.add(command);
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
