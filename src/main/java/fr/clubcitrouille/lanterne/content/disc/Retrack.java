package fr.clubcitrouille.lanterne.content.disc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;

/**
 * Fait entendre à nouveau un jukebox qui jouait déjà quand son morceau réapparaît dans la vue
 * d'un joueur.
 *
 * <h2>Le bug qui n'est pas d'ici, mais qu'on corrige quand même</h2>
 *
 * <p>{@code JukeboxBlockEntity} ne diffuse le son qu'une seule fois : au moment où
 * {@code itemChanged} appelle {@code jukeboxSongPlayer.play}, qui envoie l'évènement de niveau
 * {@code 1010} — c'est <b>cet évènement</b>, et lui seul, qui déclenche
 * {@code SimpleSoundInstance.forJukeboxSong} côté client. Quand un joueur s'éloigne assez pour que
 * le tronçon quitte sa zone suivie, le serveur cesse de le lui envoyer ; quand il revient, le
 * tronçon est retransmis via {@code loadAdditional}, qui appelle délibérément
 * {@code setSongWithoutPlaying} — <b>sans</b> rejouer l'évènement {@code 1010}. Le morceau reprend
 * sa place dans l'état du bloc, silencieusement, et rien ne le fait plus entendre tant qu'on ne
 * retire pas puis remet le disque, ce qui relance {@code itemChanged}.
 *
 * <p>C'est un comportement de vanilla, vérifié dans les sources décompilées de la 26.3
 * ({@code JukeboxBlockEntity.loadAdditional}, {@code JukeboxSongPlayer.setSongWithoutPlaying}) —
 * il touche donc aussi bien un disque vanilla qu'un disque Lanterne. Personne ne semble s'en être
 * plaint jusqu'ici parce qu'un joueur retourne rarement exprès vers un jukebox qu'il vient de
 * quitter ; avec des disques qu'on écoute et qu'on montre, c'est précisément ce qu'on fait.
 *
 * <h2>Le correctif : reproduire ce que ferait un joueur qui remet le disque</h2>
 *
 * <p>Au moment où un tronçon entre dans la zone suivie d'un joueur ({@code ChunkWatchEvent.Watch}),
 * on cherche les jukebox de ce tronçon dont le lecteur est actif, et on envoie à <b>ce seul joueur</b>
 * — pas une diffusion, les autres l'entendent déjà correctement — le même évènement {@code 1010}
 * que {@code JukeboxSongPlayer.play} aurait envoyé. Le morceau repart du début pour lui, exactement
 * ce que remettre le disque produirait déjà ; vanilla lui-même n'offre aucun moyen de reprendre au
 * milieu d'un morceau pour un client qui vient de le recevoir.
 */
public final class Retrack {
    private Retrack() {}

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(Retrack.class);
    }

    @SubscribeEvent
    public static void onWatch(ChunkWatchEvent.Watch event) {
        ServerPlayer player = event.getPlayer();
        LevelChunk chunk = event.getChunk();
        Registry<JukeboxSong> songs = event.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG);

        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            if (!(entity instanceof JukeboxBlockEntity jukebox)) {
                continue;
            }
            JukeboxSong song = jukebox.getSongPlayer().getSong();
            if (song == null) {
                continue;
            }
            int songId = songs.getId(song);
            if (songId < 0) {
                continue;
            }
            BlockPos pos = jukebox.getBlockPos();
            player.connection.send(
                    new ClientboundLevelEventPacket(LevelEvent.SOUND_PLAY_JUKEBOX_SONG, pos, songId, false));
        }
    }
}
