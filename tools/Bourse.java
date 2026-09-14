import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * La bourse : vingt joueurs, un mois, hors du jeu.
 *
 * <p>Ce programme ne fait pas partie du mod. Il vit dans {@code tools/}, comme {@code Iso} et
 * {@code ItemTex}, il ne dépend de rien, et il se lance à la main :
 *
 * <pre>
 *     cd tools
 *     javac -encoding UTF-8 Bourse.java
 *     java Bourse            # AVANT / APRÈS sur 37 jours, et la comparaison
 *     java Bourse --long     # le même sur 90 jours
 *     java Bourse --balayage # le balayage des réglages, 120 lignes
 * </pre>
 *
 * <h2>Pourquoi il existe</h2>
 *
 * <p>L'audit d'arbitrage de {@code content.shop.Assay} démontre qu'on ne peut pas <b>fabriquer</b>
 * de l'argent par une boucle d'artisanat. C'est nécessaire, et c'est fait. Mais cela ne dit
 * strictement rien du défaut qui tue réellement les économies de serveur : <b>l'accumulation</b>.
 * Une ferme à fer automatique ne triche pas, elle ne viole aucune inégalité — elle verse simplement,
 * heure après heure, une somme qu'aucun joueur ordinaire ne peut approcher, et au bout d'un mois
 * l'écart entre deux joueurs se compte en ordres de grandeur.
 *
 * <p>La dérive des prix amortit une partie du choc : un article massivement vendu perd de la valeur.
 * La question est de savoir <em>combien</em>, et la réponse n'est pas de l'ordre de l'intuition. Ce
 * programme la calcule.
 *
 * <h2>Ce qui est reproduit exactement, et ce qui ne l'est pas</h2>
 *
 * <p><b>Exactement</b> : l'arithmétique entière en centimes de {@code Coin}, la formule de dérive de
 * {@code Drift} avec ses bornes, la détente et le bornage des volumes de {@code Ledger}, la
 * retenue de {@code Toll}, et l'ordre des opérations de {@code Till} — le prix est arrêté avant la
 * transaction et vaut pour tout le lot.
 *
 * <p><b>Approché</b> : les joueurs. Aucun modèle de joueur n'est vrai. Les débits retenus ici sont
 * ceux de fermes réelles et documentées, et volontairement pris dans le haut de la fourchette : un
 * réglage qui tient contre un joueur plus productif que le pire joueur réel tiendra contre le vrai.
 * Ce qui n'est pas modélisé est écrit à la fin du fichier.
 */
public final class Bourse {

    // ======================================================================
    //  La monnaie — copie de content.shop.Coin
    // ======================================================================

    static final long UNIT = 100L;

    static String sou(long cents) {
        return String.format(Locale.ROOT, "%,.2f", cents / 100.0).replace(',', ' ')
                .replace('.', ',');
    }

    static String piece(long cents) {
        long pieces = cents / UNIT;
        return String.format(Locale.ROOT, "%,d", pieces).replace(',', ' ');
    }

    // ======================================================================
    //  Les réglages — copie de content.shop.Tariff
    // ======================================================================

    /** Amplitude de la dérive, en pourcent. */
    static int amplitude = 50;
    /** Élasticité, en unités. */
    static int elasticite = 2048;
    /** Part du volume effacée à chaque détente, en pour mille. */
    static int detente = 60;
    /** Période de détente, en minutes de serveur. */
    static final int DETENTE_MINUTES = 5;

    /** Le quota dégressif par joueur ET PAR ARTICLE. */
    static boolean quotaActif = true;
    /** Q : la franchise, en PIÈCES, d'un même article. */
    static int quotaPalier = 4_000;
    /** D : la retenue maximale du quota, en pourcent. */
    static int quotaRetenue = 90;

    /** Le débit dégressif par joueur, TOUS ARTICLES CONFONDUS. */
    static boolean debitActif = true;
    /** R : le revenu récent, en PIÈCES, au-delà duquel la retenue est bien installée. */
    static int debitPalier = 20_000;
    /** La retenue maximale du débit, en pourcent. */
    static int debitRetenue = 60;

    /**
     * Part des compteurs de puits effacée à chaque détente, en pour mille.
     *
     * <p>C'est le réglage le moins visible et le plus décisif : il fixe la <b>période</b> sur
     * laquelle le quota compte. À 20 pour mille toutes les 5 minutes, la demi-vie est de trois
     * heures et le compteur est remis à neuf entre deux soirées — le quota devient « par session »,
     * c'est-à-dire presque rien pour qui joue tous les jours. À 2 pour mille, la demi-vie est de
     * vingt-neuf heures : le quota devient « par jour », et il s'accumule sur celui qui vend tous
     * les jours. C'est ce second régime qui répond aux fermes.
     */
    static int puitsDetente = 2;

