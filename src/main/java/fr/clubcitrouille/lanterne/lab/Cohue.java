package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.AABB;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Foule;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.report.Herd;

/**
 * La cohue : le banc du terme quadratique, et de lui seul.
 *
 * <h2>Pourquoi un banc de plus alors que {@link Cheptel} existe</h2>
 *
 * <p>{@code Cheptel} est le bon instrument pour la question qu'il pose : <em>où part le temps du
 * serveur quand le troupeau grandit</em>. Il profile un tick entier, poste par poste, et publie des
 * exposants de croissance. C'est lui qui a désigné {@code EntitySection.getEntities}.
 *
 * <p>Mais un tick entier mesure aussi tout le reste — le cerveau des bêtes, la cadence, la densité,
 * le réseau — et chacun de ces postes a son propre exposant. Pour <b>éprouver</b> une correction
 * portant sur un seul parcours, cette richesse devient du bruit : il faudrait des dizaines
 * d'exécutions pour extraire un facteur trois d'un tick où quinze modules se disputent la parole.
 *
 * <p>Ce banc fait l'inverse : il appelle {@code EntitySectionStorage.getEntities} <b>directement</b>,
 * un grand nombre de fois, sur une charge dont il connaît la taille, et chronomètre cela et rien
 * d'autre. Le résultat n'est pas une part de tick, c'est le coût du parcours lui-même.
 *
 * <h2>Ce qu'il mesure exactement</h2>
 *
 * <p>Pour chaque palier de troupeau, vingt mille requêtes de voisinage. La boîte demandée est celle
 * que le jeu emploie pour la poussée — la boîte de la bête, élargie de vingt centimètres à
 * l'horizontale — et les bêtes sont prises à tour de rôle, donc la charge est celle d'un tick réel
 * où chacune interroge son voisinage.
 *
 * <p>Le chemin employé est celui de {@code Shove} : {@code EntitySectionStorage} et non l'interface
 * publique, parce que c'est le seul qui n'enveloppe rien et ne collecte rien. Ce qu'on chronomètre
 * est donc le parcours, pas l'allocation d'une liste.
 *
 * <h2>Le contrôle d'identité, qui vaut mieux qu'une promesse</h2>
 *
 * <p>Une optimisation de recherche qui perd une entité est indétectable en jeu : deux vaches qui ne
 * se poussent plus se superposent, et personne ne dépose de rapport. Ce banc compte donc, à chaque
 * passe, <b>le nombre de réponses et la somme de leurs identifiants</b>.
 *
 * <p>Ces deux nombres ne dépendent pas de l'ordre de parcours. S'ils diffèrent entre le chemin du
 * jeu et celui de la grille, l'ensemble rendu a changé — et le verdict le dit en toutes lettres au
 * lieu de publier un beau rapport de vitesse sur un résultat faux.
 *
 * <h2>Alterné, et la meilleure passe retenue</h2>
 *
 * <p>Les deux chemins sont mesurés <b>en alternance, une passe par tick</b>, et non l'un après
 * l'autre. Une dérive de la machine — un autre processus, une montée en fréquence, un ramassage de
 * miettes — frappe alors les deux également au lieu de se loger dans celui qui passe en second.
 *
 * <p>On retient la <b>meilleure</b> passe de chaque côté et non la moyenne. C'est l'estimateur usuel
 * d'un micro-banc : le bruit d'une machine partagée ne peut qu'ajouter du temps, jamais en retirer,
 * si bien que le minimum est le seul relevé qui approche le coût réel.
 *
 * <h2>Les exposants, et comment les lire</h2>
 *
 * <p>Entre deux paliers, la charge est multipliée par {@code r} et le coût par {@code c}. L'exposant
 * vaut {@code log(c) / log(r)} : un pour un poste linéaire, <b>deux pour un poste quadratique</b>.
 * C'est la seule mesure qui dise si le défaut visé est bien celui qu'on croit — et si la correction
 * en change le <em>degré</em> ou seulement la constante.
 *
 * <p>Une précision honnête : ce terme <b>reste quadratique</b> après correction, et il ne peut pas
 * en être autrement. À densité croissante, la réponse elle-même grandit ; une recherche exacte ne
 * peut pas rendre {@code k} voisines en moins de {@code k} opérations. Ce que la grille divise est
 * la <b>constante</b>, en cessant de regarder les quatre mille blocs cubes de la section pour ne
 * regarder que les cellules qui peuvent répondre. C'est ce rapport-là qu'il faut lire dans la
 * colonne « gain », et non l'espoir d'un exposant qui tomberait à un.
 *
 * <h2>Ce qui fait refuser de conclure</h2>
 *
 * <ul>
 *   <li><b>La charge n'est pas née.</b> Sans doublure, les chunks ne sont pas simulés et les bêtes
 *       disparaissent dans le tick. Le banc fait donc entrer une doublure et attend les chunks avant
 *       de semer — six épreuves de ce dépôt sont tombées dans ce piège.</li>
 *   <li><b>La foule n'en est pas une.</b> Si aucune section n'atteint le seuil, la grille n'est
 *       jamais construite et les deux colonnes mesurent le même code. Le banc le dit.</li>
 *   <li><b>Les réponses diffèrent.</b> Alors le rapport de vitesse n'a aucune valeur, et c'est la
 *       seule chose qui est publiée.</li>
 * </ul>
 */
