package fr.clubcitrouille.lanterne.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.disc.Post;
import fr.clubcitrouille.lanterne.content.disc.Reel;
import fr.clubcitrouille.lanterne.content.disc.Slots;
import fr.clubcitrouille.lanterne.content.painting.Studio;
import fr.clubcitrouille.lanterne.content.screen.Embed;

/**
 * Les disques YouTube : le jukebox joue un lecteur intégré officiel, en son seul.
 *
 * <h2>Aucune extraction de flux</h2>
 *
 * <p>Un disque YouTube ne porte que l'identifiant de la vidéo (empreinte {@code yt-<id>}). Pour le
 * jouer, on ouvre la même page que le projecteur — {@link Hote} autour du lecteur intégrable, piloté
 * par {@link Chrome} — dont on n'affiche jamais la texture. Aucune adresse de fichier n'est
 * cherchée : voir la Javadoc de {@link Chrome} pour pourquoi c'est la frontière qui compte.
 *
 * <h2>Comment on sait qu'un jukebox joue un disque YouTube</h2>
 *
 * <p>Vanilla fait jouer un jukebox par l'évènement de niveau 1010, qui lance le son du sillon
 * ({@code lanterne:disques/graves/grave_NN}). Pour un sillon YouTube, ce son est un silence
 * ({@code Reel.open} ne trouve aucun fichier) : on l'annule dans {@link PlaySoundEvent} et on ouvre
 * une séance à la place. Tous les joueurs à portée reçoivent le 1010 au même instant — ils partent
 * donc ensemble. Un joueur qui arrive en cours de morceau reçoit, avant le 1010 que rejoue
 * {@code Retrack}, un {@link Post.Tempo} qui dit où en est le morceau : il reprend au bon endroit.
 *
 * <h2>Arrêt</h2>
 *
 * <p>Une séance s'arrête quand le jukebox n'a plus de disque ({@code HAS_RECORD}), quand le bloc
 * n'en est plus un, quand son tronçon est déchargé (le 1010 rejoué au retour la relancera au bon
 * endroit), au-delà de la durée du disque, au changement de dimension et à la déconnexion. Hors de
 * portée, la séance continue de compter mais sa page est fermée : pas de navigateur fantôme.
 *
 * <h2>Coût</h2>
 *
 * <p>Une page = un navigateur CEF hors écran. On n'en ouvre qu'au plus {@link #CAP} à la fois, pour
 * les jukebox les plus proches ; un disque plus lointain attend qu'une place se libère.
 */
public final class Juke {
    /** Portée d'un jukebox vanilla (volume 4, atténuation linéaire : 4 × 16 blocs). */
    private static final double RANGE = 64d;

    /** Pages ouvertes au plus à la fois. */
    private static final int CAP = 2;

    /** Hauteur du rendu de la page : le minimum qu'accepte {@link Chrome}, puisqu'on ne l'affiche pas. */
    private static final int HEIGHT = 240;

    private static final class Session {
        private final String videoId;
        private final String title;
        private final long startedAt;
        private final long lengthMillis;
        private final ResourceKey<Level> dimension;
        private Engine.Reel reel;
        private boolean failed;
        private boolean told;

        private Session(String videoId, String title, long startedAt, long lengthMillis,
                ResourceKey<Level> dimension) {
            this.videoId = videoId;
            this.title = title;
            this.startedAt = startedAt;
            this.lengthMillis = lengthMillis;
            this.dimension = dimension;
        }
    }

    private static final Map<BlockPos, Session> SESSIONS = new HashMap<>();
    /** Position reçue par {@link Post.Tempo}, en attente du 1010 qui la suit : ticks et instant. */
    private static final Map<BlockPos, long[]> TEMPOS = new HashMap<>();