    /** Part du solde qu'un joueur garde toujours en réserve, en pourcent. */
    static int reserve = 20;

    static void tousPuitsEteints() {
        quotaActif = false;
        debitActif = false;
    }

    // ======================================================================
    //  La dérive — copie de content.shop.Drift
    // ======================================================================

    static double facteur(long volume) {
        if (volume == 0L) {
            return 1.0;
        }
        return 1.0 + amplitude / 100.0 * Math.tanh((double) volume / elasticite);
    }

    static long prixAchat(Art art, long volume) {
        return Math.max(1L, Math.min(1_000_000_000L, Math.round(art.achat * facteur(volume))));
    }

    /** Le cours du marché, avant toute retenue personnelle. */
    static long prixRachat(Art art, long volume) {
        if (art.rachat <= 0L) {
            return 0L;
        }
        long prix = Math.max(1L, Math.min(1_000_000_000L, Math.round(art.rachat * facteur(volume))));
        return Math.max(0L, Math.min(prix, prixAchat(art, volume) - 1L));
    }

    // ======================================================================
    //  Les puits — copie de content.shop.Toll
    // ======================================================================

    /**
     * La retenue, en pour mille, au-delà d'une franchise.
     *
     * <pre>    retenue = D · tanh( max(0, compteur − franchise) / franchise )</pre>
     *
     * <p><b>La franchise est le point important, et la première rédaction s'en passait.</b> Une
     * {@code tanh} nue a une pente de 1 à l'origine : elle mord dès la première unité vendue. Le
     * balayage l'a montré sans appel — le nouveau venu, qui ne vend que six cents blocs de pierre
     * par semaine, perdait déjà un cinquième de sa recette, alors que le but était de ne toucher
     * que les fermes. Avec la franchise, tout ce qui reste sous le seuil est payé plein tarif, et
     * la dégressivité ne commence qu'après. C'est aussi ce qu'un joueur comprend sans explication :
     * « tant d'unités par jour au prix affiché, et au-delà ça baisse ».
     */
    static int retenuePourMille(long compteur, long franchise, int maximum) {
        if (franchise <= 0L || compteur <= franchise) {
            return 0;
        }
        double au_dela = (double) (compteur - franchise) / franchise;
        return (int) Math.round(maximum / 100.0 * Math.tanh(au_dela) * 1000.0);
    }

    /** Le pour mille du cours que CE joueur touche réellement sur CET article. */
    static int part(Joueur joueur, int article) {
        int garde = 1000;
        if (quotaActif) {
            garde = garde * (1000 - retenuePourMille(joueur.quota[article],
                    (long) quotaPalier * UNIT, quotaRetenue)) / 1000;
        }
        if (debitActif) {
            garde = garde * (1000 - retenuePourMille(joueur.debit, (long) debitPalier * UNIT,
                    debitRetenue)) / 1000;
        }
        return Math.max(0, Math.min(1000, garde));
    }

    /** Le prix net, retenues faites. Jamais zéro tant que le cours ne l'est pas. */
    static long net(long cours, int part) {
        if (cours <= 0L) {
            return 0L;
        }
        return Math.max(1L, cours * part / 1000L);
    }

    // ======================================================================
    //  Le catalogue — un panier représentatif, aux vrais prix de Seam/Assay
    // ======================================================================

    /**
     * Un article.
     *
     * @param nom    l'identifiant, pour l'affichage
     * @param achat  l'ancre d'achat, en centimes — la valeur réelle du catalogue engendré
     * @param rachat l'ancre de rachat : 25 % de l'achat, comme le générateur la pose
     */
    record Art(String nom, long achat, long rachat) {
        static Art de(String nom, long achat) {
            return new Art(nom, achat, achat * 25 / 100);
        }
    }

    // Les prix sont ceux de la table Seam, ou ceux qu'Assay en déduit (ingrédients x 1,20).
    static final Art[] CATALOGUE = {
        /*  0 */ Art.de("iron_ingot", 1440),      // raw_iron 1200 fondu
        /*  1 */ Art.de("gold_ingot", 2880),      // raw_gold 2400 fondu
        /*  2 */ Art.de("copper_ingot", 720),     // raw_copper 600 fondu
        /*  3 */ Art.de("coal", 400),
        /*  4 */ Art.de("redstone", 150),
        /*  5 */ Art.de("gunpowder", 600),
        /*  6 */ Art.de("bone", 200),
        /*  7 */ Art.de("string", 200),
        /*  8 */ Art.de("rotten_flesh", 60),
        /*  9 */ Art.de("ender_pearl", 4000),
        /* 10 */ Art.de("wheat", 150),
        /* 11 */ Art.de("sugar_cane", 120),
        /* 12 */ Art.de("melon_slice", 80),
        /* 13 */ Art.de("bamboo", 40),
        /* 14 */ Art.de("beef", 300),
        /* 15 */ Art.de("diamond", 24000),
        /* 16 */ Art.de("emerald", 12000),
        /* 17 */ Art.de("amethyst_shard", 2000),
        /* 18 */ Art.de("slime_ball", 600),
        /* 19 */ Art.de("honeycomb", 800),
        /* 20 */ Art.de("cobblestone", 50),
        /* 21 */ Art.de("stone", 60),
        /* 22 */ Art.de("stone_bricks", 72),
        /* 23 */ Art.de("oak_planks", 75),
        /* 24 */ Art.de("glass", 72),
        /* 25 */ Art.de("sand", 60),
        /* 26 */ Art.de("white_wool", 960),
        /* 27 */ Art.de("deepslate", 60),
    };

