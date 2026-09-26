package fr.clubcitrouille.lanterne.client;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.mixin.DisconnectedScreenAccessor;

/**
 * Filet de sécurité : si, malgré la Mèche de configuration, ce client est quand même refusé par
 * « The server sent registries with unknown keys » et que les clés en cause sont dans l'espace
 * {@code lanterne}, on remplace ce message brut par une explication qui dit quoi faire.
 *
 * <p>Cas où cela peut encore arriver : un serveur dont la Mèche est éteinte, ou un serveur plus
 * ancien que la 26.3.147 qui aurait des blocs que ce client n'a pas. On ne touche à AUCUN autre
 * refus : une clé d'un autre mod garde le message de NeoForge, qui est alors le bon.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class MecheFilet {
    private static final String CLE = "neoforge.network.registries.sync.server-with-unknown-keys";

    private MecheFilet() {}

    @SubscribeEvent
    static void onOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof DisconnectedScreen ecran)) {
            return;
        }
        try {
            DisconnectedScreenAccessor acces = (DisconnectedScreenAccessor) ecran;
            Component raison = acces.lanterne$details().reason();
            if (!(raison.getContents() instanceof TranslatableContents traduisible)
                    || !CLE.equals(traduisible.getKey())) {
                return;
            }
            String detail = raison.getString();
            if (!detail.contains(Lanterne.ID + ":")) {
                return;
            }
            Lanterne.LOG.warn("[MÈCHE] refus « unknown keys » dû à Lanterne, message remplacé : {}", detail);
            event.setNewScreen(new DisconnectedScreen(acces.lanterne$parent(), ecran.getTitle(),
                    Component.literal("Le serveur a une version plus récente de Lanterne, avec des blocs "
                            + "ou objets que la tienne ne connaît pas.\n\n"
                            + "Mets Lanterne à jour, puis relance le jeu : si ce serveur propose la mise à "
                            + "jour automatique, vérifie que synchro_auto = true dans config/lanterne-maj-client.toml "
                            + "et reconnecte-toi ; sinon, demande le jar de Lanterne du serveur à son administrateur et mets-le dans mods/ (en retirant l'ancien).")));
        } catch (RuntimeException imprevu) {
            // Au pire, le message d'origine reste affiché : jamais pire qu'avant ce filet.
        }
    }
}
