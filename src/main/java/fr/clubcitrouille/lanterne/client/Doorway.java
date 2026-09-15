package fr.clubcitrouille.lanterne.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La troisième porte vers l'écran de réglages : la liste des mods.
 *
 * <h2>Pourquoi trois portes et non une</h2>
 *
 * <p>L'écran de réglages de Lanterne s'ouvrait par une seule voie : un bouton greffé sur l'écran
 * des options vidéo de vanilla. Cette voie disparaît dès qu'un mod remplace cet écran — et
 * <b>Sodium le remplace</b>, en annulant la seule ligne de tout Minecraft qui construit
 * {@code VideoSettingsScreen}. Un joueur qui installe Sodium perdait donc l'accès à la totalité des
 * réglages client du mod, sans le moindre message.
 *
 * <p>Une seconde porte a été posée <em>chez Sodium</em> ({@code client.sodium.Graft}), qui inscrit
 * une page dans sa propre colonne. Elle est meilleure que la première quand Sodium est là.
 *
 * <p>Celle-ci est la troisième, et c'est la seule qui ne dépende de <b>personne</b> : NeoForge
 * affiche un bouton de configuration à côté de chaque mod dans sa liste, dès lors qu'on lui en
 * fournit un. Elle marche avec Sodium comme sans, avec Chloride, avec Reese's, et elle survivra au
 * prochain mod qui décidera de refaire l'écran vidéo.
 *
 * <p>Trois portes pour un écran peut sembler excessif. Mais la journée a montré qu'une porte
 * unique est une porte qui se ferme : le défaut n'a été découvert que parce qu'un joueur s'en est
 * plaint, et il aurait pu ne jamais l'être.
 *
 * <h2>Une classe que le serveur ne doit jamais voir</h2>
 *
 * <p>{@link IConfigScreenFactory} vit dans les sources <em>client</em> de NeoForge, et
 * {@link Dials} descend de {@code Screen}. Sur un serveur dédié, ces classes n'existent pas : les
 * nommer depuis un chemin commun donnerait un {@code NoClassDefFoundError} au démarrage — et le
 * serveur ne démarrerait pas du tout.
 *
 * <p>D'où {@code @EventBusSubscriber(value = Dist.CLIENT)} : NeoForge ne charge cette classe que du
 * côté client. C'est la même précaution que {@link Pane}, prise pour la même raison — et ce dépôt
 * s'est déjà fait avoir une fois, par une lambda qui nommait {@code Screen} depuis
 * {@code Lanterne.java}.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Doorway {
    private Doorway() {}

    /**
     * Pose le bouton de configuration.
     *
     * <p>{@code FMLClientSetupEvent} plutôt que le constructeur du mod : le point d'extension doit
     * être enregistré sur le conteneur, et ce moment-là est celui où NeoForge garantit que le côté
     * client est prêt. L'enregistrer plus tôt fonctionnerait probablement ; « probablement » n'est
     * pas une raison suffisante quand l'alternative coûte trois lignes.
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ModContainer container = net.neoforged.fml.ModList.get()
                .getModContainerById(Lanterne.ID).orElse(null);
        if (container == null) {
            return;
        }
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (ignoredContainer, parent) -> new Dials(parent));
    }
}
