package fr.clubcitrouille.lanterne.content;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * La Boussole d'Ancre : retrouver ce qu'on a posé et oublié.
 *
 * <h2>Le problème qu'elle résout, et il est réel</h2>
 *
 * <p>Une Ancre garde son chunk chargé indéfiniment. C'est exactement ce qu'on lui demande, et c'est
 * aussi ce qui la rend facile à oublier : elle ne fait pas de bruit, ne s'use pas, et un serveur de
 * six mois finit par en porter que personne ne sait plus situer.
 *
 * <p>Vanilla a bien {@code /forceload query}, mais il répond à un opérateur, en coordonnées de chunk,
 * dans une console. Cette boussole répond à un <b>joueur</b>, en blocs, dans sa direction de regard.
 *
 * <h2>Pourquoi elle lit le chargement forcé et non une liste à elle</h2>
 *
 * <p>L'Ancre s'appuie sur {@code setChunkForced}, le mécanisme de vanilla. Tenir une seconde liste en
 * parallèle serait une source de désaccord : deux registres du même fait finissent toujours par
 * diverger, et c'est le plus discret des deux qui ment.
 *
 * <p>Elle interroge donc directement {@code level.getForcedChunks()} — la vérité, celle sur laquelle
 * le jeu agit vraiment. Conséquence assumée : un chunk forcé à la main par un opérateur apparaîtra
 * aussi. Ce n'est pas un défaut, c'est la même question posée honnêtement.
 *
 * <h2>Pourquoi elle parle au lieu de tourner</h2>
 *
 * <p>Une aiguille qui tourne demanderait un état synchronisé par objet et par joueur, et le système
 * de modèles d'objets de 26.1 n'est pas le même que celui des tutoriels. Un message dit la distance
 * <b>et</b> la direction relative au regard, ce qu'aucune aiguille ne sait faire.
 */
public class AnchorCompass extends Item {
    public AnchorCompass(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }

        // En 26.1 le chargement forcé est passé au système de tickets : la liste vit sur la
        // source de chunks, plus sur le niveau. C'est toujours la même vérité, celle sur
        // laquelle le jeu agit — pas une seconde liste tenue par ce mod.
        var forced = server.getChunkSource().getForceLoadedChunks();
        if (forced.isEmpty()) {
            say(player, "Aucun chunk gardé dans ce monde.", ChatFormatting.GRAY);
            return InteractionResult.SUCCESS;
        }

        long best = 0L;
        double bestDistance = Double.MAX_VALUE;
        for (long packed : forced) {
            // Le centre du chunk, et non son coin : un écart de huit blocs sur une direction rendrait
            // la boussole fausse de près de quarante-cinq degrés à courte portée.
            double x = ChunkPos.getX(packed) * 16 + 8;
            double z = ChunkPos.getZ(packed) * 16 + 8;
            double dx = x - player.getX();
            double dz = z - player.getZ();
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = packed;
            }
        }

        double x = ChunkPos.getX(best) * 16 + 8;
        double z = ChunkPos.getZ(best) * 16 + 8;
        double far = Math.sqrt(bestDistance);
        say(player, String.format(Locale.ROOT,
                "Chunk gardé le plus proche : %.0f blocs, %s — en (%d, %d). %d gardé(s) au total.",
                far, heading(player, x, z), (int) x, (int) z, forced.size()),
                ChatFormatting.GOLD);
        return InteractionResult.SUCCESS;
    }

    /**
     * La direction, relative au regard du joueur.
     *
     * <p>« Nord-est » suppose de savoir où est le nord. « Devant toi, un peu à gauche » ne le suppose
     * pas — et c'est ce dont on a besoin quand on cherche quelque chose.
     */
    private static String heading(Player player, double x, double z) {
        double dx = x - player.getX();
        double dz = z - player.getZ();
        float toward = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90d);
        float delta = toward - player.getYRot();
        while (delta <= -180f) {
            delta += 360f;
        }
        while (delta > 180f) {
            delta -= 360f;
        }
        float away = Math.abs(delta);
        if (away < 20f) {
            return "droit devant";
        }
        if (away > 160f) {
            return "droit derrière";
        }
        String side = delta < 0f ? "gauche" : "droite";
        if (away < 70f) {
            return "devant, sur ta " + side;
        }
        if (away < 110f) {
            return "sur ta " + side;
        }
        return "derrière, sur ta " + side;
    }

    private static void say(Player player, String text, ChatFormatting colour) {
        player.sendSystemMessage(Component.literal(text).withStyle(colour));
    }
}
