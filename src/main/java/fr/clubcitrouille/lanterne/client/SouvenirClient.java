package fr.clubcitrouille.lanterne.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.network.SouvenirKept;
import fr.clubcitrouille.lanterne.core.network.SouvenirManifest;
import fr.clubcitrouille.lanterne.core.network.SouvenirRevision;

/**
 * Le côté client de {@code core.Souvenir} : l'identité du monde en cours, la manifeste à envoyer, et
 * la correspondance entre un chunk qui vient d'arriver et la révision qui l'étiquette.
 *
 * <h2>Pourquoi une manifeste par dimension, envoyée en une fois</h2>
 *
 * <p>Une manifeste par chunk demandé coûterait un aller-retour avant chaque envoi possible — exactement
 * la latence que ce module doit retirer, pas ajouter. On envoie donc, une fois par arrivée dans une
 * dimension, tout ce que le disque sait déjà pour elle. Voir {@link SouvenirManifest} pour le plafond.
 *
 * <h2>La correspondance chunk ↔ révision, et pourquoi elle est bornée dans le temps</h2>
 *
 * <p>{@link fr.clubcitrouille.lanterne.mixin.SouvenirReceiveMixin} capture les octets d'un chunk au
 * moment où il arrive et les range ici, en attente de {@link SouvenirRevision} qui doit suivre sur la
 * même connexion. Si ce paquet n'arrive jamais — serveur vanilla, module éteint côté serveur — l'entrée
 * reste seule dans {@link #pending} : elle est purgée au tick suivant plutôt que de fuir indéfiniment.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class SouvenirClient {
    private static UUID worldId;
    private static Identifier lastAnnouncedDimension;

    /** Octets reçus, en attente de leur étiquette de révision. Purgé à chaque tick : voir la classe. */
    private static final Map<Long, byte[]> pending = new HashMap<>();

    private SouvenirClient() {}

    public static void reset() {
        worldId = null;
        lastAnnouncedDimension = null;
        pending.clear();
    }

    public static UUID worldId() {
        return worldId;
    }

    /** Le serveur vient d'annoncer l'identité de ce monde. */
    public static void acceptHello(UUID id) {
        worldId = id;
        lastAnnouncedDimension = null; // force l'envoi d'une manifeste au prochain tick
    }

    /** Un chunk vient d'être traité : ses octets attendent leur révision. */
    public static void stage(ChunkPos pos, byte[] bytes) {
        pending.put(pos.pack(), bytes);
    }

    /** La révision d'un chunk vient d'arriver : on ferme la correspondance et on persiste. */
    public static void acceptRevision(SouvenirRevision revision) {
        if (worldId == null) {
            return;
        }
        byte[] bytes = pending.remove(revision.pos().pack());
        if (bytes == null) {
            return; // capture manquée ou déjà purgée : rien à garder, sans casser quoi que ce soit
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        SouvenirVault.store(worldId, level.dimension().identifier(), revision.pos(),
                revision.revision(), bytes);
    }

    /**
     * Le serveur dit que ce chunk n'a pas changé : on recharge nos propres octets et on les rejoue
     * dans le même code que celui qui traite un chunk arrivé du réseau.
     */
    public static void acceptKept(SouvenirKept kept) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        var level = Minecraft.getInstance().level;
        if (worldId == null || connection == null || level == null) {
            return;
        }
        byte[] bytes = SouvenirVault.loadBytes(worldId, level.dimension().identifier(), kept.pos());
        if (bytes == null) {
            // Le disque ne l'a plus — vidé entre-temps, plafond de budget. On ne peut rien faire de
            // ce paquet : le chunk restera tel qu'il était avant, ce qui n'est jamais pire que ce que
            // vanilla ferait s'il ne le recevait pas du tout.
            Lanterne.LOG.warn("[SOUVENIR] {},{} annoncé gardé, mais absent du disque local.",
                    kept.pos().x(), kept.pos().z());
            return;
        }
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.wrappedBuffer(bytes), level.registryAccess());
            ClientboundLevelChunkWithLightPacket packet =
                    ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
            connection.handleLevelChunkWithLight(packet);
        } catch (RuntimeException malformed) {
            Lanterne.LOG.warn("[SOUVENIR] {},{} : copie locale illisible, ignorée.",
                    kept.pos().x(), kept.pos().z());
        }
    }

    /**
     * Une fois par tick : purge les captures orphelines, et envoie une manifeste neuve si la
     * dimension a changé depuis la dernière annoncée — ce qui couvre aussi bien la connexion
     * initiale (aucune dimension encore annoncée) que le passage d'un portail.
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!Settings.souvenir() || worldId == null) {
            return;
        }
        pending.clear(); // voir la javadoc de classe : une entrée qui survit un tick est orpheline

        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Identifier dimension = level.dimension().identifier();
        if (dimension.equals(lastAnnouncedDimension)) {
            return;
        }
        lastAnnouncedDimension = dimension;
        sendManifest(dimension);
    }

    private static void sendManifest(Identifier dimension) {
        Map<Long, Long> known = SouvenirVault.allKnownRevisions(worldId, dimension);
        if (known.isEmpty()) {
            return; // rien a annoncer : ne pas envoyer une manifeste vide, ce n'est pas une erreur
        }
        List<SouvenirManifest.Entry> entries = new ArrayList<>(known.size());
        known.forEach((pos, revision) -> entries.add(new SouvenirManifest.Entry(pos, revision)));
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new SouvenirManifest(dimension, entries));
        Lanterne.LOG.info("[SOUVENIR] {} : {} chunk(s) connu(s) annoncé(s) au serveur.",
                dimension, entries.size());
    }
}
