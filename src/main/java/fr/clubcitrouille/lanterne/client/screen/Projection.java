package fr.clubcitrouille.lanterne.client.screen;

import net.minecraft.client.Minecraft;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import fr.clubcitrouille.lanterne.content.screen.Mail;
import fr.clubcitrouille.lanterne.content.screen.Screens;

/**
 * La part cliente de la projection : ce qui n'existe que là où il y a un écran.
 *
 * <h2>Pourquoi tout passe par cette classe et par elle seule</h2>
 *
 * <p>Le code commun de {@code content.screen} ne nomme <b>aucune</b> classe cliente. Il déclare une
 * interface — {@code Screens.Projectionist} — et un serveur dédié en garde l'implémentation muette.
 * C'est cette classe qui s'annonce, et elle n'est appelée que derrière une garde de distribution
 * dans la classe principale du mod.
 *
 * <p>Ce n'est pas du zèle. La leçon est écrite en toutes lettres dans {@code content.painting} : un
 * serveur dédié a déjà planté sur un {@code ClassNotFoundException: Screen}, parce qu'un corps de
 * lambda est compilé dans une méthode de sa classe englobante et chargé avec elle. Il suffit qu'une
 * classe commune <em>mentionne</em> un écran de jeu pour qu'un serveur sans client s'arrête au
 * démarrage. La garde de distribution ne protège que si personne ne la contourne, et la seule façon
 * de s'en assurer est qu'il n'existe qu'une porte.
 */
public final class Projection implements Screens.Projectionist {
    private Projection() {}

    public static void register(IEventBus modBus) {
        Screens.clientSide(new Projection());
        modBus.addListener(Projection::onRegisterRenderers);

        // Le tour de ronde du décodeur : une fois par tick client. Quand rien ne joue — c'est-à-dire
        // presque toujours — cet appel parcourt une table vide.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> Gaze.pump());

        // Quitter un serveur rend les pellicules et ferme les bobines. Sans cela, la mémoire vidéo
        // resterait retenue pendant tout le temps passé dans les menus, et les connexions sortantes
        // vers les hôtes du serveur qu'on vient de quitter resteraient ouvertes.
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> Gaze.forget());
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(Screens.PANEL_ENTITY.get(), PanelRenderer::new);
        event.registerBlockEntityRenderer(Screens.BEAMER_ENTITY.get(), BeamerRenderer::new);
    }

    /**
     * Le serveur a validé le verrou : on ouvre.
     *
     * <p>Jamais de l'initiative du client. Voir {@code Mail.Open} — un refus doit arriver avant le
     * travail, et chez celui qui détient la clé.
     */
    @Override
    public void open(Mail.Open payload) {
        // « gui.setScreen » et non « Minecraft.setScreen » : en 26.2 l'ouverture d'un écran est
        // passée sous la responsabilité du gestionnaire d'interface, et la méthode de Minecraft qui
        // reste — « setScreenAndShow » — sert au chemin de démarrage. Suivre ce que fait
        // ClientPacketListener pour les écrans de conteneur est le seul repère fiable ici.
        Minecraft.getInstance().gui.setScreen(new Console(payload.pos()));
    }

    @Override
    public void rules(Mail.Rules payload) {
        Gaze.accept(payload);
    }
}
