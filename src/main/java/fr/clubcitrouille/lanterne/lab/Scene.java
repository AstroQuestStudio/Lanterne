package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Les charges d'essai : reproduire ce qui casse réellement un serveur.
 *
 * <h2>Pourquoi un anneau de vaches ne suffisait pas</h2>
 *
 * <p>Jusqu'ici, la seule charge mesurée était un disque de créatures réparties régulièrement autour
 * du joueur. Elle a servi, et elle a bien servi : c'est elle qui a mis au jour le débordement
 * d'entier, la chute divisée par seize, et le gain réel du niveau de détail.
 *
 * <p>Mais elle a un défaut qu'aucune répétition ne corrige : <b>elle est homogène</b>. Chaque vache
 * y a deux ou trois voisines. Or les situations qui mettent un serveur à genoux ne sont jamais
 * homogènes — ce sont des <em>concentrations</em> :
 *
 * <ul>
 *   <li>un enclos de quinze blocs de côté contenant mille bêtes, où chacune en a deux cents autour
 *       d'elle et où le coût de la bousculade croît avec le carré du nombre ;</li>
 *   <li>un sol jonché de dizaines de milliers d'objets, chacun refaisant vingt fois par seconde un
 *       test de collision dont la réponse n'a pas changé depuis trois minutes.</li>
 * </ul>
 *
 * <p>Ces deux cas ne sont pas des inventions de laboratoire : ce sont les deux plaintes les plus
 * fréquentes des administrateurs, et aucune des deux n'apparaissait dans le banc. <b>Un banc qui ne
 * reproduit pas la panne ne peut pas juger le remède.</b>
 *
 * <h2>Ce qui rend une charge mesurable</h2>
 *
 * <p>Le banc compare deux phases de vingt-cinq secondes. Pour que la comparaison ait un sens, la
 * charge doit être <b>stable sur les cinquante secondes</b> : ni décroissante, ni destructrice.
 *
 * <p>Ce paragraphe a longtemps dit que la dynamite <em>détruisait le terrain</em> qu'elle mesure, que
 * la seconde phase ne travaillerait donc plus sur le même monde que la première, et que l'écart
 * mesuré ne vaudrait rien. C'était exact — et la charge a pourtant été ajoutée sans que la réserve
 * soit levée. Quatre verdicts faux en sont sortis, et quatre modules écrits pour expliquer une
 * régression qui n'existait pas. Voir {@link #rearm}.
 *
 * <p>La dynamite est désormais mesurable, parce qu'elle est <b>reconstituée entre les deux phases</b>
 * : dalle reposée, restes balayés, charges réamorcées, altitude figée à la première construction. Ce
 * n'est pas une précaution de confort — c'est la condition sans laquelle le chiffre ne veut rien
 * dire, et le banc refuse maintenant de conclure quand elle n'est pas remplie.
 *
 * <p>L'eau, elle, attend toujours son banc : elle se <em>stabilise</em>, et au bout de quelques
 * secondes la seconde phase mesurerait une nappe au repos. Le remède est le même — reconstituer — et
 * il n'est pas encore écrit.
 */
