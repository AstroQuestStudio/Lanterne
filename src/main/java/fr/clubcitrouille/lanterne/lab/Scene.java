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
 * <p>C'est ce qui écarte, pour l'instant, deux charges pourtant intéressantes. La dynamite
 * <em>détruit le terrain</em> qu'elle mesure : la seconde phase ne travaillerait plus sur le même
 * monde que la première, et l'écart mesuré ne vaudrait rien. L'eau se <em>stabilise</em> : au bout de
 * quelques secondes elle ne coule plus, et la seconde phase mesurerait une nappe au repos.
 *
 * <p>Les deux méritent un banc, mais un banc d'un autre genre — à charge reconstituée entre chaque
 * relevé. Les annoncer comme mesurées ici serait le genre de raccourci qui produit les chiffres faux
 * que ce projet passe son temps à traquer.
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
        TNT
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
        if (builtKind != Kind.TNT) {
            return; // seule la dynamite modifie le monde ; les autres charges sont réversibles
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
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0) + 4;
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

    /** L'étendue du champ de charges, calculée au même endroit par la pose et par la reconstruction. */
    private static int side(int count) {
        return Math.max(16, (int) Math.ceil(Math.sqrt(count)) * 2);
    }

    public static int build(ServerLevel level, Kind kind, int count, int radius) {
        builtKind = kind;
        builtCount = count;
        builtRadius = radius;
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
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
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
