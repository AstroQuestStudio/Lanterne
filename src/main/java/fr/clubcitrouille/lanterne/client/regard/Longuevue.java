package fr.clubcitrouille.lanterne.client.regard;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

import fr.clubcitrouille.lanterne.client.ponder.Ease;
import fr.clubcitrouille.lanterne.client.waypoint.Compass;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La Longuevue : la touche C maintenue, comme Sodium — un zoom de caméra sans objet en main.
 *
 * <h2>Deux évènements NeoForge, aucun mixin</h2>
 *
 * <p>Lu par {@code javap} sur le vrai jar patché (jamais supposé, jamais sur {@code .mcsrc/}) : ce
 * moteur calcule le champ de vision effectif par la chaîne {@code Camera.calculateFov} →
 * {@code Camera.modifyFovBasedOnDeathOrFluid} → {@code AbstractClientPlayer.getFieldOfViewModifier}
 * → {@code ClientHooks.getFieldOfViewModifier}, et cette dernière méthode POSTE
 * {@link ComputeFovModifierEvent} sur {@code NeoForge.EVENT_BUS} avant de renvoyer
 * {@code getNewFovModifier()}. C'est un point d'accroche NeoForge officiel, déjà emprunté par vanilla
 * pour le tremblement du FOV à la course, au vol et au tir à l'arc — pas une supposition, une lecture
 * de bytecode.
 *
 * <p>Même vérification pour la sensibilité de la souris : {@code MouseHandler.turnPlayer} appelle
 * {@code ClientHooks.getTurnPlayerValues(sensibilité, caméraCinéma)}, qui poste
 * {@link CalculatePlayerTurnEvent} et lit {@code getMouseSensitivity()} en retour.
 *
 * <p>Utiliser ces deux évènements plutôt qu'un mixin sur {@code GameRenderer}/{@code Camera}/
 * {@code MouseHandler} élimine tout risque de conflit avec un autre chantier de ce dépôt qui
 * toucherait par ailleurs au rendu ou à la caméra — en particulier les mixins Vulkan et
 * {@code UpscaleGameRendererMixin}, dont ce module ne nomme ni ne touche aucune classe.
 *
 * <h2>Pourquoi pas {@code LocalPlayer.isScoping()}, le mécanisme de la vraie longue-vue</h2>
 *
 * <p>{@code AbstractClientPlayer.getFieldOfViewModifier} (désassemblé) montre que la longue-vue
 * vanilla court-circuite entièrement {@link ComputeFovModifierEvent} : si {@code isUsingItem()} est
 * vrai ET {@code isScoping()} est vrai en vue première personne, la méthode renvoie directement
 * {@code 0.1f} SANS jamais atteindre l'évènement. Or {@code isUsingItem()} est le même drapeau que
 * l'utilisation d'un arc ou d'une nourriture : il ralentit le déplacement et coupe la course. C'est
 * le comportement voulu pour l'objet longue-vue — on s'immobilise pour viser — mais pas celui d'une
 * simple touche de zoom façon Sodium, qui doit rester utilisable en marchant. D'où le choix de
 * l'évènement générique plutôt que du mécanisme de visée, malgré la tentation d'obtenir la réduction
 * de sensibilité « gratuitement » via la branche {@code isScoping()} de
 * {@code MouseHandler.turnPlayer} (vue, elle aussi, par désassemblage : huit fois moins vive en
 * première personne pendant la visée).
 *
 * <h2>Le lissage : une fois par tick, comme vanilla lisse son propre FOV</h2>
 *
 * <p>{@code Camera.tickFov()} — qui produit la valeur que consulte {@link ComputeFovModifierEvent} —
 * n'est appelée que depuis {@code Camera.tick()}, une vraie méthode de TICK (vingt fois par seconde),
 * jamais depuis le chemin par image. {@link #progress} est donc mis à jour à la même cadence, dans
 * {@link #onClientTick}, par un lissage exponentiel simple — {@code Ease.lerp(progress, cible, taux)}
 * répété, exactement l'idiome déjà écrit dans {@code Sight#smooth} pour les barres de vie — plutôt
 * qu'une capture de nanosecondes : les deux évènements consultés ici ne partagent pas la même cadence
 * d'appel, et {@link #progress} doit rester une seule vérité lue par les deux, pas recalculée par
 * chacun.
 *
 * <h2>Le blocage en zoom après un écran fermé — le bug qu'il fallait éviter</h2>
 *
 * <p>{@code client.ponder.Hint} documente déjà, pour ce même moteur, que {@code KeyMapping.isDown()}
 * cesse d'être alimentée dès qu'un écran est ouvert : le jeu ne relaie plus les évènements clavier aux
 * {@code KeyMapping} pendant qu'une interface a la main. Un joueur qui maintient C, ouvre un coffre,
 * relâche C, puis referme le coffre verrait donc {@code isDown()} rester bloquée sur « appuyée » —
 * exactement le zoom qui ne se referme jamais que la mission redoute. {@link #held} évite le problème
 * comme {@code Hint#held} l'évite déjà : interroger l'état clavier brut via
 * {@code InputConstants.isKeyDown}, jamais {@code KeyMapping.isDown()}. {@link #onClientTick} exige en
 * plus {@code Minecraft.gui.screen() == null} pour viser le zoom, donc l'ouverture d'un écran ramène
 * la cible à zéro sur-le-champ, quel que soit l'état — périmé ou non — que rendrait la touche.
 */
public final class Longuevue {
    public static final KeyMapping ZOOM = new KeyMapping(
            "key.lanterne.longuevue",
            InputConstants.Type.KEYBOARD,
            // C, comme Sodium — la touche par défaut la plus connue pour ce geste précis. NeoForge
            // gère lui-même un conflit de touche (le joueur rebind dans les options) ; ce mod ne
            // déclare aucune autre touche sur C.
            org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_C,
            // La catégorie appartient à Compass, qui l'a déclarée en premier. La redéclarer lève.
            Compass.OPEN.getCategory());

    private Longuevue() {}

    /**
     * L'avancement du zoom, de zéro (relâché) à un (zoom complet), lissé une fois par tick dans
     * {@link #onClientTick} et simplement LU — jamais recalculé — par les deux évènements de rendu.
     */
    private static float progress;

    public static void register(IEventBus modBus) {
        modBus.addListener(Longuevue::onRegisterKeys);
        NeoForge.EVENT_BUS.addListener(Longuevue::onClientTick);
        NeoForge.EVENT_BUS.addListener(Longuevue::onComputeFovModifier);
        NeoForge.EVENT_BUS.addListener(Longuevue::onCalculatePlayerTurn);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(ZOOM);
    }

    /**
     * Une seule source de vérité pour « la touche est-elle vraiment tenue ? », vingt fois par
     * seconde — voir la Javadoc de classe pour la raison exacte de ne pas appeler
     * {@code KeyMapping.isDown()} ici, identique à celle de {@code Hint#held}.
     */
    private static boolean held() {
        return !ZOOM.isUnbound() && ZOOM.getKey().getType() == InputConstants.Type.KEYBOARD
                && InputConstants.isKeyDown(ZOOM.getKey().getValue());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            progress = 0f;
            return;
        }
        // Un écran ouvert ramène la cible à zéro immédiatement, que la touche soit encore tenue ou
        // non — voir la Javadoc de classe : c'est ce qui empêche le FOV de rester bloqué en zoom
        // après la fermeture d'un coffre ou d'un inventaire.
        boolean wantZoom = Settings.zoom() && client.gui.screen() == null && held();
        float target = wantZoom ? 1f : 0f;
        progress = Ease.lerp(progress, target, Settings.zoomTransitionRate());
        // Se pose exactement sur la cible une fois l'écart négligeable : au repos complet, les deux
        // évènements ci-dessous sortent dès leur première ligne, coût nul — même principe que
        // Sight#draw, qui ne calcule rien tant que rien n'est visé.
        if (Math.abs(progress - target) < 0.001f) {
            progress = target;
        }
    }

    /**
     * Le champ de vision : compose avec la valeur par défaut de vanilla plutôt que de l'écraser, pour
     * qu'une course ou un tir à l'arc en cours de zoom continue de s'y mélanger proprement au lieu de
     * disparaître pendant que le zoom est actif.
     */
    private static void onComputeFovModifier(ComputeFovModifierEvent event) {
        if (progress <= 0f) {
            return;
        }
        float eased = Ease.smooth(progress);
        float zoomed = 1f / Settings.zoomFactor();
        event.setNewFovModifier(Ease.lerp(event.getNewFovModifier(), zoomed, eased));
    }

    /**
     * La sensibilité de la souris, réduite dans la même proportion que le champ de vision — voir la
     * Javadoc de classe pour la mesure par bytecode qui montre que la longue-vue vanilla fait déjà
     * exactement cela.
     */
    private static void onCalculatePlayerTurn(CalculatePlayerTurnEvent event) {
        if (progress <= 0f) {
            return;
        }
        double eased = Ease.smooth(progress);
        double base = event.getMouseSensitivity();
        double reduced = base / Settings.zoomFactor();
        event.setMouseSensitivity(Mth.lerp(eased, base, reduced));
    }
}