    private Juke() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(Juke::onPlaySound);
        NeoForge.EVENT_BUS.addListener(Juke::onTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> closeAll());
    }

    /** Voir {@link Post.Tempo}. Appelé sur le fil du jeu. */
    public static void tempo(BlockPos pos, long ticks) {
        TEMPOS.put(pos.immutable(), new long[] {Math.max(0L, ticks), System.currentTimeMillis()});
    }

    private static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getOriginalSound();
        if (sound == null) {
            return;
        }
        Identifier id = sound.getIdentifier();
        String prefix = Reel.BURNT_DIR + "/";
        if (!Lanterne.ID.equals(id.getNamespace()) || !id.getPath().startsWith(prefix)) {
            return;
        }
        int slot = slotOf(id.getPath().substring(prefix.length()));
        Slots.Cut cut = slot < 0 ? null : Slots.at(slot);
        if (cut == null || !Post.tube(cut.hash())) {
            return;
        }
        event.setSound(null);
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        BlockPos pos = BlockPos.containing(sound.getX(), sound.getY(), sound.getZ());
        long now = System.currentTimeMillis();
        long offset = 0L;
        long[] tempo = TEMPOS.remove(pos);
        if (tempo != null && now - tempo[1] < 10_000L) {
            offset = tempo[0] * 50L + (now - tempo[1]);
        }
        end(SESSIONS.remove(pos));
        String videoId = cut.hash().substring(Post.TUBE_PREFIX.length());
        SESSIONS.put(pos.immutable(), new Session(videoId, cut.title(), now - offset,
                Math.round(cut.seconds() * 1000d), client.level.dimension()));
        Lanterne.LOG.info("[DISQUE] YouTube « {} » ({}) au jukebox {} — départ à {} s.", cut.title(),
                videoId, pos.toShortString(), offset / 1000L);
    }

    private static int slotOf(String name) {
        for (int slot = 0; slot < Studio.discSlots(); slot++) {
            if (Slots.name(slot).equals(name)) {
                return slot;
            }
        }
        return -1;
    }

    private static void onTick(ClientTickEvent.Post event) {
        tickProbe();
        if (SESSIONS.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            closeAll();
            return;
        }
        long now = System.currentTimeMillis();
        List<Map.Entry<BlockPos, Session>> audible = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Session> entry = it.next();
            BlockPos pos = entry.getKey();
            Session session = entry.getValue();
            if (!over(client, pos, session, now)) {
                if (!session.failed && distance(client, pos) <= RANGE) {
                    audible.add(entry);
                } else {
                    close(session);
                }
                continue;
            }
            end(session);
            it.remove();
        }
        audible.sort((a, b) -> Double.compare(distance(client, a.getKey()), distance(client, b.getKey())));
        for (int i = 0; i < audible.size(); i++) {
            BlockPos pos = audible.get(i).getKey();
            Session session = audible.get(i).getValue();
            if (i >= CAP) {
                close(session);
                continue;
            }
            drive(client, pos, session, now);
        }
    }

    /** La séance est-elle finie pour de bon ? */
    private static boolean over(Minecraft client, BlockPos pos, Session session, long now) {
        if (client.level.dimension() != session.dimension || !client.level.isLoaded(pos)) {
            return true;
        }
        BlockState state = client.level.getBlockState(pos);
        if (!(state.getBlock() instanceof JukeboxBlock) || !state.getValue(JukeboxBlock.HAS_RECORD)) {
            return true;
        }
        return now - session.startedAt > session.lengthMillis + 3_000L;
    }

    private static void drive(Minecraft client, BlockPos pos, Session session, long now) {
        if (session.reel == null) {
            Engine.Verdict verdict = Chrome.INSTANCE.verdict();
            switch (verdict) {
                case SANS_MOD -> {
                    tell(session, "Ce jukebox joue un disque YouTube : installe « Rinku » pour "
                            + "l'entendre.", ChatFormatting.YELLOW);
                    session.failed = true;
                    return;
                }
                case SANS_CONSENTEMENT -> {
                    tell(session, "Ce jukebox joue un disque YouTube. Pour l'entendre, autorise les "
                            + "médias distants (clic sur un écran ou un projecteur → « Autoriser les "
                            + "médias distants »).", ChatFormatting.YELLOW);
                    session.failed = true;
                    return;
                }
                case PRET -> { }
                default -> {
                    return; // Rinku installe encore son navigateur : on réessaiera au tick suivant.
                }
            }
            session.reel = Chrome.INSTANCE.open("https://www.youtube.com/watch?v=" + session.videoId,
                    HEIGHT);
            if (session.reel == null) {
                tell(session, "Disque « " + session.title + " » : le lecteur YouTube n'a pas pu "
                        + "s'ouvrir.", ChatFormatting.RED);
                session.failed = true;
                return;
            }
        }
        Engine.Reel reel = session.reel;
        String trouble = reel.trouble();
        if (trouble != null && !trouble.isBlank()) {
            tell(session, "Disque « " + session.title + " » : " + trouble + ". Essaie la version "
                    + "« Topic » ou « Lyrics » du morceau — souvent autorisée à l'intégration.",
                    ChatFormatting.RED);
            session.failed = true;
            close(session);
            return;
        }
        double near = Math.max(0d, 1d - distance(client, pos) / RANGE);
        float gain = client.options.getFinalSoundSourceVolume(SoundSource.RECORDS);
        reel.volume((float) (near * gain));
        Chrome.playing(reel, true);
        Chrome.looping(reel, false);
        reel.present(now - session.startedAt, null);
    }

    private static double distance(Minecraft client, BlockPos pos) {
        return Math.sqrt(client.player.distanceToSqr(pos.getX() + 0.5d, pos.getY() + 0.5d,
                pos.getZ() + 0.5d));
    }

    private static void tell(Session session, String text, ChatFormatting colour) {
        if (session.told) {
            return;
        }
        session.told = true;
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal(text).withStyle(colour));
        }
    }

    private static void close(Session session) {
        if (session != null && session.reel != null) {
            session.reel.close();
            session.reel = null;
        }
    }

    private static void end(Session session) {
        close(session);
    }

    private static void closeAll() {
        SESSIONS.values().forEach(Juke::end);
        SESSIONS.clear();
        TEMPOS.clear();
        cancelProbe();
    }

    // --- La sonde du graveur ------------------------------------------------

    /** Ce que la sonde rend au graveur. */
    public interface Probe {
        void ok(String videoId, float seconds, String title);

        void fail(String reason);
    }

    private static Engine.Reel probeReel;
    private static Probe probeEar;
    private static String probeId;
    private static long probeStarted;
    private static final long PROBE_TIMEOUT = 25_000L;

    /**
     * Ouvre le lien dans un lecteur muet pour lire, avant de graver, ce que YouTube en dit : sa durée,
     * son titre, et surtout s'il refuse l'intégration (erreur 150/101) — mieux vaut le savoir au
     * graveur qu'au jukebox. Le lecteur joue en muet le temps de la sonde (quelques secondes) :
     * c'est ce qui fait remonter la durée et une éventuelle erreur.
     */
    public static void probe(String link, Probe ear) {
        cancelProbe();
        if (Embed.youtubeChannelLink(link)) {
            ear.fail("Lien de chaîne, pas de vidéo. Sur la vidéo : Partager → copie le lien youtu.be/…");
            return;
        }
        String id = Embed.youtubeVideoId(link);
        if (id == null || !Post.plausibleVideoId(id)) {
            ear.fail("Lien YouTube illisible.");
            return;
        }
        switch (Chrome.INSTANCE.verdict()) {
            case SANS_MOD -> {
                ear.fail("YouTube demande le mod « Rinku » (lecteur intégré).");
                return;
            }
            case SANS_CONSENTEMENT -> {
                ear.fail("Autorise d'abord les médias distants (clic sur un écran → « Autoriser »).");
                return;
            }
            case PRET -> { }
            default -> {
                ear.fail("Rinku installe encore son navigateur — réessaie dans un instant.");
                return;
            }
        }
        Engine.Reel reel = Chrome.INSTANCE.open("https://www.youtube.com/watch?v=" + id, HEIGHT);
        if (reel == null) {
            ear.fail("Le lecteur YouTube n'a pas pu s'ouvrir.");
            return;
        }
        probeReel = reel;
        probeEar = ear;
        probeId = id;
        probeStarted = System.currentTimeMillis();
    }

    public static void cancelProbe() {
        if (probeReel != null) {
            probeReel.close();
        }
        probeReel = null;
        probeEar = null;
        probeId = null;
    }

    private static void tickProbe() {
        Engine.Reel reel = probeReel;
        Probe ear = probeEar;
        if (reel == null || ear == null) {
            return;
        }
        long now = System.currentTimeMillis();
        reel.volume(0f);
        Chrome.playing(reel, true);
        Chrome.looping(reel, false);
        reel.present(now - probeStarted, null);

        String trouble = reel.trouble();
        String id = probeId;
        if (trouble != null && !trouble.isBlank()) {
            cancelProbe();
            ear.fail(trouble + " — essaie la version « Topic » ou « Lyrics ».");
        } else if (Chrome.liveOf(reel)) {
            cancelProbe();
            ear.fail("C'est un direct : il n'a pas de durée, on ne peut pas le graver.");
        } else if (reel.duration() > 0L) {
            float seconds = reel.duration() / 1000f;
            String title = Chrome.titleOf(reel);
            cancelProbe();
            ear.ok(id, seconds, title);
        } else if (now - probeStarted > PROBE_TIMEOUT) {
            cancelProbe();
            ear.fail("YouTube ne répond pas pour cette vidéo — réessaie.");
        }
    }
}
