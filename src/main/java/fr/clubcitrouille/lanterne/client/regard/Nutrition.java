package fr.clubcitrouille.lanterne.client.regard;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La Nutrition : ce qu'un aliment en main va vraiment changer, avant de le manger.
 *
 * <h2>Dans l'esprit d'AppleSkin, sans en reprendre le code</h2>
 *
 * <p>AppleSkin (par squeek502) fait deux choses que vanilla ne dit jamais : une infobulle avec la
 * faim/saturation exactes d'un aliment, et un fantôme d'icônes posé PAR-DESSUS la vraie barre de
 * faim pour montrer ce qu'elle deviendrait. Ce fichier reproduit le COMPORTEMENT, pas le code —
 * aucune source d'AppleSkin n'a été lue pour l'écrire, seulement l'API vanilla de ce moteur,
 * vérifiée par {@code javap} sur le vrai jar patché avant d'écrire une ligne (voir plus bas).
 *
 * <h2>La formule exacte, lue dans {@code FoodData}, pas devinée</h2>
 *
 * <p>{@code FoodData.eat(FoodProperties)} appelle {@code add(nutrition, saturation)}, et
 * {@code add} (désassemblé) fait très précisément :
 *
 * <pre>
 * nouvelleFaim       = clamp(faim + nutrition, 0, 20)
 * nouvelleSaturation = clamp(saturation + FoodProperties.saturation(), 0, nouvelleFaim)
 * </pre>
 *
 * <p>{@link #predict} applique exactement cette formule, rien de plus : aucun risque d'annoncer un
 * gain que le jeu ne donnera pas.
 *
 * <h2>Les coordonnées de la barre de faim, capturées, pas recalculées</h2>
 *
 * <p>{@code Hud.extractFood} reçoit du code appelant deux entiers déjà calculés — {@code droite =
 * largeurEcran/2 + 91}, {@code haut = hauteurEcran - rightHeight} — et ce second nombre dépend
 * d'éléments HUD empilés dont ce module n'a aucune raison de connaître l'état (vie de monture,
 * niveau d'air…). Les recalculer à la main aurait fini par diverger dès qu'un de ces éléments
 * s'affiche. {@link fr.clubcitrouille.lanterne.mixin.NutritionAnchorMixin} les intercepte donc à la
 * source, au moment exact où vanilla les a déterminés, et {@link #noteAnchor} ne fait que les
 * mémoriser. Le calque de ce fichier les relit tels quels.
 *
 * <p>Le calcul par icône reprend, lui aussi, exactement la boucle désassemblée de
 * {@code Hud.extractFood} : dix icônes, {@code x = droite - i*8 - 9}, chacune représentant deux
 * points de faim.
 *
 * <h2>Sous vanilla, pas dessus : le fantôme se pose AVANT, comme dans AppleSkin</h2>
 *
 * <p>Première version : les icônes fantômes se dessinaient PAR-DESSUS la barre réelle
 * ({@code registerAbove}), teintées vert — retour de jeu du 22/09/2026, « un truc jaune et une
 * pulsation… horrible ». Le vrai AppleSkin (licence Unlicense, domaine public — vérifié sur son
 * dépôt avant d'en reprendre le PRINCIPE, jamais le fichier) les pose au contraire AVANT, dans
 * {@code RenderGameOverlayEvent.Pre} : le fantôme apparaît alors DERRIÈRE l'icône vide que vanilla
 * dessine par-dessus, et ne fait que déborder discrètement de son contour plutôt que de la
 * recouvrir. {@code registerBelow(VanillaGuiLayers.FOOD_LEVEL, …)} reproduit exactement cet ordre
 * ici.
 *
 * <h2>La saturation, en dessous de la barre plutôt qu'en toute petite ligne de texte</h2>
 *
 * <p>Le retour du 22/09/2026 demandait explicitement de « massivement améliorer » ce que ce module
 * montre de la saturation. {@link #drawSaturationBar} ajoute donc une fine bande sous la rangée de
 * faim — la saturation ACTUELLE en teinte pleine, le GAIN prévu accolé juste après en teinte plus
 * claire — plutôt que de la laisser uniquement dans l'infobulle. Largeur bornée à la portée réelle
 * de la barre de faim (90 px, dix icônes de neuf), jamais au-delà.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Nutrition {
    private static final Identifier FOOD_FULL =
            Identifier.fromNamespaceAndPath("minecraft", "hud/food_full");
    private static final Identifier FOOD_HALF =
            Identifier.fromNamespaceAndPath("minecraft", "hud/food_half");

    /** Vert translucide : la teinte du gain, posée derrière l'icône vide que vanilla dessine. */
    private static final int GHOST_TINT = 0xB057E389;
    /** Saturation ACTUELLE : cyan plein. */
    private static final int SATURATION_NOW = 0xFF57C7E3;
    /** GAIN de saturation prévu : même teinte, plus claire. */
    private static final int SATURATION_GAIN = 0xFFB8ECF5;
    private static final int SATURATION_TRACK = 0x60000000;
    private static final int SATURATION_BAR_WIDTH = 90;
    private static final int SATURATION_BAR_HEIGHT = 2;

    private static Player lastAnchoredPlayer;
    private static int lastTop;
    private static int lastRight;

    private Nutrition() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(Nutrition::onRegisterLayers);
    }

    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        // AVANT la vraie barre, pas après — voir la Javadoc de classe : le fantôme doit apparaître
        // derrière l'icône vide que vanilla dessine ensuite, jamais recouvrir une icône déjà pleine.
        event.registerBelow(VanillaGuiLayers.FOOD_LEVEL,
                Identifier.fromNamespaceAndPath(Lanterne.ID, "nutrition"), Nutrition::draw);
    }

    /** Appelée par {@link fr.clubcitrouille.lanterne.mixin.NutritionAnchorMixin} à chaque image. */
    public static void noteAnchor(Player player, int top, int right) {
        lastAnchoredPlayer = player;
        lastTop = top;
        lastRight = right;
    }

    // ------------------------------------------------------------------------------------------
    // Le calque : icônes fantômes sur la vraie barre de faim.
    // ------------------------------------------------------------------------------------------

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!Settings.nutrition() || mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null
                || mc.player != lastAnchoredPlayer) {
            return;
        }

        FoodProperties food = heldFood(mc.player);
        if (food == null) {
            return;
        }

        FoodData data = mc.player.getFoodData();
        int currentLevel = data.getFoodLevel();
        int predictedLevel = predict(currentLevel, food.nutrition());
        float currentSat = data.getSaturationLevel();
        float predictedSat = predictSaturation(currentSat, predictedLevel, food.saturation());

        if (predictedLevel > currentLevel) {
            for (int i = 0; i < 10; i++) {
                int already = Mth.clamp(currentLevel - i * 2, 0, 2);
                int predicted = Mth.clamp(predictedLevel - i * 2, 0, 2);
                if (predicted <= already) {
                    continue;
                }
                int x = lastRight - i * 8 - 9;
                Identifier sprite = predicted >= 2 ? FOOD_FULL : FOOD_HALF;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, lastTop, 9, 9, GHOST_TINT);
            }
        }

        drawSaturationBar(graphics, currentSat, predictedSat, predictedLevel);
    }

    /**
     * La bande sous la barre de faim : saturation actuelle en plein, gain accolé en clair.
     *
     * <p>Bornée à {@code predictedLevel} (jamais au-delà de vingt) comme le fait déjà
     * {@link #predictSaturation} — la barre ne peut donc jamais paraître plus pleine que la faim
     * qu'elle accompagne, exactement la contrainte que {@code FoodData.add} applique en jeu.
     */
    private static void drawSaturationBar(GuiGraphicsExtractor graphics, float currentSat,
            float predictedSat, int predictedLevel) {
        if (predictedLevel <= 0) {
            return;
        }
        int y = lastTop + 9;
        int left = lastRight - SATURATION_BAR_WIDTH;
        graphics.fill(left, y, lastRight, y + SATURATION_BAR_HEIGHT, SATURATION_TRACK);

        float scale = SATURATION_BAR_WIDTH / (float) predictedLevel;
        int nowPx = Math.round(Mth.clamp(currentSat, 0f, (float) predictedLevel) * scale);
        int gainPx = Math.round(Mth.clamp(predictedSat, 0f, (float) predictedLevel) * scale);
        if (nowPx > 0) {
            graphics.fill(left, y, left + nowPx, y + SATURATION_BAR_HEIGHT, SATURATION_NOW);
        }
        if (gainPx > nowPx) {
            graphics.fill(left + nowPx, y, left + gainPx, y + SATURATION_BAR_HEIGHT, SATURATION_GAIN);
        }
    }

    /** La même formule que {@code FoodData.add} — voir la Javadoc de classe pour la preuve bytecode. */
    private static int predict(int currentLevel, int nutrition) {
        return Mth.clamp(currentLevel + nutrition, 0, 20);
    }

    private static float predictSaturation(float currentSaturation, int predictedLevel, float gain) {
        return Mth.clamp(currentSaturation + gain, 0f, (float) predictedLevel);
    }

    /** La main droite d'abord, l'offhand ensuite — le geste que vanilla privilégie pour manger. */
    private static FoodProperties heldFood(Player player) {
        ItemStack main = player.getMainHandItem();
        FoodProperties food = main.get(DataComponents.FOOD);
        if (food != null) {
            return food;
        }
        ItemStack off = player.getOffhandItem();
        return off.get(DataComponents.FOOD);
    }

    // ------------------------------------------------------------------------------------------
    // L'infobulle : faim, saturation, et les effets qu'avaler l'objet declenche.
    // ------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (!Settings.nutrition()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        List<Component> tooltip = event.getToolTip();

        tooltip.add(Component.translatable("lanterne.nutrition.faim", food.nutrition())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("lanterne.nutrition.saturation",
                String.format(java.util.Locale.ROOT, "%.1f", food.saturation()))
                .withStyle(ChatFormatting.GRAY));
        if (food.canAlwaysEat()) {
            tooltip.add(Component.translatable("lanterne.nutrition.toujours")
                    .withStyle(ChatFormatting.DARK_GREEN));
        }

        if (player != null) {
            FoodData data = player.getFoodData();
            int predictedLevel = predict(data.getFoodLevel(), food.nutrition());
            float predictedSat = predictSaturation(data.getSaturationLevel(), predictedLevel,
                    food.saturation());
            tooltip.add(Component.translatable("lanterne.nutrition.previsionnel",
                    predictedLevel, String.format(java.util.Locale.ROOT, "%.1f", predictedSat))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null) {
            return;
        }
        List<MobEffectInstance> effects = new java.util.ArrayList<>();
        for (ConsumeEffect effect : consumable.onConsumeEffects()) {
            if (effect instanceof ApplyStatusEffectsConsumeEffect applied) {
                effects.addAll(applied.effects());
            }
        }
        if (!effects.isEmpty()) {
            // Le meme formatage que vanilla pour une potion : nom, amplitude, duree, couleur par
            // categorie d'effet - reutilise l'utilitaire du jeu plutot que d'en reecrire un.
            net.minecraft.world.item.alchemy.PotionContents.addPotionTooltip(
                    effects, tooltip::add, 1f, 20f);
        }
    }
}