    static final int ARTICLES = CATALOGUE.length;

    // ======================================================================
    //  Les joueurs
    // ======================================================================

    enum Profil { OCCASIONNEL, MINEUR, FERMIER, BATISSEUR }

    static final class Joueur {
        final String nom;
        final Profil profil;
        /** Heures de jeu par jour. */
        final double heures;
        /** Unités vendues par heure de jeu, article par article. */
        final double[] vend = new double[ARTICLES];
        /** Unités souhaitées à l'achat par heure de jeu, article par article. */
        final double[] achete = new double[ARTICLES];

        long solde;
        /** Le compteur de quota, article par article — centimes vendus récemment, au cours. */
        final long[] quota = new long[ARTICLES];
        /** Le compteur de débit — centimes touchés récemment, tous articles confondus. */
        long debit;

        long gagne;
        long depense;
        long retenu;
        /** L'instant (en pas de 5 minutes) où ce joueur entre dans la partie. */
        int arrivee;

        Joueur(String nom, Profil profil, double heures, long solde, int arrivee) {
            this.nom = nom;
            this.profil = profil;
            this.heures = heures;
            this.solde = solde;
            this.arrivee = arrivee;
        }

        void vendra(int article, double parHeure) {
            this.vend[article] = parHeure;
        }

        void achetera(int article, double parHeure) {
            this.achete[article] = parHeure;
        }
    }

    /**
     * Le peuplement.
     *
     * <p>Quatre profils, vingt joueurs. Les débits sont ceux de fermes réelles, pris dans le haut de
     * la fourchette : un tueur à mobs en donjon rend 500 à 1 500 butins par heure, une ferme à fer à
     * plusieurs modules 400 à 800 lingots, un portail à or du Nether 300 à 900. Le fermier est de
     * surcroît supposé laisser tourner ses machines pendant qu'il fait autre chose, ce qui est la
     * situation normale : son débit horaire est donc le double du débit nominal.
     */
    static List<Joueur> peupler() {
        List<Joueur> monde = new ArrayList<>();
        long depart = 100L * UNIT;

        // --- Les occasionnels : une heure par soir, un peu de tout ---------
        for (int i = 1; i <= 8; i++) {
            Joueur j = new Joueur("occasionnel" + i, Profil.OCCASIONNEL, 1.0, depart, 0);
            j.vendra(0, 15);      // fer
            j.vendra(3, 40);      // charbon
            j.vendra(10, 80);     // blé
            j.vendra(14, 25);     // bœuf
            j.vendra(20, 120);    // pierre de taille
            j.achetera(23, 64);   // planches
            j.achetera(24, 32);   // verre
            j.achetera(22, 64);   // briques
            monde.add(j);
        }

        // --- Les mineurs : trois heures, du minerai ------------------------
        for (int i = 1; i <= 6; i++) {
            Joueur j = new Joueur("mineur" + i, Profil.MINEUR, 3.0, depart, 0);
            j.vendra(0, 90);      // fer
            j.vendra(1, 25);      // or
            j.vendra(2, 120);     // cuivre
            j.vendra(3, 250);     // charbon
            j.vendra(4, 200);     // redstone
            j.vendra(15, 5);      // diamant
            j.vendra(16, 1);      // émeraude
            j.vendra(17, 20);     // améthyste
            j.vendra(27, 300);    // ardoise des abîmes
            j.achetera(23, 32);
            j.achetera(14, 16);
            monde.add(j);
        }

        // --- Les fermiers : le vrai sujet ----------------------------------
        for (int i = 1; i <= 3; i++) {
            Joueur j = new Joueur("fermier" + i, Profil.FERMIER, 4.0, depart, 0);
            j.vendra(0, 700);     // ferme à fer
            j.vendra(1, 900);     // portail à or
            j.vendra(5, 300);     // poudre à canon
            j.vendra(6, 500);     // os
            j.vendra(7, 500);     // fil
            j.vendra(8, 700);     // chair putréfiée
            j.vendra(11, 1400);   // canne à sucre
            j.vendra(12, 900);    // pastèque
            j.vendra(13, 2500);   // bambou
            j.vendra(19, 60);     // rayon de miel
            j.achetera(22, 128);
            monde.add(j);
        }

        // --- Les bâtisseurs : ils achètent, et c'est là que l'argent meurt --
        for (int i = 1; i <= 3; i++) {
            Joueur j = new Joueur("batisseur" + i, Profil.BATISSEUR, 3.0, depart, 0);
            j.vendra(20, 1500);   // ce que l'excavation rend
            j.vendra(27, 800);
            j.achetera(22, 1200); // briques de pierre
            j.achetera(24, 300);  // verre
            j.achetera(23, 600);  // planches
            j.achetera(26, 100);  // laine
            j.achetera(21, 400);  // pierre
            monde.add(j);
        }
        return monde;
    }