public final class Scene {
    /** Les charges disponibles. */
    public enum Kind {
        /** Le disque historique : gradient de distance, charge homogène. */
        RING,
        /** L'élevage intensif : tout le troupeau dans un carré de quinze blocs. */
        PEN,
        /** Le sol jonché : des milliers d'objets posés, immobiles, qui tickent quand même. */
        ITEMS,
        /** Les deux à la fois, ce qui est la situation d'un serveur habité. */
        MIXED,
        /** Un duplicateur qui part : des milliers de charges amorcées, échelonnées. */
        TNT,
        /**
         * Des sources de lumière qui s'allument et s'éteignent sans arrêt, sous terre.
         *
         * <h2>Pourquoi il a fallu inventer cette charge</h2>
         *
         * <p>Le profileur, une fois ouvert à tous les fils, a été braqué sur les deux charges les plus
         * lourdes du projet pour y trouver le moteur de lumière. Il n'y a trouvé presque rien :
         *
         * <pre>
         * dynamite, 2,5 M de blocs détruits  →  SkyLightEngine.propagateIncrease   0,6 %
         * génération de chunks               →  aucune trace du moteur de lumière
         * </pre>
         *
         * <p>Ce n'est pas une preuve que la lumière est gratuite : c'est la preuve qu'aucune de ces
         * deux charges ne la sollicite. Une explosion à ciel ouvert ne fait que <em>descendre</em> la
         * colonne de ciel, ce qui est le cas le moins cher du moteur ; et la lumière d'un chunk en
         * génération est calculée une fois, en bloc, sur un fil qui dort le reste du temps.
         *
         * <p>Le cas coûteux est ailleurs, et c'est un cas que les joueurs construisent tous les
         * jours : <b>une source de lumière qui change d'état dans une pièce fermée</b>. Une lampe de
         * redstone qui clignote, un four qui s'allume, un portail qui bat. Chaque changement force le
         * moteur à effacer puis à repropager une sphère de quinze blocs de rayon — quelques milliers
         * de cases, deux fois, par bascule.
         *
         * <p>Sous terre, et non en surface : la lumière du ciel écraserait tout et le moteur n'aurait
         * rien à calculer.
         */
        LIGHT,
        /**
         * Un ciel plein de projectiles, qui cherchent tous quelque chose à toucher.
         *
         * <p>Chaque flèche en vol appelle, à chaque tick, {@code ProjectileUtil.getEntityHitResult} —
         * lequel demande à {@code level.getEntities} la liste des entités de sa boîte de déplacement.
         * C.est une allocation de liste et un parcours de section par projectile et par tick, pour
         * une réponse qui est presque toujours « rien ».
         *
         * <p>C.est le même gaspillage que celui des collisions d.entités, atteint par un autre chemin,
         * et il n.avait jamais été mesuré. Une bataille, une ferme à flèches, un joueur qui mitraille :
         * les projectiles sont l.un des rares postes qui montent en flèche sans qu.aucun mod connu ne
         * s.en occupe.
         */
        ARROWS
    }

    /**
     * Côté de l'enclos, en blocs.
     *
     * <p>Quinze : la dimension que prend un enclos construit à la main, et celle qu'on voit sur les
     * captures d'écran de fermes surpeuplées.
     */
    private static final int PEN_SIDE = 15;

    /**
     * Graine fixe du tirage de positions.
     *
     * <p>Deux exécutions du banc doivent poser les bêtes aux mêmes endroits, sans quoi l'écart entre
     * deux mesures contiendrait une part de hasard qu'on prendrait pour un effet du mod.
     */
    private static final long SEED = 0x10A7E12EL;

    private Scene() {}

