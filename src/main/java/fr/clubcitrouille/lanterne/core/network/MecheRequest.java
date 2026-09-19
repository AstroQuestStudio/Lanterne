package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Je n'ai pas ce jar-là — envoie-le. » Réponse du client à {@link MecheManifest}, quand son
 * empreinte locale ne correspond pas à celle annoncée et que le joueur a consenti au téléchargement
 * (voir {@code client.MecheConsent}).
 *
 * <h2>Ce que le serveur n'y croit pas</h2>
 *
 * <p>{@code modId} est vérifié contre la petite liste des mods que {@code core.Meche} gère
 * effectivement avant tout usage — jamais utilisé tel quel pour construire un chemin de fichier. Un
 * client qui redemande plusieurs fois de suite sans attendre la fin du transfert précédent est ignoré :
 * voir {@code core.Meche.onRequest}.
 *
 * @param modId le mod dont le client veut le jar de référence.
 */
public record MecheRequest(String modId) implements CustomPacketPayload {

    public static final Type<MecheRequest> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_demande"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MecheRequest> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MecheManifest.MOD_ID_LIMIT), MecheRequest::modId,
                    MecheRequest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