    /**
     * Le vingt-et-unième : celui qui arrive après coup.
     *
     * <p>Il est en tout point un occasionnel, à une chose près : il ne se connecte qu'au trentième
     * jour. C'est le seul test qui dise si une économie de serveur reste jouable — et c'est celui
     * qu'on oublie, parce qu'on règle toujours son économie en regardant ceux qui sont déjà là.
     */
    static Joueur nouveauVenu(int jourDArrivee) {
        Joueur j = new Joueur("nouveau", Profil.OCCASIONNEL, 1.0, 100L * UNIT,
                jourDArrivee * PAS_PAR_JOUR);
        j.vendra(0, 15);
        j.vendra(3, 40);
        j.vendra(10, 80);
        j.vendra(14, 25);
        j.vendra(20, 120);
        j.achetera(23, 64);
        j.achetera(24, 32);
        j.achetera(22, 64);
        return j;
    }

    // ======================================================================
    //  La simulation
    // ======================================================================

    static final int PAS_PAR_HEURE = 60 / DETENTE_MINUTES;
    static final int PAS_PAR_JOUR = 24 * PAS_PAR_HEURE;

    static final class Marche {
        final long[] volume = new long[ARTICLES];
        long creee;
        long detruite;
        long retenue;

        void detendre() {
            for (int i = 0; i < ARTICLES; i++) {
                long v = this.volume[i];
                if (v == 0L) {
                    continue;
                }
                long perte = Math.max(1L, Math.abs(v) * detente / 1000L);
                long suite = v > 0L ? v - perte : v + perte;
                this.volume[i] = (v > 0L && suite < 0L) || (v < 0L && suite > 0L) ? 0L : suite;
            }
        }

        void echanger(int article, long unites) {
            long borne = 100L * elasticite;
            this.volume[article] = Math.max(-borne,
                    Math.min(borne, this.volume[article] + unites));
        }
    }

    static void detendrePuits(Joueur j) {
        for (int i = 0; i < ARTICLES; i++) {
            long q = j.quota[i];
            if (q > 0L) {
                j.quota[i] = Math.max(0L, q - Math.max(1L, q * puitsDetente / 1000L));
            }
        }
        if (j.debit > 0L) {
            j.debit = Math.max(0L, j.debit - Math.max(1L, j.debit * puitsDetente / 1000L));
        }
    }

    /** Une vente, exactement comme {@code Till.sell} la ferait. */
    static void vendre(Marche marche, Joueur j, int article, int unites) {
        if (unites <= 0) {
            return;
        }
        Art art = CATALOGUE[article];
        long cours = prixRachat(art, marche.volume[article]);
        if (cours <= 0L) {
            return;
        }
        int part = part(j, article);
        long unitaire = net(cours, part);
        long total = unitaire * unites;
        j.solde += total;
        j.gagne += total;
        j.retenu += (cours - unitaire) * unites;
        marche.creee += total;
        marche.retenue += (cours - unitaire) * unites;
        marche.echanger(article, -unites);
        // Les deux compteurs suivent le BRUT, jamais le net. Les faire suivre le net créerait une
        // boucle de retour : plus on est retenu, moins le compteur monte, donc moins on est retenu.
        j.quota[article] += cours * unites;
        j.debit += cours * unites;
    }

    /**
     * Un achat, exactement comme {@code Till.buy} le ferait — dans la limite du solde.
     *
     * <p>Le joueur garde une réserve. Sans elle, les bâtisseurs du modèle finissaient le mois à zéro
     * centime en permanence, ce qui écrasait la médiane et rendait l'écart riche/pauvre illisible —
     * et ce n'est pas ainsi qu'un joueur se conduit : personne ne dépense jusqu'au dernier centime.
     */
    static void acheter(Marche marche, Joueur j, int article, int voulu) {
        if (voulu <= 0) {
            return;
        }
        Art art = CATALOGUE[article];
        long unitaire = prixAchat(art, marche.volume[article]);
        long bourse = j.solde - j.solde * reserve / 100L;
        long possible = Math.min(voulu, bourse / Math.max(1L, unitaire));
        if (possible <= 0L) {
            return;
        }
        long total = unitaire * possible;
        j.solde -= total;
        j.depense += total;
        marche.detruite += total;
        marche.echanger(article, possible);
    }

