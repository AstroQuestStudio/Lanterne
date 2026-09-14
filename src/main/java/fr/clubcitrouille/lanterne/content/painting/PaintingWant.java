package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Je ne connais pas cette image — envoie-la. »
 *
 * <p>C'est le miroir exact de {@link PaintingWish}, dans l'autre sens. La symétrie n'est pas
 * cosmétique : les deux camps suivent la même règle — <b>on ne transmet que ce que l'autre n'a
 * pas</b> — et aucun des deux n'envoie d'octets de sa propre initiative.
 *
 * <p>Le serveur n'émet ce paquet qu'en réponse à un {@link PaintingSet} qu'il a déjà accepté :
 * droits vérifiés, quota vérifié, cadence vérifiée. Un client qui recevrait cette demande sans
 * l'avoir provoquée n'y répondrait pas — il ne garde des octets en réserve que pour l'image qu'il
 * vient lui-même de préparer.
 *
 * @param hash l'image réclamée.
 */
public record PaintingWant(String hash) implements CustomPacketPayload {

    public static final Type<PaintingWant> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "tableau_reclame"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PaintingWant> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(Plate.HASH_LENGTH), PaintingWant::hash,
                    PaintingWant::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
