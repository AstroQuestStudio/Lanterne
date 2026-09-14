package fr.clubcitrouille.lanterne.content.shop;

import java.util.UUID;

import net.minecraft.resources.Identifier;

/**
 * Le péage : ce que la boutique ne verse pas, et pourquoi elle ne le verse pas.
 *
 * <h2>Le défaut que ce fichier existe pour corriger</h2>
 *
 * <p>{@link Assay} démontre qu'aucune recette du jeu ne permet de <b>fabriquer</b> de l'argent : on
 * ne peut pas acheter des ingrédients, revendre le produit, et sortir gagnant. C'est nécessaire, et
 * c'est acquis. Mais cette démonstration ne dit <em>rien</em> du défaut qui tue réellement les
 * économies de serveur, et qui n'est pas une triche : <b>l'accumulation</b>.
 *
 * <p>Une ferme à fer automatique ne viole aucune inégalité. Elle verse simplement, heure après
 * heure, une somme qu'un joueur ordinaire ne peut pas approcher. La simulation hors du jeu
 * ({@code tools/Bourse.java}, vingt joueurs, trente-sept jours, les débits de fermes réelles) donne
 * le chiffre : <b>avec la dérive pour seul amortisseur, l'exploitant de fermes gagne soixante fois
 * ce que gagne un joueur occasionnel à l'heure de jeu</b>, et la fortune du plus riche atteint
 * seize cents fois la médiane. Le serveur n'a plus d'économie : il a un homme riche et vingt
 * figurants.
 *
 * <p>La dérive fait ce qu'elle peut — un article massivement vendu tombe à la moitié de son ancre —
 * mais elle est <b>bornée</b>, et c'est par construction : {@code tanh} sature. Passé le plancher de
 * moins cinquante pour cent, le débit d'une ferme n'est plus amorti du tout. La dérive protège les
 * <em>prix relatifs</em> ; elle ne protège pas de l'accumulation, et il ne faut pas lui demander ce
 * qu'elle ne peut pas donner.
 *
 * <h2>Les deux compteurs, et pourquoi il en faut deux</h2>
 *
 * <p>Chaque joueur porte deux compteurs de <b>recettes récentes</b>, en centimes, qui montent à
 * chaque vente et redescendent tout seuls :
 *
 * <ol>
 *   <li><b>le quota</b>, un compteur <em>par article</em> : ce que la boutique lui a repris de
 *       <em>cet</em> objet ;</li>
 *   <li><b>le débit</b>, un compteur <em>unique</em> : ce qu'elle lui a repris en tout.</li>
 * </ol>
 *
 * <p>Le premier seul ne suffit pas, et la simulation le montre : un exploitant qui possède dix
 * fermes différentes touche dix fois la franchise au plein tarif, et il ressort encore à seize fois
 * le joueur occasionnel. Le second seul ne suffit pas non plus — il frappe indistinctement celui qui
 * vend dix mille lingots d'un coup et celui qui vide un coffre de bric-à-brac. <b>Ensemble</b>, le
 * premier dit « diversifie » et le second dit « il y a une limite à ce qu'un marché absorbe ». Avec
 * les deux, le rapport tombe à neuf, et la masse monétaire à trente-sept jours passe de 3 790 970 ¤
 * à 1 027 091 ¤.
 *
 * <h2>La franchise — la décision qui fait toute la différence</h2>
 *
 * <p>La retenue vaut :
 *
 * <pre>    retenue = R · tanh( max(0, compteur − franchise) / franchise )</pre>
 *
 * <p>La première rédaction se passait de franchise, et elle était fausse. Une {@code tanh} nue a une
 * pente de 1 à l'origine : <b>elle mord dès la première unité vendue</b>. Le balayage de réglages
 * l'a montré sans appel — un nouveau venu qui vend six cents blocs de pierre dans sa semaine perdait
 * déjà un cinquième de sa recette, alors que le dispositif tout entier n'existe que pour toucher les
 * fermes.
 *
 * <p>Avec la franchise, tout ce qui reste sous le seuil est payé <b>plein tarif, sans exception</b>,
 * et la dégressivité ne commence qu'après. Le résultat mesuré est celui qu'on voulait : sur les
 * quatre profils simulés, <b>trois subissent exactement zéro centime de retenue</b> — l'occasionnel,
 * le mineur, le bâtisseur —, et seul l'exploitant de fermes est concerné. Le nouveau venu arrivé au
 * trentième jour gagne <b>exactement la même chose</b> qu'avec les puits éteints : 208 ¤ dans sa
 * première semaine, au centime près.
 *
 * <h2>La franchise est une valeur, pas un nombre d'objets</h2>
 *
 * <p>Compter en <em>unités</em> — « cinq cents lingots par jour » — se lit mieux, et c'est faux dès
 * qu'on regarde le catalogue : il contient le diamant à 240 ¤ et le pavé à 0,12 ¤. Une franchise en
 * unités laisse passer une ferme à diamants et étrangle un bâtisseur qui revend sa pierre
 * d'excavation. C'est exactement ce qu'a produit la première version — le bâtisseur simulé tombait à
 * un cinquième de sa recette pour avoir vendu des gravats.
 *
 * <p>La franchise est donc en <b>centimes</b>, et elle vaut la même chose pour les mille trois cents
 * articles du catalogue : {@code quota_franchise} pièces du même objet, quel qu'il soit. Un
 * administrateur règle <em>un</em> nombre au lieu d'une table.
 *
 * <h2>Le compteur suit le brut, jamais le net</h2>
 *
 * <p>Sans ce détail, le dispositif se saborde : plus la retenue est forte, moins le compteur monte,
 * donc moins la retenue est forte. Une boucle de retour qui ramollit précisément là où elle devrait
 * serrer. Le compteur suit donc <b>le cours du marché multiplié par les unités vendues</b>, c'est-à-dire
 * ce que la vente <em>valait</em>, et non ce qui a été versé.
 *
 * <h2>Ce que ce fichier ne peut pas casser</h2>
 *
 * <p>La retenue est un facteur compris entre 0 et 1 appliqué au <b>seul prix de rachat</b>. Elle ne
 * peut donc que le faire <em>baisser</em>. L'inégalité de sûreté de {@link Tariff#safety()} —
 * {@code rachat × facteur × (1+A)/(1−A) < 1}, vérifiée à 0,944 sur les 1 446 recettes du jeu — reste
 * vraie <em>a fortiori</em> : tout ce qui diminue le rachat éloigne du 1. Aucune boucle d'arbitrage
 * ne peut naître de ce fichier, et c'est une propriété structurelle, pas un test qu'il faudrait
 * refaire.
 *
 * <h2>Ce dont je ne suis pas sûr</h2>
 *
 * <ul>
 *   <li><b>Les comptes multiples.</b> Les compteurs sont par {@code UUID}. Un joueur qui possède
 *       deux comptes double sa franchise, et rien ici ne peut l'en empêcher — c'est une question de
 *       modération, pas d'arithmétique. La sanction reste que le second compte doit être connecté
 *       pour vendre.</li>
 *   <li><b>Les virements entre amis.</b> Le fermier peut payer trois joueurs pour vendre à sa place.
 *       La franchise devient alors quatre fois plus grande. {@code virement_frais_pourcent} existe
 *       pour rendre l'opération coûteuse, mais il est <b>éteint par défaut</b> : sur un serveur
 *       d'amis, taxer l'entraide est un remède pire que le mal. À allumer si le cas se présente.</li>
 *   <li><b>La masse monétaire croît toujours.</b> Les puits divisent sa pente par quatre ; ils ne
 *       l'annulent pas. L'annuler demanderait un prélèvement sur les <em>soldes</em>, et je ne l'ai
 *       pas fait — voir la note de {@code NOTES-SHOP.md} sur la raison, qui tient en une phrase :
 *       la boutique <b>ancre</b> les prix, donc une masse qui grossit n'érode le pouvoir d'achat de
 *       personne.</li>
 * </ul>
 */
