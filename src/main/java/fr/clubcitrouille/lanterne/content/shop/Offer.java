package fr.clubcitrouille.lanterne.content.shop;

import net.minecraft.resources.Identifier;

/**
 * Un article du catalogue de l'administrateur : une ancre, et rien d'autre.
 *
 * <h2>Ce qu'est une ancre</h2>
 *
 * <p>Les deux prix écrits ici ne sont pas les prix affichés : ce sont les prix <b>de référence</b>.
 * La dérive les fait bouger autour d'eux, dans une fourchette bornée, et c'est {@link Drift} qui
 * calcule le prix effectif. Un administrateur qui relit son fichier de boutique retrouve donc ses
 * chiffres inchangés, même après un mois de commerce — ce qui est la seule façon de garder la main
 * sur une économie vivante.
 *
 * <p>Le stock, lui, est infini. C'est volontaire et c'est le cœur du dispositif : une boutique
 * administrateur n'est pas un marchand, c'est un <b>étalon</b>. Tant qu'elle achète le fer à un prix
 * connu, aucun joueur ne peut vendre son fer beaucoup plus cher, et aucun ne peut en acheter beaucoup
 * moins cher. Elle ancre tous les prix du serveur sans avoir à les fixer.
 *
 * <h2>La règle qui gouverne le couple de prix</h2>
 *
 * <p>{@code sell} doit être <b>strictement inférieur</b> à {@code buy}, et d'au moins la marge
 * minimale du serveur. Un article où l'on rachète au prix de vente, ou à un centime en dessous, est
 * une machine à fabriquer de l'argent : acheter, revendre, recommencer. Elle tourne aussi vite qu'un
 * joueur clique, ou infiniment vite avec un automate. C'est le seul refus que {@link Stall} oppose
 * sans jamais de dérogation, y compris à un administrateur.
 *
 * @param item     l'objet, par son identifiant de registre
 * @param buy      ce que le joueur paie pour une unité, en centimes ; toujours strictement positif
 * @param sell     ce que la boutique paie pour une unité, en centimes ; <b>zéro signifie « non
 *                 racheté »</b>, ce qui est une décision d'équilibrage courante pour les objets que
 *                 les fermes produisent sans limite
 * @param category le rayon, pour le classement à l'écran ; jamais vide
 */
public record Offer(Identifier item, long buy, long sell, String category) {
    /** Longueur maximale d'un nom de rayon — au-delà, la colonne de gauche de l'écran déborde. */
    public static final int CATEGORY_LIMIT = 24;

    /** Le rayon de ce qui n'en a pas reçu. */
    public static final String LOOSE = "Divers";

    /** Vrai si la boutique reprend cet article. */
    public boolean bought() {
        return this.sell > 0L;
    }

    /** Un rayon sans espaces parasites, tronqué, jamais vide. */
    public static String cleanCategory(String raw) {
        if (raw == null) {
            return LOOSE;
        }
        String trimmed = raw.trim().replace(';', ' ').replace('\n', ' ').trim();
        if (trimmed.isEmpty()) {
            return LOOSE;
        }
        return trimmed.length() > CATEGORY_LIMIT ? trimmed.substring(0, CATEGORY_LIMIT) : trimmed;
    }
}