    /** Vrai si ce joueur est connecté à ce pas. Les soirées sont étalées pour éviter un pic. */
    static boolean connecte(Joueur j, int pas, int rang) {
        int heureDuJour = (pas % PAS_PAR_JOUR) / PAS_PAR_HEURE;
        int debut = 17 + (rang % 4);
        int fin = debut + (int) Math.ceil(j.heures);
        return heureDuJour >= debut && heureDuJour < fin;
    }

    record Bilan(long masse, long plusRiche, long median, long plusPauvre, double gini,
                 long creee, long detruite, long retenue) {}

    static Bilan bilan(List<Joueur> monde, Marche marche) {
        long[] soldes = new long[monde.size()];
        for (int i = 0; i < monde.size(); i++) {
            soldes[i] = monde.get(i).solde;
        }
        Arrays.sort(soldes);
        long masse = 0L;
        for (long s : soldes) {
            masse += s;
        }
        double gini = 0.0;
        if (masse > 0L) {
            double cumul = 0.0;
            for (int i = 0; i < soldes.length; i++) {
                cumul += (double) (i + 1) * soldes[i];
            }
            gini = 2.0 * cumul / (soldes.length * (double) masse) - (soldes.length + 1.0)
                    / soldes.length;
        }
        return new Bilan(masse, soldes[soldes.length - 1], soldes[soldes.length / 2], soldes[0],
                gini, marche.creee, marche.detruite, marche.retenue);
    }

    /**
     * Le pouvoir d'achat d'un nouveau venu.
     *
     * <p>C'est le test le plus parlant, et le plus souvent oublié. On prend un joueur neuf, au solde
     * de départ, aux compteurs de puits vierges, et on lui fait vendre une heure de récolte
     * ordinaire — celle d'un occasionnel — <b>sur le marché tel qu'il est à cet instant</b>. Ce qu'il
     * en tire se compare directement à ce qu'il en aurait tiré au premier jour.
     */
    static long recetteDuNouveau(Marche marche) {
        Joueur neuf = new Joueur("nouveau", Profil.OCCASIONNEL, 1.0, 100L * UNIT, 0);
        long total = 0L;
        int[] panier = {0, 3, 10, 14, 20};
        int[] combien = {15, 40, 80, 25, 120};
        for (int k = 0; k < panier.length; k++) {
            Art art = CATALOGUE[panier[k]];
            long cours = prixRachat(art, marche.volume[panier[k]]);
            total += net(cours, part(neuf, panier[k])) * combien[k];
        }
        return total;
    }

    /** Ce que coûte, au même instant, le panier d'équipement d'un débutant. */
    static long paniersDuNouveau(Marche marche) {
        int[] panier = {23, 24, 22, 15};
        int[] combien = {128, 64, 128, 3};
        long total = 0L;
        for (int k = 0; k < panier.length; k++) {
            total += prixAchat(CATALOGUE[panier[k]], marche.volume[panier[k]]) * combien[k];
        }
        return total;
    }

    /** Le jour où le nouveau venu se connecte, et la semaine qu'on lui laisse pour se faire. */
    static final int JOUR_DU_NOUVEAU = 30;
    static final int SEMAINE = 7;

    static final class Resultat {
        Bilan fin;
        long recetteJour1;
        long recetteFin;
        long panierJour1;
        long panierFin;
        long[] volumeFinal;
        List<Joueur> monde;
        Joueur nouveau;
        /** Ce qu'un occasionnel du premier jour avait après une semaine. */
        long temoinApresUneSemaine;
        /** Ce que le nouveau venu a après sa propre semaine, un mois plus tard. */
        long nouveauApresUneSemaine;
    }

