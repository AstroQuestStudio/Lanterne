package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.client.Minecraft;

/**
 * Les mains du client : le seul endroit où un paquet de tableau touche à l'interface.
 *
 * <h2>Pourquoi ce fichier existe, alors qu'il ne fait que déléguer</h2>
 *
 * <p>Il tient toutes les références clientes que {@link Easel} ne peut pas porter. Un serveur dédié
 * ne charge jamais cette classe : rien ne la nomme en dehors de {@link CanvasRenderer#register},
 * appelé uniquement derrière une garde de distribution.
 *
 * <p>C'est une couche d'indirection de vingt lignes, et elle a une raison d'être très concrète : sans
 * elle, le mod ne se charge pas sur un serveur dédié. Le vérificateur de la machine virtuelle résout
 * les types nommés dans les corps de méthodes au moment de préparer la classe, avant toute exécution.
 * Une garde {@code if (client)} autour d'un appel ne sauve donc rien si le type client est nommé
 * <em>dans la même classe</em>. Voir {@link Easel.Brushwork} pour le récit complet.
 *
 * <h2>Le fil</h2>
 *
 * <p>Toutes ces méthodes arrivent déjà sur le fil du client : {@code Easel} les enveloppe dans
 * {@code enqueueWork}. Elles peuvent donc toucher à la mosaïque et aux écrans sans précaution
 * supplémentaire — ce qui serait faux si elles étaient appelées depuis le fil réseau.
 */
public final class Hands implements Easel.Brushwork {
    private Hands() {}

    /** Branche les mains. Appelé uniquement côté client. */
    public static void install() {
        Easel.clientSide(new Hands());
    }

    @Override
    public void roll(PaintingRoll payload) {
        Hoard.accept(payload.plates(), payload.rules());
    }

    @Override
    public void shard(PaintingShard payload) {
        Hoard.absorb(payload);
    }

    @Override
    public void want(PaintingWant payload) {
        Hoard.grant(payload);
    }

    /**
     * Ouvre l'atelier sur la toile que le serveur désigne.
     *
     * <p>Les règles ne viennent pas du paquet mais du dernier catalogue reçu : elles sont déjà là
     * depuis la connexion, et les répéter à chaque ouverture n'aurait rien ajouté.
     */
    @Override
    public void open(PaintingOpen payload) {
        Minecraft.getInstance().gui.setScreen(new Frame(payload.entityId(), payload.hash(),
                payload.width(), payload.height(), payload.trim(), Hoard.rules()));
    }
}
