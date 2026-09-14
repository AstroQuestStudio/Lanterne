package fr.clubcitrouille.lanterne.content.shop;

/**
 * La monnaie : une seule unité, entière, et la façon de l'écrire.
 *
 * <h2>Pourquoi des centimes entiers et jamais un flottant</h2>
 *
 * <p>Un {@code double} ne peut pas représenter exactement 0,10. Additionner dix fois dix centimes en
 * virgule flottante donne 0,9999999999999999, et un solde qui a passé une soirée à s'additionner
 * finit par ne plus être égal à lui-même. Sur une économie de serveur, ce n'est pas un détail
 * cosmétique : c'est la porte ouverte à la pièce qui apparaît ou disparaît à l'arrondi, et donc à un
 * duplicateur d'argent que personne ne saura reproduire à la demande.
 *
 * <p>Tout est donc compté en <b>centimes</b>, dans un {@code long}. Une pièce vaut cent centimes. Les
 * quatre opérations restent exactes, les comparaisons aussi, et la seule conversion en décimal a lieu
 * au dernier moment, pour l'affichage. Le fichier de la boutique, lui, est écrit en décimal parce
 * qu'un humain l'édite — et il est relu par {@link #parse}, qui n'emploie pas non plus de flottant.
 *
 * <h2>Les plafonds, et à quoi ils servent</h2>
 *
 * <p>{@link #PRICE_CEILING} et {@link #CEILING} ne sont pas des limites de confort : ce sont les
 * bornes qui rendent {@code Math.multiplyExact} inutile dans les chemins chauds. Un prix au plus égal
 * à 10<sup>9</sup> centimes, multiplié par un lot au plus égal à 10<sup>4</sup> unités, tient dans
 * 10<sup>13</sup> — très loin des 9,2 × 10<sup>18</sup> d'un {@code long}. La caisse emploie tout de
 * même les opérations vérifiées : c'est de la ceinture avec des bretelles, et l'une des deux suffit.
 */
public final class Coin {
    /** Ce que {@link #parse} rend quand le texte n'est pas un montant. */
    public static final long BAD = Long.MIN_VALUE;

    /** Cent centimes font une pièce. */
    public static final long UNIT = 100L;

    /** Prix unitaire maximal, en centimes — dix millions de pièces. */
    public static final long PRICE_CEILING = 1_000_000_000L;

    /** Solde maximal, en centimes — dix mille milliards de pièces. */
    public static final long CEILING = 1_000_000_000_000_000L;

    private Coin() {}

    /** Le montant suivi du symbole de la monnaie : {@code "1 234,50 ¤"}. */
    public static String say(long cents) {
        return amount(cents) + " " + Tariff.symbol();
    }

    /**
     * Le montant seul, groupé par milliers, sans symbole.
     *
     * <p>La partie décimale est omise quand elle est nulle : « 480 » se lit mieux que « 480,00 », et
     * la grille de la boutique en contient plusieurs dizaines à la fois.
     */
    public static String amount(long cents) {
        long safe = cents == Long.MIN_VALUE ? Long.MIN_VALUE + 1 : cents;
        boolean negative = safe < 0;
        long abs = Math.abs(safe);
        long whole = abs / UNIT;
        long frac = abs % UNIT;
        StringBuilder out = new StringBuilder(24);
        if (negative) {
            out.append('-');
        }
        group(out, whole);
        if (frac != 0L) {
            out.append(',');
            if (frac < 10L) {
                out.append('0');
            }
            out.append(frac);
        }
        return out.toString();
    }

    /**
     * Le montant tel qu'il est écrit dans le fichier de la boutique : deux décimales, un point,
     * aucun groupement.
     *
     * <p>Volontairement différent de {@link #amount} : ce que lit un humain et ce que relit un
     * analyseur n'ont pas les mêmes contraintes, et confondre les deux finit toujours par un fichier
     * qu'on ne sait plus relire parce qu'il contient des espaces insécables.
     */
    public static String plain(long cents) {
        long abs = Math.abs(cents);
        return (cents < 0 ? "-" : "") + (abs / UNIT) + "." + (abs % UNIT < 10 ? "0" : "")
                + (abs % UNIT);
    }

    private static void group(StringBuilder out, long whole) {
        String digits = Long.toString(whole);
        int lead = digits.length() % 3;
        if (lead == 0) {
            lead = 3;
        }
        out.append(digits, 0, lead);
        for (int at = lead; at < digits.length(); at += 3) {
            out.append(' ').append(digits, at, at + 3);
        }
    }

    /**
     * Lit un montant écrit par un humain, et rend des centimes.
     *
     * <p>Accepte la virgule comme le point, les espaces de groupement, le tiret bas, un signe. Refuse
     * tout le reste, y compris trois décimales — accepter « 0,005 » obligerait à décider ce qu'on en
     * fait, et toute réponse à cette question crée un arrondi que quelqu'un finira par exploiter.
     *
     * @return les centimes, ou {@link #BAD} si le texte n'est pas un montant
     */
    public static long parse(String raw) {
        if (raw == null) {
            return BAD;
        }
        // Les separateurs sont retires plutot que refuses : un montant colle depuis un tableur
        // arrive avec des espaces de groupement, et trois d'entre eux ne se voient pas dans un
        // editeur. Ils sont donc ecrits en echappements, et filtres caractere par caractere.
        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ' ' || c == '_' || c == '\u00A0' || c == '\u202F') {
                continue;
            }
            cleaned.append(c == ',' ? '.' : c);
        }
        String text = cleaned.toString().trim();
        if (text.isEmpty()) {
            return BAD;
        }
        boolean negative = text.charAt(0) == '-';
        if (negative || text.charAt(0) == '+') {
            text = text.substring(1);
        }
        if (text.isEmpty()) {
            return BAD;
        }
        int dot = text.indexOf('.');
        String wholePart = dot < 0 ? text : text.substring(0, dot);
        String fracPart = dot < 0 ? "" : text.substring(dot + 1);
        if (fracPart.indexOf('.') >= 0 || fracPart.length() > 2) {
            return BAD;
        }
        if (wholePart.isEmpty()) {
            wholePart = "0";
        }
        if (!digits(wholePart) || !digits(fracPart)) {
            return BAD;
        }
        while (fracPart.length() < 2) {
            fracPart = fracPart + "0";
        }
        long whole;
        long frac;
        try {
            whole = Long.parseLong(wholePart);
            frac = Long.parseLong(fracPart);
        } catch (NumberFormatException tooLong) {
            return BAD;
        }
        if (whole > CEILING / UNIT) {
            return BAD;
        }
        long cents = whole * UNIT + frac;
        return negative ? -cents : cents;
    }

    /** Vrai si la chaîne ne contient que des chiffres. La chaîne vide passe : elle vaut zéro. */
    private static boolean digits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