public final class Cohue {
    /**
     * Les tailles de troupeau éprouvées, dans l'ordre.
     *
     * <p>Quatre doublements. Deux points suffiraient à tracer une droite ; cinq permettent de voir
     * si la droite en est une, ce qui est toute la différence entre mesurer un exposant et le
     * supposer.
     */
    private static final int[] PALIERS = {250, 500, 1000, 2000, 4000};

    /**
     * Rayon de l'enclos, en blocs.
     *
     * <p>Sept : le troupeau tient dans un chunk, donc dans une colonne de sections. C'est le cas qui
     * intéresse — la ferme, le parc, l'entassement — et non une dispersion sur cent chunks où le
     * rangement du jeu suffit déjà.
     */
    private static final int RAYON = 7;

    /** Requêtes de voisinage par balayage. */
    private static final int SONDES = 10_000;

    /** Passes jetées, le temps que le compilateur à la volée se fixe. */
    private static final int CHAUFFE = 3;

    /** Passes retenues. Chacune mesure les deux chemins, dans le même tick. */
    private static final int PASSES = 6;

    /** Ticks d'attente après l'entrée de la doublure, le temps que les chunks arrivent. */
    private static final int ARRIVEE = 120;

    /** Ticks d'attente après les semailles, le temps que les bêtes s'installent. */
    private static final int REPOS = 60;

    /**
     * Le banc a-t-il été demandé ?
     *
     * <p>Une variable d'environnement et non un réglage : c'est la convention de tous les bancs de ce
     * dépôt, et elle a l'avantage de ne pas pouvoir s'allumer par accident sur un serveur de jeu.
     */
    private static final boolean DEMANDE = "1".equals(System.getenv("LANTERNE_COHUE"));

    /** Ticks laissés au serveur avant de faire entrer la doublure. */
    private static final int DEPART = 40;

    private enum Etape { DORT, PATIENTE, ATTEND, SEME, INSTALLE, MESURE, FINI }

    /** Ce qu'un palier a rendu. */
    private record Palier(int voulu, int nees, double msJeu, double msGrille,
            long repJeu, long repGrille, long sommeJeu, long sommeGrille, boolean diverge) {}

    private static Etape etape = Etape.DORT;
    private static int reste;
    private static int rang;
    private static int passe;
    private static final List<Palier> RESULTATS = new ArrayList<>();

    private static Entity[] betes = new Entity[0];
    private static long meilleurJeu;
    private static long meilleureGrille;
    private static long repJeu;
    private static long repGrille;
    private static long sommeJeu;
    private static long sommeGrille;
    private static int nees;
    private static boolean diverge;

    private Cohue() {}

    /**
     * Prend la main si le banc a été demandé.
     *
     * <p>Un seul point d'entrée, pour ne coûter qu'une ligne au fichier partagé qu'est
     * {@code report/SelfTest}. Tant que {@code -Dlanterne.cohue} n'est pas posé, cette méthode rend
     * {@code false} sur une lecture de constante et l'épreuve en cours continue comme si de rien
     * n'était.
     */
    public static boolean claim(MinecraftServer server) {
        if (!DEMANDE || etape == Etape.FINI) {
            return false;
        }
        if (etape == Etape.DORT) {
            // Le tout premier tick d'un serveur n'est pas un bon moment pour faire entrer un joueur :
            // on laisse le monde finir de s'ouvrir avant de toucher à quoi que ce soit.
            etape = Etape.PATIENTE;
            reste = DEPART;
            Lanterne.LOG.info("[COHUE] épreuve armée — {} tick(s) avant l'entrée de la doublure.",
                    DEPART);
            return true;
        }
        tick(server);
        return true;
    }

