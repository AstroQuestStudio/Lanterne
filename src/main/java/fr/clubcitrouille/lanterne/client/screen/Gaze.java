package fr.clubcitrouille.lanterne.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.screen.Clock;
import fr.clubcitrouille.lanterne.content.screen.Feed;
import fr.clubcitrouille.lanterne.content.screen.Mail;
import fr.clubcitrouille.lanterne.content.screen.Sieve;
import fr.clubcitrouille.lanterne.content.screen.Stage;

/**
 * Le regard : qui décode, à quelle cadence, et qui ne décode pas du tout.
 *
 * <h2>Le défaut que cette classe existe pour ne pas commettre</h2>
 *
 * <p>Un écran vidéo dans un jeu est l'archétype de la fonctionnalité qui coûte cher sans qu'on s'en
 * aperçoive. Décoder une image de 1280 × 720, la convertir et la téléverser vers la carte graphique
 * coûte quelques millisecondes ; le faire soixante fois par seconde en coûte cent quatre-vingts par
 * seconde, pour <b>un</b> écran. Huit écrans dans une salle, et le jeu ne tient plus ses images —
 * dans un mod dont c'est précisément le sujet.
 *
 * <p>Et le coût se paie <b>même quand personne ne regarde</b>, si l'on n'y prend pas garde : un écran
 * derrière soi, un écran dans le dos d'un mur, un écran à cent blocs continuent de décoder tant que
 * leur chunk est chargé. C'est le défaut le plus fréquent de ce genre de mod, et il est invisible à
 * celui qui l'écrit parce qu'il teste avec un seul écran, en face de lui.
 *
 * <h2>Trois leviers, et le même gradient qu'ailleurs dans ce mod</h2>
 *
 * <p><b>Rien ne décode si personne ne regarde.</b> Un écran ne se signale que depuis
 * {@code extractRenderState}, c'est-à-dire seulement s'il est passé par le tri de visibilité du jeu.
 * Un écran hors du champ, hors de portée, ou caché par le terrain ne se signale pas, donc ferme sa
 * bobine — voir {@link #FORGET}.
 *
 * <p><b>La cadence baisse avec la distance.</b> Pleine cadence en deçà de seize blocs, puis une
 * décroissance continue jusqu'à cinq images par seconde au bord. C'est la même forme que
 * {@code core.Cadence}, et pour la même raison qui y est écrite : un palier est trop prudent en haut
 * de tranche et trop brutal en bas, une droite n'a pas ce défaut.
 *
 * <p><b>Il y a un plafond.</b> Quatre écrans décodent, les autres gardent leur dernière image. Une
 * texture déjà envoyée se redessine gratuitement : un écran plafonné reste visible, reste à l'image,
 * et ne coûte plus rien. C'est ce qui rend le coût total <b>borné</b> plutôt que proportionnel au
 * nombre d'écrans posés — et un coût borné est la seule promesse qu'un mod de performance ait le
 * droit de faire.
 *
 * <h2>Le tamis, une seconde fois</h2>
 *
 * <p>Rien ne s'ouvre sans repasser par {@link Sieve} avec la liste que le serveur a annoncée. C'est
 * redondant avec la vérification du serveur, et c'est le but : voir {@code Mail.Rules}. Un client
 * n'expose pas son adresse IP sur la seule foi de la partie qu'il ne contrôle pas.
 */
public final class Gaze {
    /**
     * Ticks sans être vu au bout desquels une bobine se ferme.
     *
     * <p>Quarante ticks — deux secondes. Assez pour qu'un demi-tour sur soi-même ne coûte pas une
     * réouverture, ce qui serait le pire des deux mondes : on paierait l'ouverture <em>et</em> on
     * perdrait l'image. Assez court pour qu'un joueur qui quitte la pièce cesse de payer avant
     * d'avoir eu le temps de s'en rendre compte.
     */
    private static final int FORGET = 40;

    /** En deçà, pleine cadence. Au-delà, elle décroît. La même distance que la zone franche du tick. */
    private static final double FULL_RATE = 16d;

    /** Cadence plancher, en images par seconde. En deçà, l'image saccade plus qu'elle ne bouge. */
    private static final double SLOWEST_RATE = 5d;

