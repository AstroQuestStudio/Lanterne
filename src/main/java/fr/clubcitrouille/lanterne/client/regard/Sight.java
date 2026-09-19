package fr.clubcitrouille.lanterne.client.regard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import fr.clubcitrouille.lanterne.content.regard.RegardAsk;
import fr.clubcitrouille.lanterne.content.regard.RegardTell;
import fr.clubcitrouille.lanterne.core.Bulk;
import fr.clubcitrouille.lanterne.core.Cadence;
import fr.clubcitrouille.lanterne.core.Config;
import fr.clubcitrouille.lanterne.core.Gather;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le Regard : une tooltip HUD qui dit ce qu'il y a vraiment dans le bloc ou l'entité visé(e).
 *
 * <h2>Dans l'esprit de Jade/HWYLA, construit pour ce moteur</h2>
 *
 * <p>La présentation reprend ce que Jade et son ancêtre HWYLA ont établi comme évident dix ans avant
 * ce mod — un bandeau sombre, un nom en tête, un aperçu d'inventaire en grille, une barre pour ce qui
 * se remplit (vie, cuisson). Rien de tout cela n'est copié : c'est redessiné avec la palette déjà
 * posée par {@code client.screen.Palette}/{@code client.Dials} (fond {@link #PANEL}, filet
 * {@link #AMBER}), et bâti sur {@code GuiGraphicsExtractor} — l'API 2D de CE moteur, vérifiée par
 * {@code javap} sur le jar patché, jamais sur les sources obsolètes de {@code .mcsrc/}.
 *
 * <h2>Ce qui dépasse la copie : relié aux VRAIS modules de Lanterne</h2>
 *
 * <p>Trois choses qu'aucun mod d'inspection générique ne peut montrer, parce qu'elles n'existent que
 * dans CE mod :
 *
 * <ul>
 *   <li><b>La cadence de simulation d'une créature</b> — voir {@link #appendCadence} — vient
 *       directement de {@code core.Cadence}, la formule qui décide en jeu si cette créature réfléchit
 *       à chaque tick ou une fois toutes les quelques secondes. Étiquetée <i>estimée</i> parce que la
 *       pression du serveur (voir {@code core.Pressure}) n'est pas connue du client — un mod qui
 *       tairait cette nuance mentirait par précision excessive.</li>
 *   <li><b>Le vrai débit d'un entonnoir</b> — voir {@link #appendHopperInfo} — lit {@code Config.BULK}
 *       et {@code core.Bulk.LOT} : le nombre RÉEL d'objets qu'il déplace par recherche sur CE serveur,
 *       pas une moyenne générique.</li>
 *   <li><b>La vraie portée de fusion d'un tas d'objets au sol</b> — voir
 *       {@link #renderItemPile} — lit {@code core.Gather}, les constantes que le serveur applique
 *       réellement, plutôt que d'afficher le comportement vanilla si ce module l'a remplacé.</li>
 * </ul>
 *
 * <h2>Ce qui a été volontairement laissé de côté cette session</h2>
 *
 * <p>Le G-buffer de normales ({@code client.upscale.Gbuffer}, {@code CameraUniforms}) aurait permis un
 * contour de surbrillance plus propre qu'un simple cadre 2D. Ce n'est PAS tenté ici : composer une
 * passe de détection de contours écran-espace correcte est un chantier de rendu 3D à part entière, et
 * la consigne de ce module est explicite — une tooltip HUD 2D, sans risque de rendu 3D. Le gain visuel
 * n'aurait pas justifié le risque un soir où la base doit être solide. Une piste pour un futur
 * chantier, pas un renoncement après essai.
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
 * <p>Coût mesuré par {@code lab.Radiographie} (voir son rapport automatique) : négligeable devant le
 * reste du rendu d'une image. Désactivable (réglage « regard », voir {@code core.ClientConfig}) si
 * jamais ce n'était pas vrai sur une machine donnée.
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

    private static final int PADDING = 6;
    private static final int LINE_H = 10;
    private static final int BAR_H = 3;
    private static final int BAR_W = 90;
    private static final int ICON = 18;
    private static final int MIN_WIDTH = 90;
    private static final int MAX_WIDTH = 210;
    private static final int TOP_MARGIN = 6;

    /** Entre deux rafraîchissements d'un même conteneur, en ticks — la moitié d'une seconde. */
    private static final long ASK_PERIOD_TICKS = 10L;

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

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!Settings.regard() || mc.player == null || mc.level == null
                || mc.gui.hud.isHidden() || mc.gui.screen() != null) {
            return;
        }

        Entity target = mc.crosshairPickEntity;
        if (target != null) {
            renderEntity(graphics, mc, target);
            return;
        }
        if (mc.hitResult instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK) {
            renderBlock(graphics, mc, blockHit.getBlockPos());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Bloc
    // ------------------------------------------------------------------------------------------

    private static void renderBlock(GuiGraphicsExtractor graphics, Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        Card card = new Card();
        card.line(state.getBlock().getName().getString(), AMBER);

        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        card.line(modName(blockId.getNamespace()), DIM);

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

        paint(graphics, mc, card);
    }

    private static void appendContainer(Card card, RegardTell tell) {
        if (tell.cookPercent() >= 0) {
            card.bar("Cuisson", tell.cookPercent() / 100f, AMBER);
        }
        if (tell.fuelPercent() >= 0) {
            card.bar("Combustible", tell.fuelPercent() / 100f, WARN);
        }
        if (tell.total() > 0) {
            card.line(tell.filled() > tell.preview().size()
                    ? tell.filled() + " / " + tell.total() + " emplacements ("
                            + tell.preview().size() + " affichés)"
                    : tell.filled() + " / " + tell.total() + " emplacements", DIM);
        }
        card.icons = tell.preview();
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

    private static void renderEntity(GuiGraphicsExtractor graphics, Minecraft mc, Entity entity) {
        if (entity instanceof ItemEntity itemEntity) {
            renderItemPile(graphics, mc, itemEntity);
            return;
        }

        Card card = new Card();
        card.line(entity.getDisplayName().getString(), AMBER);

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

            appendCadence(card, mc, entity);
        }

        paint(graphics, mc, card);
    }

    /** La cadence de simulation réelle appliquée à cette créature — voir la Javadoc de classe. */
    private static void appendCadence(Card card, Minecraft mc, Entity entity) {
        if (entity instanceof Player || !(entity instanceof Mob mob) || mc.player == null) {
            return;
        }
        if (!Config.SPEC.isLoaded()) {
            return;
        }
        if (!Config.LOD.get()) {
            card.line("Niveau de détail : désactivé sur ce serveur (pleine simulation)", DIM);
            return;
        }
        // Pression inconnue du client : on affiche le meilleur cas (0), et on le dit. Une créature
        // réellement plus dégradée à cet instant ne le sera jamais moins que ce qui est affiché ici.
        double distance = mc.player.distanceTo(entity);
        int period = Cadence.forEntity(mob, distance, 0d);
        String bucket = Cadence.bucket(period);
        boolean guarded = Cadence.ceiling(mob) <= 4;
        card.line("Cadence Lanterne (estimée) : " + bucket + (guarded ? " — production protégée" : ""),
                period <= 1 ? GOOD : AMBER);
    }

    // ------------------------------------------------------------------------------------------
    // Objet au sol
    // ------------------------------------------------------------------------------------------

    private static void renderItemPile(GuiGraphicsExtractor graphics, Minecraft mc, ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return;
        }
        Card card = new Card();
        card.line(stack.getCount() + " × " + stack.getHoverName().getString(), AMBER);
        card.icons = List.of(stack);

        if (Config.SPEC.isLoaded() && Config.GATHER.get()) {
            card.line(String.format(Locale.ROOT,
                    "Fusion élargie : %.0f blocs / %.0f vertical, recherche /%d ticks",
                    Gather.SPREAD, Gather.STACK, Gather.SETTLED_RATE), GOOD);
        } else {
            card.line("Fusion vanilla : 0,5 bloc, jamais à la verticale, /40 ticks", DIM);
        }

        paint(graphics, mc, card);
    }

    // ------------------------------------------------------------------------------------------
    // Le dessin : un panneau, empilé de haut en bas — lignes, barres, grille d'objets.
    // ------------------------------------------------------------------------------------------

    private record Line(String text, int colour) {}

    private record Bar(String label, float fraction, int colour) {}

    private static final class Card {
        final List<Line> lines = new ArrayList<>();
        final List<Bar> bars = new ArrayList<>();
        List<ItemStack> icons = List.of();

        void line(String text, int colour) {
            lines.add(new Line(text, colour));
        }

        void bar(String label, float fraction, int colour) {
            bars.add(new Bar(label, fraction, colour));
        }
    }

    private static void paint(GuiGraphicsExtractor graphics, Minecraft mc, Card card) {
        Font font = mc.font;
        int contentWidth = MIN_WIDTH - PADDING * 2;
        for (Line line : card.lines) {
            contentWidth = Math.max(contentWidth, font.width(line.text()));
        }
        for (Bar bar : card.bars) {
            contentWidth = Math.max(contentWidth, Math.max(BAR_W, font.width(bar.label())));
        }
        int iconRows = card.icons.isEmpty() ? 0 : (card.icons.size() + 5) / 6;
        if (iconRows > 0) {
            contentWidth = Math.max(contentWidth, Math.min(card.icons.size(), 6) * ICON);
        }
        contentWidth = Math.min(contentWidth, MAX_WIDTH - PADDING * 2);

        int width = contentWidth + PADDING * 2;
        int height = PADDING * 2 + card.lines.size() * LINE_H + card.bars.size() * (LINE_H + BAR_H + 2)
                + (iconRows > 0 ? 2 + iconRows * ICON : 0);

        int x = (graphics.guiWidth() - width) / 2;
        int y = TOP_MARGIN;

        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, EDGE);
        graphics.fill(x, y, x + width, y + height, PANEL);
        graphics.fill(x, y, x + width, y + 2, AMBER);

        int cursorY = y + PADDING;
        for (Line line : card.lines) {
            String shown = font.plainSubstrByWidth(line.text(), contentWidth);
            graphics.text(font, shown, x + PADDING, cursorY, line.colour(), false);
            cursorY += LINE_H;
        }
        for (Bar bar : card.bars) {
            String shown = font.plainSubstrByWidth(bar.label(), contentWidth);
            graphics.text(font, shown, x + PADDING, cursorY, TEXT, false);
            cursorY += LINE_H;
            drawBar(graphics, x + PADDING, cursorY, contentWidth, BAR_H, bar.fraction(), bar.colour());
            cursorY += BAR_H + 2;
        }
        if (iconRows > 0) {
            cursorY += 2;
            int col = 0;
            int iconY = cursorY;
            for (ItemStack stack : card.icons) {
                int ix = x + PADDING + col * ICON;
                graphics.item(stack, ix, iconY);
                graphics.itemDecorations(font, stack, ix, iconY);
                col++;
                if (col >= 6) {
                    col = 0;
                    iconY += ICON;
                }
            }
        }
    }

    private static void drawBar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
            float fraction, int colour) {
        graphics.fill(x, y, x + width, y + height, EDGE);
        int filled = Math.round(width * Math.max(0f, Math.min(1f, fraction)));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, colour);
        }
    }
}
