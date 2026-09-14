package fr.clubcitrouille.lanterne.content.disc;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Relis ton dossier de disques. »
 *
 * <h2>Pourquoi ce paquet existe alors que les tableaux n'en ont pas besoin</h2>
 *
 * <p>Une image nouvelle arrive dans la mosaïque en cours de partie : c'est une texture, on la pose et
 * elle est là. Un son nouveau, non — il passe par le <b>gestionnaire de ressources</b>, qui ne lit
 * son inventaire qu'au chargement. Tant que ce chargement n'a pas lieu, {@code sounds.json} ne
 * contient pas l'entrée du disque, et le jukebox joue le silence.
 *
 * <p>La seule façon d'y remédier sans mixin est donc de <b>demander un rechargement complet des
 * ressources</b> — celui de F3+T. Il coûte quelques secondes et fait clignoter l'écran ; c'est
 * précisément pourquoi il est déclenché par une commande explicite et jamais tout seul.
 *
 * <p>Ce paquet ne porte aucune donnée : il n'a rien à dire d'autre que « maintenant ». Un record
 * vide est ce qui décrit le mieux cette intention, et son encodeur n'écrit pas un octet.
 */
public record DiscRefresh() implements CustomPacketPayload {

    public static final Type<DiscRefresh> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "disques_relis"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DiscRefresh> STREAM_CODEC =
            StreamCodec.unit(new DiscRefresh());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