    /** Ce qu'une séance vivante occupe chez ce client. */
    private static final class Show {
        private Engine.Reel reel;
        private Film film;
        /** La source réellement ouverte — pas celle demandée. Voir {@link #retune}. */
        private String opened = "";
        private double distance = Double.MAX_VALUE;
        private long seenAt;
        private long framedAt;
        private boolean measured;
        /** Hauteur de décodage demandée à l'ouverture. Voir {@link #retune}. */
        private int openedHeight;
        /** Le son en cours, ou {@code null} : muet, ou pas encore lancé. */
        private Blare sound;
        /** Ce que l'écran doit dire tant qu'il n'a pas d'image. Jamais nul. */
        private Slate slate = Slate.NONE;
        /** Largeur du mur, ou l'envergure du projecteur. Sert au mode automatique de {@link Grade}. */
        private float breadth = 1f;
    }

    private static final Map<BlockPos, Show> SHOWS = new HashMap<>();

    /** Les règles du serveur, telles qu'il les a annoncées à la connexion. */
    private static List<String> domains = List.of();
    private static boolean filtering = true;
    private static boolean serverActive = true;
    private static int rangeCap = 48;
    private static int throwCap = 32;

    private Gaze() {}

    // --- Ce que le serveur annonce --------------------------------------------

    public static void accept(Mail.Rules rules) {
        domains = List.copyOf(rules.domains());
        filtering = rules.filtering();
        serverActive = rules.active();
        rangeCap = rules.rangeCap();
        throwCap = rules.throwCap();
        Lanterne.LOG.info("[PROJECTION] règles du serveur : projection {}, liste blanche {} ({} "
                + "domaine(s)).", serverActive ? "active" : "coupée",
                filtering ? "active" : "désactivée", domains.size());
    }

    public static List<String> domains() {
        return domains;
    }

    public static boolean filtering() {
        return filtering;
    }

    public static boolean serverActive() {
        return serverActive;
    }

    public static int rangeCap() {
        return rangeCap;
    }

    public static int throwCap() {
        return throwCap;
    }

    /** Le verdict du tamis, avec la liste du serveur. Sert à l'écran de réglages et à l'ouverture. */
    public static Sieve.Verdict admits(String source) {
        return Sieve.admits(source, domains, filtering);
    }

    // --- Ce que le rendu signale ----------------------------------------------

    /**
     * « Cet écran est sous les yeux du joueur, à telle distance. »
     *
     * <p>Appelée depuis {@code extractRenderState} et de nulle part ailleurs. C'est cette contrainte
     * qui donne gratuitement la première garantie : le jeu a déjà écarté ce qui est hors du champ,
     * hors de portée d'affichage et derrière le terrain, et l'on hérite de ce tri sans le refaire.
     */
    public static void notice(BlockPos pos, double distance, float breadth) {
        Show show = SHOWS.computeIfAbsent(pos, ignored -> new Show());
        show.seenAt = tick();
        show.distance = distance;
        show.breadth = breadth;
    }

    /**
     * L'image d'un écran : de quoi la dessiner, d'où qu'elle vienne.
     *
     * @param id la texture à lier
     * @param aspect son rapport largeur sur hauteur, pour le cadrage
     */
    public record Picture(Identifier id, float aspect) {}

    /**
     * Ce qu'un écran a à montrer, ou {@code null}.
     *
     * <p>Une pellicule remplie par un décodeur, ou la texture qu'un navigateur tient lui-même : le
     * rendu ne fait pas la différence, et c'est tout l'intérêt. Voir {@code Engine.Reel.texture}.
     */
    public static Picture picture(BlockPos pos) {
        Show show = SHOWS.get(pos);
        if (show == null) {
            return null;
        }
        Engine.Reel reel = show.reel;
        if (reel != null) {
            Identifier own = reel.texture();
            if (own != null) {
                return new Picture(own, (float) reel.width() / Math.max(1, reel.height()));
            }
        }
        Film film = show.film;
        return film == null ? null
                : new Picture(film.id(), (float) film.width() / Math.max(1, film.height()));
    }

    /**
     * Ce que cet écran doit écrire, faute d'image.
     *
     * <p>Jamais nul, et jamais vide tant qu'il n'y a pas d'image : c'est la garantie qui remplace
     * celle, fausse, qui prétendait qu'« aucun chemin ne mène à l'écran noir ». Le rendu ne décide
     * plus de rien — voir {@link Slate}.
     */
    public static Slate slate(BlockPos pos, Feed feed) {
        Show show = SHOWS.get(pos);
        if (show == null) {
            // Jamais vu par le tour de ronde : il vient d'apparaître, ou il est trop loin pour
            // décoder. Dans les deux cas c'est l'état de la source qui parle.
            return Slate.of(feed, false, false, null);
        }
        return show.slate;
    }

