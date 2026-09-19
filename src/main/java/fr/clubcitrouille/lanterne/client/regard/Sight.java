package fr.clubcitrouille.lanterne.client.regard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.ponder.Ease;
import fr.clubcitrouille.lanterne.content.regard.RegardAsk;
import fr.clubcitrouille.lanterne.content.regard.RegardTell;
import fr.clubcitrouille.lanterne.core.Bulk;
import fr.clubcitrouille.lanterne.core.Config;
import fr.clubcitrouille.lanterne.core.Gather;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le Regard : une tooltip HUD qui dit ce qu'il y a vraiment dans le bloc ou l'entité visé(e).
 *
 * <h2>Dans l'esprit de Jade/HWYLA, construit pour ce moteur</h2>
 *
 * <p>La présentation reprend ce que Jade et son ancêtre HWYLA ont établi comme évident dix ans avant
 * ce mod — un bandeau compact, une icône réelle du bloc ou de l'objet à gauche, un nom en tête, un
 * indicateur binaire (l'outil en main peut-il récolter ceci ?) plutôt qu'une phrase, le nom du mod
 * d'origine en dessous. Rien de tout cela n'est copié : c'est redessiné avec la palette déjà posée
 * par {@code client.screen.Palette}/{@code client.Dials} (fond {@link #PANEL}, filet {@link #AMBER}),
 * et bâti sur {@code GuiGraphicsExtractor} — l'API 2D de CE moteur, vérifiée par {@code javap} sur le
 * jar patché, jamais sur les sources obsolètes de {@code .mcsrc/}.
 *
 * <h2>Une première version texte plat, revue après un vrai test en jeu</h2>
 *
 * <p>La toute première version de ce module empilait des lignes de texte plates dans un rectangle
 * uni, immobile, et affichait une ligne « Cadence Lanterne (estimée) » — une métrique interne au mod,
 * utile à qui le débogue, inutile à qui joue. Un test en jeu par captures réelles ({@code
 * RegardSelfCheck}) a confirmé les deux défauts : rien n'indiquait au joueur ce qui comptait, et rien
 * ne bougeait. Cette version corrige les deux. La cadence de simulation reste calculée par
 * {@code core.Cadence} et pilote toujours le jeu en coulisse — d'autres modules du socle en dépendent
 * — mais elle n'a plus sa place ICI : un joueur qui vise une vache veut savoir si elle a mal, pas à
 * quelle fréquence le serveur la fait réfléchir.
 *
 * <h2>Compact, hiérarchisé, et rien d'inutile</h2>
 *
 * <ul>
 *   <li><b>Une icône réelle en tête</b> — {@link #blockCard} prend l'objet du bloc visé
 *       ({@code Block#asItem}), {@link #entityCard} l'œuf d'apparition de l'entité quand il existe
 *       ({@code SpawnEggItem.byId}), {@link #itemPileCard} la pile elle-même. Une icône se lit plus
 *       vite qu'un mot, et c'est ce que Jade a compris avant tout le monde.</li>
 *   <li><b>Un badge de récolte, pas une phrase</b> — voir {@link #blockCard} : dès qu'un bloc exige le
 *       bon outil ({@code BlockState#requiresCorrectToolForDrops}), une coche verte ou une croix rouge
 *       dit en un coup d'œil si l'outil en main convient
 *       ({@code ItemStack#isCorrectToolForDrops}). Tue depuis toujours par vanilla, jamais montré par
 *       aucun mod générique de ce serveur — et strictement plus utile qu'un pourcentage de cadence.
 *       Absent pour les blocs qui n'exigent rien : un badge qui dirait « oui » sur chaque bloc du jeu
 *       n'apprendrait rien, voir la mission de ce module sur le bruit inutile.</li>
 *   <li><b>Les articles dominants, pas une grille</b> — voir {@link #appendContainer} et la Javadoc de
 *       {@code content.regard.RegardTell#dominant} : un coffre de bazar avec trente-deux objets
 *       différents ne montre que les deux ou trois qui pèsent vraiment, triés par quantité, en ligne
 *       « icône · × quantité · nom ». Le nombre d'emplacements occupés (« 3 / 27 ») reste affiché à
 *       part : deux informations différentes, jamais mélangées.</li>
 *   <li><b>Un four montré pour ce qu'il est</b> — voir {@link #appendFurnace} : la case d'entrée et la
 *       case de combustible, chacune avec son icône et sa quantité, plutôt que fondues dans un
 *       classement qui n'a pas de sens sur un conteneur à trois cases fixes. Les barres de cuisson et
 *       de combustible restantes complètent, elles n'annoncent rien de nouveau.</li>
 * </ul>
 *
 * <h2>Ce qui reste relié aux VRAIS modules de Lanterne</h2>
 *
 * <p>Deux choses qu'aucun mod d'inspection générique ne peut montrer, parce qu'elles n'existent que
 * dans CE mod :
 *
 * <ul>
 *   <li><b>Le vrai débit d'un entonnoir</b> — voir {@link #appendHopperInfo} — lit {@code Config.BULK}
 *       et {@code core.Bulk.LOT} : le nombre RÉEL d'objets qu'il déplace par recherche sur CE serveur,
 *       pas une moyenne générique.</li>
 *   <li><b>La vraie portée de fusion d'un tas d'objets au sol</b> — voir
 *       {@link #itemPileCard} — lit {@code core.Gather}, les constantes que le serveur applique
 *       réellement, plutôt que d'afficher le comportement vanilla si ce module l'a remplacé.</li>
 * </ul>
 *
 * <h2>Vivant : une image qui apparaît, glisse d'une cible à l'autre, ne coupe jamais net</h2>
 *
 * <p>Trois transitions, chacune bâtie avec {@code client.ponder.Ease} — la même petite bibliothèque de
 * courbes que {@code client.ponder.Scene}/{@code Stage} utilisent déjà pour Le Guide, pas une
 * dépendance nouvelle. {@link #draw} tient une horloge par transition (un instant de départ en
 * nanosecondes) et en tire un avancement {@code k} de 0 à 1 via {@code Ease.over}, exactement comme
 * {@code Scene#place} tire le sien pour animer la caméra :
 *
 * <ul>
 *   <li><b>Apparition</b> (aucune cible visée, puis une) : fondu {@code Ease.out}, un léger glissement
 *       vertical qui se résorbe, et un « pop » d'échelle qui dépasse légèrement 1 avant de s'y poser
 *       ({@code Ease.back}) — le même ressort que {@code Scene#drawBlock} donne à un bloc qui vient de
 *       tomber.</li>
 *   <li><b>Changement de cible</b> (une créature en regarde une autre, un coffre succède à un four) :
 *       le panneau ne s'éteint jamais pour se rallumer — un clignotement se lirait comme une erreur de
 *       rendu, pas comme une transition. À la place, un « pop » plus discret et un éclat bref du filet
 *       supérieur ({@code Ease.smooth} entre une teinte claire et {@link #AMBER}) signalent le
 *       changement sans jamais faire disparaître l'information.</li>
 *   <li><b>Disparition</b> (plus aucune cible) : la dernière carte connue reste affichée le temps d'un
 *       fondu {@code Ease.in} qui rétrécit et remonte légèrement — la même bascule qu'un figurant du
 *       Guide qui « fond » (voir {@code Scene#drawBlock}, la branche {@code melt}) plutôt que de
 *       s'effacer d'une image sur l'autre.</li>
 * </ul>
 *
 * <p>La transformation est portée par {@code graphics.pose()} — le {@code Matrix3x2fStack} 2D de
 * {@code GuiGraphicsExtractor}, pas un {@code PoseStack} 3D — avec exactement l'idiome déjà en place
 * dans {@code client.ponder.Stage#block} : {@code pushMatrix / translate / scale / popMatrix} autour
 * de tout ce que {@link #paint} dessine, icônes d'objets comprises puisqu'elles passent par la même
 * pile. Les barres de vie/cuisson/combustible lissent en plus leur propre valeur d'une image à
 * l'autre par un simple {@code Ease.lerp} vers la vraie valeur — une vache qui encaisse un coup voit
 * sa barre glisser, pas sauter.
 *
 * <p>Cette couche d'animation n'a pas été re-mesurée séparément dans cette session — aucun client
 * n'a été lancé, voir plus bas. Elle ajoute par image, quand un panneau est affiché, quelques dizaines
 * d'appels {@code fill}/{@code rotate}/{@code scale} : le même ordre de grandeur que ce que
 * {@code Stage}/{@code Scene} font déjà pendant Le Guide sans que {@code lab.Radiographie} ne l'ait
 * jamais signalé comme un poste notable.
 *
 * <h2>Ce qui a été volontairement laissé de côté cette session</h2>
 *
 * <p>Le G-buffer de normales ({@code client.upscale.Gbuffer}, {@code CameraUniforms}) aurait permis un
 * contour de surbrillance plus propre qu'un simple cadre 2D. Ce n'est PAS tenté ici : composer une
 * passe de détection de contours écran-espace correcte est un chantier de rendu 3D à part entière, et
 * la consigne de ce module est explicite — une tooltip HUD 2D, sans risque de rendu 3D.
 *
 * <h2>Pourquoi le contenu d'un conteneur passe par le réseau</h2>
 *
 * <p>Un client ne connaît JAMAIS le contenu d'un coffre qu'il n'a pas ouvert, ni la progression de
 * cuisson d'un four qu'il regarde sans l'avoir fait — vanilla ne synchronise ces informations qu'à
 * travers un menu ouvert. {@link RegardAsk}/{@link RegardTell} (voir {@code content.regard.Regard}
 * côté serveur) comblent ce trou par une vraie question au serveur, jamais par une supposition — même
 * principe que {@code content.painting.Hoard} envers les images.
 *
 * <h2>Le coût, mesuré et tenu à zéro quand ce n'est pas nécessaire</h2>
 *
 * <p>Ce module tourne à chaque image quand actif ({@link #draw}), donc trois règles tenues sans
 * exception :
 *
 * <ul>
 *   <li><b>Aucun rayon supplémentaire n'est lancé.</b> La cible vient de
 *       {@code Minecraft.hitResult}/{@code crosshairPickEntity}, déjà calculés par le jeu à chaque
 *       image pour ses propres besoins (viser, attaquer) — les lire coûte deux accès de champ.</li>
 *   <li><b>Aucune requête réseau par image.</b> {@link #maybeAsk} n'envoie qu'au changement de cible
 *       ou toutes les {@link #ASK_PERIOD_TICKS} ticks, et seulement si la cible est réellement un
 *       conteneur — une pierre ou un mouton ne déclenchent jamais de paquet.</li>
 *   <li><b>Rien ne s'exécute quand c'est masqué.</b> {@link #draw} sort dès la première condition
 *       fausse — un menu ouvert, l'interface masquée, ou le réglage coupé — avant tout calcul.</li>
 * </ul>
 *
 * <p>Coût mesuré par {@code lab.Radiographie} (voir son rapport automatique) pour la version texte
 * plat d'origine : négligeable devant le reste du rendu d'une image. Désactivable (réglage « regard »,
 * voir {@code core.ClientConfig}) si jamais ce n'était pas vrai sur une machine donnée.
 */
public final class Sight {
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int GOOD = 0xFF6BCB77;
    private static final int WARN = 0xFFE8A33D;
    private static final int BAD = 0xFFE05B5B;
    /** Le nom du mod d'origine, sous le titre — bleu discret, dans l'esprit Jade. */
    private static final int MOD = 0xFF7C9CBF;
    private static final int SHADOW = 0xFF000000;
    /** Le fond d'une case d'icône : légèrement en retrait du panneau, comme une case d'inventaire. */
    private static final int SLOT_BG = 0x40000000;
    /** La teinte claire d'où part l'éclat du filet supérieur au changement de cible. */
    private static final int FLASH = 0xFFFFF3D6;

    private static final int PADDING = 6;
    private static final int LINE_H = 10;
    private static final int BAR_H = 3;
    private static final int BAR_W = 90;
    /** Taille d'une case d'icône — en-tête, articles dominants, four, équipement : une seule taille. */
    private static final int ICON = 18;
    private static final int TITLE_H = 10;
    private static final int SUB_H = 9;
    private static final int SEP_GAP = 6;
    /** Le badge de récolte : une petite coche ou croix, jamais plus grande que le texte qu'elle résume. */
    private static final int BADGE = 8;
    private static final int MIN_WIDTH = 100;
    private static final int MAX_WIDTH = 220;
    private static final int TOP_MARGIN = 6;

    /** Entre deux rafraîchissements d'un même conteneur, en ticks — la moitié d'une seconde. */
    private static final long ASK_PERIOD_TICKS = 10L;

    /** Apparition depuis rien : fondu + pop + glissement. Voir la Javadoc de classe. */
    private static final float APPEAR_MS = 180f;
    /** Changement d'une cible à l'autre : pop discret + éclat du filet, jamais de fondu à zéro. */
    private static final float SWAP_MS = 150f;
    /** Disparition : la dernière carte connue s'efface plutôt que de sauter au néant. */
    private static final float VANISH_MS = 130f;
    /** Vitesse de rattrapage d'une barre vers sa vraie valeur, par image — un simple lerp répété. */
    private static final float BAR_SMOOTH = 0.3f;

    private static final EquipmentSlot[] EQUIPMENT_ORDER = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
    };

    /** La dernière réponse reçue du serveur. Peut désigner une position qu'on ne regarde plus. */
    private static RegardTell lastTell;

    private static BlockPos lastAskedPos;
    private static long lastAskedTick = Long.MIN_VALUE;

    /** Espace de noms -> nom d'affichage du mod. Une recherche dans la liste des mods par image
     * serait un gaspillage pour une valeur qui ne change jamais en cours de partie. */
    private static final Map<String, String> MOD_NAMES = new HashMap<>();

    /** Identité de la carte actuellement verrouillée ({@code "block:<pos>"}/{@code "entity:<uuid>"}). */
    private static String lastKey;
    /** Vrai si la transition en cours part de rien (première apparition), faux si c'est un changement
     * de cible — les deux n'ont pas la même animation, voir la Javadoc de classe. */
    private static boolean cameFromNothing;
    private static long transitionStartNanos;
    /** Zéro tant qu'une cible est visée ; l'instant où elle a cessé de l'être, sinon. */
    private static long vanishStartNanos;
    /** La dernière carte construite — rejouée telle quelle pendant le fondu de disparition. */
    private static Card lastCard;
    /** Valeurs de barres lissées, par {@code "<clé de cible>|<étiquette>"}. Vidée à chaque changement
     * de cible pour ne jamais grossir sans fin ni mélanger deux créatures différentes. */
    private static final Map<String, Float> smoothedBars = new HashMap<>();

    private Sight() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(Sight::onRegisterLayers);
    }

    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR,
                Identifier.fromNamespaceAndPath(Lanterne.ID, "regard"), Sight::draw);
    }

    /** Appelée par {@code content.regard.Regard} quand une réponse arrive, sur le fil client. */
    public static void accept(RegardTell tell) {
        lastTell = tell;
    }

    // ------------------------------------------------------------------------------------------
    // La boucle d'image : choisir la cible, faire vivre la transition, peindre.
    // ------------------------------------------------------------------------------------------

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!Settings.regard() || mc.player == null || mc.level == null
                || mc.gui.hud.isHidden() || mc.gui.screen() != null) {
            return;
        }

        Card card = buildCard(mc);
        long now = System.nanoTime();

        if (card != null) {
            vanishStartNanos = 0L;
            boolean changed = !card.key.equals(lastKey);
            if (changed) {
                cameFromNothing = lastKey == null;
                transitionStartNanos = now;
                lastKey = card.key;
                smoothedBars.clear();
            }
            lastCard = card;

            float ageMs = msSince(transitionStartNanos, now);
            float alpha;
            float pop;
            float rise;
            int accent;
            if (cameFromNothing) {
                float k = Ease.over(ageMs, 0f, APPEAR_MS);
                alpha = Ease.out(k);
                pop = Ease.lerp(0.88f, 1f, Ease.back(k));
                rise = Ease.lerp(6f, 0f, Ease.out(k));
                accent = AMBER;
            } else {
                float k = Ease.over(ageMs, 0f, SWAP_MS);
                alpha = 1f;
                pop = Ease.lerp(0.95f, 1f, Ease.back(k));
                rise = 0f;
                accent = mixRgb(FLASH, AMBER, Ease.smooth(k));
            }
            paint(graphics, mc, card, alpha, pop, rise, accent);
            return;
        }

        // Plus aucune cible : on continue de montrer la dernière carte connue, en train de s'effacer,
        // plutôt que de couper net — voir la Javadoc de classe.
        if (lastCard == null) {
            return;
        }
        if (vanishStartNanos == 0L) {
            vanishStartNanos = now;
        }
        float vk = Ease.over(msSince(vanishStartNanos, now), 0f, VANISH_MS);
        if (vk >= 1f) {
            lastCard = null;
            lastKey = null;
            vanishStartNanos = 0L;
            smoothedBars.clear();
            return;
        }
        float alpha = 1f - Ease.in(vk);
        float pop = Ease.lerp(1f, 0.92f, Ease.in(vk));
        float rise = Ease.lerp(0f, -5f, Ease.in(vk));
        paint(graphics, mc, lastCard, alpha, pop, rise, AMBER);
    }

    private static float msSince(long startNanos, long now) {
        return (now - startNanos) / 1_000_000f;
    }

    /** La cible du moment, telle que {@code Minecraft} l'a déjà calculée — voir la Javadoc de classe. */
    private static Card buildCard(Minecraft mc) {
        Entity target = mc.crosshairPickEntity;
        if (target != null) {
            return entityCard(mc, target);
        }
        if (mc.hitResult instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK) {
            return blockCard(mc, blockHit.getBlockPos());
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Bloc
    // ------------------------------------------------------------------------------------------

    private static Card blockCard(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        Card card = new Card();
        card.key = "block:" + pos.asLong();
        card.title = state.getBlock().getName().getString();
        card.titleColour = AMBER;

        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        card.subtitle = modName(blockId.getNamespace());

        Item item = state.getBlock().asItem();
        if (item != Items.AIR) {
            card.icon = new ItemStack(item);
        }

        // Le badge de récolte : voir la Javadoc de classe. Silencieux quand la question ne se pose
        // pas — la terre ne demande jamais de coche.
        if (state.requiresCorrectToolForDrops()) {
            card.harvestable = mc.player.getMainHandItem().isCorrectToolForDrops(state);
        }

        if (state.hasProperty(BlockStateProperties.POWERED)) {
            boolean on = state.getValue(BlockStateProperties.POWERED);
            card.line(on ? "Redstone : activé" : "Redstone : inactif", on ? GOOD : DIM);
        }
        if (state.hasProperty(BlockStateProperties.POWER)) {
            int power = state.getValue(BlockStateProperties.POWER);
            card.line("Signal : " + power + " / 15", power > 0 ? AMBER : DIM);
        }

        if (mc.level.getBlockEntity(pos) instanceof Container) {
            maybeAsk(mc, pos);
            if (lastTell != null && pos.equals(lastTell.pos())) {
                appendContainer(card, lastTell);
            } else {
                card.line("(lecture du contenu…)", DIM);
            }
            if (state.getBlock() instanceof HopperBlock) {
                appendHopperInfo(card);
            }
        }

        return card;
    }

    private static void appendContainer(Card card, RegardTell tell) {
        if (tell.cookPercent() >= 0 || tell.fuelPercent() >= 0) {
            appendFurnace(card, tell);
            return;
        }
        if (tell.total() > 0) {
            card.line(tell.filled() + " / " + tell.total() + " emplacements", DIM);
        }
        card.dominant = tell.dominant();
    }

    /** Un four montré pour ce qu'il est : entrée et combustible nommés — voir la Javadoc de classe. */
    private static void appendFurnace(Card card, RegardTell tell) {
        card.furnace = true;
        card.furnaceInput = tell.furnaceInput();
        card.furnaceFuel = tell.furnaceFuel();
        if (tell.cookPercent() >= 0) {
            card.bar("Cuisson", tell.cookPercent() / 100f, AMBER);
        }
        if (tell.fuelPercent() >= 0) {
            card.bar("Combustible", tell.fuelPercent() / 100f, WARN);
        }
    }

    /** Le vrai débit d'un entonnoir sur CE serveur — voir la Javadoc de classe. */
    private static void appendHopperInfo(Card card) {
        if (!Config.SPEC.isLoaded()) {
            return;
        }
        card.line(Config.BULK.get()
                ? "Lots actifs : jusqu'à " + Bulk.LOT + " objets par recherche"
                : "Lots inactifs : 1 objet par recherche (vanilla)",
                Config.BULK.get() ? GOOD : DIM);
    }

    private static void maybeAsk(Minecraft mc, BlockPos pos) {
        long now = mc.level.getGameTime();
        boolean changed = !pos.equals(lastAskedPos);
        if (!changed && now - lastAskedTick < ASK_PERIOD_TICKS) {
            return;
        }
        lastAskedPos = pos.immutable();
        lastAskedTick = now;
        ClientPacketDistributor.sendToServer(new RegardAsk(pos));
    }

    private static String modName(String namespace) {
        if ("minecraft".equals(namespace)) {
            return "Minecraft";
        }
        return MOD_NAMES.computeIfAbsent(namespace, ns -> {
            try {
                return ModList.get().getModContainerById(ns)
                        .map(container -> container.getModInfo().getDisplayName())
                        .orElse(ns);
            } catch (Throwable unreadable) {
                return ns;
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Entité
    // ------------------------------------------------------------------------------------------

    private static Card entityCard(Minecraft mc, Entity entity) {
        if (entity instanceof ItemEntity itemEntity) {
            return itemPileCard(itemEntity);
        }

        Card card = new Card();
        card.key = "entity:" + entity.getUUID();
        card.title = entity.getDisplayName().getString();
        card.titleColour = AMBER;

        // L'œuf d'apparition sert d'icône quand il existe — la plupart des créatures en ont un ;
        // les absentes (joueurs, patrons de donjon) montrent simplement le titre sans icône.
        Optional<Holder<Item>> egg = SpawnEggItem.byId(entity.getType());
        egg.ifPresent(holder -> card.icon = new ItemStack(holder));

        if (entity instanceof LivingEntity living) {
            float health = living.getHealth();
            float max = living.getMaxHealth();
            if (max > 0f) {
                float fraction = Math.max(0f, Math.min(1f, health / max));
                int colour = fraction > 0.5f ? GOOD : fraction > 0.25f ? WARN : BAD;
                card.bar(String.format(Locale.ROOT, "Vie : %.1f / %.1f", health, max),
                        fraction, colour);
            }
            if (living instanceof AgeableMob ageable && ageable.isBaby()) {
                card.line("Jeune", DIM);
            }

            List<ItemStack> equipment = new ArrayList<>();
            for (EquipmentSlot slot : EQUIPMENT_ORDER) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    equipment.add(stack);
                }
            }
            card.icons = equipment;
        }

        return card;
    }

    // ------------------------------------------------------------------------------------------
    // Objet au sol
    // ------------------------------------------------------------------------------------------

    private static Card itemPileCard(ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return null;
        }
        Card card = new Card();
        card.key = "entity:" + itemEntity.getUUID();
        card.title = stack.getCount() + " × " + stack.getHoverName().getString();
        card.titleColour = AMBER;
        card.icon = stack;

        if (Config.SPEC.isLoaded() && Config.GATHER.get()) {
            card.line(String.format(Locale.ROOT,
                    "Fusion élargie : %.0f blocs / %.0f vertical, recherche /%d ticks",
                    Gather.SPREAD, Gather.STACK, Gather.SETTLED_RATE), GOOD);
        } else {
            card.line("Fusion vanilla : 0,5 bloc, jamais à la verticale, /40 ticks", DIM);
        }

        return card;
    }

    // ------------------------------------------------------------------------------------------
    // Le dessin : en-tête compact (icône, titre, sous-titre, badge), puis lignes, barres, listes.
    // ------------------------------------------------------------------------------------------

    private record Line(String text, int colour) {}

    private record Bar(String label, float fraction, int colour) {}

    private static final class Card {
        String key = "";
        ItemStack icon = ItemStack.EMPTY;
        String title = "";
        int titleColour = TEXT;
        String subtitle;
        /** {@code null} : pas de question de récolte. Sinon : l'outil en main convient-il ? */
        Boolean harvestable;
        final List<Line> lines = new ArrayList<>();
        final List<Bar> bars = new ArrayList<>();
        /** Équipement d'une créature, en grille — un ensemble petit et fixe, une grille lui va. */
        List<ItemStack> icons = List.of();
        /** Articles dominants d'un conteneur, en liste — voir la Javadoc de classe. */
        List<ItemStack> dominant = List.of();
        boolean furnace;
        ItemStack furnaceInput = ItemStack.EMPTY;
        ItemStack furnaceFuel = ItemStack.EMPTY;

        void line(String text, int colour) {
            lines.add(new Line(text, colour));
        }

        void bar(String label, float fraction, int colour) {
            bars.add(new Bar(label, fraction, colour));
        }
    }

    private static void paint(GuiGraphicsExtractor graphics, Minecraft mc, Card card, float alpha,
            float pop, float riseY, int accent) {
        Font font = mc.font;
        boolean hasIcon = !card.icon.isEmpty();
        int headerIconW = hasIcon ? ICON + 6 : 0;
        boolean hasBadge = card.harvestable != null;
        int badgeW = hasBadge ? BADGE + 6 : 0;

        // --- Mesure : une largeur de contenu qui satisfait tout ce qu'il y a à montrer -------------
        int contentWidth = MIN_WIDTH - PADDING * 2;
        contentWidth = Math.max(contentWidth, headerIconW + font.width(card.title) + badgeW);
        if (card.subtitle != null) {
            contentWidth = Math.max(contentWidth, headerIconW + font.width(card.subtitle));
        }
        for (Line line : card.lines) {
            contentWidth = Math.max(contentWidth, font.width(line.text()));
        }
        for (Bar bar : card.bars) {
            contentWidth = Math.max(contentWidth, Math.max(BAR_W, font.width(bar.label())));
        }
        for (ItemStack stack : card.dominant) {
            contentWidth = Math.max(contentWidth, ICON + 4 + font.width(dominantText(stack)));
        }
        if (card.furnace) {
            String inText = card.furnaceInput.isEmpty() ? "—" : "×" + card.furnaceInput.getCount();
            String fuText = card.furnaceFuel.isEmpty() ? "—" : "×" + card.furnaceFuel.getCount();
            contentWidth = Math.max(contentWidth,
                    ICON + 4 + font.width(inText) + 16 + ICON + 4 + font.width(fuText));
        }
        int iconRows = card.icons.isEmpty() ? 0 : (card.icons.size() + 5) / 6;
        if (iconRows > 0) {
            contentWidth = Math.max(contentWidth, Math.min(card.icons.size(), 6) * ICON);
        }
        contentWidth = Math.min(contentWidth, MAX_WIDTH - PADDING * 2);

        int titleAvail = Math.max(4, contentWidth - headerIconW - badgeW);
        int subAvail = Math.max(4, contentWidth - headerIconW);
        Component title = styled(font, card.title, titleAvail, ChatFormatting.BOLD);
        Component subtitle = card.subtitle == null ? null
                : styled(font, card.subtitle, subAvail, ChatFormatting.ITALIC);

        int headerContentH = TITLE_H + (subtitle != null ? SUB_H : 0);
        int headerH = Math.max(hasIcon ? ICON : 0, headerContentH);

        boolean hasBody = !card.lines.isEmpty() || !card.bars.isEmpty() || !card.dominant.isEmpty()
                || card.furnace || iconRows > 0;

        int bodyH = card.lines.size() * LINE_H
                + card.bars.size() * (LINE_H + BAR_H + 2)
                + card.dominant.size() * ICON
                + (card.furnace ? ICON + 2 : 0)
                + (iconRows > 0 ? 2 + iconRows * ICON : 0);

        int width = contentWidth + PADDING * 2;
        int height = PADDING * 2 + headerH + (hasBody ? SEP_GAP + bodyH : 0);

        int x = (graphics.guiWidth() - width) / 2;
        int y = TOP_MARGIN;
        float cx = x + width / 2f;
        float cy = y + height / 2f;

        // --- La transformation : tout ce qui suit — fond, texte, icônes — en hérite. -----------
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx, cy + riseY);
        graphics.pose().scale(pop, pop);
        graphics.pose().translate(-cx, -cy);

        // Une ombre douce, pour que le panneau flotte plutôt que de coller au jeu derrière lui.
        graphics.fill(x + 2, y + 3, x + width + 4, y + height + 5, withAlpha(SHADOW, alpha * 0.35f));

        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, withAlpha(EDGE, alpha));
        graphics.fill(x, y, x + width, y + height, withAlpha(PANEL, alpha));
        // Le filet supérieur respire lentement même hors transition — un panneau vivant, jamais figé.
        float breathe = 0.88f + 0.12f * (float) Math.sin(System.nanoTime() / 1.6e9d);
        int accentDeep = mixRgb(accent, 0x000000, 0.4f);
        graphics.fillGradient(x, y, x + width, y + 3,
                withAlpha(accent, alpha), withAlpha(mixRgb(accentDeep, accent, breathe), alpha));

        // --- L'en-tête : icône, titre en gras, sous-titre en italique, badge de récolte. -----------
        int headerTop = y + PADDING;
        int textLeft = x + PADDING + headerIconW;
        if (hasIcon && alpha > 0.25f) {
            int iconY = headerTop + (headerH - ICON) / 2;
            graphics.item(card.icon, x + PADDING, iconY);
        }
        int titleY = headerTop + (headerH - headerContentH) / 2;
        graphics.text(font, title, textLeft, titleY, withAlpha(card.titleColour, alpha), true);
        if (subtitle != null) {
            graphics.text(font, subtitle, textLeft, titleY + TITLE_H, withAlpha(MOD, alpha), false);
        }
        if (hasBadge) {
            int bx = x + width - PADDING - BADGE;
            int by = headerTop + (headerH - BADGE) / 2;
            drawBadge(graphics, bx, by, card.harvestable, alpha);
        }

        // --- Le corps : séparateur, puis lignes, barres, four, articles dominants, équipement. -----
        int cursorY = headerTop + headerH;
        if (hasBody) {
            cursorY += SEP_GAP / 2;
            graphics.fill(x + PADDING, cursorY, x + width - PADDING, cursorY + 1, withAlpha(EDGE, alpha));
            cursorY += SEP_GAP / 2 + 2;
        }

        for (Line line : card.lines) {
            String shown = font.plainSubstrByWidth(line.text(), contentWidth);
            graphics.text(font, shown, x + PADDING, cursorY, withAlpha(line.colour(), alpha), false);
            cursorY += LINE_H;
        }
        for (Bar bar : card.bars) {
            String shown = font.plainSubstrByWidth(bar.label(), contentWidth);
            graphics.text(font, shown, x + PADDING, cursorY, withAlpha(TEXT, alpha), false);
            cursorY += LINE_H;
            float shownFraction = smooth(card.key + "|" + bar.label(), bar.fraction());
            drawBar(graphics, x + PADDING, cursorY, contentWidth, BAR_H, shownFraction, bar.colour(),
                    alpha);
            cursorY += BAR_H + 2;
        }
        if (card.furnace) {
            drawFurnaceRow(graphics, font, card, x + PADDING, cursorY, alpha);
            cursorY += ICON;
        }
        for (int i = 0; i < card.dominant.size(); i++) {
            drawDominantRow(graphics, font, card.dominant.get(i), x + PADDING, cursorY, contentWidth,
                    alpha, i);
            cursorY += ICON;
        }
        if (iconRows > 0) {
            cursorY += 2;
            int col = 0;
            int iconY = cursorY;
            for (ItemStack stack : card.icons) {
                int ix = x + PADDING + col * ICON;
                graphics.fill(ix, iconY, ix + ICON - 1, iconY + ICON - 1, withAlpha(SLOT_BG, alpha));
                if (alpha > 0.3f) {
                    graphics.item(stack, ix, iconY);
                    graphics.itemDecorations(font, stack, ix, iconY);
                }
                col++;
                if (col >= 6) {
                    col = 0;
                    iconY += ICON;
                }
            }
        }

        graphics.pose().popMatrix();
    }

    private static String dominantText(ItemStack stack) {
        return "×" + stack.getCount() + "  " + stack.getHoverName().getString();
    }

    /** Une ligne « icône · × quantité · nom » — voir la Javadoc de classe sur les articles dominants. */
    private static void drawDominantRow(GuiGraphicsExtractor graphics, Font font, ItemStack stack,
            int x, int y, int contentWidth, float alpha, int index) {
        graphics.fill(x, y, x + ICON - 1, y + ICON - 1, withAlpha(SLOT_BG, alpha));
        if (alpha > 0.3f) {
            graphics.item(stack, x, y);
            graphics.itemDecorations(font, stack, x, y);
        }
        String shown = font.plainSubstrByWidth(dominantText(stack), Math.max(4, contentWidth - ICON - 4));
        // Le premier article — le plus nombreux — se détache ; les suivants restent en retrait.
        int colour = index == 0 ? TEXT : DIM;
        graphics.text(font, shown, x + ICON + 4, y + ICON / 2 - 4, withAlpha(colour, alpha), false);
    }

    /** Entrée et combustible d'un four, nommés côte à côte — voir la Javadoc de classe. */
    private static void drawFurnaceRow(GuiGraphicsExtractor graphics, Font font, Card card, int left,
            int top, float alpha) {
        boolean active = !card.bars.isEmpty();
        int cursor = drawFurnaceSlot(graphics, font, card.furnaceInput, left, top, alpha);
        cursor += 3;
        int arrowY = top + ICON / 2;
        int arrowColour = withAlpha(active ? AMBER : DIM, alpha);
        diagonal(graphics, cursor, arrowY - 3f, cursor + 7f, arrowY, 1.6f, arrowColour);
        diagonal(graphics, cursor, arrowY + 3f, cursor + 7f, arrowY, 1.6f, arrowColour);
        cursor += 13;
        drawFurnaceSlot(graphics, font, card.furnaceFuel, cursor, top, alpha);
    }

    private static int drawFurnaceSlot(GuiGraphicsExtractor graphics, Font font, ItemStack stack,
            int x, int y, float alpha) {
        if (stack.isEmpty()) {
            String dash = "—";
            graphics.text(font, dash, x, y + ICON / 2 - 4, withAlpha(DIM, alpha), false);
            return x + font.width(dash) + 4;
        }
        graphics.fill(x, y, x + ICON - 1, y + ICON - 1, withAlpha(SLOT_BG, alpha));
        if (alpha > 0.3f) {
            graphics.item(stack, x, y);
            graphics.itemDecorations(font, stack, x, y);
        }
        String text = "×" + stack.getCount();
        graphics.text(font, text, x + ICON + 2, y + ICON / 2 - 4, withAlpha(TEXT, alpha), false);
        return x + ICON + 2 + font.width(text) + 4;
    }

    /** Coche verte ou croix rouge : l'outil en main convient-il ? Voir la Javadoc de classe. */
    private static void drawBadge(GuiGraphicsExtractor graphics, int bx, int by, boolean ok,
            float alpha) {
        int colour = ok ? GOOD : BAD;
        graphics.fill(bx - 1, by - 1, bx + BADGE + 1, by + BADGE + 1, withAlpha(colour, alpha * 0.22f));
        int c = withAlpha(colour, alpha);
        if (ok) {
            diagonal(graphics, bx + 1f, by + 4.5f, bx + 3.2f, by + 6.6f, 1.6f, c);
            diagonal(graphics, bx + 3.2f, by + 6.6f, bx + 7f, by + 1.3f, 1.6f, c);
        } else {
            diagonal(graphics, bx + 1.4f, by + 1.4f, bx + 6.6f, by + 6.6f, 1.4f, c);
            diagonal(graphics, bx + 6.6f, by + 1.4f, bx + 1.4f, by + 6.6f, 1.4f, c);
        }
    }

    /**
     * Un segment entre deux points de l'écran, épaissi puis tourné — le même idiome que
     * {@code client.ponder.Stage#edge}, ici en espace 2D plutôt qu'en espace projeté. Sert au badge
     * de récolte et à la flèche de cuisson : deux traits valent mieux qu'une police dont le glyphe
     * n'est pas garanti dans toutes les polices de ressources.
     */
    private static void diagonal(GuiGraphicsExtractor graphics, float x1, float y1, float x2, float y2,
            float thickness, int colour) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.4f) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x1, y1);
        graphics.pose().rotate((float) Math.atan2(dy, dx));
        graphics.pose().scale(len / 16f, Math.max(0.4f, thickness * 0.5f));
        graphics.fill(0, -1, 16, 1, colour);
        graphics.pose().popMatrix();
    }

    private static void drawBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
            float fraction, int colour, float alpha) {
        graphics.fill(x, y, x + width, y + height, withAlpha(EDGE, alpha));
        int filled = Math.round(width * Math.max(0f, Math.min(1f, fraction)));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, withAlpha(colour, alpha));
            // Un reflet sur le haut du remplissage : la différence entre un aplat et une barre.
            graphics.fill(x, y, x + filled, y + 1, withAlpha(mixRgb(colour, 0xFFFFFF, 0.35f), alpha));
        }
    }

    /** Un texte tronqué à la largeur donnée, puis mis en forme — gras pour un titre, italique pour un
     * sous-titre. La troncature se fait AVANT la mise en forme : {@code Font#plainSubstrByWidth} ne
     * comprend pas les styles, et le gras ajoute de toute façon moins d'un pixel par lettre. */
    private static Component styled(Font font, String text, int maxWidth, ChatFormatting... styles) {
        String shown = font.plainSubstrByWidth(text, Math.max(0, maxWidth));
        return Component.literal(shown).withStyle(styles);
    }

    /** Rattrape doucement une valeur affichée vers sa vraie cible — un simple lerp répété à chaque
     * image, vidé au changement de cible par {@link #smoothedBars}. */
    private static float smooth(String key, float target) {
        Float previous = smoothedBars.get(key);
        float shown = previous == null ? target : Ease.lerp(previous, target, BAR_SMOOTH);
        smoothedBars.put(key, shown);
        return shown;
    }

    /** Remplace le canal alpha d'une couleur {@code 0xAARRGGBB} par sa fraction du canal d'origine. */
    private static int withAlpha(int argb, float factor) {
        int a = Math.round(((argb >>> 24) & 0xFF) * Ease.clamp(factor));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Mélange deux couleurs RVB (l'alpha n'entre pas en jeu, le résultat est toujours opaque). */
    private static int mixRgb(int a, int b, float t) {
        float k = Ease.clamp(t);
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = Math.round(Ease.lerp(ar, br, k));
        int g = Math.round(Ease.lerp(ag, bg, k));
        int bl = Math.round(Ease.lerp(ab, bb, k));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