public final class Toll {
    /** Rien n'est retenu : le joueur touche le cours entier. */
    public static final int FULL = 1000;

    private Toll() {}

    /**
     * La retenue, en pour mille, pour un compteur donné.
     *
     * <p>Rendue publique parce que l'écran s'en sert pour écrire « −34 % » sans avoir à refaire le
     * raisonnement, et parce qu'un lecteur qui veut vérifier la formule doit pouvoir la lire sans
     * traverser trois classes.
     *
     * @param counter   les recettes récentes, en centimes
     * @param allowance la franchise, en centimes ; zéro ou moins désactive la retenue
     * @param most      la retenue maximale, en pourcent
     */
    public static int withhold(long counter, long allowance, int most) {
        if (allowance <= 0L || most <= 0 || counter <= allowance) {
            return 0;
        }
        double beyond = (double) (counter - allowance) / (double) allowance;
        int permille = (int) Math.round(most / 100.0 * Math.tanh(beyond) * 1000.0);
        return Math.max(0, Math.min(1000, permille));
    }

    /**
     * Le pour mille du cours que ce joueur touche réellement sur cet article.
     *
     * <p>Le chemin rapide n'est pas une optimisation gratuite : {@link Bazaar#sync} appelle cette
     * méthode une fois <b>par article du catalogue</b>, soit mille trois cents fois, à chaque
     * ouverture de l'écran. Un joueur qui n'a rien vendu depuis des heures — le cas courant — n'a
     * aucun compteur, et l'on ressort alors sans avoir calculé une seule tangente hyperbolique.
     */
    public static int keep(Ledger ledger, UUID who, Identifier item) {
        if (ledger == null || who == null) {
            return FULL;
        }
        boolean quota = Tariff.quotaOn();
        boolean debit = Tariff.debitOn();
        if (!quota && !debit) {
            return FULL;
        }
        long total = ledger.takings(who);
        if (total <= 0L) {
            return FULL;
        }
        int keep = FULL;
        if (quota) {
            keep = keep * (1000 - withhold(ledger.takings(who, item),
                    Tariff.quotaAllowance(), Tariff.quotaMost())) / 1000;
        }
        if (debit) {
            keep = keep * (1000 - withhold(total, Tariff.debitAllowance(), Tariff.debitMost()))
                    / 1000;
        }
        return Math.max(0, Math.min(FULL, keep));
    }

