package fr.clubcitrouille.lanterne.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Le Réseau : un registre événementiel pur, jamais une simulation de déplacement.
 *
 * <h2>La décision d'architecture qui rend tout ça sans lag</h2>
 *
 * <p>Ce registre ne déplace jamais rien tout seul, et ne tourne à aucun rythme : il n'existe qu'au
 * moment où un {@code Aiguillage}, un {@code CoffreDepot} ou un {@code CoffreReception} entre ou sort
 * d'un chunk chargé (deux appels, {@link #register} et {@link #unregister}, tous deux O(1)), et au
 * moment où un joueur clique une entrée du {@code Guichet} ({@link #aggregate} puis {@link #withdraw},
 * tous deux un parcours LINÉAIRE des nœuds connus — quelques dizaines sur un serveur coopératif, rien
 * du tout). Il n'y a ni tick périodique, ni graphe de câbles à parcourir, ni file de hoppers à faire
 * avancer d'un bloc par tick façon vanilla : c'est précisément l'absence de cette simulation qui rend
 * une distance de cent blocs aussi gratuite qu'une distance d'un bloc. Ne JAMAIS réintroduire un tick
 * périodique ici — voir la Javadoc de classe de {@code content.reseau.Aiguillage} pour ce qui a été
 * délibérément laissé de côté pour tenir cette promesse.
 *
 * <h2>Pourquoi un compte de références, et pas un simple ensemble</h2>
 *
 * <p>Un {@code BlockPos} peut être annoncé par PLUSIEURS nœuds à la fois : un {@code CoffreDepot}
 * s'annonce lui-même, et un {@code Aiguillage} posé à côté peut annoncer CE MÊME coffre s'il le prend
 * pour son voisin unique. Un simple ensemble ferait disparaître le coffre du réseau dès que l'UN des
 * deux annonceurs se désenregistre, même si l'autre est toujours là et compte toujours dessus. Le
 * compteur ne retire une position qu'au moment où plus personne ne la réclame — c'est la seule façon
 * dont {@link #register}/{@link #unregister} restent des opérations locales et idempotentes, sans
 * qu'aucun nœud n'ait besoin de savoir si un autre annonce la même position que lui.
 *
 * <h2>Ce qui est délibérément PAS mis en cache</h2>
 *
 * <p>Seule la position est retenue ici, jamais le {@code BlockEntity} ni le {@code Container} qu'elle
 * désigne. Chaque appel à {@link #aggregate} ou {@link #withdraw} résout le conteneur réel à la demande,
 * via {@code level.getBlockEntity(pos)}, et l'oublie aussitôt l'appel terminé. Un {@code BlockPos} dont
 * le chunk est déchargé ou dont le bloc a été cassé sans passer par {@link #unregister} (un crash, une
 * commande de triche, un mod tiers qui manipule le monde directement) ne résout simplement plus rien
 * et est ignoré en silence — défense en profondeur, pas un cas qu'on attend de voir souvent.
 *
 * <h2>Aucune limite de distance, et ce n'est pas un oubli</h2>
 *
 * <p>{@link #withdraw} ne consulte jamais la position du joueur ni celle du {@code Guichet} qui a
 * déclenché la demande. C'est le point de tout ce design : la portée du réseau est celle du niveau
 * entier, parce que le coût d'une demande ne dépend QUE du nombre de nœuds connus, jamais de la
 * distance qui les sépare.
 */
public final class Reseau {
    /**
     * Un nœud par position, compté par le nombre de blocs qui l'annoncent en ce moment. La table
     * externe n'existe qu'en {@code ServerLevel} : le client ne connaît jamais ce registre, exactement
     * comme le catalogue de {@code content.painting.Easel} ne vit que d'un côté.
     */
    private static final Map<ServerLevel, Map<BlockPos, Integer>> NOEUDS = new HashMap<>();

    private Reseau() {}

    /**
     * Un nœud rejoint le réseau. Idempotent au sens où appeler ceci deux fois pour la même position
     * ne la fait pas apparaître deux fois dans {@link #aggregate} — mais il FAUT alors appeler
     * {@link #unregister} deux fois aussi pour qu'elle disparaisse ; voir la Javadoc de classe.
     */
    public static void register(ServerLevel level, BlockPos pos) {
        NOEUDS.computeIfAbsent(level, ignored -> new LinkedHashMap<>())
                .merge(pos.immutable(), 1, Integer::sum);
    }

    /** Un nœud quitte le réseau — à la casse du bloc qui l'annonçait, ou au déchargement de son chunk. */
    public static void unregister(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Integer> noeuds = NOEUDS.get(level);
        if (noeuds == null) {
            return;
        }
        noeuds.computeIfPresent(pos.immutable(), (ignored, compte) -> compte > 1 ? compte - 1 : null);
        if (noeuds.isEmpty()) {
            NOEUDS.remove(level);
        }
    }

    /** Vide le registre d'un niveau. Appelé à l'arrêt du serveur — voir {@code content.reseau.Reseaux}. */
    public static void clear() {
        NOEUDS.clear();
    }

    /**
     * Le total par type d'objet, sur tous les nœuds dont le conteneur résout encore.
     *
     * <p>Copie la liste des positions avant de parcourir : {@code level.getBlockEntity} ne devrait
     * jamais ré-entrer dans {@link #register}/{@link #unregister}, mais copier d'abord coûte une poignée
     * de références sur un registre qui n'en compte jamais que quelques dizaines, et met ce parcours à
     * l'abri d'une future surprise plutôt que de la découvrir en production.
     */
    public static Map<Item, Integer> aggregate(ServerLevel level) {
        Map<Item, Integer> total = new LinkedHashMap<>();
        for (BlockPos pos : positions(level)) {
            if (!(level.getBlockEntity(pos) instanceof Container conteneur)) {
                continue;
            }
            for (int i = 0; i < conteneur.getContainerSize(); i++) {
                ItemStack pile = conteneur.getItem(i);
                if (!pile.isEmpty()) {
                    total.merge(pile.getItem(), pile.getCount(), Integer::sum);
                }
            }
        }
        return total;
    }

    /**
     * Retire jusqu'à {@code quantite} exemplaires de {@code item}, trouvés n'importe où dans le réseau,
     * et les livre dans {@code destination}. Rend le nombre RÉELLEMENT obtenu — jamais plus que ce que
     * {@code destination} a pu accepter, jamais moins que ce que le réseau contenait si la place ne
     * manquait pas.
     *
     * <h2>Pourquoi ceci ne peut pas dupliquer un objet</h2>
     *
     * <p>Pour chaque pile source rencontrée, {@link #deposit} est appelé D'ABORD — il essaie de placer
     * la portion demandée dans {@code destination} et rend le nombre RÉELLEMENT accepté, en mutant
     * {@code destination} pour ce nombre-là et RIEN DE PLUS. Ce n'est qu'ENSUITE, avec ce nombre déjà
     * connu, que {@code source.removeItem(i, accepte)} retire EXACTEMENT cette quantité de la pile
     * source. Entre les deux appels il n'y a ni retour anticipé, ni attente, ni appel réseau : ce sont
     * deux instructions Java consécutives dans la même méthode, sur le même fil, et rien d'autre ne
     * peut s'exécuter entre les deux au cours du même tick serveur. Le total (source + destination) est
     * donc conservé à chaque pas, jamais seulement à la fin — une exception entre les deux appels
     * laisserait au pire {@code accepte} objets comptés deux fois dans les DEUX conteneurs à la fois
     * pendant une fraction d'instruction, jamais zéro fois ni disparus, et ce cas ne peut survenir que
     * pour une erreur qui ferait de toute façon planter le serveur.
     *
     * <p>{@code source == destination} est explicitement écarté : c'est ce qui permet à un
     * {@code CoffreReception} de s'auto-réapprovisionner sans jamais se vider dans lui-même, en passant
     * simplement SA PROPRE instance comme {@code destination} — voir sa Javadoc de classe.
     */
    public static int withdraw(ServerLevel level, Item item, int quantite, Container destination) {
        if (quantite <= 0) {
            return 0;
        }
        int obtenu = 0;
        for (BlockPos pos : positions(level)) {
            if (obtenu >= quantite) {
                break;
            }
            if (!(level.getBlockEntity(pos) instanceof Container source) || source == destination) {
                continue;
            }
            obtenu += drain(source, item, quantite - obtenu, destination);
        }
        return obtenu;
    }

    /** Puise jusqu'à {@code besoin} exemplaires de {@code item} dans {@code source}. Voir {@link #withdraw}. */
    private static int drain(Container source, Item item, int besoin, Container destination) {
        int obtenu = 0;
        for (int i = 0; i < source.getContainerSize() && obtenu < besoin; i++) {
            ItemStack pile = source.getItem(i);
            if (pile.isEmpty() || !pile.is(item)) {
                continue;
            }
            int tente = Math.min(besoin - obtenu, pile.getCount());
            int accepte = deposit(destination, pile.copyWithCount(tente));
            if (accepte <= 0) {
                // La destination refuse : elle refusera tout autant le reste de ce type d'objet.
                // Continuer à parcourir les autres piles de CETTE source ne coûterait rien de faux,
                // seulement du temps perdu — mais arrêter tout de suite est aussi bon marché et évite
                // de le refaire pour chaque pile restante.
                break;
            }
            source.removeItem(i, accepte);
            obtenu += accepte;
        }
        return obtenu;
    }

    /**
     * Essaie de placer {@code apport} dans {@code destination} — d'abord dans une pile déjà présente du
     * même objet, puis dans une case vide. Ne mute {@code destination} que pour ce qu'elle accepte
     * réellement, et rend ce nombre. {@code apport} lui-même n'est jamais modifié ni ne rejoint aucun
     * conteneur tel quel : il ne sert que de gabarit (objet, composants) pour ce qui est écrit.
     */
    private static int deposit(Container destination, ItemStack apport) {
        int restant = apport.getCount();
        int taille = destination.getContainerSize();
        for (int i = 0; i < taille && restant > 0; i++) {
            ItemStack presente = destination.getItem(i);
            if (presente.isEmpty() || !ItemStack.isSameItemSameComponents(presente, apport)
                    || !destination.canPlaceItem(i, apport)) {
                continue;
            }
            int place = Math.min(restant, destination.getMaxStackSize(presente) - presente.getCount());
            if (place <= 0) {
                continue;
            }
            presente.grow(place);
            restant -= place;
        }
        for (int i = 0; i < taille && restant > 0; i++) {
            if (!destination.getItem(i).isEmpty() || !destination.canPlaceItem(i, apport)) {
                continue;
            }
            int place = Math.min(restant, destination.getMaxStackSize(apport));
            destination.setItem(i, apport.copyWithCount(place));
            restant -= place;
        }
        int accepte = apport.getCount() - restant;
        if (accepte > 0) {
            destination.setChanged();
        }
        return accepte;
    }

    private static List<BlockPos> positions(ServerLevel level) {
        Map<BlockPos, Integer> noeuds = NOEUDS.get(level);
        return noeuds == null ? List.of() : new ArrayList<>(noeuds.keySet());
    }
}
