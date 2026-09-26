package fr.clubcitrouille.lanterne.chantier;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

/** Les lectures : blocs d'une boîte, blocs-entités, entités, hauteurs. Rien n'y écrit dans le monde. */
final class Lectures {
    private Lectures() {}

    /**
     * Les blocs d'une boîte : palette + tableau d'indices dans l'ordre y, z, x ({@link Boite}),
     * compressé par plages ({@code "rle": [indice, longueur, indice, longueur, …]}) ou brut.
     * Avec {@code nbt}, les blocs-entités suivent en SNBT. Sert aussi au cliché ({@link Cliche}).
     */
    static class Blocs extends Travail {
        final Boite boite;
        final boolean avecBlocs;
        final boolean avecNbt;
        final boolean rle;
        final int[] donnees;
        final Etats.Palette palette = new Etats.Palette();
        final List<Object[]> blocEntites = new ArrayList<>();
        private final Boite.Curseur c;
        private LevelChunk chunk;
        private long chunkCourant;

        Blocs(String nature, ServerLevel level, Boite boite, boolean avecBlocs, boolean avecNbt, boolean rle) {
            super(nature, level);
            this.boite = boite;
            this.avecBlocs = avecBlocs;
            this.avecNbt = avecNbt;
            this.rle = rle;
            this.donnees = avecBlocs ? new int[(int) boite.volume()] : null;
            this.c = boite.new Curseur();
        }

        @Override
        boolean avancer(long echeance) {
            chunkCourant = Long.MIN_VALUE;
            int k = 0;
            while (!c.fini()) {
                if ((++k & 127) == 0 && System.nanoTime() >= echeance) {
                    return false;
                }
                if (c.chunk() != chunkCourant) {
                    chunk = pret(level, c);
                    if (chunk == null) {
                        return false;
                    }
                    chunkCourant = c.chunk();
                }
                BlockPos.MutableBlockPos p = c.courant();
                BlockState s = chunk.getBlockState(p);
                lu(p, s);
                c.avancer();
            }
            return true;
        }

        void lu(BlockPos.MutableBlockPos p, BlockState s) {
            if (avecBlocs) {
                donnees[boite.index(p.getX(), p.getY(), p.getZ())] = palette.id(s);
            }
            if (avecNbt && s.hasBlockEntity()) {
                CompoundTag t = Etats.sauver(level, p, s);
                if (t != null) {
                    blocEntites.add(new Object[] {p.immutable(), s, t});
                }
            }
        }

        @Override
        JsonObject avancement() {
            JsonObject o = new JsonObject();
            o.addProperty("attentes_chunk", attenteChunks);
            return o;
        }

        @Override
        Resultat conclure() throws Exception {
            JsonObject o = mesures();
            o.add("boite", boite.json());
            o.addProperty("dim", level.dimension().identifier().toString());
            if (avecBlocs) {
                o.addProperty("ordre", "y,z,x");
                JsonArray pal = new JsonArray();
                for (BlockState s : palette.etats) {
                    pal.add(Etats.texte(s));
                }
                o.add("palette", pal);
                JsonArray t = new JsonArray();
                if (rle) {
                    int i = 0;
                    while (i < donnees.length) {
                        int v = donnees[i];
                        int j = i + 1;
                        while (j < donnees.length && donnees[j] == v) {
                            j++;
                        }
                        t.add(v);
                        t.add(j - i);
                        i = j;
                    }
                    o.add("rle", t);
                } else {
                    for (int v : donnees) {
                        t.add(v);
                    }
                    o.add("blocs", t);
                }
            }
            if (avecNbt) {
                JsonArray be = new JsonArray();
                for (Object[] e : blocEntites) {
                    JsonObject b = new JsonObject();
                    b.add("p", Json.xyz((BlockPos) e[0]));
                    b.addProperty("bloc", Etats.texte((BlockState) e[1]));
                    b.addProperty("snbt", e[2].toString());
                    be.add(b);
                }
                o.add("bloc_entites", be);
            }
            return Resultat.ok(o);
        }
    }

    /** Les entités d'une boîte. Attend que leurs chunks ET leurs sections d'entités soient chargés. */
    static final class Entites extends Travail {
        private static final int ATTENTE_MAX = 200;
        private final Boite boite;
        private final boolean avecNbt;
        private final List<Long> chunks;
        private int attente;
        private final JsonArray sortie = new JsonArray();
        private boolean incomplet;

        Entites(ServerLevel level, Boite boite, boolean avecNbt) {
            super("lire_entites", level);
            this.boite = boite;
            this.avecNbt = avecNbt;
            this.chunks = boite.chunks();
            if (chunks.size() > 1024) {
                throw new Refus(413, "lecture d'entités limitée à 1024 chunks (" + chunks.size() + " demandés)");
            }
        }