    // --- Le tour de ronde ------------------------------------------------------

    /**
     * Ouvre, ferme, et demande les images qu'il faut.
     *
     * <p>Une fois par tick client, et non une fois par image : ouvrir ou fermer une bobine est cher,
     * et le faire à soixante hertz rendrait le plafond lui-même coûteux. La <em>cadence</em>, elle,
     * se mesure en millisecondes réelles — elle ne doit pas dépendre du taux d'images du joueur.
     */
    public static void pump() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            forget();
            return;
        }
        // « arm » AVANT « announce », et l'ordre inverse a menti dans le journal du joueur.
        // L'annonce n'a lieu qu'une fois ; faite avant que Fetch n'ait regardé le disque, elle
        // relevait « bibliothèque absente » sur une machine où le décodeur était installé — et
        // c'est cette ligne, unique et fausse, qui a envoyé chercher au mauvais endroit.
        Fetch.arm();
        Engine.announce();
        // <h2>Pourquoi le téléchargement n'est PAS déclenché ici</h2>
        //
        // Il l'était : « consentement donné et décodeur absent » suffisait. Un audit l'a relevé, et
        // la remarque est juste — le consentement est un état PERSISTÉ. Un joueur qui a dit oui il y
        // a trois semaines, pour un écran qu'il a depuis cassé, voyait son jeu partir chercher
        // trente et un mébioctets au démarrage suivant sans qu'aucun geste neuf ne l'ait demandé.
        //
        // Le consentement reste la condition NÉCESSAIRE — Fetch.ensure le vérifie en première
        // ligne — mais il cesse d'être la condition SUFFISANTE. Ce qui déclenche désormais est une
        // intention présente : le bouton de l'écran de réglages, que l'ardoise nomme quand un écran
        // qu'on regarde a besoin du décodeur. Voir Slate et Console.
        //
        // Ce que cela coûte : un clic de plus, une fois. Ce que cela achète : aucun téléchargement
        // que le joueur n'ait demandé au moment où il le demande.
        long now = tick();

        // Ce qu'on n'a pas vu depuis deux secondes s'en va. En premier : les places libérées
        // profitent au classement de ce tour-ci, et non au suivant.
        Iterator<Map.Entry<BlockPos, Show>> stale = SHOWS.entrySet().iterator();
        while (stale.hasNext()) {
            Map.Entry<BlockPos, Show> entry = stale.next();
            if (now - entry.getValue().seenAt > FORGET) {
                shut(entry.getValue());
                stale.remove();
            }
        }

        // Les plus proches d'abord. Le tri porte sur quelques dizaines d'entrées au plus — le nombre
        // d'écrans visibles à la fois — et non sur le nombre d'écrans posés dans le monde.
        List<Map.Entry<BlockPos, Show>> ranked = new ArrayList<>(SHOWS.entrySet());
        ranked.sort((left, right) ->
                Double.compare(left.getValue().distance, right.getValue().distance));

        int budget = Consent.activeCap();
        int reach = Consent.distance();
        for (Map.Entry<BlockPos, Show> entry : ranked) {
            Show show = entry.getValue();
            Stage stage = stageAt(client, entry.getKey());
            boolean worth = stage != null && budget > 0 && show.distance <= reach
                    && serverActive && !stage.feed().idle();
            if (!worth) {
                shut(show);
                // L'ardoise se tient à jour MÊME quand on ne décode pas : un écran sans source, ou
                // trop loin pour mériter une image, doit dire pourquoi. C'est l'omission exacte qui
                // a produit un mur de huit sur cinq entièrement noir.
                show.slate = Slate.of(stage == null ? Feed.BLANK : stage.feed(), false, false, null);
                continue;
            }
            budget--;
            retune(entry.getKey(), stage, show);
            advance(entry.getKey(), stage, show);
            refreshSlate(show, stage.feed());
        }
    }

    /**
     * Ouvre la bonne source, ou ferme celle qui ne l'est plus.
     *
     * <p>La comparaison porte sur la source <b>réellement ouverte</b> et non sur un drapeau
     * « ouvert ». C'est ce qui fait qu'un changement de source pendant la lecture ferme l'ancienne
     * bobine au lieu de continuer à jouer un film que plus personne n'a demandé — le genre de défaut
     * qui ne se voit qu'à plusieurs, parce que celui qui change la source voit bien le bon film.
     */
    /** Relit l'ardoise de cette séance. Une fois par tour de ronde, et jamais depuis le rendu. */
    private static void refreshSlate(Show show, Feed feed) {
        Engine.Reel reel = show.reel;
        boolean playing = reel != null && !reel.broken()
                && (show.film != null || reel.texture() != null);
        boolean opening = reel != null && !reel.ready() && !reel.broken();
        String trouble = reel == null ? null : reel.trouble();
        show.slate = Slate.of(feed, opening, playing, trouble);
    }

    private static void retune(BlockPos pos, Stage stage, Show show) {
        String wanted = stage.feed().source();
        int height = stage.feed().grade().resolve(show.breadth, show.distance, Consent.ceiling());
        // Une qualité qui change rouvre le flux : la taille de décodage est figée à l'ouverture de
        // swscale, et la pellicule est allouée à cette taille. Le seuil évite qu'un joueur qui
        // marche fasse rouvrir le flux à chaque pas — Grade ne rend que trois paliers, mais deux
        // écrans de tailles voisines pourraient osciller entre deux d'entre eux.
        boolean sameSize = show.openedHeight == height;
        if (wanted.equals(show.opened) && show.reel != null && sameSize) {
            return;
        }
        shut(show);
        show.opened = wanted;
        show.openedHeight = height;
        show.measured = false;
        if (wanted.isBlank()) {
            return;
        }
        // Le tamis, une seconde fois, et avec la liste du serveur. Voir l'en-tête.
        if (!admits(wanted).ok()) {
            return;
        }
        Engine engine = Engine.forSource(wanted);
        if (engine.verdict() != Engine.Verdict.PRET) {
            return;
        }
        show.reel = engine.open(wanted, height);
    }

    /**
     * Demande l'image de l'instant, si le moment est venu.
     *
     * <p>Tout ce qui décide est ici : la cadence, la position que commande l'horloge partagée, la
     * compensation de latence, le rattrapage de dérive, et le volume pondéré par la distance. C'est
     * l'endroit où toute la mécanique de {@code content.screen} se rejoint, et il tient en trente
     * lignes parce que chacune des pièces a été écrite séparément et correctement.
     */
    private static void advance(BlockPos pos, Stage stage, Show show) {
        Engine.Reel reel = show.reel;
        if (reel == null) {
            return;
        }
        long wall = System.currentTimeMillis();
        double fps = rate(show.distance);
        if (wall - show.framedAt < 1000d / fps) {
            return;
        }
        show.framedAt = wall;

        Feed feed = stage.feed();
        Clock beat = stage.clock();
        long target = beat.position(gameTime(), 0f) + Clock.compensation(ping());
        // Un lecteur web tient son propre état de lecture : il faut le lui dire, là où un décodeur
        // se contente de rendre l'image de l'instant demandé.
        Chrome.playing(reel, !beat.paused());

        // La dérive, et ce qu'on en fait. La politique vit dans Clock — une fonction pure, qu'on
        // peut relire et discuter sans lancer le jeu. Ici on ne fait qu'obéir.
        switch (Clock.correct(target, target - reel.drift())) {
            case Clock.Correction.Hold ignored -> reel.rate(1f);
            case Clock.Correction.Trim trim -> reel.rate(trim.rate());
            case Clock.Correction.Seek seek -> {
                reel.rate(1f);
                target = seek.millis();
            }
        }

        float loudness = feed.loudness(distanceToPlayer(pos));
        reel.volume(loudness);
        sing(pos, show, reel, loudness);

        // Une bobine qui porte sa propre texture n'a pas besoin de pellicule : le navigateur
        // peint la sienne. Lui en réserver une serait trois mébioctets et demi de mémoire vidéo
        // pour une texture que personne n'écrirait jamais.
        if (reel.texture() != null) {
            return;
        }

        // La pellicule est allouée ICI, sur le fil client, et jamais depuis le fil de décodage :
        // créer une texture n'est pas sûr ailleurs. On attend que la bobine connaisse sa taille,
        // ce qui arrive un aller-retour réseau après l'ouverture.
        if (show.film == null) {
            if (!reel.ready()) {
                return;
            }
            show.film = Film.reserve(pos, reel.width(), reel.height());
            if (show.film == null) {
                // Mémoire vidéo refusée. On ferme plutôt que de réessayer à chaque tick : réessayer
                // sur une carte saturée est le meilleur moyen de la saturer davantage.
                shut(show);
                return;
            }
        }
        reel.present(target, show.film);

        // La durée, une fois, si le serveur ne la connaît pas. Voir Mail.Measure : c'est le seul pli
        // montant que personne ne réclame, et le seul où le serveur croit un client.
        if (!show.measured && beat.durationMillis() <= 0L && reel.duration() > 0L) {
            show.measured = true;
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                    new Mail.Measure(pos, reel.duration()));
        }
    }

    /**
     * Images par seconde à cette distance.
     *
     * <p>Pleine cadence sous seize blocs, puis une droite jusqu'au plancher. Le calcul est en
     * distance et non en carré de distance : ce qu'on veut suivre est la taille apparente de l'écran,
     * qui décroît comme l'inverse de la distance, pas de son carré.
     */
    private static double rate(double distance) {
        double cap = Consent.frameCap();
        if (distance <= FULL_RATE) {
            return cap;
        }
        double span = Math.max(1d, Consent.distance() - FULL_RATE);
        double share = Math.min(1d, (distance - FULL_RATE) / span);
        return Math.max(SLOWEST_RATE, cap - (cap - SLOWEST_RATE) * share);
    }

    /**
     * La latence de ce client, telle que le serveur la mesure et la diffuse.
     *
     * <p>Elle vient de la liste des joueurs, où le jeu la range déjà : {@code PlayerInfo.getLatency}.
     * La mesurer nous-mêmes aurait demandé un aller-retour de plus, sur un canal de plus, pour
     * obtenir moins bien ce qui existe déjà.
     */
    private static int ping() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) {
            return 0;
        }
        var info = client.getConnection().getPlayerInfo(client.player.getUUID());
        return info == null ? 0 : Math.max(0, info.getLatency());
    }

    private static Stage stageAt(Minecraft client, BlockPos pos) {
        return client.level != null && client.level.getBlockEntity(pos) instanceof Stage stage
                ? stage : null;
    }

    private static double distanceToPlayer(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? Double.MAX_VALUE
                : Math.sqrt(client.player.distanceToSqr(
                        pos.getX() + 0.5d, pos.getY() + 0.5d, pos.getZ() + 0.5d));
    }

    private static long gameTime() {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? 0L : client.level.getGameTime();
    }

    private static long tick() {
        return gameTime();
    }

    /**
     * Fait jouer le son de cette séance, et le suit.
     *
     * <h2>Le son démarre en retard, et c'est normal</h2>
     *
     * <p>La piste sonore n'existe qu'une fois le flux ouvert, c'est-à-dire un aller-retour réseau
     * après {@code open}. On ne peut donc pas lancer le son au même moment que l'image : on attend
     * que la bobine ait une onde à offrir. Un écran muet pendant une demi-seconde au démarrage vaut
     * mieux qu'un son lancé sur une source vide, que le moteur refermerait aussitôt.
     *
     * <p>Le volume, lui, est reposé à chaque tour : il suit la distance du joueur. C'est
     * {@code Blare.tick} qui le lisse, parce qu'un volume changé d'un coup à chaque pas crépite.
     */
    private static void sing(BlockPos pos, Show show, Engine.Reel reel, float loudness) {
        if (!(reel instanceof Pump pump)) {
            return;
        }
        if (show.sound == null) {
            Airwave wave = pump.wave();
            if (wave == null) {
                return; // source muette : rien à jouer, et rien à réessayer
            }
            show.sound = new Blare(pos, wave);
            Minecraft.getInstance().getSoundManager().play(show.sound);
        }
        show.sound.volume(loudness);
    }

    private static void shut(Show show) {
        if (show.sound != null) {
            // « finish » plutôt que « stop » du gestionnaire : l'instance se retire d'elle-même au
            // tick suivant, ce qui laisse au moteur le soin de fermer le canal OpenAL dans son
            // propre ordre. L'arrêter de l'extérieur pendant qu'il lit produit un claquement.
            show.sound.finish();
            show.sound = null;
        }
        if (show.reel != null) {
            show.reel.close();
            show.reel = null;
        }
        if (show.film != null) {
            show.film.release();
            show.film = null;
        }
        show.opened = "";
    }

    /**
     * Tout rendre : retour au menu, changement de serveur.
     *
     * <p>Sans cela, les pellicules resteraient allouées en mémoire vidéo pendant tout le temps passé
     * dans les menus, et les bobines garderaient leurs connexions ouvertes vers des hôtes que le
     * joueur a quittés. C'est la même faute que {@code Mosaic} avait faite et corrigée — celle qui ne
     * se manifeste qu'après une longue session.
     */
    public static void forget() {
        SHOWS.values().forEach(Gaze::shut);
        SHOWS.clear();
    }
}