    static Resultat courir(int jours, boolean trace) {
        List<Joueur> monde = peupler();
        Joueur nouveau = nouveauVenu(JOUR_DU_NOUVEAU);
        monde.add(nouveau);
        Marche marche = new Marche();
        Resultat resultat = new Resultat();
        resultat.nouveau = nouveau;
        Random hasard = new Random(20260915L);

        resultat.recetteJour1 = recetteDuNouveau(marche);
        resultat.panierJour1 = paniersDuNouveau(marche);

        int pas = jours * PAS_PAR_JOUR;
        if (trace) {
            System.out.printf(Locale.ROOT, "%5s %14s %14s %14s %12s %7s %14s %14s%n",
                    "jour", "masse", "+ riche", "médian", "+ pauvre", "Gini", "créée", "détruite");
        }
        for (int t = 0; t < pas; t++) {
            for (int r = 0; r < monde.size(); r++) {
                Joueur j = monde.get(r);
                if (t < j.arrivee || !connecte(j, t, r)) {
                    continue;
                }
                // Une fraction d'heure par pas. Le bruit évite qu'une population entière agisse
                // au centième près de la même façon, ce qui donnerait des marches d'escalier.
                double fraction = 1.0 / PAS_PAR_HEURE * (0.85 + 0.3 * hasard.nextDouble());
                for (int a = 0; a < ARTICLES; a++) {
                    if (j.vend[a] > 0.0) {
                        vendre(marche, j, a, (int) Math.round(j.vend[a] * fraction));
                    }
                }
                for (int a = 0; a < ARTICLES; a++) {
                    if (j.achete[a] > 0.0) {
                        acheter(marche, j, a, (int) Math.round(j.achete[a] * fraction));
                    }
                }
            }
            marche.detendre();
            for (Joueur j : monde) {
                detendrePuits(j);
            }
            int jour = (t + 1) / PAS_PAR_JOUR;
            if ((t + 1) % PAS_PAR_JOUR == 0) {
                if (jour == SEMAINE) {
                    long somme = 0L;
                    int combien = 0;
                    for (Joueur j : monde) {
                        if (j.profil == Profil.OCCASIONNEL && j.arrivee == 0) {
                            somme += j.solde;
                            combien++;
                        }
                    }
                    resultat.temoinApresUneSemaine = somme / combien;
                }
                if (jour == JOUR_DU_NOUVEAU + SEMAINE) {
                    resultat.nouveauApresUneSemaine = nouveau.solde;
                }
            }
            if (trace && (t + 1) % (PAS_PAR_JOUR * 5) == 0) {
                Bilan b = bilan(monde, marche);
                System.out.printf(Locale.ROOT, "%5d %14s %14s %14s %12s %7s %14s %14s%n",
                        jour, piece(b.masse), piece(b.plusRiche),
                        piece(b.median), piece(b.plusPauvre),
                        String.format(Locale.ROOT, "%.3f", b.gini),
                        piece(b.creee), piece(b.detruite));
            }
        }
        resultat.fin = bilan(monde, marche);
        resultat.recetteFin = recetteDuNouveau(marche);
        resultat.panierFin = paniersDuNouveau(marche);
        resultat.volumeFinal = marche.volume.clone();
        resultat.monde = monde;
        return resultat;
    }

    /** La retenue moyenne subie par un profil — zéro veut dire « ce profil n'est pas concerné ». */
    static long retenuMoyen(Resultat r, Profil profil) {
        long somme = 0L;
        int combien = 0;
        for (Joueur j : r.monde) {
            if (j.profil == profil && j != r.nouveau) {
                somme += j.retenu;
                combien++;
            }
        }
        return combien == 0 ? 0L : somme / combien;
    }

    /** Le gain moyen par HEURE DE JEU d'un profil : la seule mesure honnête de l'équité. */
    static long parHeure(Resultat r, Profil profil, int jours) {
        long gagne = 0L;
        double heures = 0.0;
        for (Joueur j : r.monde) {
            if (j.profil != profil || j != r.nouveau && j.arrivee != 0) {
                continue;
            }
            if (j == r.nouveau) {
                continue;
            }
            gagne += j.gagne;
            heures += j.heures * jours;
        }
        return heures <= 0.0 ? 0L : (long) (gagne / heures);
    }

    // ======================================================================
    //  L'affichage
    // ======================================================================