        @Override
        boolean avancer(long echeance) {
            boolean prets = tousPrets(level, chunks);
            if (prets) {
                for (long k : chunks) {
                    if (!level.areEntitiesLoaded(k)) {
                        prets = false;
                        break;
                    }
                }
            }
            if (!prets && ++attente < ATTENTE_MAX) {
                return false;
            }
            incomplet = !prets;
            AABB zone = new AABB(boite.minX(), boite.minY(), boite.minZ(), boite.maxX() + 1, boite.maxY() + 1, boite.maxZ() + 1);
            for (Entity e : level.getEntities((Entity) null, zone, x -> true)) {
                JsonObject o = new JsonObject();
                o.addProperty("type", EntityType.getKey(e.getType()).toString());
                o.addProperty("uuid", e.getStringUUID());
                JsonArray p = new JsonArray();
                p.add(e.getX());
                p.add(e.getY());
                p.add(e.getZ());
                o.add("pos", p);
                o.addProperty("lacet", e.getYRot());
                o.addProperty("tangage", e.getXRot());
                if (e.hasCustomName()) {
                    o.addProperty("nom", e.getCustomName().getString());
                }
                if (avecNbt) {
                    TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                    e.saveWithoutId(out);
                    o.addProperty("snbt", out.buildResult().toString());
                }
                sortie.add(o);
            }
            return true;
        }

        @Override
        Resultat conclure() {
            JsonObject o = mesures();
            o.add("boite", boite.json());
            o.add("entites", sortie);
            o.addProperty("nombre", sortie.size());
            if (incomplet) {
                o.addProperty("avertissement", "certaines sections d'entités n'étaient pas chargées après "
                        + ATTENTE_MAX + " ticks : liste possiblement incomplète");
            }
            return Resultat.ok(o);
        }
    }

    /**
     * Les hauteurs d'une zone : pour chaque colonne (ordre z puis x), le plus haut bloc non-air dans
     * la tranche y de la boîte, ou la carte des hauteurs du jeu si {@code type} est donné.
     */
    static final class Hauteurs extends Travail {
        private final Boite boite;
        private final Heightmap.@Nullable Types type;
        private final int[] y;
        private final int[] bloc;
        private final Etats.Palette palette = new Etats.Palette();
        private final List<Long> chunks;
        private int c;

        Hauteurs(ServerLevel level, Boite boite, Heightmap.@Nullable Types type) {
            super("lire_hauteurs", level);
            this.boite = boite;
            this.type = type;
            int n = boite.sx() * boite.sz();
            this.y = new int[n];
            this.bloc = new int[n];
            this.chunks = boite.chunks();
        }

        @Override
        boolean avancer(long echeance) {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            while (c < chunks.size()) {
                if (System.nanoTime() >= echeance) {
                    return false;
                }
                long k = chunks.get(c);
                for (int j = c + 1; j < Math.min(chunks.size(), c + 7); j++) {
                    demander(level, chunks.get(j));
                }
                LevelChunk ch = pret(level, k);
                if (ch == null) {
                    return false;
                }
                int cx = net.minecraft.world.level.ChunkPos.getX(k) << 4;
                int cz = net.minecraft.world.level.ChunkPos.getZ(k) << 4;
                for (int z = Math.max(boite.minZ(), cz); z <= Math.min(boite.maxZ(), cz + 15); z++) {
                    for (int x = Math.max(boite.minX(), cx); x <= Math.min(boite.maxX(), cx + 15); x++) {
                        int i = (z - boite.minZ()) * boite.sx() + (x - boite.minX());
                        int trouve = boite.minY() - 1;
                        BlockState s = null;
                        if (type != null) {
                            trouve = ch.getHeight(type, x & 15, z & 15);
                            s = ch.getBlockState(p.set(x, trouve, z));
                        } else {
                            for (int yy = boite.maxY(); yy >= boite.minY(); yy--) {
                                BlockState t = ch.getBlockState(p.set(x, yy, z));
                                if (!t.isAir()) {
                                    trouve = yy;
                                    s = t;
                                    break;
                                }
                            }
                        }
                        y[i] = trouve;
                        bloc[i] = s == null ? -1 : palette.id(s);
                    }
                }
                c++;
            }
            return true;
        }

        @Override
        Resultat conclure() {
            JsonObject o = mesures();
            o.add("boite", boite.json());
            o.addProperty("ordre", "z,x");
            o.addProperty("type", type == null ? "premier_non_air" : type.getSerializedName());
            JsonArray hy = new JsonArray();
            for (int v : y) {
                hy.add(v);
            }
            JsonArray hb = new JsonArray();
            for (int v : bloc) {
                hb.add(v);
            }
            JsonArray pal = new JsonArray();
            for (BlockState s : palette.etats) {
                pal.add(Etats.texte(s));
            }
            o.add("hauteurs", hy);
            o.add("blocs", hb);
            o.add("palette", pal);
            o.addProperty("note", "hauteur = y du bloc trouvé ; minY-1 et bloc -1 si la colonne est vide");
            return Resultat.ok(o);
        }
    }
}