    /** Lit la charge demandée. Absente ou inconnue : le disque historique. */
    public static Kind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Kind.RING;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "pen", "enclos", "elevage", "élevage" -> Kind.PEN;
            case "items", "objets", "sol" -> Kind.ITEMS;
            case "mixed", "mixte", "tout" -> Kind.MIXED;
            case "tnt", "dynamite", "boom" -> Kind.TNT;
            case "light", "lumiere", "lumière", "lampes" -> Kind.LIGHT;
            case "arrows", "fleches", "flèches", "projectiles" -> Kind.ARROWS;
            default -> Kind.RING;
        };
    }

    /**
     * Pose la charge demandée et rend le nombre d'entités réellement créées.
     *
     * <p>On rend ce qui vit, pas ce qu'on a demandé : c'est cette distinction qui avait révélé que
     * deux mille vaches s'évaporaient faute de chunk chargé, trois bancs durant.
     */
    /** Ce qui a été bâti, pour pouvoir le rebâtir à l'identique entre deux phases. */
    private static Kind builtKind;
    private static int builtCount;
    private static int builtRadius;

    /**
     * L'altitude du sol, figée à la première construction.
     *
     * <h2>Une dalle qui montait de quatre blocs à chaque reconstruction</h2>
     *
     * <p>La dalle et les charges s'ancraient sur {@code getHeight}, c'est-à-dire sur le sommet du
     * terrain. Après la première phase, ce sommet n'est plus le terrain naturel : c'est <b>la dalle
     * elle-même</b>, qui est de la pierre et bloque donc le mouvement. La reconstruction bâtissait la
     * suivante quatre blocs plus haut, sur les décombres de la précédente.
     *
     * <p>Les deux phases ne se déroulaient donc pas au même endroit, et le compte d'explosions le
     * disait : deux mille neuf cent cinquante et une contre deux mille cinq cent cinquante-quatre,
     * soit seize pour cent d'écart, reproduit à l'identique sur trois exécutions et sur des modules
     * qui ne touchent pas à la physique.
     *
     * <p>C'est le même défaut que celui qui vient d'être corrigé, à un étage de plus : on croit
     * remettre une scène à neuf, et l'on en bâtit une autre. L'altitude est désormais relevée une
     * seule fois, à la construction, et toutes les reconstructions s'y tiennent.
     */
    private static int builtGround = Integer.MIN_VALUE;

    /**
     * Remet la scène dans l'état exact où la première phase l'a trouvée.
     *
     * <h2>Le banc qui comparait deux mondes différents</h2>
     *
     * <p>Le protocole mesure le mod actif, puis le mod éteint, sur « le même monde et la même
     * charge ». Cette phrase était fausse pour la dynamite, et personne ne l'avait vérifié — pas même
     * le commentaire de {@link #dynamite} qui posait pourtant la règle : <em>« une charge qui détruit
     * ce qu'elle mesure ne se mesure pas deux fois »</em>.
     *
     * <p>Six mille charges explosent pendant la première phase. Elles creusent le sol, et elles
     * laissent derrière elles des milliers d'objets. La seconde phase hérite d'un cratère : ses
     * explosions ne rencontrent plus de matière, ne produisent plus de butin, ne posent plus d'objets.
     * Le banc a mesuré cet écart et l'a attribué au mod :
     *
     * <pre>
     * phase 1 (mod actif)   : 11,34 Go alloués   443 paquets par tick
     * phase 2 (mod éteint)  :  5,34 Go alloués   123 paquets par tick
     * </pre>
     *
     * <p>Un facteur trois sur le <b>travail accompli</b>, dans un protocole qui suppose les deux
     * phases identiques. Le verdict « le mod coûte deux fois plus cher » ne disait rien du mod : il
     * disait que la première phase avait détruit le décor de la seconde.
     *
     * <p>C'est le même défaut que le banc de génération de terrain avait avant d'être apparié, et que
     * le banc d'entonnoirs avait avant qu'on vide les coffres entre les tirs. Trois fois la même
     * erreur, trois fois trouvée après coup. Elle est désormais traitée à la source : toute scène
     * destructive se reconstruit entre les phases, et le banc refuse de conclure si les deux phases
     * n'ont pas abattu la même quantité de travail.
     */
    public static void rearm(ServerLevel level) {
        if (builtKind == Kind.ARROWS) {
            volley(level, arrowsWanted);
            return;
        }
        if (builtKind == Kind.LIGHT) {
            // La salle n.est pas détruite par l.épreuve : seules les lampes changent d.état. Il
            // suffit donc de toutes les éteindre et de remettre le tirage à sa graine.
            var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            for (long key : LIT) {
                level.setBlock(BlockPos.of(key), air, 3);
            }
            LIT.clear();
            lampDice = new java.util.Random(SEED);
            lampToggles = 0;
            Lanterne.LOG.info("[SCÈNE] remise à neuf : toutes les lampes éteintes, tirage réarmé.");
            return;
        }
        if (builtKind != Kind.TNT) {
            return; // ni la dynamite ni la lumière : les autres charges sont réversibles
        }
        // Collecter d'abord, supprimer ensuite : discard() retire l'entité de la table que l'on est
        // en train de parcourir, et fastutil rend alors un index hors bornes plutôt qu'une erreur de
        // modification concurrente — plus difficile à relier à sa cause.
        java.util.List<net.minecraft.world.entity.Entity> doomed = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity leftover : level.getAllEntities()) {
            if (leftover instanceof net.minecraft.world.entity.item.ItemEntity
                    || leftover instanceof net.minecraft.world.entity.item.PrimedTnt) {
                doomed.add(leftover);
            }
        }
        for (net.minecraft.world.entity.Entity leftover : doomed) {
            leftover.discard();
        }
        int removed = doomed.size();
        int rebuilt = slab(level, builtCount);
        int born = dynamite(level, builtCount);
        Lanterne.LOG.info("[SCÈNE] remise à neuf : {} entité(s) balayée(s), {} bloc(s) reposé(s), "
                + "{} charge(s) réamorcée(s) — la seconde phase part du même décor que la première.",
                removed, rebuilt, born);
    }

    /**
     * La dalle que la dynamite a pour mission de manger.
     *
     * <p>Sans elle, les charges explosent en l'air au-dessus d'un terrain naturel : la matière enlevée
     * dépend du relief, donc de la graine, et la première phase creuse un trou que la seconde ne
     * retrouvera pas. Une dalle de pierre pleine, d'étendue et d'épaisseur connues, est au contraire
     * reconstructible à l'identique en quelques dizaines de millisecondes — hors fenêtre de mesure.
     *
     * <p>Trois blocs d'épaisseur : assez pour qu'une explosion de portée quatre en emporte une part
     * substantielle, pas assez pour que la reconstruction devienne un banc à elle seule.
     */
    private static int slab(ServerLevel level, int count) {
        int side = side(count) + 24;
        int top = ground(level) + 4;
        net.minecraft.world.level.block.state.BlockState stone =
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        net.minecraft.core.BlockPos.MutableBlockPos cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
        int placed = 0;
        for (int x = -side / 2; x <= side / 2; x++) {
            for (int z = -side / 2; z <= side / 2; z++) {
                for (int y = top - 2; y <= top; y++) {
                    cursor.set(x, y, z);
                    // Drapeau 2 : on notifie les clients sans déclencher de mise à jour de voisinage.
                    // Poser cent mille blocs en cascadant les mises à jour prendrait des minutes.
                    if (level.setBlock(cursor, stone, 2)) {
                        placed++;
                    }
                }
            }
        }
        return placed;
    }

    /**
     * L.altitude de référence, relevée une seule fois et jamais recalculée.
     *
     * <p>Voir {@link #builtGround} : la recalculer après une phase destructive ferait bâtir la scène
     * suivante sur les décombres de la précédente.
     */
    private static int ground(ServerLevel level) {
        if (builtGround == Integer.MIN_VALUE) {
            builtGround = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        }
        return builtGround;
    }

    /** Côté de la salle creusée pour l'épreuve de lumière, et sa hauteur. */
    private static final int HALL_SIDE = 64;
    private static final int HALL_HEIGHT = 16;

    /** Altitude du plancher de la salle, sous le niveau du sol pour échapper à la lumière du ciel. */
    private static int hallFloor;

    /** Positions où une source de lumière peut apparaître, et celles qui sont allumées. */
    private static final java.util.List<BlockPos> LAMP_SPOTS = new java.util.ArrayList<>();
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet LIT =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static java.util.Random lampDice = new java.util.Random(SEED);
    private static long lampToggles;

    /**
     * Creuse la salle et choisit les emplacements de lampes.
     *
     * <p>Quarante blocs sous le sol : assez profond pour que la lumière du ciel n'atteigne jamais la
     * salle, et donc que chaque lampe soit seule à éclairer ce qu'elle éclaire. En surface, le moteur
     * n'aurait presque rien à calculer et l'épreuve mesurerait le vide.
     *
     * <p>La salle est vidée en air, sans mise à jour de voisinage : soixante-cinq mille poses de bloc
     * qui cascaderaient prendraient des minutes, et elles ont lieu hors de toute fenêtre chronométrée.
     */
    private static int hall(ServerLevel level, int count) {
        hallFloor = ground(level) - 40;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        int dug = 0;
        for (int x = -HALL_SIDE / 2; x <= HALL_SIDE / 2; x++) {
            for (int z = -HALL_SIDE / 2; z <= HALL_SIDE / 2; z++) {
                for (int y = hallFloor; y < hallFloor + HALL_HEIGHT; y++) {
                    cursor.set(x, y, z);
                    if (level.setBlock(cursor, air, 2)) {
                        dug++;
                    }
                }
            }
        }

        // Cette épreuve mesure un moteur de lumière, pas une faune. Les créatures qui apparaissent
        // naturellement dans une salle souterraine obscure fausseraient la mesure — et le contrôle
        // préalable refuse à juste titre de mesurer une charge qu.il ne reconnaît pas.
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, level.getServer());
        java.util.List<net.minecraft.world.entity.Entity> strays = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity wanderer : level.getAllEntities()) {
            if (wanderer instanceof net.minecraft.world.entity.Mob) {
                strays.add(wanderer);
            }
        }
        for (net.minecraft.world.entity.Entity wanderer : strays) {
            wanderer.discard();
        }

        LAMP_SPOTS.clear();
        LIT.clear();
        lampDice = new java.util.Random(SEED);
        // Les emplacements sont tirés une fois et figés : deux exécutions du banc doivent allumer les
        // mêmes lampes aux mêmes endroits, sinon l'écart entre deux mesures contiendrait du hasard.
        int wanted = Math.max(64, Math.min(count, 4096));
        for (int i = 0; i < wanted; i++) {
            LAMP_SPOTS.add(new BlockPos(
                    lampDice.nextInt(HALL_SIDE) - HALL_SIDE / 2,
                    hallFloor + 1 + lampDice.nextInt(Math.max(1, HALL_HEIGHT - 2)),
                    lampDice.nextInt(HALL_SIDE) - HALL_SIDE / 2));
        }
        Lanterne.LOG.info("[SCÈNE] lumière : salle de {}×{}×{} creusée à y={} ({} bloc(s) retirés), "
                + "{} emplacement(s) de lampe, {} bascule(s) par tick.",
                HALL_SIDE, HALL_HEIGHT, HALL_SIDE, hallFloor, dug, LAMP_SPOTS.size(), togglesPerTick());
        // Zéro : cette charge ne crée aucune entité, et le contrôle préalable compte des entités.
        // Lui annoncer deux mille lampes lui ferait refuser la mesure pour une raison inexistante.
        // La vérification qui convient ici est le nombre de bascules réellement effectuées, que le
        // rapport affiche — une charge de lumière qui n.a rien basculé n.a rien mesuré.
        return 0;
    }

    /**
     * Bascules de lampe par tick.
     *
     * <p>Chacune force le moteur à effacer puis à repropager une sphère de quinze blocs de rayon.
     * Huit par tick suffisent à saturer un moteur de lumière ; le réglage existe pour pouvoir chercher
     * le point où il cède.
     */
    private static int togglesPerTick() {
        String raw = System.getenv("LANTERNE_TOGGLES");
        if (raw == null || raw.isBlank()) {
            return 8;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException malformed) {
            return 8;
        }
    }

    /**
     * Fait battre les lampes — appelé à chaque tick du banc, dans les deux phases.
     *
     * <p>C'est la seule charge du projet qui <b>agit pendant la mesure</b> plutôt que d'être posée
     * avant elle. Une lampe immobile ne coûte rien : le moteur de lumière ne travaille que sur le
     * changement. Il fallait donc un mécanisme, et le plus fidèle est aussi le plus simple — on
     * allume ce qui est éteint, on éteint ce qui est allumé.
     */
    public static void stir(ServerLevel level) {
        if (builtKind == Kind.ARROWS) {
            refillArrows(level);
            return;
        }
        if (builtKind != Kind.LIGHT || LAMP_SPOTS.isEmpty()) {
            return;
        }
        var glow = net.minecraft.world.level.block.Blocks.GLOWSTONE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        int toggles = togglesPerTick();
        for (int i = 0; i < toggles; i++) {
            BlockPos spot = LAMP_SPOTS.get(lampDice.nextInt(LAMP_SPOTS.size()));
            long key = spot.asLong();
            // Drapeau 3 : notifier les clients et les voisins. C'est ce que fait une lampe de
            // redstone, et c'est ce qui déclenche réellement le moteur de lumière.
            if (LIT.remove(key)) {
                level.setBlock(spot, air, 3);
            } else {
                LIT.add(key);
                level.setBlock(spot, glow, 3);
            }
            lampToggles++;
        }
    }

    /** Bascules de lampe effectuées, pour que le rapport dise sur quoi il a porté. */
    public static long lampToggles() {
        return lampToggles;
    }

    /** Flèches que la volée maintient en vol, et le tirage qui les relance. */
    private static int arrowsWanted;
    private static java.util.Random arrowDice = new java.util.Random(SEED);
    private static long arrowsFired;

    /**
     * Une volée continue de projectiles au-dessus d'un sol dégagé.
     *
     * <h2>Pourquoi il faut les relancer sans cesse</h2>
     *
     * <p>Une flèche ne vole que quelques secondes : elle se plante, puis ne coûte presque plus rien.
     * Une charge posée une fois pour toutes mesurerait donc un tapis de flèches plantées, c'est-à-dire
     * l'inverse de ce qu'on veut. La volée est réapprovisionnée à chaque tick par {@link #stir}, comme
     * les lampes de l'épreuve de lumière — c'est la seconde charge du projet qui agit <b>pendant</b> la
     * mesure.
     *
     * <p>Elles sont tirées vers le haut et en tous sens depuis un même point, ce qui les fait se
     * croiser : chacune trouve donc réellement des entités dans sa boîte de recherche, et l'on mesure
     * le cas coûteux plutôt que le cas vide.
     */
    private static int volley(ServerLevel level, int count) {
        arrowsWanted = Math.max(16, count);
        arrowDice = new java.util.Random(SEED);
        arrowsFired = 0L;
        VOLLEY.clear();
        // Les flèches plantées au sol ne comptent pas comme des projectiles en vol, et un tapis de
        // flèches d'une exécution précédente fausserait la densité.
        java.util.List<net.minecraft.world.entity.Entity> leftovers = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity old : level.getAllEntities()) {
            if (old instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) {
                leftovers.add(old);
            }
        }
        for (net.minecraft.world.entity.Entity old : leftovers) {
            old.discard();
        }
        Lanterne.LOG.info("[SCÈNE] projectiles : {} flèche(s) maintenues en vol, {} ancienne(s) "
                + "balayée(s).", arrowsWanted, leftovers.size());
        return 0; // aucune entité durable : le contrôle préalable compterait un monde vide
    }

    /** Les flèches que cette épreuve a tirées, pour les suivre sans parcourir le monde entier. */
    private static final java.util.List<net.minecraft.world.entity.projectile.arrow.AbstractArrow>
            VOLLEY = new java.util.ArrayList<>();

    /**
     * Relance ce qui est retombé, et retire ce qui s'est planté.
     *
     * <h2>La charge qui n'était pas celle qu'on croyait</h2>
     *
     * <p>La première version se contentait de compter les flèches en vol et d'en créer jusqu'au
     * compte voulu. Les flèches plantées, elles, restaient — et une flèche plante en trois secondes.
     * Le banc a donc mesuré, sans le dire, un tapis de dix-neuf mille flèches immobiles :
     *
     * <pre>
     * Entités dans le monde : 19 482     (pour mille cinq cents demandées)
     * </pre>
     *
     * <p>Ce n'était pas une charge de projectiles : c'était une charge d'objets inertes, qu'on mesure
     * déjà ailleurs et bien mieux. Les flèches plantées sont donc retirées à mesure, et l'on suit
     * <b>sa propre volée</b> plutôt que de parcourir toutes les entités du monde — ce parcours coûtait
     * lui-même, à dix-neuf mille entités, plus cher que ce qu'il mesurait.
     */
    private static void refillArrows(ServerLevel level) {
        int flying = 0;
        for (java.util.Iterator<net.minecraft.world.entity.projectile.arrow.AbstractArrow> pass =
                VOLLEY.iterator(); pass.hasNext(); ) {
            var arrow = pass.next();
            // isInGround() est protégée ; une flèche plantée a une vitesse nulle, ce qui est le même
            // renseignement obtenu par l'API publique.
            if (!arrow.isAlive() || arrow.getDeltaMovement().lengthSqr() <= 1.0E-4d) {
                arrow.discard();
                pass.remove();
                continue;
            }
            flying++;
        }
        int ground = ground(level);
        while (flying < arrowsWanted) {
            var arrow = net.minecraft.world.entity.EntityType.ARROW.create(
                    level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (arrow == null) {
                return;
            }
            arrow.snapTo(0d, ground + 3d, 0d, 0f, 0f);
            // Vers le haut et en tous sens : les trajectoires se croisent, donc chaque flèche trouve
            // vraiment des voisines dans sa boîte de recherche.
            arrow.setDeltaMovement(
                    (arrowDice.nextDouble() - 0.5d) * 2.4d,
                    0.8d + arrowDice.nextDouble() * 1.2d,
                    (arrowDice.nextDouble() - 0.5d) * 2.4d);
            if (!level.addFreshEntity(arrow)) {
                return;
            }
            VOLLEY.add(arrow);
            arrowsFired++;
            flying++;
        }
    }

    /** Flèches tirées depuis le début, pour que le rapport dise sur quoi il a porté. */
    public static long arrowsFired() {
        return arrowsFired;
    }

    /** L'étendue du champ de charges, calculée au même endroit par la pose et par la reconstruction. */
    private static int side(int count) {
        return Math.max(16, (int) Math.ceil(Math.sqrt(count)) * 2);
    }

    public static int build(ServerLevel level, Kind kind, int count, int radius) {
        builtKind = kind;
        builtCount = count;
        builtRadius = radius;
        builtKind = kind;
        if (kind == Kind.TNT) {
            Lanterne.LOG.info("[SCÈNE] dalle de pierre : {} bloc(s) posé(s) — la matière à enlever.",
                    slab(level, count));
        }
        return switch (kind) {
            case RING -> fr.clubcitrouille.lanterne.report.Herd.populate(level, 0d, 0d, count, radius);
            case PEN -> pen(level, count);
            case ITEMS -> items(level, count);
            case MIXED -> {
                int born = pen(level, count / 2);
                born += items(level, count / 2);
                yield born;
            }
            case TNT -> dynamite(level, count);
            case LIGHT -> hall(level, count);
            case ARROWS -> volley(level, count);
        };
    }

    /**
     * L'élevage intensif : tout le troupeau dans un carré de quinze blocs.
     *
     * <h2>Pourquoi il faut désarmer l'entassement</h2>
     *
     * <p>La règle {@code max_entity_cramming} tue les créatures dès qu'elles sont plus de vingt-quatre
     * à se toucher. Mille vaches dans deux cent vingt-cinq blocs déclencheraient donc une hécatombe,
     * et le banc mesurerait une charge qui fond pendant qu'il la mesure.
     *
     * <p>On la désarme, et ce n'est pas une tricherie : c'est exactement ce que fait <b>tout serveur
     * qui possède une ferme de ce genre</b>. Un administrateur qui entasse mille bêtes a mis la règle
     * à zéro avant nous — sans quoi il n'aurait pas de ferme.
     */
    private static int pen(ServerLevel level, int count) {
        level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 0, level.getServer());
        Lanterne.LOG.info("[SCÈNE] max_entity_cramming mis à zéro : sans cela, l'enclos se vide.");

        Random dice = new Random(SEED);
        int ground = ground(level);
        int born = 0;

        for (int i = 0; i < count; i++) {
            double x = (dice.nextDouble() - 0.5d) * PEN_SIDE;
            double z = (dice.nextDouble() - 0.5d) * PEN_SIDE;

            Cow cow = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
            if (cow == null) {
                continue;
            }
            cow.snapTo(x, ground, z, dice.nextFloat() * 360f, 0f);
            cow.setPersistenceRequired();
            if (level.addFreshEntity(cow)) {
                born++;
            }
        }
        Lanterne.LOG.info("[SCÈNE] enclos : {} bête(s) dans un carré de {} blocs — soit {} par bloc.",
                born, PEN_SIDE, String.format(Locale.ROOT, "%.1f",
                        born / (double) (PEN_SIDE * PEN_SIDE)));
        return born;
    }

    /**
     * Le sol jonché d'objets.
     *
     * <h2>Des piles pleines, et non des unités</h2>
     *
     * <p>Le premier réflexe serait de poser des objets à l'unité. Ce serait une charge irréaliste et,
     * par accident, <b>auto-destructrice</b> : des objets à l'unité sont fusionnables, ils se
     * fondraient les uns dans les autres, et le banc mesurerait un tas qui se résorbe.
     *
     * <p>Or les vrais tas de serveur ne sont pas faits d'unités. Ils sont faits de <b>piles de
     * soixante-quatre</b> — sortie d'une ferme, d'un mineur automatique, d'un coffre qui déborde. Et
     * une pile pleine n'est pas fusionnable : {@code isMergable()} exige {@code count <
     * maxStackSize}.
     *
     * <p>Ce qui est à la fois la charge la plus réaliste et le pire cas pour le tick : chaque objet
     * paie son test de collision et sa gravité, sans jamais avoir la chance de disparaître dans un
     * voisin.
     *
     * <h2>La durée de vie</h2>
     *
     * <p>Un objet disparaît au bout de cinq minutes. Le banc dure moins longtemps, mais la marge est
     * mince et une disparition en cours de route ferait fondre la seconde phase. On la supprime :
     * {@code setUnlimitedLifetime()} est une fonction du jeu, pas un artifice du banc — c'est celle
     * qu'utilisent les objets protégés.
     */
    private static int items(ServerLevel level, int count) {
        // Un carré dont le côté croît avec la racine du nombre : on garde une densité constante
        // d'environ seize objets par bloc, ce qu'on observe sous une trémie qui déborde.
        int side = Math.max(4, (int) Math.ceil(Math.sqrt(count / 16d)));
        Random dice = new Random(SEED);
        int born = 0;
        int dropped = 0;

        for (int i = 0; i < count; i++) {
            double x = (dice.nextDouble() - 0.5d) * side;
            double z = (dice.nextDouble() - 0.5d) * side;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.floor(x), (int) Math.floor(z));

            ItemEntity item = new ItemEntity(level, x, ground + 0.2d, z,
                    new ItemStack(Items.COBBLESTONE, 64));
            // Sans vitesse initiale : le constructeur en donne une, et mille objets qui retombent
            // mesureraient la chute, pas le repos.
            item.setDeltaMovement(0d, 0d, 0d);
            item.setUnlimitedLifetime();
            if (level.addFreshEntity(item)) {
                born++;
            } else {
                dropped++;
            }
        }
        Lanterne.LOG.info("[SCÈNE] sol : {} objet(s) sur un carré de {} blocs{}.",
                born, side, dropped > 0 ? " (" + dropped + " refusé(s))" : "");
        return born;
    }

    /**
     * Un duplicateur qui part : des milliers de charges amorcées, échelonnées.
     *
     * <h2>Pourquoi les mèches sont étalées</h2>
     *
     * <p>Vingt mille charges qui explosent au même tick produiraient un seul pic monstrueux, puis plus
     * rien. Le banc mesure une <b>médiane</b> : il verrait un tick catastrophique noyé dans mille ticks
     * vides, et conclurait que tout va bien.
     *
     * <p>Or ce qu'on veut reproduire n'est pas l'instant du pic, c'est le <b>régime</b> : un
     * duplicateur en marche fait sauter des charges en continu, tick après tick, pendant que le joueur
     * regarde. Les mèches sont donc réparties sur toute la durée de la mesure, ce qui donne un flux
     * d'explosions au lieu d'une détonation unique.
     *
     * <h2>En l'air, et non posées</h2>
     *
     * <p>Les charges sont amorcées au-dessus du sol plutôt qu'enterrées. Une charge enterrée creuse un
     * cratère, et les suivantes explosent alors dans le vide : la charge de la seconde moitié du banc
     * ne serait plus celle de la première. En l'air, chaque explosion rencontre à peu près le même
     * terrain — le sol en dessous — et les deux moitiés restent comparables.
     *
     * <p>C'est le même souci que pour l'eau et la dynamite du banc à charge reconstituée : <b>une
     * charge qui détruit ce qu'elle mesure ne se mesure pas deux fois.</b> Ici on ne peut pas
     * reconstruire entre chaque tir, alors on s'arrange pour que la destruction reste marginale.
     */
    private static int dynamite(ServerLevel level, int count) {
        Random dice = new Random(SEED);
        // Un carré large : les charges ne doivent pas toutes se détruire entre elles avant d'exploser,
        // sinon on mesurerait une réaction en chaîne et non un flux régulier.
        int side = side(count);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        int born = 0;

        for (int i = 0; i < count; i++) {
            double x = (dice.nextDouble() - 0.5d) * side;
            double z = (dice.nextDouble() - 0.5d) * side;

            var charge = net.minecraft.world.entity.EntityType.TNT.create(
                    level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (charge == null) {
                continue;
            }
            charge.snapTo(x, ground + 6d, z, 0f, 0f);
            charge.setDeltaMovement(0d, 0d, 0d);
            // Étalées sur mille six cents ticks : la durée complète du banc, chauffe comprise. Chaque
            // tick voit donc éclater à peu près le même nombre de charges.
            charge.setFuse(20 + (i * 1600) / Math.max(1, count));
            if (level.addFreshEntity(charge)) {
                born++;
            }
        }
        Lanterne.LOG.info("[SCÈNE] dynamite : {} charge(s) amorcée(s) sur un carré de {} blocs, "
                + "mèches étalées sur 1600 ticks — soit environ {} explosion(s) par tick.",
                born, side, Math.max(1, born / 1600));
        return born;
    }

    /**
     * Compte les entités par famille, pour que le rapport dise sur quoi il a porté.
     *
     * <p>Un banc qui annonce « dix mille entités » sans dire lesquelles laisse croire que le chiffre
     * est comparable d'une exécution à l'autre. Mille vaches et nulle part d'objets, ou l'inverse, ne
     * mesurent pas du tout la même chose.
     */
    public static String describe(ServerLevel level) {
        int cows = 0;
        int items = 0;
        int others = 0;
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity) {
                items++;
            } else if (entity instanceof net.minecraft.world.entity.Mob) {
                cows++;
            } else {
                others++;
            }
        }
        return String.format(Locale.ROOT, "%d créature(s), %d objet(s), %d autre(s)",
                cows, items, others);
    }

    /** Position du centre de l'enclos, pour que l'observateur s'y poste. */
    public static BlockPos heart(ServerLevel level) {
        return new BlockPos(0, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0), 0);
    }
}
