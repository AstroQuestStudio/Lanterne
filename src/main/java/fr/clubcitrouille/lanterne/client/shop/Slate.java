package fr.clubcitrouille.lanterne.client.shop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.content.shop.Coin;
import fr.clubcitrouille.lanterne.content.shop.Quote;
import fr.clubcitrouille.lanterne.content.shop.StallSync;
import fr.clubcitrouille.lanterne.content.shop.TradeEcho;

/**
 * L'ardoise : l'écho, chez le client, de ce que le serveur a bien voulu dire.
 *
 * <h2>Ce que l'ardoise ne fait pas</h2>
 *
 * <p>Elle ne calcule aucun prix. Elle ne décide d'aucun refus. Elle n'a aucune autorité : tout ce
 * qu'elle contient vient d'un paquet, et le serveur refera de son côté le moindre calcul avant de
 * débiter quoi que ce soit. Un joueur qui modifierait cette classe ne gagnerait que le droit de voir
 * des prix faux — et de se les faire corriger au premier clic.
 *
 * <p>C'est la même séparation que {@code client.waypoint.Marks} : un miroir, pas une source.
 *
 * <h2>Le symbole vient du paquet, pas de la configuration</h2>
 *
 * <p>Le fichier de réglages de la boutique est de type SERVER. On pourrait compter sur NeoForge pour
 * le recopier chez le client, et cela marcherait — jusqu'au jour où quelqu'un le passe en COMMON, ou
 * bien sur un client qui rejoint un serveur dont la synchronisation a échoué. Le symbole voyage donc
 * avec les prix, dans le même paquet, et l'écran n'a aucune raison de lire une configuration.
 */
public final class Slate {
    private static final Map<Identifier, Quote> BOARD = new LinkedHashMap<>();

    private static String symbol = "¤";
    private static long balance;

    /** Le lot maximal du serveur. Sert au bouton « tout » ; le serveur le refera respecter. */
    private static int batch = 4096;

    /** Le dernier ticket reçu, et l'instant où il est arrivé — pour le faire pâlir. */
    private static TradeEcho ticket;
    private static long ticketAt;

    private Slate() {}

    /**
     * Le catalogue vient d'arriver : on remplace tout.
     *
     * <p>Remplacer plutôt que fusionner rend la désynchronisation silencieuse impossible — voir
     * {@code content.shop.StallSync}. Si le paquet demande l'ouverture, l'écran s'ouvre ici, sur le
     * fil du client, parce que c'est le seul endroit où l'on est certain que l'ardoise est déjà à
     * jour : ouvrir avant de remplir afficherait un catalogue vide pendant une image.
     */
    public static void accept(StallSync sync) {
        BOARD.clear();
        for (Quote quote : sync.quotes()) {
            BOARD.put(quote.item(), quote);
        }
        symbol = sync.symbol() == null || sync.symbol().isBlank() ? "¤" : sync.symbol();
        balance = sync.balance();
        batch = Math.max(1, sync.batch());
        if (sync.open()) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && !(client.screen instanceof Counter)) {
                client.setScreen(new Counter());
            }
        }
    }

    /** Un ticket de caisse : le solde, l'article touché, et le message à afficher. */
    public static void absorb(TradeEcho echo) {
        balance = echo.balance();
        Quote known = BOARD.get(echo.item());
        if (known != null && echo.buy() > 0L) {
            BOARD.put(echo.item(), new Quote(echo.item(), echo.buy(), echo.sell(), echo.trend(),
                    known.category(), echo.keep()));
        }
        ticket = echo;
        ticketAt = System.currentTimeMillis();
    }

    /**
     * Depuis combien de millisecondes le dernier ticket est arrivé.
     *
     * <p>Sert au clignotement de confirmation de l'écran. Sans lui, deux achats identiques à la
     * suite produisent deux fois le même message, au même endroit, de la même couleur — et le
     * second est rigoureusement invisible. Le joueur reclique, croyant que rien n'est parti. C'est
     * le défaut d'interface le plus courant des écrans de boutique, et il ne coûte qu'un
     * horodatage.
     *
     * @return l'âge en millisecondes, ou {@link Long#MAX_VALUE} s'il n'y a pas de ticket
     */
    public static long ticketAge() {
        return ticket == null ? Long.MAX_VALUE : System.currentTimeMillis() - ticketAt;
    }

    public static List<Quote> all() {
        return new ArrayList<>(BOARD.values());
    }

    public static Quote find(Identifier item) {
        return BOARD.get(item);
    }

    public static boolean empty() {
        return BOARD.isEmpty();
    }

    public static long balance() {
        return balance;
    }

    public static String symbol() {
        return symbol;
    }

    public static int batch() {
        return batch;
    }

    /** Un montant écrit avec le symbole que le <b>serveur</b> a choisi. */
    public static String money(long cents) {
        return Coin.amount(cents) + " " + symbol;
    }

    /** Le dernier ticket, ou {@code null} s'il n'y en a pas eu ou s'il a assez vécu. */
    public static TradeEcho ticket() {
        if (ticket == null) {
            return null;
        }
        if (System.currentTimeMillis() - ticketAt > 8000L) {
            ticket = null;
        }
        return ticket;
    }

    /** Efface le message — appelé quand le joueur fait autre chose, pour ne pas le laisser traîner. */
    public static void clearTicket() {
        ticket = null;
    }
}
