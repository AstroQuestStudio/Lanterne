package fr.clubcitrouille.lanterne.chantier;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Un travail du chantier : une requête devenue une suite de petits pas sur le thread serveur.
 *
 * <p>Trois temps. {@link #demarrer} une fois, sur le thread serveur (vérifications qui lisent le
 * monde : joueurs proches…). {@link #avancer} à chaque tick tant qu'il reste à faire, en rendant la
 * main à l'échéance — ou plus tôt s'il attend un chunk. {@link #conclure} enfin, HORS du thread
 * serveur : c'est là que se paient la mise en JSON, l'encodage PNG et l'écriture des journaux.
 *
 * <h2>Chunks</h2>
 *
 * <p>Un chunk absent n'est jamais chargé de force sur le thread serveur (un chargement synchrone
 * gèlerait le tick le temps d'une lecture disque, voire d'une génération). Il est demandé par un
 * ticket {@link TicketType#PLAYER_SPAWN} — chargement seul, sans simulation, qui expire de lui-même
 * vingt ticks après son dernier renouvellement et ne peut pas expirer tant qu'il charge — et le
 * travail attend le tick suivant. Aucun ticket n'est donc jamais oublié : un travail qui plante
 * laisse au pire des chunks chargés vingt ticks de trop.
 */
abstract class Travail {
    static final TicketType TICKET = TicketType.PLAYER_SPAWN;
    private static final int AVANCE = 6;

    final String id = Journal.nouvelId();
    final String nature;
    final ServerLevel level;
    final CompletableFuture<Resultat> fin = new CompletableFuture<>();
    final long cree = System.nanoTime();
    long demarre;
    long termine;
    long tempsServeur;
    long pireTick;
    int ticks;
    int attenteChunks;
    volatile String etat = "en_attente";
    /** Non nul si le serveur s'est arrêté avant la fin : {@link #conclure} doit rendre ce qui a été fait. */
    volatile @Nullable String interrompu;
    private final LongOpenHashSet demandes = new LongOpenHashSet();

    record Resultat(int code, @Nullable JsonObject json, byte @Nullable [] png) {
        static Resultat ok(JsonObject json) {
            return new Resultat(200, json, null);
        }
    }

    Travail(String nature, ServerLevel level) {
        this.nature = nature;
        this.level = level;
    }

    /** Une fois, sur le thread serveur, avant le premier pas. Peut lever {@link Refus}. */
    void demarrer() {}

    /** Avance jusqu'à {@code echeance} (nanoTime). Vrai si le travail côté serveur est fini. */
    abstract boolean avancer(long echeance);

    /** Hors du thread serveur, une fois fini : met en forme le résultat. */
    abstract Resultat conclure() throws Exception;

    /** Ce qu'on peut dire de l'avancement, pour {@code /travail}. */
    JsonObject avancement() {
        return new JsonObject();
    }

    /**
     * Le chunk est-il prêt ? S'il l'est, son ticket est renouvelé ; sinon il est demandé et
     * l'appelant doit rendre la main. Les suivants sont demandés d'avance dans les deux cas. Avec
     * {@code rayon} 1, les huit voisins doivent être chargés aussi.
     */
    final @Nullable LevelChunk pret(ServerLevel lvl, Parcours p) {
        return pret(lvl, p, 0);
    }

    final @Nullable LevelChunk pret(ServerLevel lvl, Parcours p, int rayon) {
        long k = p.chunk();
        for (long suivant : p.aVenir(AVANCE)) {
            demander(lvl, suivant, rayon);
        }
        return pret(lvl, k, rayon);
    }

    final @Nullable LevelChunk pret(ServerLevel lvl, long k) {
        return pret(lvl, k, 0);
    }

    final @Nullable LevelChunk pret(ServerLevel lvl, long k, int rayon) {
        int cx = ChunkPos.getX(k);
        int cz = ChunkPos.getZ(k);
        demander(lvl, k, rayon);
        LevelChunk c = lvl.getChunkSource().getChunkNow(cx, cz);
        if (c != null && rayon > 0) {
            for (int dx = -rayon; dx <= rayon && c != null; dx++) {
                for (int dz = -rayon; dz <= rayon; dz++) {
                    if (lvl.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) {
                        c = null;
                        break;
                    }
                }
            }
        }
        if (c == null) {
            attenteChunks++;
        }
        return c;
    }

    final void demander(ServerLevel lvl, long k) {
        demander(lvl, k, 0);
    }

    final void demander(ServerLevel lvl, long k, int rayon) {
        ChunkPos cp = ChunkPos.unpack(k);
        if (demandes.add(k * 2 + rayon)) {
            lvl.getChunkSource().addTicketAndLoadWithRadius(TICKET, cp, rayon);
        } else {
            lvl.getChunkSource().addTicketWithRadius(TICKET, cp, rayon);
        }
    }

    /** Tous ces chunks sont-ils prêts ? Les demande sinon (pour un travail qui a besoin d'une zone entière). */
    final boolean tousPrets(ServerLevel lvl, List<Long> chunks) {
        boolean ok = true;
        for (long k : chunks) {
            if (pret(lvl, k) == null) {
                ok = false;
            }
        }
        return ok;
    }

    /** Les mesures communes à tout résultat. */
    final JsonObject mesures() {
        JsonObject m = new JsonObject();
        m.addProperty("travail", id);
        m.addProperty("duree_ms", (termine - demarre) / 1_000_000L);
        m.addProperty("attente_file_ms", (demarre - cree) / 1_000_000L);
        m.addProperty("ticks", ticks);
        m.addProperty("temps_serveur_ms", Math.round(tempsServeur / 10_000.0) / 100.0);
        m.addProperty("pire_tick_ms", Math.round(pireTick / 10_000.0) / 100.0);
        m.addProperty("attentes_chunk", attenteChunks);
        return m;
    }
}
