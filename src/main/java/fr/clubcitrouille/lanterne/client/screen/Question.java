package fr.clubcitrouille.lanterne.client.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * La question des médias distants, posée une fois, au moment où un contenu distant doit se charger.
 *
 * <p>Séparée de {@link Consent} parce que celle-ci est chargée aussi par un serveur dédié (elle
 * enregistre sa configuration) : un type client dans sa signature — {@code Screen} — y faisait
 * échouer le chargement du mod. Seul le code client nomme cette classe-ci.
 *
 * <p>Trois états, retenus par {@link Consent} : jamais répondu (on demande), autorisé, refusé (on ne
 * redemande plus ; l'écran d'un projecteur permet de changer d'avis). Au plus une question par
 * session tant qu'aucune réponse n'est donnée, et jamais par-dessus un écran déjà ouvert.
 */
public final class Question {
    private static boolean askedThisSession;
    private static final List<Runnable> WAITING = new ArrayList<>();

    private Question() {}

    /**
     * Pose la question si elle doit l'être. À appeler à chaque tick tant que la réponse manque : la
     * garde de session empêche toute répétition.
     *
     * @param what « une vidéo », « de la musique »…
     * @param host le domaine, dit au joueur
     * @param onYes relancé immédiatement si le joueur accepte, ou {@code null}
     */
    public static void ask(String what, String host, Runnable onYes) {
        if (Consent.answered() || askedThisSession) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() != null || client.player == null) {
            return;
        }
        if (onYes != null) {
            WAITING.add(onYes);
        }
        client.gui.setScreen(screen(what, host, null));
    }

    /** L'écran de la question, qui rend la main à {@code back} une fois répondu. */
    public static Screen screen(String what, String host, Screen back) {
        askedThisSession = true;
        Minecraft client = Minecraft.getInstance();
        return new ConfirmScreen(yes -> {
            Consent.allowRemote(yes);
            client.gui.setScreen(back);
            List<Runnable> ready = new ArrayList<>(WAITING);
            WAITING.clear();
            if (yes) {
                ready.forEach(Runnable::run);
            }
        }, Component.literal("Autoriser les médias distants ?"),
                Component.literal("Ce serveur diffuse " + what + " depuis "
                        + (host == null || host.isBlank() ? "Internet" : host) + ".\n\n"
                        + "Oui : ta machine se connecte à cet hôte (il verra ton adresse IP, comme "
                        + "n'importe quel site que tu visites).\nNon : rien ne se charge ; tu pourras "
                        + "changer d'avis dans l'écran d'un projecteur."),
                Component.literal("Oui, autoriser"),
                Component.literal("Non"));
    }
}
