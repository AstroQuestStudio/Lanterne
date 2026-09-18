package fr.clubcitrouille.lanterne.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.lab.Glass;

/**
 * Le seul point du mod qui touche au client, et pourquoi il est seul.
 *
 * <h2>Une classe que le serveur ne doit jamais voir</h2>
 *
 * <p>Sur un serveur dédié, les classes {@code net.minecraft.client.*} <b>n'existent pas</b>. Il ne
 * s'agit pas d'une bonne pratique mais d'une contrainte matérielle : la moindre référence chargée
 * provoque un {@code NoClassDefFoundError} au démarrage, et le serveur ne démarre pas du tout.
 *
 * <p>Tout le code de mesure du rendu vit donc dans {@code Glass}, et {@code Glass} n'est nommé que
 * depuis <b>ici</b>. Cette classe-ci porte {@code @EventBusSubscriber(value = Dist.CLIENT)}, ce qui
 * demande à NeoForge de ne la découvrir que sur la distribution client : sur un serveur, elle n'est
 * jamais chargée, donc {@code Glass} non plus, donc aucune classe de rendu n'est atteinte.
 *
 * <p>C'est la raison pour laquelle il n'y a qu'un seul fichier dans ce paquet. Chaque référence
 * supplémentaire au code client depuis le code commun serait une occasion de casser tous les serveurs
 * dédiés — la panne la plus visible qu'un mod puisse provoquer, et celle qu'aucun essai en solo ne
 * révèle.
 *
 * <h2>Pourquoi après l'image et non avant</h2>
 *
 * <p>{@code Minecraft} calcule la durée d'une image juste après avoir tiré cet évènement. Se brancher
 * ici signifie donc lire la durée de l'image <em>précédente</em>, et non de celle qui vient de finir.
 *
 * <p>Le décalage est d'un rang, sur des centaines de relevés dont on prend la médiane : il est sans
 * conséquence. Il est signalé parce qu'il aurait pu en avoir une, et parce que c'est exactement le
 * genre de détail qu'on découvre trop tard quand on ne l'écrit pas.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Pane {
    private Pane() {}

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        // Hors banc comme en banc : le masquage des feuilles dépend d'une option graphique que le
        // joueur peut changer à tout moment, et le changement exige une reconstruction. Le vérifier
        // ici coûte deux lectures de champ par image, et évite qu'un réglage reste sans effet
        // visible jusqu'au prochain chargement du monde.
        // La fenêtre est construite, le contexte graphique aussi : la lentille peut désormais
        // répondre. Avant la première image, elle se tait — voir Lens.awaken.
        fr.clubcitrouille.lanterne.core.Lens.awaken();
        // Relevé même quand la jauge est masquée : l'affichage doit être prêt dès
        // qu'on l'ouvre, pas deux secondes plus tard.
        Gauge.note();
        Foliage.refresh(net.minecraft.client.Minecraft.getInstance());

        // Instrumentation temporaire de diagnostic (450->130 FPS, lentille, flashs) : journalise
        // periodiquement le temps d'image reel et l'etat de la lentille sur une vraie partie, sans
        // armer Glass et sans construire de scene synthetique. A retirer une fois la cause confirmee.
        if ("1".equals(System.getenv("LANTERNE_OBSERVE"))) {
            fr.clubcitrouille.lanterne.lab.Observe.frame();
        }

        // La radiographie : demarre et s'arrete toute seule au rythme des mondes vus/quittes, ecrit
        // son rapport sans qu'on le lui demande. Voir Radiographie pour le pourquoi.
        fr.clubcitrouille.lanterne.lab.Radiographie.pulse();

        // Capture d'ecran automatique de verification (LANTERNE_AUTO_SCREENSHOT=1 uniquement,
        // jamais pour un joueur normal). Voir Snap.
        fr.clubcitrouille.lanterne.lab.Snap.pulse();

        if (!Glass.armed()) {
            return;
        }
        if (!Glass.running()) {
            Glass.begin();
            return;
        }
        Glass.frame();
    }
}