    static void rapport(String titre, Resultat r, int jours) {
        System.out.println();
        System.out.println("=== " + titre + " ===");
        System.out.printf(Locale.ROOT, "  masse monétaire à %2d jours     %18s ¤%n",
                jours, piece(r.fin.masse));
        System.out.printf(Locale.ROOT, "  argent créé (ventes)           %18s ¤%n",
                piece(r.fin.creee));
        System.out.printf(Locale.ROOT, "  argent détruit (achats)        %18s ¤%n",
                piece(r.fin.detruite));
        System.out.printf(Locale.ROOT, "  jamais versé (retenues)        %18s ¤%n",
                piece(r.fin.retenue));
        System.out.printf(Locale.ROOT, "  le plus riche                  %18s ¤%n",
                piece(r.fin.plusRiche));
        System.out.printf(Locale.ROOT, "  le médian                      %18s ¤%n",
                piece(r.fin.median));
        System.out.printf(Locale.ROOT, "  le plus pauvre                 %18s ¤%n",
                piece(r.fin.plusPauvre));
        System.out.printf(Locale.ROOT, "  écart riche / médian           %18s%n",
                r.fin.median == 0 ? "infini"
                        : String.format(Locale.ROOT, "× %.1f",
                                r.fin.plusRiche / (double) r.fin.median));
        System.out.printf(Locale.ROOT, "  Gini                           %18.3f%n", r.fin.gini);

        System.out.println();
        System.out.println("  CE QUE RAPPORTE UNE HEURE DE JEU, par profil :");
        long occasionnel = Math.max(1L, parHeure(r, Profil.OCCASIONNEL, jours));
        for (Profil p : Profil.values()) {
            long h = parHeure(r, p, jours);
            System.out.printf(Locale.ROOT, "    %-12s %14s ¤ / h      × %6.1f par rapport à"
                    + " l'occasionnel%n", p, piece(h), h / (double) occasionnel);
        }

        System.out.println();
        System.out.println("  LE NOUVEAU VENU");
        System.out.printf(Locale.ROOT, "    une heure de récolte, au jour 1     %14s ¤%n",
                piece(r.recetteJour1));
        System.out.printf(Locale.ROOT, "    la même, au jour %2d                 %14s ¤"
                + "   (%.0f %% du jour 1)%n",
                jours, piece(r.recetteFin), 100.0 * r.recetteFin / r.recetteJour1);
        System.out.printf(Locale.ROOT, "    son panier d'équipement, au jour 1  %14s ¤%n",
                piece(r.panierJour1));
        System.out.printf(Locale.ROOT, "    le même, au jour %2d                 %14s ¤"
                + "   (%.0f %% du jour 1)%n",
                jours, piece(r.panierFin), 100.0 * r.panierFin / r.panierJour1);
        System.out.printf(Locale.ROOT, "    heures de jeu pour le payer         %8.1f h au jour 1"
                + "   ->  %.1f h au jour %d%n",
                r.panierJour1 / (double) Math.max(1L, r.recetteJour1),
                r.panierFin / (double) Math.max(1L, r.recetteFin), jours);
        System.out.printf(Locale.ROOT,
                "    ARRIVÉ AU JOUR %d, après une semaine  %14s ¤%n",
                JOUR_DU_NOUVEAU, piece(r.nouveauApresUneSemaine));
        System.out.printf(Locale.ROOT,
                "    un joueur du premier jour, après sa semaine  %7s ¤   (il en est à %.0f %%)%n",
                piece(r.temoinApresUneSemaine),
                100.0 * r.nouveauApresUneSemaine / Math.max(1L, r.temoinApresUneSemaine));

        System.out.println();
        System.out.println("  par profil (moyenne du solde en fin de course) :");
        for (Profil p : Profil.values()) {
            long somme = 0L;
            int combien = 0;
            long gagne = 0L;
            long retenu = 0L;
            for (Joueur j : r.monde) {
                if (j.profil == p && j != r.nouveau) {
                    somme += j.solde;
                    gagne += j.gagne;
                    retenu += j.retenu;
                    combien++;
                }
            }
            System.out.printf(Locale.ROOT,
                    "    %-12s %3d joueur(s)  solde %14s ¤   gagné %14s ¤   retenu %12s ¤%n",
                    p, combien, piece(somme / combien), piece(gagne / combien),
                    piece(retenu / combien));
        }
    }

    static void cours(Resultat r) {
        System.out.println();
        System.out.println("  les dix articles les plus déplacés (volume net, et le cours qui en sort) :");
        Integer[] ordre = new Integer[ARTICLES];
        for (int i = 0; i < ARTICLES; i++) {
            ordre[i] = i;
        }
        Arrays.sort(ordre, (a, b) -> Long.compare(Math.abs(r.volumeFinal[b]),
                Math.abs(r.volumeFinal[a])));
        for (int k = 0; k < 10; k++) {
            int i = ordre[k];
            Art art = CATALOGUE[i];
            double f = facteur(r.volumeFinal[i]);
            System.out.printf(Locale.ROOT,
                    "    %-16s volume %+10d   cours %+6.1f %%   achat %10s ¤   rachat %10s ¤%n",
                    art.nom, r.volumeFinal[i], (f - 1.0) * 100.0,
                    sou(prixAchat(art, r.volumeFinal[i])), sou(prixRachat(art, r.volumeFinal[i])));
        }
    }

    // ======================================================================

