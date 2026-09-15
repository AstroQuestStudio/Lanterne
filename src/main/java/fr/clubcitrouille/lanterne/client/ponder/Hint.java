package fr.clubcitrouille.lanterne.client.ponder;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.waypoint.Compass;

/**
 * Le geste : maintenir une touche sur un objet pour en voir le guide.
 *
 * <h2>C'est celui que l'utilisateur a décrit, mot pour mot</h2>
 *
 * <p><em>« Je suis sur une GUI, je cherche un truc, et y a une animation qui explique comment tout
 * fonctionne. »</em> Ce n'est pas un menu qu'il décrit, c'est un <b>survol</b> : la question naît
 * devant l'objet, la réponse doit arriver là. C'est ce que fait Create, et c'est ce que fait ce
 * fichier.
 *
 * <h2>Et il n'a coûté aucune dépendance</h2>
 *
 * <p>Chez Create, ce geste passe par une greffe JEI, parce que les objets de la liste de JEI ne sont
 * pas dans des cases d'inventaire : rien ne dit au mod qu'on les survole. Le brief de ce chantier
 * partait donc de l'idée qu'il faudrait soit prendre JEI en dépendance facultative, soit renoncer.
 *
 * <p>Ni l'un ni l'autre. La vérification tient en deux faits, tous deux lus dans les fichiers plutôt
 * que supposés :
 *
 * <ul>
 *   <li>{@code ItemStack.getTooltipLines} déclenche {@code ItemTooltipEvent} — {@code ItemStack.java}
 *       ligne 926 des sources 26.2, via {@code EventHooks.onItemTooltip} ;</li>
 *   <li>JEI construit ses infobulles par ce même appel — {@code getTooltipLines} apparaît dans la
 *       table des constantes de {@code mezz.jei.library.render.ItemStackRenderer}, dans le jar
 *       {@code jei-26.2-neoforge-30.29.0.201} installé chez le joueur.</li>
 * </ul>
 *
 * <p>Écouter l'évènement d'infobulle suffit donc, et c'est <b>meilleur</b> qu'une greffe : cela
 * marche dans l'inventaire, dans JEI, et dans EMI ou REI si le joueur en change un jour, sans une
 * ligne de plus dans {@code build.gradle} ni un mod de plus à charger.
 *
 * <h2>Pourquoi maintenir, et non appuyer</h2>
 *
 * <p>Un appui simple sur une touche de déplacement, dans un écran, est trop facile à déclencher par
 * accident — et l'accident consiste ici à remplacer l'écran du joueur. Le maintien de sept dixièmes
 * de seconde est un engagement, et la barre qui se remplit dans l'infobulle le dit pendant qu'il se
 * prend : on voit ce qu'on est en train de faire, et on peut y renoncer.
 *
 * <h2>Pourquoi la touche est lue à la main</h2>
 *
 * <p>{@code KeyMapping.isDown} ne répond pas quand un écran est ouvert : le jeu cesse d'alimenter
 * l'état des touches dès qu'il rend la main à l'interface. Or c'est <em>exactement</em> là que ce
 * fichier travaille. On interroge donc GLFW directement.
 *
 * <p>La touche par défaut est <b>W</b>, celle de Create, et elle est déclarée en contexte
 * {@code GUI} : NeoForge ne la compte alors pas comme un conflit avec « avancer », parce qu'elles ne
 * sont jamais actives en même temps.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Hint {
    private Hint() {}

    /** Durée du maintien, en ticks. Quatorze : assez pour être voulu, assez peu pour ne pas lasser. */
    private static final float HOLD = 14f;
    /** Retombée par tick quand on relâche. Plus vive que la montée : renoncer doit être immédiat. */
    private static final float FALL = 0.22f;
    private static final int BARS = 10;

    public static final KeyMapping OPEN = new KeyMapping(
            "key.lanterne.guide",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_W,
            // La catégorie appartient à Compass, qui l'a déclarée. La redéclarer lève.
            Compass.OPEN.getCategory());

    static {
        OPEN.setKeyConflictContext(KeyConflictContext.GUI);
    }

    private static ItemStack hovered = ItemStack.EMPTY;
    /** L'infobulle a-t-elle été redessinée depuis le dernier tick ? Voir {@link #onClientTick}. */
    private static boolean fresh;
    private static float charge;

    /** À appeler depuis la garde cliente de {@code Lanterne}, comme {@code Compass.register}. */
    public static void register(IEventBus modBus) {
        modBus.addListener(Hint::onRegisterKeys);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (Guide.sceneFor(event.getItemStack().getItem()) == null) {
            return;
        }
        hovered = event.getItemStack();
        fresh = true;
        event.getToolTip().add(charge <= 0.01f ? invitation() : gauge());
    }

    private static Component invitation() {
        return Component.translatable("lanterne.guide.infobulle", OPEN.getTranslatedKeyMessage())
                .withStyle(ChatFormatting.DARK_GRAY);
    }

    /**
     * La barre qui se remplit.
     *
     * <p>Des barres verticales et non un caractère de bloc : {@code |} est dans la table la plus
     * ancienne de la police, donc présent dans tout pack de ressources. Un carré blanc à la place
     * d'une jauge, chez un joueur qui a changé de pack, est un défaut qu'on ne verrait jamais ici.
     */
    private static Component gauge() {
        int filled = Math.max(1, Math.min(BARS, Math.round(charge * BARS)));
        return Component.literal("|".repeat(filled)).withColor(0xFFC857)
                .append(Component.literal("|".repeat(BARS - filled)).withColor(0x44444E));
    }

    /**
     * Fait monter ou retomber la jauge, et ouvre à plein.
     *
     * <p>L'infobulle est construite au <em>rendu</em>, ce tick-ci au <em>tick</em> : ils ne vont pas
     * à la même cadence. D'où {@link #fresh} plutôt qu'un effacement sec — sans lui, un client dont
     * la cadence d'images tombe sous vingt par seconde verrait la jauge se vider toute seule pendant
     * qu'il maintient la touche, ce qui est précisément la panne qu'on ne reproduit jamais chez soi.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        // Le catalogue est bâti ici, sur un tick, et non à la première infobulle ou à la première
        // image de l'écran : construire trois scénarios en plein rendu se voit. Les fois suivantes,
        // l'appel ne fait qu'une comparaison de référence.
        Guide.all();
        if (!fresh) {
            hovered = ItemStack.EMPTY;
        }
        fresh = false;

        if (hovered.isEmpty() || client.gui.screen() == null || !held(client)) {
            charge = Math.max(0f, charge - FALL);
            return;
        }
        charge += 1f / HOLD;
        if (charge < 1f) {
            return;
        }
        Scene scene = Guide.sceneFor(hovered.getItem());
        charge = 0f;
        hovered = ItemStack.EMPTY;
        if (scene != null) {
            // L'écran d'où l'on vient devient le parent : Échap y ramène. Le remplacer n'a aucun
            // effet sur le conteneur ouvert — « AbstractContainerMenu.removed » ne fait rien quand
            // le joueur n'est pas un ServerPlayer, et côté client il ne l'est jamais.
            Theatre.open(client.gui.screen(), scene);
        }
    }

    private static boolean held(Minecraft client) {
        if (OPEN.isUnbound() || OPEN.getKey().getType() != InputConstants.Type.KEYSYM) {
            return false;
        }
        return InputConstants.isKeyDown(client.getWindow(), OPEN.getKey().getValue());
    }
}
