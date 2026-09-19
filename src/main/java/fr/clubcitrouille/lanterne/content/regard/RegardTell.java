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
 * <h2>Dominant, pas exhaustif</h2>
 *
 * <p>{@link #dominant} ne porte plus « les douze premières piles trouvées en fouillant les cases dans
 * l'ordre » — ça donnait une grille illisible dès qu'un coffre de bazar rangeait trente-deux objets
 * différents. {@code Regard.onAsk} additionne maintenant les quantités par TYPE d'objet sur le
 * conteneur entier, trie par quantité décroissante, et n'envoie que les {@code Regard.DOMINANT_CAP}
 * premiers — ce qui pèse vraiment dans ce coffre, pas tout ce qu'il contient.
 *
 * @param pos     le bloc décrit — le client vérifie qu'il correspond encore à sa cible avant d'afficher
 *                quoi que ce soit : une réponse en retard sur une cible qui a changé ne doit rien
 *                montrer de faux.
 * @param dominant les articles les plus nombreux de ce conteneur, triés par quantité totale
 *                décroissante et plafonnés à {@code Regard.DOMINANT_CAP} — voir plus haut.
 * @param filled  nombre RÉEL d'emplacements occupés, indépendant de {@code dominant} — c'est lui qui
 *                porte « 3 / 27 emplacements », même si {@code dominant} n'en montre que trois types.
 * @param total   nombre d'emplacements du conteneur.
 * @param cookPercent progression de cuisson, 0-100, ou -1 si ce bloc n'est pas un four.
 * @param fuelPercent combustible restant, 0-100, ou -1 si ce bloc n'est pas un four.
 * @param furnaceInput la pile en cours de cuisson d'un four (case 0), vide si ce bloc n'en est pas un
 *                ou que la case est vide. Un four n'a que trois cases fixes : {@code dominant} n'a pas
 *                de sens pour lui, il mérite ses deux cases nommées plutôt qu'un classement.
 * @param furnaceFuel la pile de combustible d'un four (case 1), même remarque.
 */
public record RegardTell(BlockPos pos, List<ItemStack> dominant, int filled, int total,
        int cookPercent, int fuelPercent, ItemStack furnaceInput, ItemStack furnaceFuel)
        implements CustomPacketPayload {

    public static final Type<RegardTell> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "regard_tell"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RegardTell> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RegardTell::pos,
            ItemStack.OPTIONAL_LIST_STREAM_CODEC, RegardTell::dominant,
            ByteBufCodecs.VAR_INT, RegardTell::filled,
            ByteBufCodecs.VAR_INT, RegardTell::total,
            ByteBufCodecs.VAR_INT, RegardTell::cookPercent,
            ByteBufCodecs.VAR_INT, RegardTell::fuelPercent,
            ItemStack.OPTIONAL_STREAM_CODEC, RegardTell::furnaceInput,
            ItemStack.OPTIONAL_STREAM_CODEC, RegardTell::furnaceFuel,
            RegardTell::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
