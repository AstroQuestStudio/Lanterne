package fr.clubcitrouille.lanterne.client.reglage;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * {@code /lanterne reglage} : la liste, la lecture, l'écriture, les préréglages, l'export et l'import.
 *
 * <p>Commande de client : elle ne quitte jamais l'ordinateur du joueur, et elle vit à côté de
 * {@code /lanterne guide} (même racine, même événement) sans rien changer aux commandes du serveur.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class ReglageCommand {
    private ReglageCommand() {}

    @SubscribeEvent
    static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lanterne")
                .then(Commands.literal("reglage")
                        .executes(context -> lister())
                        .then(Commands.literal("restaurer").executes(context -> {
                            Reglages.restaurer();
                            dire("Tous les réglages sont revenus à leur valeur d'origine.",
                                    ChatFormatting.GREEN);
                            return 1;
                        }))
                        .then(Commands.literal("preset")
                                .then(Commands.argument("nom", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                Reglages.PREREGLAGES.keySet(), builder))
                                        .executes(context -> preset(
                                                StringArgumentType.getString(context, "nom")))))
                        .then(Commands.literal("exporter").executes(context -> exporter()))
                        .then(Commands.literal("importer")
                                .then(Commands.argument("texte", StringArgumentType.greedyString())
                                        .executes(context -> importer(
                                                StringArgumentType.getString(context, "texte")))))
                        .then(Commands.argument("nom", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Reglages.tous().stream().map(Reglages.Reglage::id).toList(), builder))
                                .executes(context -> lire(StringArgumentType.getString(context, "nom")))
                                .then(Commands.argument("valeur", IntegerArgumentType.integer(0, 1000))
                                        .executes(context -> ecrire(
                                                StringArgumentType.getString(context, "nom"),
                                                IntegerArgumentType.getInteger(context, "valeur")))))));
    }

    private static int lister() {
        dire("Réglages de performance (« /lanterne reglage <nom> <valeur> ») :", ChatFormatting.GOLD);
        for (Reglages.Reglage reglage : Reglages.tous()) {
            dire(" " + reglage.id() + " = " + reglage.texte() + (reglage.estDefaut() ? "" : " *")
                    + "  [perte visible : " + reglage.perte().texte() + "]", ChatFormatting.GRAY);
        }
        dire("Préréglages : " + String.join(", ", Reglages.PREREGLAGES.keySet())
                + " (« /lanterne reglage preset <nom> »), « restaurer », « exporter », « importer ».",
                ChatFormatting.YELLOW);
        return 1;
    }

    private static int lire(String nom) {
        Reglages.Reglage reglage = Reglages.parId(nom);
        if (reglage == null) {
            dire("Réglage inconnu : " + nom + ". « /lanterne reglage » donne la liste.", ChatFormatting.RED);
            return 0;
        }
        dire(reglage.nom() + " (" + reglage.id() + ") = " + reglage.texte() + " — défaut : "
                + reglage.texte(reglage.defaut()) + " — perte visible : " + reglage.perte().texte(),
                ChatFormatting.GOLD);
        dire(reglage.quoi(), ChatFormatting.GRAY);
        dire("Gain : " + reglage.gain() + ". Valeurs permises : " + valeursPermises(reglage) + ".",
                ChatFormatting.GRAY);
        return 1;
    }

    private static String valeursPermises(Reglages.Reglage reglage) {
        StringBuilder texte = new StringBuilder();
        for (int niveau : reglage.niveaux()) {
            if (texte.length() > 0) {
                texte.append(", ");
            }
            texte.append(niveau);
        }
        return texte.toString();
    }

    private static int ecrire(String nom, int valeur) {
        Reglages.Reglage reglage = Reglages.parId(nom);
        if (reglage == null) {
            dire("Réglage inconnu : " + nom + ".", ChatFormatting.RED);
            return 0;
        }
        if (!ClientConfig.SPEC.isLoaded()) {
            dire("Le fichier de réglages n'est pas encore chargé.", ChatFormatting.RED);
            return 0;
        }
        reglage.poser(valeur);
        Reglages.appliquer();
        dire(reglage.nom() + " = " + reglage.texte() + ".", ChatFormatting.GREEN);
        return 1;
    }

    private static int preset(String nom) {
        if (!Reglages.appliquerPrereglage(nom)) {
            dire("Préréglage inconnu : " + nom + ". Choix : " + String.join(", ", Reglages.PREREGLAGES.keySet()),
                    ChatFormatting.RED);
            return 0;
        }
        dire("Préréglage « " + nom + " » appliqué.", ChatFormatting.GREEN);
        return 1;
    }

    private static int exporter() {
        String ligne = Reglages.exporter();
        Minecraft.getInstance().keyboardHandler.setClipboard(ligne);
        dire("Copié dans le presse-papiers : " + ligne, ChatFormatting.GREEN);
        return 1;
    }

    private static int importer(String texte) {
        int poses = Reglages.importer(texte.strip());
        if (poses < 0) {
            dire("Texte illisible : il doit commencer par « lanterne-reglages; ».", ChatFormatting.RED);
            return 0;
        }
        dire(poses + " réglage(s) importé(s).", ChatFormatting.GREEN);
        return 1;
    }

    private static void dire(String message, ChatFormatting couleur) {
        var joueur = Minecraft.getInstance().player;
        if (joueur != null) {
            joueur.sendSystemMessage(Component.literal(message).withStyle(couleur));
        }
    }
}