    public static boolean running() {
        return etape != Etape.DORT && etape != Etape.FINI;
    }

    public static void begin(MinecraftServer server) {
        RESULTATS.clear();
        rang = 0;
        etape = Etape.ATTEND;
        reste = ARRIVEE;
        // Sans doublure, aucun chunk n'est simulé : les bêtes naissent dans un monde qui ne tick pas
        // et s'évaporent avant la première mesure.
        Understudy.enter(server, server.overworld(), 1, 512);
        Lanterne.LOG.info("[COHUE] doublure en place — {} tick(s) d'attente avant les semailles.",
                ARRIVEE);
        if (!Settings.foule()) {
            Lanterne.LOG.warn("[COHUE] le module « foule » est ÉTEINT dans la configuration : les "
                    + "deux colonnes mesureraient le même code. Rallumez-le avant de conclure.");
        }
    }

    public static void tick(MinecraftServer server) {
        ServerLevel niveau = server.overworld();
        switch (etape) {
            case PATIENTE -> {
                if (--reste <= 0) {
                    begin(server);
                }
            }
            case ATTEND -> {
                if (--reste <= 0) {
                    etape = Etape.SEME;
                }
            }
            case SEME -> {
                Herd.sweepEntities(niveau);
                nees = Herd.populate(niveau, 0d, 0d, PALIERS[rang], RAYON);
                Lanterne.LOG.info("[COHUE] palier {} — {} bête(s) demandée(s), {} née(s).",
                        rang + 1, PALIERS[rang], nees);
                etape = Etape.INSTALLE;
                reste = REPOS;
            }
            case INSTALLE -> {
                if (--reste <= 0) {
                    ouvreLesPasses(niveau);
                }
            }
            case MESURE -> mesure(server, niveau);
            default -> { }
        }
    }

    private static void ouvreLesPasses(ServerLevel niveau) {
        List<Entity> vivantes = new ArrayList<>();
        for (Entity bete : niveau.getAllEntities()) {
            if (bete != null) {
                vivantes.add(bete);
            }
        }
        betes = vivantes.toArray(new Entity[0]);
        // Le renseignement sans lequel un « aucun gain » ne veut rien dire : si la section la plus
        // peuplée n'atteint pas le seuil de foule, aucune grille n'est bâtie et les deux colonnes
        // mesurent le même code. Le banc doit pouvoir distinguer « inutile » de « pas déclenché ».
        int pic = 0;
        var sections = sectionsDe(niveau);
        if (sections != null) {
            for (int cx = -2; cx <= 2; cx++) {
                for (int cz = -2; cz <= 2; cz++) {
                    var chacune = sections.getExistingSectionsInChunk(ChunkPos.pack(cx, cz))
                            .iterator();
                    while (chacune.hasNext()) {
                        pic = Math.max(pic, chacune.next().size());
                    }
                }
            }
        }
        Lanterne.LOG.info("[COHUE] {} bête(s) vivante(s) — section la plus peuplée : {} (seuil de "
                + "foule : {}).", betes.length, pic, Foule.SEUIL);
        passe = 0;
        meilleurJeu = Long.MAX_VALUE;
        meilleureGrille = Long.MAX_VALUE;
        repJeu = 0L;
        repGrille = 0L;
        sommeJeu = 0L;
        sommeGrille = 0L;
        diverge = false;
        etape = Etape.MESURE;
    }

