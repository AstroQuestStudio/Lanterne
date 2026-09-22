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
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Nutrition {
    private static final Identifier FOOD_FULL =
            Identifier.fromNamespaceAndPath("minecraft", "hud/food_full");
    private static final Identifier FOOD_HALF =
            Identifier.fromNamespaceAndPath("minecraft", "hud/food_half");

    /** Vert translucide : la teinte du gain, posée sur la même icône que vanilla dessine à vide. */
    private static final int GHOST_TINT = 0x9057E389;

    private static Player lastAnchoredPlayer;
    private static int lastTop;
    private static int lastRight;

    private Nutrition() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(Nutrition::onRegisterLayers);
    }

    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.FOOD_LEVEL,
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
        if (predictedLevel <= currentLevel) {
            return;
        }

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
