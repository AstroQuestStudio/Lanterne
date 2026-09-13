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
 *
 * <h2>Les décors s'accumulent, et cela fausse tout ce qui touche aux blocs</h2>
 *
 * <p>Le monde d'essai est <b>le même d'une exécution à l'autre</b>. Les entités sont balayées avant
 * chaque charge — cela, l'auto-test le fait depuis longtemps. Les <b>blocs</b>, non.
 *
 * <p>Une dalle de pierre posée par la dynamite, une salle creusée par l'épreuve de lumière, un champ
 * de quatre mille parcelles laissé par la charge agricole : tout cela subsiste et continue de ticker
 * pendant les mesures suivantes.
 *
 * <p>Le cas s'est présenté et il a coûté un module. Une optimisation de la recherche d'eau des
 * parcelles cultivées a été écrite, mesurée, et n'a rien rendu — son compteur annonçait
 * <em>157,1 positions examinées sur 162</em>, c'est-à-dire que l'eau n'était presque jamais trouvée.
 * La cause n'était pas le module : les deux cent cinquante mille recherches venaient à peu près
 * toutes d'un champ <b>sans eau</b> laissé par une charge précédente, et non du champ irrigué que la
 * charge en cours venait de bâtir.
 *
 * <p>{@link #clearDecor} efface donc le décor des charges précédentes avant d'en bâtir un nouveau.
 * Sans quoi chaque charge mesure, en plus d'elle-même, les ruines de toutes celles qui l'ont
 * précédée — et le rapport attribue au mod ce qui appartient à l'archéologie.
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
        ARROWS,
        /**
         * Un champ de blé sur terre labourée, maintenu jeune pour qu.il pousse sans cesse.
         *
         * <p>Chaque tick aléatoire d.une culture appelle {@code CropBlock.getGrowthSpeed}, qui lit
         * <b>treize blocs</b> autour d.elle et alloue autant de positions — et qui est appelé
         * <em>avant</em> le tirage au sort de la croissance, donc vingt-quatre fois sur vingt-cinq
         * pour rien.
         *
         * <p>C.est le pendant végétal du four : beaucoup de calculs pour une réponse qui ne change
         * presque jamais. Un champ ne se relaboure pas tout seul, et l.hydratation d.une parcelle ne
         * varie qu.au rythme de la pluie.
         *
         * <h2>La limite de cette charge, dite avant son premier chiffre</h2>
         *
         * <p>Elle porte {@code randomTickSpeed} à 256 au lieu de 3, sans quoi un banc de vingt-cinq
         * secondes ne verrait qu.une poignée de ticks de culture. Les deux phases subissent le même
         * réglage, donc la comparaison reste juste — mais le <b>profil</b>, lui, est déformé : il
         * amplifie d.un facteur quatre-vingt-cinq le coût du tirage aléatoire lui-même par rapport à
         * celui des cultures.
         *
         * <p>C.est ce qui explique {@code PalettedContainer.get} à 33,8 % et
         * {@code FluidState.isRandomlyTicking} à 9,2 % dans le premier relevé : ce sont les frais du
         * tirage, et non ceux du blé. Les lire comme des cibles serait répéter l.erreur du compteur
         * qui doublait le coût de l.étape qu.il mesurait.
         */
        FARM,
        /**
         * Un serveur habité : tout à la fois, et dans les proportions qu.on rencontre vraiment.
         *
         * <h2>Pourquoi les charges isolées ne suffisent plus</h2>
         *
         * <p>Chaque charge de ce projet éprouve un poste et le met en évidence en le poussant à
         * l.extrême : huit mille objets, six mille flèches, quatre mille parcelles. C.est ce qu.il
         * faut pour juger un module — et c.est trompeur pour décider lequel écrire ensuite.
         *
         * <p>Un profil pris sur une charge extrême désigne le poste qu.on a soi-même exagéré. Pour
         * savoir <b>ce qui tick le plus sur un serveur réel</b>, il faut une charge où rien n.est
         * exagéré : des créatures, des objets, des fours, des cultures, dans les proportions d.un
         * village habité.
         *
         * <p>Elle ne touche à <b>aucun réglage</b> — ni entassement désarmé, ni vitesse de tick
         * aléatoire relevée. C.est sa raison d.être : le classement qu.elle rend doit être celui d.un
         * serveur, pas celui d.un laboratoire.
         */
        VILLAGE,
        /**
         * Un tapis d.orbes d.expérience, jamais mesuré.
         *
         * <p>Une orbe fait, à chaque tick, deux appels à {@code noCollision} — le chemin que le module
         * des collisions court-circuite déjà — plus une recherche du joueur le plus proche, et une fois
         * sur vingt une recherche des orbes voisines pour fusionner avec elles.
         *
         * <p>Les fermes à expérience en produisent par milliers, et c.est une plainte courante. Le
         * domaine n.avait jamais été éprouvé : cette charge dira s.il reste quelque chose à y prendre
         * une fois les collisions traitées.
         */
        ORBS,
        /**
         * Un village entier de villageois, l.intelligence la plus chère du jeu.
         *
         * <p>Un villageois ne se contente pas d.un objectif : il porte un {@code Brain} avec des
         * dizaines de comportements, des souvenirs, des capteurs qui balaient son voisinage, un métier,
         * un horaire et un lieu de travail. C.est, de loin, la créature la plus coûteuse de vanilla —
         * et la plainte la plus fréquente des serveurs qui ont un vrai village.
         *
         * <p>Le domaine n.avait jamais été éprouvé. Cette charge dira ce que le niveau de détail et la
         * densité y font déjà, et s.il reste un poste propre aux villageois.
         */
        VILLAGERS,
        /**
         * Des lignes de redstone alimentées par une horloge, jamais éprouvées.
         *
         * <p>La propagation d.un signal de redstone est réputée coûteuse, et vanilla en porte deux
         * versions : l.historique, et un {@code ExperimentalRedstoneWireEvaluator} qui ne s.active
         * qu.avec un indicateur de fonctionnalité expérimental. Le second dort donc sur tous les
         * serveurs ordinaires.
         *
         * <p>Cette charge dira ce que coûte le premier, et ce que le second ferait gagner — avant de
         * décider si l.on a le droit de l.allumer, car il ne change pas que la vitesse.
         */
        REDSTONE,
        /**
         * Des chaînes d.entonnoirs en marche, alimentées par des coffres pleins.
         *
         * <h2>Ce qui coûte dans un entonnoir, et où le chercher</h2>
         *
         * <p>Un entonnoir <b>en marche</b> ne travaille qu.un tick sur huit : après chaque transfert
         * réussi, {@code setCooldown(8)} impose sept ticks qui ne font que décrémenter un compteur.
         * C.est peu, et c.est pourquoi la première tentative de ce projet — l.endormir pendant sa
         * recharge — ne pouvait rien rapporter.
         *
         * <p>Mais ce tick sur huit fait <b>deux recherches de conteneur</b> pour déplacer <b>un seul
         * objet</b>. Soixante-quatre cailloux qui traversent un entonnoir, ce sont cent vingt-huit
         * recherches. C.est là qu.est le coût, et c.est ce que le transfert par lots divise.
         *
         * <p>La charge reproduit un transport d.objets réel : des chaînes de huit entonnoirs, un
         * coffre plein en tête, un coffre de réception en queue. Tous les entonnoirs y sont actifs —
         * c.est le cas que le module vise, et le seul où sa mesure veut dire quelque chose.
         */
        HOPPERS,
        /**
         * Une récolte au sol : des petites piles éparpillées, comme en laisse une ferme.
         *
         * <h2>Pourquoi {@link #ITEMS} ne pouvait pas servir</h2>
         *
         * <p>La charge des objets pose des piles <b>pleines</b> de soixante-quatre, et appelle
         * {@code setUnlimitedLifetime()}. Or vanilla refuse de fusionner dans ces deux cas :
         *
         * <pre>
         * return this.isAlive() &amp;&amp; this.pickupDelay != 32767
         *     &amp;&amp; this.age != -32768                      // &lt;- durée de vie illimitée
         *     &amp;&amp; this.age &lt; 6000
         *     &amp;&amp; item.getCount() &lt; item.getMaxStackSize(); // &lt;- pile pleine
         * </pre>
         *
         * <p>Huit mille piles pleines et immortelles ne peuvent donc <b>jamais</b> fusionner, et le
         * module s'y serait mesuré à zéro. La charge aurait rendu un chiffre juste sur une situation
         * où le module ne peut rien faire — le genre de mesure qui condamne un module innocent.
         *
         * <p>Celle-ci pose ce qu'une ferme laisse tomber : des piles de un à quatre, d'âge normal.
         */
        DROPS
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
            case "farm", "champ", "agriculture", "ble", "blé" -> Kind.FARM;
            case "village", "reel", "réel", "serveur" -> Kind.VILLAGE;
            case "orbs", "orbes", "xp", "experience" -> Kind.ORBS;
            case "villagers", "villageois", "pnj" -> Kind.VILLAGERS;
            case "redstone", "signal", "circuit" -> Kind.REDSTONE;
            case "hoppers", "entonnoirs", "tremies", "trémies", "tri" -> Kind.HOPPERS;
            case "drops", "recolte", "récolte", "chutes", "fusion" -> Kind.DROPS;
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
        if (builtKind == Kind.VILLAGERS) {
            // Six cents villageois se dispersent en une minute, et leur intelligence dépend de leur
            // voisinage : la seconde phase mesurerait un village étalé là où la première voyait une
            // foule. Un tir de contrôle, mod éteint des deux côtés, a rendu 54,07 contre 43,66 ms sur
            // cette charge — vingt pour cent d.écart pour rien.
            java.util.List<net.minecraft.world.entity.Entity> old = new java.util.ArrayList<>();
            for (net.minecraft.world.entity.Entity soul : level.getAllEntities()) {
                if (soul instanceof net.minecraft.world.entity.npc.villager.Villager) {
                    old.add(soul);
                }
            }
            for (net.minecraft.world.entity.Entity soul : old) {
                soul.discard();
            }
            int born = villagers(level, builtCount);
            Lanterne.LOG.info("[SCÈNE] remise à neuf : {} villageois balayé(s), {} reposé(s).",
                    old.size(), born);
            return;
        }
        if (builtKind == Kind.ORBS) {
            // Les orbes FUSIONNENT : la première phase en laisse deux fois moins que la seconde n.en
            // recevrait, et le banc mesurerait deux charges différentes. C.est le cinquième banc de ce
            // projet à rencontrer ce défaut, et le premier où il était prévisible.
            java.util.List<net.minecraft.world.entity.Entity> spent = new java.util.ArrayList<>();
            for (net.minecraft.world.entity.Entity old : level.getAllEntities()) {
                if (old instanceof net.minecraft.world.entity.ExperienceOrb) {
                    spent.add(old);
                }
            }
            for (net.minecraft.world.entity.Entity old : spent) {
                old.discard();
            }
            int born = orbs(level, builtCount);
            Lanterne.LOG.info("[SCÈNE] remise à neuf : {} orbe(s) balayée(s), {} reposée(s).",
                    spent.size(), born);
            return;
        }
        if (builtKind == Kind.DROPS) {
            // Cette charge FUSIONNE — c'est tout son objet. La première phase en laisse deux fois
            // moins que la seconde n'en recevrait, et le banc mesurerait deux charges différentes.
            // C'est la sixième fois que ce projet rencontre ce défaut, et la première où il était
            // prévu avant d'avoir menti.
            java.util.List<net.minecraft.world.entity.Entity> spent = new java.util.ArrayList<>();
            for (net.minecraft.world.entity.Entity old : level.getAllEntities()) {
                if (old instanceof ItemEntity) {
                    spent.add(old);
                }
            }
            for (net.minecraft.world.entity.Entity old : spent) {
                old.discard();
            }
            int born = harvest(level, builtCount);
            Lanterne.LOG.info("[SCÈNE] remise à neuf : {} objet(s) balayé(s), {} reposé(s).",
                    spent.size(), born);
            return;
        }
        if (builtKind == Kind.HOPPERS) {
            // La charge se consomme : les coffres de tête se vident, ceux de queue se remplissent, et
            // la seconde phase commencerait sur un transport déjà à moitié fait. On repose tout.
            //
            // Les compteurs de {@link Bulk}, eux, ne sont PAS remis à zéro ici. Ils le sont au
            // lancement de l'épreuve, et pas entre les phases : la phase sans le mod n'en incrémente
            // aucun, si bien que le total publié à la fin est exactement celui de la phase active.
            // Les remettre à zéro à chaque remise à neuf effacerait précisément le chiffre qu'on veut
            // lire.
            conveyors(level, builtCount);
            return;
        }
        if (builtKind == Kind.FARM) {
            field(level, fieldSide * fieldSide);
            return;
        }
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
            // <h2>La carte des hauteurs compte ce qu'on a bâti dessus</h2>
            //
            // La première version lisait getHeight(MOTION_BLOCKING_NO_LEAVES, 0, 0). Or (0, 0) est au
            // MILIEU du chantier du banc : après une charge d'entonnoirs, la carte des hauteurs y
            // renvoie l'altitude d'un coffre, soit le sol plus trois.
            //
            // Le sol gelé montait donc de trois blocs à chaque exécution. clearDecor, qui efface de
            // « sol + 1 » à « sol + 10 », nettoyait au-dessus des ruines au lieu de dedans ; le
            // chantier suivant se bâtissait par-dessus l'ancien ; et les mesures additionnaient des
            // charges empilées. Le nombre de blocs effacés restait identique d'une exécution à
            // l'autre — 58 081, puis 58 963 — alors même que douze mille cinq cents blocs de
            // chantier auraient dû s'y ajouter. C'est ce chiffre trop stable qui a trahi l'affaire.
            //
            // C'est la même faute que la dalle de pierre qui montait de quatre blocs à chaque
            // reconstruction, revenue ailleurs sous un autre visage.
            //
            // On demande donc son altitude au GÉNÉRATEUR de terrain, et non au monde. Le générateur
            // répond ce que le relief vaut là où rien n'a jamais été posé : une propriété du terrain,
            // que rien de ce que le banc construit ne peut déplacer.
            var source = level.getChunkSource();
            builtGround = source.getGenerator().getBaseHeight(0, 0,
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level, source.randomState());
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
        if (builtKind == Kind.REDSTONE) {
            pulse(level);
            return;
        }
        if (builtKind == Kind.FARM) {
            resetCrops(level);
            return;
        }
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

    /**
     * Un serveur habité, dans les proportions qu'on rencontre vraiment.
     *
     * <h2>D'où viennent ces proportions</h2>
     *
     * <p>Elles ne sont pas tirées au sort. Sur un serveur entre amis qui tourne depuis quelques mois,
     * un joueur à son point d'apparition a autour de lui, à peu près dans cet ordre : des créatures
     * apparues naturellement et jamais tuées, les objets tombés de ce qu'il a cassé, une poignée de
     * fours qui cuisent, un champ, et des coffres.
     *
     * <p>Le rapport entre ces postes compte plus que leur valeur absolue. C'est lui qui décide quel
     * module écrire ensuite — et c'est très exactement ce qu'aucune des charges extrêmes de ce projet
     * ne peut dire, puisque chacune exagère le poste qu'elle éprouve.
     *
     * <p><b>Aucun réglage n'est touché.</b> L'entassement reste armé, la vitesse de tick aléatoire
     * reste à trois. Une charge dont le classement servirait à décider du travail ne doit pas être une
     * charge truquée.
     */
    private static int village(ServerLevel level, int count) {
        int souls = Math.max(40, count / 4);
        int litter = Math.max(60, count / 2);
        int furnaces = Math.max(8, count / 40);
        int plots = Math.max(100, count / 4);

        int born = ring(level, souls);
        born += items(level, litter);
        int lit = fr.clubcitrouille.lanterne.report.Herd.ovens(level, furnaces, 96);
        int sown = plough(level, plots);

        Lanterne.LOG.info("[SCÈNE] village : {} créature(s), {} objet(s) au sol, {} four(s) allumé(s), "
                + "{} parcelle(s) cultivée(s) — et aucun réglage touché.",
                souls, litter, lit, sown);
        return born;
    }

    /**
     * Un tapis d.orbes d.expérience, dispersées comme au pied d.une ferme.
     *
     * <p>Les orbes fusionnent entre elles quand elles se touchent : les poser toutes au même endroit
     * les ferait disparaître en une poignée de ticks, et le banc mesurerait une charge qui fond
     * pendant qu.il la mesure. Elles sont donc réparties, et le rapport dit combien survivent.
     */
    private static int orbs(ServerLevel level, int count) {
        int side = Math.max(24, (int) Math.ceil(Math.sqrt(Math.max(1, count))) * 2);
        int ground = ground(level) + 1;
        Random dice = new Random(SEED);
        int born = 0;
        for (int i = 0; i < count; i++) {
            double x = (dice.nextDouble() - 0.5d) * side;
            double z = (dice.nextDouble() - 0.5d) * side;
            var orb = new net.minecraft.world.entity.ExperienceOrb(
                    level, x, ground, z, 1);
            if (level.addFreshEntity(orb)) {
                born++;
            }
        }
        Lanterne.LOG.info("[SCÈNE] orbes : {} orbe(s) d.expérience sur un carré de {} blocs.",
                born, side);
        return born;
    }

    /**
     * Un village de villageois, répartis comme dans une bourgade.
     *
     * <p>Ils sont posés sans métier ni lit : c.est leur <b>intelligence</b> qu.on mesure, pas leur
     * commerce. Un villageois sans emploi cherche du travail, ce qui fait tourner ses capteurs
     * exactement comme un villageois occupé — et sans dépendre d.un village bâti à la main qu.il
     * faudrait reconstruire entre les phases.
     */
    private static int villagers(ServerLevel level, int count) {
        int side = Math.max(24, (int) Math.ceil(Math.sqrt(Math.max(1, count))) * 3);
        int ground = ground(level) + 1;
        Random dice = new Random(SEED);
        int born = 0;
        for (int i = 0; i < count; i++) {
            double x = (dice.nextDouble() - 0.5d) * side;
            double z = (dice.nextDouble() - 0.5d) * side;
            var villager = net.minecraft.world.entity.EntityType.VILLAGER.create(
                    level, EntitySpawnReason.COMMAND);
            if (villager == null) {
                continue;
            }
            villager.snapTo(x, ground, z, 0f, 0f);
            if (level.addFreshEntity(villager)) {
                born++;
            }
        }
        level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 0, level.getServer());
        Lanterne.LOG.info("[SCÈNE] villageois : {} sur un carré de {} blocs.", born, side);
        return born;
    }

    /** Longueur des lignes de redstone, et les positions des sources à faire battre. */
    private static int wireLength;
    private static final java.util.List<BlockPos> SOURCES = new java.util.ArrayList<>();
    private static long pulses;

    /**
     * Des lignes de redstone parallèles, chacune avec sa source.
     *
     * <p>Le signal ne porte qu.à quinze blocs, mais une ligne plus longue reste utile : elle force le
     * moteur à recalculer l.extinction sur toute sa portée à chaque battement. Les lignes sont
     * séparées de deux blocs pour ne pas se toucher — on veut mesurer la propagation, pas un
     * enchevêtrement.
     */
    private static int wiring(ServerLevel level, int count) {
        int lines = Math.max(4, count / 32);
        wireLength = 24;
        int top = ground(level);
        var wire = net.minecraft.world.level.block.Blocks.REDSTONE_WIRE.defaultBlockState();
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        SOURCES.clear();
        pulses = 0L;
        for (int line = 0; line < lines; line++) {
            int z = line * 3;
            for (int x = 0; x < wireLength; x++) {
                cursor.set(x, top, z);
                level.setBlock(cursor, stone, 2);
                cursor.set(x, top + 1, z);
                level.setBlock(cursor, wire, 2);
            }
            SOURCES.add(new BlockPos(-1, top + 1, z));
        }
        Lanterne.LOG.info("[SCÈNE] redstone : {} ligne(s) de {} blocs, une source par ligne.",
                lines, wireLength);
        return 0;
    }

    /** Fait battre les sources : c.est le changement qui coûte, jamais l.état stable. */
    private static void pulse(ServerLevel level) {
        if (SOURCES.isEmpty()) {
            return;
        }
        var block = net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        boolean on = (level.getGameTime() / 4L) % 2L == 0L;
        for (BlockPos source : SOURCES) {
            level.setBlock(source, on ? block : air, 3);
            pulses++;
        }
    }

    /** Battements de source, pour que le rapport dise sur quoi il a porté. */
    public static long pulses() {
        return pulses;
    }

    /** Un troupeau dispersé, comme une faune naturelle et non comme un élevage. */
    private static int ring(ServerLevel level, int count) {
        return fr.clubcitrouille.lanterne.report.Herd.populate(level, 0d, 0d, count, 96);
    }

    /**
     * Un champ de taille ordinaire, sans relever la vitesse de tick aléatoire.
     *
     * <p>C'est la différence avec la charge {@link Kind#FARM} : ici le blé pousse au rythme du jeu.
     * Il en tickera peu — et c'est précisément le renseignement qu'on cherche, puisque la question est
     * de savoir <em>combien</em> l'agriculture pèse réellement à côté du reste.
     */
    private static int plough(ServerLevel level, int count) {
        int side = Math.max(6, (int) Math.ceil(Math.sqrt(Math.max(1, count))));
        int top = ground(level);
        var farmland = net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState()
                .setValue(net.minecraft.world.level.block.FarmlandBlock.MOISTURE, 7);
        var wheat = net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int sown = 0;
        // Décalé du centre : le champ ne doit pas recouvrir les fours ni le point d.apparition.
        int offset = 40;
        // Un canal d.eau au milieu, comme tout joueur en creuse un. Sans lui, deux choses fausses :
        // FarmlandBlock.isNearWater parcourt ses cent soixante-deux positions sans jamais sortir tôt,
        // ce qui gonfle artificiellement son poids dans le profil ; et surtout la terre SE DESSÈCHE
        // pendant la mesure, donc la charge se dégrade en cours de route — le défaut même qui a
        // produit quatre verdicts faux sur la dynamite.
        // Des canaux tous les huit blocs, ce qui est exactement ce que fait un joueur : la portée
        // d.irrigation est de quatre, donc huit est l.espacement qui couvre tout sans gâcher de place.
        // La première version n.avait qu.un canal central, si bien que les parcelles des bords
        // étaient hors de portée — un champ mal conçu, et non le champ d.un serveur.
        var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
        for (int x = offset; x < offset + side; x++) {
            for (int z = offset; z < offset + side; z++) {
                if ((z - offset) % 8 == 4) {
                    cursor.set(x, top, z);
                    level.setBlock(cursor, water, 2);
                    continue;
                }
                cursor.set(x, top, z);
                level.setBlock(cursor, farmland, 2);
                cursor.set(x, top + 1, z);
                level.setBlock(cursor, wheat, 2);
                sown++;
            }
        }
        return sown;
    }

    /** Côté du champ cultivé, et les positions qu'il occupe. */
    private static int fieldSide;
    private static long cropsReset;

    /**
     * Un champ de blé sur terre labourée hydratée.
     *
     * <h2>Pourquoi il faut forcer les ticks aléatoires</h2>
     *
     * <p>Le jeu tire trois positions au hasard par section et par tick. Sur un champ d'un millier de
     * parcelles réparties dans quelques sections, cela fait une poignée de ticks de culture par
     * seconde : bien trop peu pour qu'un banc de vingt-cinq secondes en dise quoi que ce soit.
     *
     * <p>On relève donc {@code randomTickSpeed}, exactement comme l'épreuve de l'élevage désarme
     * l'entassement. Ce n'est pas une tricherie : c'est la même opération que fait un serveur qui veut
     * des cultures rapides, et surtout <b>les deux phases du banc subissent le même réglage</b>. Ce
     * qu'on mesure reste le coût d'un tick de culture, simplement observé assez de fois pour que la
     * médiane veuille dire quelque chose.
     *
     * <h2>Et pourquoi le blé est remis à zéro</h2>
     *
     * <p>Un blé mûr ne pousse plus : {@code randomTick} sort à la première condition et ne coûte
     * presque rien. Sans remise à zéro, la seconde phase du banc mesurerait un champ mûr, c'est-à-dire
     * l'inverse de ce qu'on cherche — le même défaut que la dynamite qui détruisait son propre décor.
     */
    private static int field(ServerLevel level, int count) {
        fieldSide = Math.max(8, (int) Math.ceil(Math.sqrt(Math.max(1, count))));
        int top = ground(level);
        var farmland = net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState()
                .setValue(net.minecraft.world.level.block.FarmlandBlock.MOISTURE, 7);
        var wheat = net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int planted = 0;
        for (int x = -fieldSide / 2; x <= fieldSide / 2; x++) {
            for (int z = -fieldSide / 2; z <= fieldSide / 2; z++) {
                cursor.set(x, top, z);
                level.setBlock(cursor, farmland, 2);
                cursor.set(x, top + 1, z);
                level.setBlock(cursor, wheat, 2);
                cursor.set(x, top + 2, z);
                level.setBlock(cursor, air, 2);
                planted++;
            }
        }
        // Un champ souterrain ne pousserait pas : CropBlock exige une luminosité d'au moins neuf.
        // Le champ est donc à ciel ouvert, et le toit dégagé sur deux blocs.
        // Poser de la terre labourée fait tomber ce qui s.y trouvait : quelques centaines d.objets
        // au sol, que le contrôle préalable refuse à juste titre — ils fausseraient la densité.
        java.util.List<net.minecraft.world.entity.Entity> drops = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity loose : level.getAllEntities()) {
            if (loose instanceof ItemEntity || loose instanceof net.minecraft.world.entity.Mob) {
                drops.add(loose);
            }
        }
        for (net.minecraft.world.entity.Entity loose : drops) {
            loose.discard();
        }
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, level.getServer());
        level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 256, level.getServer());
        cropsReset = 0L;
        Lanterne.LOG.info("[SCÈNE] champ : {} parcelle(s) de blé sur terre labourée hydratée, "
                + "carré de {} blocs, vitesse de tick aléatoire portée à 256.", planted, fieldSide);
        return 0; // aucune entité : le contrôle préalable compterait un monde vide
    }

    /** Remet le blé à l'âge zéro : un champ mûr ne pousse plus et ne coûte plus rien. */
    private static void resetCrops(ServerLevel level) {
        if (fieldSide <= 0) {
            return;
        }
        int top = ground(level) + 1;
        var wheat = net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -fieldSide / 2; x <= fieldSide / 2; x++) {
            for (int z = -fieldSide / 2; z <= fieldSide / 2; z++) {
                cursor.set(x, top, z);
                if (level.getBlockState(cursor).is(net.minecraft.world.level.block.Blocks.WHEAT)) {
                    level.setBlock(cursor, wheat, 2);
                    cropsReset++;
                }
            }
        }
    }

    /** Parcelles remises à zéro, pour que le rapport dise sur quoi il a porté. */
    public static long cropsReset() {
        return cropsReset;
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

    /**
     * Efface le décor des charges précédentes.
     *
     * <h2>Ce qu.on efface, et ce qu.on laisse</h2>
     *
     * <p>On remet en air tout ce qui se trouve au-dessus du niveau du sol dans la zone de travail des
     * charges, et l.on rétablit le sol lui-même. C.est grossier — mais le monde d.essai n.a pas
     * vocation à être beau, il a vocation à être <b>le même au début de chaque mesure</b>.
     *
     * <p>La salle souterraine de l.épreuve de lumière n.est pas rebouchée : elle est à quarante blocs
     * sous le sol, hors de portée de toutes les autres charges, et la reboucher coûterait soixante-cinq
     * mille poses de bloc à chaque démarrage.
     */
    private static void clearDecor(ServerLevel level) {
        // <h2>Les règles de jeu qu'une charge laisse derrière elle</h2>
        //
        // Le champ porte RANDOM_TICK_SPEED à 256 — il le doit, c'est ce qui fait pousser le blé
        // pendant la mesure — et ne le remet jamais. Or le monde est CONSERVÉ d'une épreuve à
        // l'autre. Toute charge lancée après un champ héritait donc d'un monde qui ticke
        // quatre-vingt-cinq fois trop vite, sans que rien ne l'annonce.
        //
        // Les conséquences se sont vues avant d'être comprises : les feuillages laissés en l'air par
        // ce même nettoyage se décomposaient à toute vitesse et semaient des milliers d'objets au
        // sol, jusqu'à faire refuser la mesure par le contrôle préalable — sur une charge qui n'a
        // rien à voir avec l'agriculture.
        //
        // On remet donc les règles à leur valeur vanilla avant chaque construction. Chaque charge
        // repose ensuite les siennes ; aucune n'hérite de celles d'une autre.
        level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 3, level.getServer());
        level.getGameRules().set(GameRules.SPAWN_MOBS, true, level.getServer());
        level.getGameRules().set(GameRules.MAX_ENTITY_CRAMMING, 24, level.getServer());

        int looseBefore = looseItems(level);
        int top = ground(level);
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var grass = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int reach = 120;
        int cleared = 0;
        for (int x = -reach; x <= reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                for (int y = top + 1; y <= top + CEILING; y++) {
                    cursor.set(x, y, z);
                    // Le drapeau 256 — UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS — coupe le lâcher du
                    // contenu. Sans lui, effacer le décor d'une charge à coffres sèmerait des
                    // milliers d'objets dans le chantier, et la charge suivante mesurerait le
                    // ramassage de la précédente. C'est la faute exacte qui rendait l'épreuve des
                    // entonnoirs de Kitchen non reproductible, découverte là-bas, corrigée ici avant
                    // qu'elle ne se reproduise.
                    if (!level.getBlockState(cursor).isAir() && level.setBlock(cursor, air, 2 | 256)) {
                        cleared++;
                    }
                }
                cursor.set(x, top, z);
                if (!level.getBlockState(cursor).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) {
                    level.setBlock(cursor, grass, 2);
                    cleared++;
                }
            }
        }
        Lanterne.LOG.info("[SCÈNE] décor précédent effacé : {} bloc(s) remis à l.état initial sur un "
                + "carré de {} blocs, du sol+1 au sol+{}. Sans cela, chaque charge mesure les ruines "
                + "des précédentes.", cleared, reach * 2, CEILING);
        // <h2>Le nettoyage salissait ce qu'il nettoyait</h2>
        //
        // Le relevé a tranché une question qu'on ne savait pas poser : sur un monde VIERGE, sans un
        // seul coffre, effacer le décor faisait apparaître cinq mille cent soixante-quinze objets au
        // sol. Ce ne sont pas des contenus de conteneurs — il n'y en avait aucun — mais de la
        // végétation : retirer la moitié haute d'une plante double fait tomber la moitié basse avec
        // ses graines, et les feuillages privés de tronc se décomposent en pousses et en pommes.
        //
        // Ces objets restaient dans le monde pendant toute la mesure, s'ajoutaient d'une exécution à
        // l'autre, et ont fini par faire refuser deux bancs pour « entités résiduelles ». Le
        // nettoyage se nettoie donc lui-même.
        int spilled = looseItems(level) - looseBefore;
        int swept = sweepLoose(level);
        Lanterne.LOG.info("[SCÈNE] objets lâchés par le nettoyage lui-même : {} — balayés ({} au "
                + "total). La végétation arrachée sème ses graines ; on ne les laisse pas.",
                Math.max(0, spilled), swept);
    }

    public static int build(ServerLevel level, Kind kind, int count, int radius) {
        clearDecor(level);
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
            case FARM -> field(level, count);
            case VILLAGE -> village(level, count);
            case ORBS -> orbs(level, count);
            case VILLAGERS -> villagers(level, count);
            case REDSTONE -> wiring(level, count);
            case HOPPERS -> conveyors(level, count);
            case DROPS -> harvest(level, count);
        };
    }

    /**
     * Une récolte au sol : de petites piles, d'âge normal, assez proches pour se rejoindre.
     *
     * <p>Trois détails décident de tout ici, et chacun est un frein de vanilla qu'il ne faut
     * <b>pas</b> désarmer par mégarde dans la charge elle-même :
     *
     * <ul>
     *   <li>La pile est <b>partielle</b> — une pile pleine est déclarée non fusionnable.</li>
     *   <li>L'âge est <b>normal</b> — {@code setUnlimitedLifetime()} le met à -32768, que
     *       {@code isMergable()} refuse. Une charge immortelle ne fusionne jamais.</li>
     *   <li>Le délai de ramassage est ordinaire — 32767 signifie « jamais ramassable », et disqualifie
     *       là aussi.</li>
     * </ul>
     *
     * <p>Une charge qui enfreindrait l'un des trois mesurerait un module empêché, et conclurait qu'il
     * ne sert à rien.
     */
    private static int harvest(ServerLevel level, int count) {
        int side = Math.max(4, (int) Math.ceil(Math.sqrt(count / 16d)));
        Random dice = new Random(SEED);
        int born = 0;

        for (int i = 0; i < count; i++) {
            double x = (dice.nextDouble() - 0.5d) * side;
            double z = (dice.nextDouble() - 0.5d) * side;
            int floor = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.floor(x), (int) Math.floor(z));
            ItemEntity grain = new ItemEntity(level, x, floor + 0.2d, z,
                    new ItemStack(Items.WHEAT, 1 + dice.nextInt(4)));
            grain.setDeltaMovement(0d, 0d, 0d);
            grain.setPickUpDelay(40);
            if (level.addFreshEntity(grain)) {
                born++;
            }
        }
        Lanterne.LOG.info("[SCÈNE] récolte : {} pile(s) de blé de 1 à 4, sur un carré de {} blocs. "
                + "Âge normal et piles partielles — sans quoi vanilla refuserait toute fusion.",
                born, side);
        return born;
    }

    /**
     * Hauteur du nettoyage, au-dessus du sol.
     *
     * <p>Dix suffisaient tant que le sol gelé était juste. Il ne l'était pas — voir {@link #ground} —
     * et des chantiers se sont empilés sur plusieurs exécutions. Vingt-quatre rattrape ce qui a pu
     * s'accumuler avant la correction, et couvre largement la plus haute des charges.
     */
    private static final int CEILING = 24;

    /** Longueur d'une chaîne d'entonnoirs. Huit : ce qu'on tire d'un coffre à l'autre sans y penser. */
    private static final int LINK = 8;
    /** Demi-côté du chantier, en blocs. Six chunks : dans le rayon de simulation, à coup sûr. */
    private static final int YARD = 96;
    /** Entonnoirs réellement posés à la dernière construction. */
    private static int laid;

    /**
     * Des chaînes d'entonnoirs en marche.
     *
     * <h2>Le décompte rendu est zéro, et c'est voulu</h2>
     *
     * <p>Cette charge ne crée aucune entité. Rendre le nombre d'entonnoirs ferait refuser la mesure
     * par le contrôle préalable, qui compte des créatures vivantes et n'en trouverait aucune — c'est
     * exactement l'erreur qui avait fait rejeter la charge des lampes.
     *
     * <p>Le contrôle propre à cette charge est ailleurs, et il est meilleur : le rapport publie le
     * nombre de transferts effectués. <b>Zéro transfert veut dire que rien ne ticke</b>, et aucune
     * durée mesurée dans ces conditions ne vaut d'être citée.
     */
    private static int conveyors(ServerLevel level, int count) {
        int top = ground(level);
        int y = top + 2;
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var chestBlock = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState();
        var hopperEast = net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HopperBlock.FACING,
                        net.minecraft.core.Direction.EAST);
        // <h2>Une charge qui fuit, et le compteur qui le dira</h2>
        //
        // Ce chantier n'est censé créer AUCUNE entité : tout va de conteneur en conteneur. Une
        // exécution a pourtant vu apparaître trente-six mille objets au sol en quarante-deux
        // secondes, et le contrôle préalable a refusé de mesurer — à juste titre.
        //
        // On relève donc l'état avant et après la construction. Si le compte monte pendant la
        // construction, c'est la pose des blocs qui lâche du contenu ; s'il monte pendant la
        // mesure, ce sont les transferts. Les deux fautes ne se corrigent pas au même endroit, et
        // deviner laquelle c'est a déjà coûté assez de temps.
        // Aucune créature ne doit apparaître : leur nombre varierait d'une phase à l'autre, et l'on
        // mesurerait la différence de peuplement au lieu de celle des entonnoirs.
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, level.getServer());

        int before = looseItems(level);
        int swept = sweepLoose(level);
        laid = 0;
        int chains = 0;

        for (int z = -YARD; z <= YARD && laid < count; z += 2) {
            for (int x = -YARD; x + LINK + 1 <= YARD && laid < count; x += LINK + 2) {
                BlockPos feeder = new BlockPos(x, y + 1, z);
                level.setBlock(feeder, air, 2 | 256);
                level.setBlock(feeder, chestBlock, 2);
                if (level.getBlockEntity(feeder) instanceof net.minecraft.world.level.block.entity
                        .ChestBlockEntity chest) {
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        chest.setItem(slot, new net.minecraft.world.item.ItemStack(
                                net.minecraft.world.item.Items.COBBLESTONE, 64));
                    }
                    chest.setChanged();
                }

                for (int i = 0; i < LINK && laid < count; i++) {
                    BlockPos link = new BlockPos(x + i, y, z);
                    level.setBlock(link, air, 2 | 256);
                    level.setBlock(link, hopperEast, 2);
                    laid++;
                }

                BlockPos sink = new BlockPos(x + LINK, y, z);
                level.setBlock(sink, air, 2 | 256);
                level.setBlock(sink, chestBlock, 2);
                chains++;
            }
        }

        int after = looseItems(level);
        Lanterne.LOG.info("[SCÈNE] {} entonnoir(s) en {} chaîne(s), chacune alimentée par un coffre "
                + "plein et vidée dans un coffre. Tous actifs : c'est le cas que le transfert par "
                + "lots vise.", laid, chains);
        Lanterne.LOG.info("[SCÈNE] objets au sol — trouvés : {} (balayés : {}) · après la pose : {} "
                + "· créés par la pose : {}. Cette charge doit n'en créer AUCUN.",
                before, swept, after, after);
        return 0;
    }

    /**
     * Efface les objets au sol avant de bâtir.
     *
     * <p>Une charge qui hérite des objets de la précédente ne mesure pas ce qu'on lui demande, et le
     * contrôle préalable refuse — à juste titre — de conclure. C'est la troisième fois que ce projet
     * pose ce balayage, après les créatures et les orbes : toute charge qui laisse des traces doit
     * les effacer elle-même, et non compter sur celle qui suit.
     */
    private static int sweepLoose(ServerLevel level) {
        java.util.List<net.minecraft.world.entity.Entity> loose = new java.util.ArrayList<>();
        for (var soul : level.getAllEntities()) {
            // Objets ET créatures : le nettoyage du décor sème les uns, l'apparition naturelle
            // apporte les autres, et cette charge ne veut ni des uns ni des autres.
            if (soul instanceof net.minecraft.world.entity.item.ItemEntity
                    || soul instanceof net.minecraft.world.entity.Mob) {
                loose.add(soul);
            }
        }
        for (var soul : loose) {
            soul.discard();
        }
        return loose.size();
    }

    /** Objets au sol présents dans le monde. Sert à savoir si la charge fuit, et quand. */
    private static int looseItems(ServerLevel level) {
        int total = 0;
        for (var soul : level.getAllEntities()) {
            if (soul instanceof net.minecraft.world.entity.item.ItemEntity) {
                total++;
            }
        }
        return total;
    }

    /** Entonnoirs posés par la dernière charge, pour le rapport. */
    public static int hoppersLaid() {
        return laid;
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