    public static void main(String[] args) {
        List<String> options = Arrays.asList(args);
        int jours = JOUR_DU_NOUVEAU + SEMAINE;

        if (options.contains("--balayage")) {
            balayage(jours);
            return;
        }
        if (options.contains("--long")) {
            jours = 90;
        }

        System.out.println("=".repeat(80));
        System.out.println(" AVANT — la dérive toute seule, telle que le mod la livre aujourd'hui");
        System.out.println("=".repeat(80));
        tousPuitsEteints();
        Resultat avant = courir(jours, true);
        rapport("AVANT", avant, jours);
        cours(avant);

        System.out.println();
        System.out.println("=".repeat(80));
        System.out.printf(Locale.ROOT,
                " APRÈS — quota %d ¤ / retenue %d %%, débit %d ¤ / retenue %d %%,"
                        + " détente %d pour mille%n",
                quotaPalier, quotaRetenue, debitPalier, debitRetenue, puitsDetente);
        System.out.println("=".repeat(80));
        quotaActif = true;
        debitActif = true;
        Resultat apres = courir(jours, true);
        rapport("APRÈS", apres, jours);
        cours(apres);

        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println(" LA COMPARAISON");
        System.out.println("=".repeat(80));
        ligne("masse monétaire", avant.fin.masse, apres.fin.masse);
        ligne("le plus riche", avant.fin.plusRiche, apres.fin.plusRiche);
        ligne("le médian", avant.fin.median, apres.fin.median);
        System.out.printf(Locale.ROOT, "  %-26s %16.1f    ->  %16.1f%n", "écart riche / médian",
                avant.fin.plusRiche / (double) avant.fin.median,
                apres.fin.plusRiche / (double) apres.fin.median);
        System.out.printf(Locale.ROOT, "  %-26s %16.3f    ->  %16.3f%n", "Gini",
                avant.fin.gini, apres.fin.gini);
        System.out.printf(Locale.ROOT, "  %-26s %16.1f    ->  %16.1f%n", "fermier / occasionnel",
                parHeure(avant, Profil.FERMIER, jours)
                        / (double) Math.max(1L, parHeure(avant, Profil.OCCASIONNEL, jours)),
                parHeure(apres, Profil.FERMIER, jours)
                        / (double) Math.max(1L, parHeure(apres, Profil.OCCASIONNEL, jours)));
        ligne("recette horaire du neuf", avant.recetteFin, apres.recetteFin);
        ligne("le nouveau, après 1 semaine", avant.nouveauApresUneSemaine,
                apres.nouveauApresUneSemaine);
    }

    static void ligne(String quoi, long avant, long apres) {
        System.out.printf(Locale.ROOT, "  %-26s %16s ¤  ->  %16s ¤   (%.0f %%)%n",
                quoi, piece(avant), piece(apres), 100.0 * apres / Math.max(1L, avant));
    }

    /** Le balayage : on cherche le réglage, on ne le devine pas. */
    static void balayage(int jours) {
        System.out.println("Balayage — le nouveau venu est le juge de paix, la colonne"
                + " « ferm/occ » est le verdict.");
        System.out.printf(Locale.ROOT, "%6s %6s %8s %6s %5s %14s %12s %9s %9s %10s%n",
                "quota", "reten", "débit", "reten", "dét", "masse", "+ riche", "min/occ",
                "ferm/occ", "ret.mineur");
        int[] quotas = {0, 1_000, 2_000, 4_000};
        int[] retenues = {90};
        int[] debits = {0, 10_000, 20_000, 40_000, 80_000};
        int[] detentes = {1, 2, 4};
        for (int det : detentes) {
            for (int q : quotas) {
                for (int d : retenues) {
                    for (int deb : debits) {
                        if (q == 0 && deb == 0) {
                            continue;
                        }
                        puitsDetente = det;
                        quotaActif = q > 0;
                        quotaPalier = Math.max(1, q);
                        quotaRetenue = d;
                        debitActif = deb > 0;
                        debitPalier = Math.max(1, deb);
                        Resultat r = courir(jours, false);
                        long occ = Math.max(1L, parHeure(r, Profil.OCCASIONNEL, jours));
                        System.out.printf(Locale.ROOT,
                                "%6d %5d %% %8d %5d %% %5d %14s %12s %9.1f %9.1f %10s%n",
                                q, d, deb, debitRetenue, det, piece(r.fin.masse),
                                piece(r.fin.plusRiche),
                                parHeure(r, Profil.MINEUR, jours) / (double) occ,
                                parHeure(r, Profil.FERMIER, jours) / (double) occ,
                                piece(retenuMoyen(r, Profil.MINEUR)));
                    }
                }
            }
        }
    }

    private Bourse() {}
}

/*
 * CE QUE CE PROGRAMME NE MODÉLISE PAS, et qu'il faut avoir en tête en lisant ses chiffres.
 *
 *  - Les échanges entre joueurs. « /banque payer » déplace de l'argent sans en créer ni en
 *    détruire : la masse est indifférente, mais l'écart riche/pauvre, lui, ne l'est pas — un
 *    fermier qui entretient trois amis est invisible ici.
 *  - Les villageois. Ils ne sont pas dans le graphe de recettes, donc pas dans le catalogue, donc
 *    pas dans ce modèle. Un serveur à fermes d'émeraudes déplacera la question.
 *  - Le plaisir. Un joueur qui gagne trop vite s'ennuie et arrête ; le modèle, lui, vend jusqu'au
 *    bout du mois avec la même constance. Les chiffres « AVANT » sont donc, si quelque chose, un
 *    peu pessimistes — mais dans le bon sens : ils disent le pire.
 *  - Les vacances, les absences, le turn-over. Vingt comptes, tous présents trente jours.
 */
