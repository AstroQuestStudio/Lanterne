package fr.clubcitrouille.lanterne.client.reseau;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import fr.clubcitrouille.lanterne.client.trieur.TrieurRenderer;
import fr.clubcitrouille.lanterne.content.Contents;
import fr.clubcitrouille.lanterne.content.reseau.Reseaux;

/**
 * La part cliente du système de coffres/réseau : rien d'autre que l'enregistrement des cinq
 * renderers de {@link Lueur}.
 *
 * <h2>Une seule porte, comme {@code client.screen.Projection}</h2>
 *
 * <p>{@code content.trieur.Trieur} et {@code content.reseau.Reseaux} sont du code COMMUN, chargé sur
 * un serveur dédié comme sur un client : ils ne nomment donc jamais une classe de
 * {@code net.minecraft.client}. Cette classe-ci, elle, ne vit que côté client, et n'est appelée que
 * derrière la garde de distribution du constructeur du mod — voir la Javadoc de {@code Projection}
 * pour le {@code ClassNotFoundException} qu'un serveur dédié lève au premier manquement à cette
 * règle.
 */
public final class Renderers {
    private Renderers() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(Renderers::onRegisterRenderers);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(Contents.TRIEUR_ENTITY.get(), TrieurRenderer::new);
        event.registerBlockEntityRenderer(Reseaux.AIGUILLAGE_ENTITY.get(), AiguillageRenderer::new);
        event.registerBlockEntityRenderer(Reseaux.GUICHET_ENTITY.get(), GuichetRenderer::new);
        event.registerBlockEntityRenderer(Reseaux.COFFRE_DEPOT_ENTITY.get(), CoffreDepotRenderer::new);
        event.registerBlockEntityRenderer(Reseaux.COFFRE_RECEPTION_ENTITY.get(),
                CoffreReceptionRenderer::new);
    }
}
