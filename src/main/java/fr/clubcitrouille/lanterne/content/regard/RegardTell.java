package fr.clubcitrouille.lanterne.content.regard;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Ce que le serveur répond à {@link RegardAsk} : le vrai contenu d'un conteneur, à l'instant demandé.
 *
 * @param pos     le bloc décrit — le client vérifie qu'il correspond encore à sa cible avant d'afficher
 *                quoi que ce soit : une réponse en retard sur une cible qui a changé ne doit rien
 *                montrer de faux.
 * @param preview les premières piles non vides, plafonnées (voir {@code Regard.PREVIEW_CAP}) — un
 *                entrepôt de bidons ne doit pas envoyer cinquante-quatre objets pour en montrer douze.
 * @param filled  nombre RÉEL d'emplacements occupés, même au-delà de ce que {@code preview} porte —
 *                c'est lui qui permet d'afficher « +14 » plutôt que de mentir par omission.
 * @param total   nombre d'emplacements du conteneur.
 * @param cookPercent progression de cuisson, 0-100, ou -1 si ce bloc n'est pas un four.
 * @param fuelPercent combustible restant, 0-100, ou -1 si ce bloc n'est pas un four.
 */
public record RegardTell(BlockPos pos, List<ItemStack> preview, int filled, int total,
        int cookPercent, int fuelPercent) implements CustomPacketPayload {

    public static final Type<RegardTell> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "regard_tell"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RegardTell> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RegardTell::pos,
            ItemStack.OPTIONAL_LIST_STREAM_CODEC, RegardTell::preview,
            ByteBufCodecs.VAR_INT, RegardTell::filled,
            ByteBufCodecs.VAR_INT, RegardTell::total,
            ByteBufCodecs.VAR_INT, RegardTell::cookPercent,
            ByteBufCodecs.VAR_INT, RegardTell::fuelPercent,
            RegardTell::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
