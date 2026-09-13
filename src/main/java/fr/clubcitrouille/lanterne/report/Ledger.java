package fr.clubcitrouille.lanterne.report;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Ce que chaque bloc-entité écrit sur le disque, type par type.
 *
 * <h2>La question qu'on ne peut pas poser à un monde vanilla</h2>
 *
 * <p>Un modpack lourd — Create, Mekanism, et leurs machines — devient lent à sauvegarder bien avant
 * de devenir lent à simuler. La raison est connue de ceux qui administrent ces serveurs : certaines
 * machines écrivent des données considérables à chaque sauvegarde, et une poignée d'entre elles
 * suffit à alourdir un monde entier.
 *
 * <p>Mais « certaines » et « considérables » ne sont pas des mesures. Ce projet ne construit rien sur
 * des impressions, et il ne peut pas mesurer ce qu'il n'a pas : un monde vanilla ne contient aucune
 * machine de Create.
 *
 * <p>Cet outil est donc <b>l'instrument, pas le résultat</b>. Il se pose sur un serveur qui porte
 * vraiment le modpack et répond en une commande : quels types de blocs-entités occupent la place, et
 * combien chacun coûte en octets. Ce qu'il dira décidera de ce qu'on écrira ensuite — et non
 * l'inverse.
 *
 * <h2>Pourquoi la taille sérialisée, et non le nombre</h2>
 *
 * <p>Compter les blocs-entités ne dit rien : mille coffres vides pèsent moins qu'une seule machine
 * qui retient son inventaire, sa recette en cours, son réseau et son historique. Ce qui compte est ce
 * qui est <b>écrit</b>, et la seule façon honnête de le savoir est de l'écrire — dans un tampon
 * mémoire plutôt que sur le disque, mais par le même chemin et le même encodeur que la sauvegarde.
 *
 * <h2>Ce que l'outil ne fait pas</h2>
 *
 * <p>Il ne sauvegarde rien, ne modifie rien, et ne touche pas au disque. Il sérialise en mémoire et
 * jette. Le lancer sur un serveur en production coûte le temps d'un parcours des chunks chargés —
 * quelques dizaines de millisecondes par millier de blocs-entités, une fois.
 */
public final class Ledger {
    /** Rayon du balayage, en chunks. Trente-deux blocs de part et d.autre suffisent à voir une usine. */
    private static final int REACH = 12;

    /** Ce qu'on retient par type : combien, et combien d'octets. */
    private record Tally(String type, long count, long bytes) {}

    private Ledger() {}

    /**
     * Parcourt les blocs-entités chargés et rend le classement par type.
     *
     * @param limit nombre de lignes à afficher
     */
    public static void survey(CommandSourceStack source, int limit) {
        ServerLevel level = source.getLevel();
        Map<String, long[]> byType = new HashMap<>();
        long total = 0L;
        long seen = 0L;
        long failed = 0L;

        for (LevelChunk chunk : loadedChunks(level, source.getPosition())) {
            for (BlockEntity entity : chunk.getBlockEntities().values()) {
                seen++;
                long size = weigh(entity, level);
                if (size < 0L) {
                    failed++;
                    continue;
                }
                total += size;
                String type = name(entity);
                long[] tally = byType.computeIfAbsent(type, key -> new long[2]);
                tally[0]++;
                tally[1] += size;
            }
        }

        if (seen == 0L) {
            source.sendSuccess(() -> Component.literal(
                    "Aucun bloc-entité chargé. Rien à peser."), false);
            return;
        }

        List<Tally> rows = new ArrayList<>();
        for (var entry : byType.entrySet()) {
            rows.add(new Tally(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        rows.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));

        final long totalBytes = total;
        final long totalSeen = seen;
        final long totalFailed = failed;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d bloc(s)-entité chargé(s), %.1f Ko écrits au total%s",
                totalSeen, totalBytes / 1024d,
                totalFailed == 0L ? "" : String.format(Locale.ROOT,
                        " (%d illisibles, ignorés)", totalFailed))), false);

        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            Tally row = rows.get(i);
            double share = row.bytes() * 100d / Math.max(1L, totalBytes);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  %5.1f %%  %-38s  %6d × %7.1f o  =  %8.1f Ko",
                    share, row.type(), row.count(),
                    row.bytes() / (double) Math.max(1L, row.count()),
                    row.bytes() / 1024d)), false);
        }

        Lanterne.LOG.info("[GRAND LIVRE] {} bloc(s)-entité pesé(s), {} Ko au total.",
                seen, String.format(Locale.ROOT, "%.1f", total / 1024d));
    }

    /**
     * Pèse un bloc-entité en l'écrivant vraiment.
     *
     * <p>On passe par le même encodeur que la sauvegarde, dans un tampon mémoire. Estimer la taille
     * en parcourant l'arbre de balises donnerait un autre chiffre — celui qu'on aurait calculé, et
     * non celui qui sera écrit.
     *
     * @return le nombre d'octets, ou {@code -1} si ce bloc-entité refuse d'être sérialisé
     */
    private static long weigh(BlockEntity entity, ServerLevel level) {
        try {
            CompoundTag tag = entity.saveWithFullMetadata(level.registryAccess());
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                NbtIo.write(tag, out);
            }
            return buffer.size();
        } catch (Throwable refused) {
            // Un bloc-entité de mod peut lever à peu près n'importe quoi ici. On ne laisse pas une
            // machine mal écrite faire échouer le relevé entier : on la compte à part et l'on
            // continue.
            return -1L;
        }
    }

    private static String name(BlockEntity entity) {
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                .getKey(entity.getType());
        return key == null ? entity.getClass().getSimpleName() : key.toString();
    }

    /**
     * Les chunks chargés autour du point d.appel.
     *
     * <p>La liste complète des chunks chargés n.est pas accessible publiquement — {@code ChunkMap}
     * garde la sienne privée. On balaie donc un carré autour de celui qui lance la commande, ce qui
     * est de toute façon plus utile : on pèse la zone qu.on veut examiner, pas le monde entier.
     */
    private static List<LevelChunk> loadedChunks(ServerLevel level, net.minecraft.world.phys.Vec3 around) {
        List<LevelChunk> found = new ArrayList<>();
        int centreX = net.minecraft.core.SectionPos.blockToSectionCoord((int) around.x);
        int centreZ = net.minecraft.core.SectionPos.blockToSectionCoord((int) around.z);
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                if (level.hasChunk(centreX + dx, centreZ + dz)) {
                    found.add(level.getChunk(centreX + dx, centreZ + dz));
                }
            }
        }
        return found;
    }
}
