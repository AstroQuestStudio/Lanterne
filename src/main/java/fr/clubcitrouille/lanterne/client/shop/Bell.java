package fr.clubcitrouille.lanterne.client.shop;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;

import fr.clubcitrouille.lanterne.client.waypoint.Compass;
import fr.clubcitrouille.lanterne.content.shop.TradeAsk;

/**
 * La sonnette : la touche qui ouvre la boutique.
 *
 * <h2>La touche demande, elle n'ouvre pas</h2>
 *
 * <p>L'appui n'ouvre pas l'écran : il envoie une demande au serveur, qui répond par le catalogue coté
 * et le drapeau d'ouverture — voir {@code content.shop.StallSync}. Cela coûte un aller-retour,
 * quelques millisecondes, et cela achète une propriété qu'on ne peut pas obtenir autrement : <b>les
 * prix affichés sont ceux de l'instant où l'écran s'ouvre</b>, par construction. Un écran qui
 * s'ouvrirait sur l'ardoise locale montrerait les prix de la dernière connexion, et le premier achat
 * ferait mentir tout ce qui est écrit.
 *
 * <p>{@code consumeClick} dépile les appuis en attente : l'interroger dans une boucle tant qu'il rend
 * vrai est ce qui empêche un appui maintenu d'envoyer vingt demandes par seconde. Même détail, même
 * raison, que {@code client.waypoint.Opener}.
 *
 * <h2>Le choix de la touche, et celui de la catégorie</h2>
 *
 * <p>{@code K} est libre en vanilla et n'entre en conflit ni avec la carte, ni avec le gestionnaire de
 * repères des mods de cartographie répandus — {@code B}, {@code J} et {@code N} chez JourneyMap. Une
 * touche par défaut qui se heurte à un autre mod rend la fonctionnalité invisible pour ceux qui ont
 * cet autre mod, c'est-à-dire souvent pour le public visé.
 *
 * <p>La catégorie est celle que {@code client.waypoint.Compass} a déjà déclarée. NeoForge refuse
 * qu'on enregistre deux fois le même identifiant de catégorie, et en créer une seconde pour un seul
 * raccourci aurait coupé en deux la liste des touches du mod dans les options. Conséquence :
 * {@link #register} doit être appelé dans la même garde client que {@code Compass.register}, et après
 * lui.
 */
public final class Bell {
    public static final KeyMapping OPEN = new KeyMapping(
            "key.lanterne.boutique",
            InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_K,
            Compass.OPEN.getCategory());

    private Bell() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(Bell::onRegisterKeys);
        NeoForge.EVENT_BUS.addListener(Bell::onClientTick);
    }

    /**
     * La catégorie n'est <b>pas</b> réenregistrée ici.
     *
     * <p>Elle appartient à {@code Compass}, qui l'a déclarée ; {@code registerCategory} lève une
     * exception au second appel, et cette exception tomberait au chargement du client, avant tout
     * message d'erreur lisible.
     */
    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.screen != null) {
            return;
        }
        boolean asked = false;
        while (OPEN.consumeClick()) {
            asked = true;
        }
        if (asked) {
            ClientPacketDistributor.sendToServer(TradeAsk.open());
        }
    }
}
