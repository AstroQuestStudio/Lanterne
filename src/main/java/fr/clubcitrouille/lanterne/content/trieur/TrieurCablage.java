package fr.clubcitrouille.lanterne.content.trieur;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.Contents;

/**
 * Les deux branchements du Trieur sur NeoForge : sa capacité d'objets, et le paquet « ajouter un
 * motif » de son écran.
 *
 * <h2>La capacité : le Trieur visible des tuyaux</h2>
 *
 * <p>NeoForge n'expose {@code Capabilities.Item.BLOCK} que pour une liste FIXE de types de
 * blocs-entités vanilla ({@code CapabilityHooks.registerVanillaProviders} : {@code HOPPER}, {@code
 * BARREL}, …) — et l'enregistrement se fait par TYPE, dont les blocs valides sont lus dans {@code
 * BlockEntityType.getValidBlocks()}. Le Trieur s'annonce sous {@link Contents#TRIEUR_ENTITY}, pas
 * sous {@code HOPPER} (voir {@link TrieurBlockEntity#getType}) : il n'avait donc AUCUNE capacité.
 * Les entonnoirs vanilla le voyaient quand même (ils cherchent d'abord un {@code Container}), mais
 * pas un tuyau de mod, ni le ticker d'un autre Trieur qui ne passe que par les capacités. On
 * l'enregistre ici avec EXACTEMENT l'enveloppe que NeoForge donne au hopper vanilla — {@link
 * VanillaContainerWrapper#of} — dont l'insertion passe par {@code canPlaceItem}, donc par le filtre.
 *
 * <h2>Le paquet : facultatif, et revérifié côté serveur</h2>
 *
 * <p>Même charpente que {@code core.network.RafaleNet}, mais enregistré en {@code optional()} comme
 * {@code Post.Tube} : un client d'avant 26.3.141 se connecte toujours (voir la mémoire Mèche : jamais
 * un canal obligatoire neuf entre deux déploiements). Le serveur ne fait confiance à rien : le joueur
 * doit avoir CE menu ouvert (même {@code containerId}), toujours valide (portée), et le texte est
 * nettoyé et borné par {@link Motif#nettoyer}.
 */
public final class TrieurCablage {
    private static final String PROTOCOL = "1";

    private TrieurCablage() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(TrieurCablage::onRegisterCapabilities);
        modBus.addListener(TrieurCablage::onRegisterPayloads);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, Contents.TRIEUR_ENTITY.get(),
                (trieur, side) -> VanillaContainerWrapper.of(trieur));
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL).optional()
                .playToServer(AjoutMotif.TYPE, AjoutMotif.STREAM_CODEC, TrieurCablage::onAjoutMotif);
    }

    private static void onAjoutMotif(AjoutMotif paquet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof TrieurMenu menu)
                    || menu.containerId != paquet.containerId()
                    || !menu.stillValid(player)) {
                return;
            }
            String motif = Motif.nettoyer(paquet.motif());
            if (motif.isEmpty()) {
                return;
            }
            if (!menu.ajouterMotif(motif)) {
                player.sendOverlayMessage(Component.translatable("lanterne.trieur.motif.plein"));
            }
        });
    }

    /** « Ajoute ce motif au filtre du Trieur que j'ai ouvert. » */
    public record AjoutMotif(int containerId, String motif) implements CustomPacketPayload {
        public static final Type<AjoutMotif> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Lanterne.ID, "trieur_motif"));

        /** Borne réseau large (quatre fois la limite) : la vraie borne est appliquée au traitement. */
        public static final StreamCodec<RegistryFriendlyByteBuf, AjoutMotif> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, AjoutMotif::containerId,
                        ByteBufCodecs.stringUtf8(Motif.LONGUEUR_MAX * 4), AjoutMotif::motif,
                        AjoutMotif::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