    /**
     * Le prix net, retenue faite.
     *
     * <p>Le plancher à un centime est délibéré : un article dont le prix net tomberait à zéro
     * cesserait d'être rachetable, et la caisse répondrait « la boutique ne reprend pas cet
     * article » — un message faux, et incompréhensible pour qui vient d'en vendre mille. Mieux vaut
     * un centime symbolique, qui dit la même chose sans mentir.
     *
     * <p>Arithmétique entière, dans cet ordre : la multiplication d'abord, la division ensuite.
     * L'inverse perdrait toute la précision sur les petits prix. Le produit vaut au plus
     * 10<sup>9</sup> × 10<sup>3</sup>, très loin des bornes d'un {@code long}.
     *
     * @param market le cours du marché, en centimes — ce que {@link Drift#sell} a rendu
     * @param keep   le pour mille rendu par {@link #keep}
     */
    public static long net(long market, int keep) {
        if (market <= 0L) {
            return 0L;
        }
        if (keep >= FULL) {
            return market;
        }
        return Math.max(1L, market * Math.max(0, keep) / FULL);
    }

    /**
     * Les frais d'un virement entre joueurs, en centimes.
     *
     * <p><b>Éteint par défaut</b>, et c'est un choix, pas un oubli. Sur un serveur de vingt
     * personnes qui se connaissent, l'entraide est ce qui fait tenir le groupe, et la taxer pour
     * fermer une porte que presque personne n'emprunte coûterait plus qu'elle ne rapporte. Le
     * réglage existe pour le jour où quelqu'un s'apercevra qu'il peut contourner sa franchise en
     * faisant vendre trois amis à sa place — et ce jour-là, il suffira d'un nombre.
     */
    public static long fee(long amount) {
        int percent = Tariff.payFee();
        if (percent <= 0 || amount <= 0L) {
            return 0L;
        }
        return Math.min(amount, amount * percent / 100L);
    }

}