    /**
     * Une passe par tick, et les deux chemins <b>dans le même tick</b>.
     *
     * <h2>Pourquoi les deux côtés doivent tomber dans le même tick</h2>
     *
     * <p>La première version mesurait un chemin par tick, en alternance. Elle rendait un rapport de
     * vitesse honnête et un contrôle d'identité <b>faux</b> : entre deux ticks, les bêtes ont bougé,
     * et les deux côtés ne répondaient pas à la même question. Deux nombres de réponses différents
     * n'auraient alors rien prouvé du tout.
     *
     * <p>Les deux balayages ont donc lieu l'un après l'autre, sans qu'un tick ne s'intercale : rien
     * ne bouge entre eux, et la comparaison des réponses devient exacte. Ce qui reste alterné d'un
     * tick à l'autre, c'est <b>l'ordre</b> des deux — sans quoi celui qui passe en second hériterait
     * systématiquement des caches chauds du premier.
     *
     * <p>Une passe entière dans un seul tick porte celui-ci à quelques centaines de millisecondes.
     * C'est sans conséquence pour un banc, et étaler les passes garde le serveur vivant et le
     * troupeau en place d'une passe à l'autre.
     */
    private static void mesure(MinecraftServer server, ServerLevel niveau) {
        EntitySectionStorage<Entity> sections = sectionsDe(niveau);
        if (sections == null || betes.length == 0) {
            Lanterne.LOG.warn("[COHUE] ni sections ni bêtes à interroger : palier abandonné.");
            passeAuSuivant(server, niveau);
            return;
        }

        long[] compteJeu = new long[2];
        long[] compteGrille = new long[2];
        long dureeJeu;
        long dureeGrille;

        if ((passe & 1) == 0) {
            dureeJeu = balaye(sections, compteJeu, true);
            dureeGrille = balaye(sections, compteGrille, false);
        } else {
            dureeGrille = balaye(sections, compteGrille, false);
            dureeJeu = balaye(sections, compteJeu, true);
        }

        if (passe >= CHAUFFE) {
            meilleurJeu = Math.min(meilleurJeu, dureeJeu);
            meilleureGrille = Math.min(meilleureGrille, dureeGrille);
            if (compteJeu[0] != compteGrille[0] || compteJeu[1] != compteGrille[1]) {
                diverge = true;
            }
            repJeu = compteJeu[0];
            repGrille = compteGrille[0];
            sommeJeu = compteJeu[1];
            sommeGrille = compteGrille[1];
        }

        passe++;
        if (passe >= CHAUFFE + PASSES) {
            RESULTATS.add(new Palier(PALIERS[rang], nees,
                    meilleurJeu / 1.0E6d, meilleureGrille / 1.0E6d,
                    repJeu, repGrille, sommeJeu, sommeGrille, diverge));
            passeAuSuivant(server, niveau);
        }
    }

    /**
     * Un balayage complet, d'un côté ou de l'autre, chronométré et compté.
     *
     * <p>Le compteur ne retient pas seulement le nombre de réponses mais la <b>somme de leurs
     * identifiants</b>. Ni l'un ni l'autre ne dépend de l'ordre de parcours, et les deux ensemble
     * ne coïncident pas par hasard : c'est le contrôle qui dit si la subdivision rend bien les mêmes
     * entités, et non seulement le même nombre.
     */
    private static long balaye(EntitySectionStorage<Entity> sections, long[] compte,
            boolean jeuNu) {
        Foule.banc(jeuNu);
        // Une sonde à blanc avant le chronomètre. C'est elle qui reconstruit la grille après la
        // bascule du banc, et ce coût-là appartient à la bascule : en jeu, une grille vit des
        // milliers de ticks et n'est bâtie qu'une fois.
        sections.getEntities(betes[0].getBoundingBox().inflate(0.2d, -0.01d, 0.2d),
                ignoree -> AbortableIterationConsumer.Continuation.CONTINUE);
        AbortableIterationConsumer<Entity> compteur = candidate -> {
            compte[0]++;
            compte[1] += candidate.getId();
            return AbortableIterationConsumer.Continuation.CONTINUE;
        };
        int combien = betes.length;
        long depart = System.nanoTime();
        for (int i = 0; i < SONDES; i++) {
            Entity bete = betes[i % combien];
            if (bete.isRemoved()) {
                continue;
            }
            // La boîte de la poussée : c'est elle que chaque créature demande à chaque tick, et
            // c'est donc elle qui fait le terme quadratique qu'on éprouve.
            AABB boite = bete.getBoundingBox().inflate(0.2d, -0.01d, 0.2d);
            sections.getEntities(boite, compteur);
        }
        return System.nanoTime() - depart;
    }

    private static void passeAuSuivant(MinecraftServer server, ServerLevel niveau) {
        Foule.banc(false);
        rang++;
        if (rang < PALIERS.length) {
            etape = Etape.SEME;
            return;
        }
        conclut(server, niveau);
    }

