package fr.clubcitrouille.lanterne.client.ponder;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;

import org.jspecify.annotations.Nullable;

/**
 * L'ouvreur : par où l'on entre dans le guide.
 *
 * <h2>Pourquoi une commande <em>cliente</em></h2>
 *
 * <p>Create ouvre son guide depuis JEI, en maintenant une touche sur un objet de l'inventaire. C'est
 * la plus belle porte qui soit — on y arrive au moment exact où l'on se demande à quoi sert l'objet
 * qu'on regarde — et Lanterne n'a pas JEI en dépendance, ni ne veut en prendre une pour ouvrir un
 * écran.
 *
 * <p>Ponder, lui, expose aussi une commande, mais elle est <b>serveur</b> : elle doit envoyer un
 * paquet au client pour qu'il ouvre l'écran, parce qu'en 1.21 il n'y avait pas mieux. NeoForge 26.2
 * donne {@link RegisterClientCommandsEvent}, donc une commande qui ne quitte jamais le client. Pas de
 * paquet, pas de permission, pas de code commun qui nommerait un écran — et donc aucun risque pour un
 * serveur dédié, qui ne chargera jamais ce fichier.
 *
 * <h2>Pourquoi l'ouverture est différée d'un tick</h2>
 *
 * <p>C'est le seul piège de ce fichier, et il est invisible à la lecture. Quand une commande part, le
 * jeu <b>referme la fenêtre de discussion après l'avoir exécutée</b> — et la refermer, c'est poser
 * l'écran courant à {@code null}. Un écran ouvert depuis le corps de la commande serait donc balayé
 * dans la foulée, et la commande paraîtrait ne rien faire.
 *
 * <p>On note donc la demande, et on l'honore au premier tick où plus aucun écran n'est ouvert. C'est
 * le même mécanisme que {@code client/waypoint/Opener.java}, pour une raison voisine.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Usher {
    private Usher() {}

    private static boolean asked;
    private static @Nullable String wanted;

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lanterne")
                .then(Commands.literal("guide")
                        .executes(context -> ask(null))
                        .then(Commands.argument("sujet", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(Theatre.subjects(), builder))
                                .executes(context ->
                                        ask(StringArgumentType.getString(context, "sujet"))))));
    }

    private static int ask(@Nullable String subject) {
        wanted = subject;
        asked = true;
        return 1;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!asked) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() != null) {
            // La discussion n'est pas encore refermée. On repassera au prochain tick.
            return;
        }
        asked = false;
        String subject = wanted;
        wanted = null;
        if (subject == null) {
            Theatre.open(null);
        } else {
            Theatre.open(null, subject);
        }
    }
}