    private static void conclut(MinecraftServer server, ServerLevel niveau) {
        Herd.sweepEntities(niveau);
        Foule.banc(false);
        etape = Etape.FINI;

        Lanterne.LOG.info("[COHUE] ── Coût de {} requêtes de voisinage, enclos de {} blocs de rayon ──",
                SONDES, RAYON);
        Lanterne.LOG.info("[COHUE] {}", String.format(Locale.ROOT,
                "%7s %8s %12s %12s %9s %9s %9s",
                "bêtes", "nées", "jeu (ms)", "grille (ms)", "gain", "exp. jeu", "exp. gr."));

        Palier avant = null;
        for (Palier palier : RESULTATS) {
            String expJeu = "—";
            String expGrille = "—";
            if (avant != null && avant.nees() > 0 && palier.nees() > avant.nees()) {
                double ratio = (double) palier.nees() / avant.nees();
                expJeu = String.format(Locale.ROOT, "%.2f",
                        Math.log(palier.msJeu() / avant.msJeu()) / Math.log(ratio));
                expGrille = String.format(Locale.ROOT, "%.2f",
                        Math.log(palier.msGrille() / avant.msGrille()) / Math.log(ratio));
            }
            Lanterne.LOG.info("[COHUE] {}", String.format(Locale.ROOT,
                    "%7d %8d %12.2f %12.2f %8s %9s %9s",
                    palier.voulu(), palier.nees(), palier.msJeu(), palier.msGrille(),
                    String.format(Locale.ROOT, "×%.2f",
                            palier.msGrille() <= 0d ? 0d : palier.msJeu() / palier.msGrille()),
                    expJeu, expGrille));
            avant = palier;
        }

        boolean identique = true;
        for (Palier palier : RESULTATS) {
            if (palier.diverge()) {
                identique = false;
                Lanterne.LOG.error("[COHUE] PALIER {} : LES RÉPONSES DIFFÈRENT — jeu {} réponse(s) "
                        + "(somme {}), grille {} réponse(s) (somme {}). Le rapport de vitesse "
                        + "ci-dessus n'a AUCUNE valeur : la grille perd ou invente des entités.",
                        palier.voulu(), palier.repJeu(), palier.sommeJeu(),
                        palier.repGrille(), palier.sommeGrille());
            }
        }

        if (identique) {
            Lanterne.LOG.info("[COHUE] CONTRÔLE D'IDENTITÉ : à chaque palier, même nombre de "
                    + "réponses et même somme d'identifiants des deux côtés. L'ensemble rendu est "
                    + "le même ; seul l'ordre de parcours diffère.");
        }

        Palier dernier = RESULTATS.isEmpty() ? null : RESULTATS.get(RESULTATS.size() - 1);
        if (dernier != null && dernier.msGrille() > 0d) {
            double gain = dernier.msJeu() / dernier.msGrille();
            if (gain > 1.5d && identique) {
                Lanterne.LOG.info("[COHUE] VERDICT : au plus fort palier, la subdivision divise le "
                        + "parcours par {}. Le terme reste quadratique — une recherche exacte ne "
                        + "peut pas rendre moins que ce qu'elle trouve — mais sa constante tombe "
                        + "d'autant, et c'est le poste le plus lourd du profil de l'enclos.",
                        String.format(Locale.ROOT, "%.2f", gain));
            } else if (identique) {
                Lanterne.LOG.info("[COHUE] VERDICT : aucun gain franc ({}). Soit l'enclos n'atteint "
                        + "le seuil de foule dans aucune section, soit la marge d'élargissement "
                        + "avale la subdivision. Dans les deux cas le module ne sert à rien tel "
                        + "quel, et il faut le dire.",
                        String.format(Locale.ROOT, "×%.2f", gain));
            }
        }

        server.halt(false);
    }

    /**
     * Le stockage de sections du monde, ou {@code null}.
     *
     * <p>Le même chemin que {@code Shove} : c'est le seul qui soit interruptible et sans enveloppe,
     * donc le seul qui mesure le parcours et rien d'autre. Voir {@code EntityGetterAccessor}, qui
     * raconte les deux autres et ce qu'ils coûtaient.
     */
    @SuppressWarnings("unchecked")
    private static EntitySectionStorage<Entity> sectionsDe(ServerLevel niveau) {
        var acces = niveau.getEntities();
        if (acces instanceof LevelEntityGetterAdapter) {
            return ((fr.clubcitrouille.lanterne.mixin.EntityGetterAccessor<Entity>) acces)
                    .lanterne$sections();
        }
        return null;
    }
}
