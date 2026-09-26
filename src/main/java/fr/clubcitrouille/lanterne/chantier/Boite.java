package fr.clubcitrouille.lanterne.chantier;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Une boîte de blocs, bornes incluses, dans l'ordre que tout le chantier partage.
 *
 * <p><b>L'ordre des blocs</b> (lecture, clichés) est y, puis z, puis x : l'index d'un bloc vaut
 * {@code ((y - minY) * sz + (z - minZ)) * sx + (x - minX)}. C'est l'ordre des schémas Sponge, celui
 * qu'un script Python décode sans y penser.
 *
 * <p><b>L'ordre de parcours</b> (écriture, rendu) est autre : colonne de chunk par colonne de chunk,
 * pour qu'un chunk ne soit chargé qu'une fois et relâché aussitôt. {@link Curseur} le suit.
 */
record Boite(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    static Boite de(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Boite(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** Lit {@code "de": [x,y,z], "a": [x,y,z]} ; refuse une boîte hors du monde ou trop grosse. */
    static Boite lire(JsonObject corps, Level level, long max) {
        int[] de = triplet(corps, "de");
        int[] a = triplet(corps, "a");
        Boite b = de(de[0], de[1], de[2], a[0], a[1], a[2]);
        b.verifier(level, max);
        return b;
    }

    static int[] triplet(JsonObject corps, String cle) {
        JsonElement e = corps.get(cle);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
            throw new Refus(400, "« " + cle + " » attendu sous la forme [x, y, z]");
        }
        JsonArray t = e.getAsJsonArray();
        return new int[] {t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt()};
    }

    void verifier(Level level, long max) {
        if (minY < level.getMinY() || maxY > level.getMaxY()) {
            throw new Refus(400, "y hors du monde (" + level.getMinY() + " à " + level.getMaxY() + ")");
        }
        if (Math.abs((long) minX) > 29_999_000L || Math.abs((long) maxX) > 29_999_000L
                || Math.abs((long) minZ) > 29_999_000L || Math.abs((long) maxZ) > 29_999_000L) {
            throw new Refus(400, "x/z hors de la bordure du monde");
        }
        if (volume() > max) {
            throw new Refus(413, "boîte de " + volume() + " blocs, au-delà de la limite de " + max
                    + " (réglage blocs_max) — découpe-la");
        }
    }

    int sx() { return maxX - minX + 1; }
    int sy() { return maxY - minY + 1; }
    int sz() { return maxZ - minZ + 1; }

    long volume() {
        return (long) sx() * sy() * sz();
    }

    boolean contient(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    int index(int x, int y, int z) {
        return ((y - minY) * sz() + (z - minZ)) * sx() + (x - minX);
    }

    /** Distance horizontale+verticale au carré d'un point à la boîte (0 dedans). */
    double distance2(double x, double y, double z) {
        double dx = Math.max(0, Math.max(minX - x, x - (maxX + 1)));
        double dy = Math.max(0, Math.max(minY - y, y - (maxY + 1)));
        double dz = Math.max(0, Math.max(minZ - z, z - (maxZ + 1)));
        return dx * dx + dy * dy + dz * dz;
    }

    /** Les colonnes de chunk que la boîte touche, dans l'ordre de parcours. */
    List<Long> chunks() {
        List<Long> out = new ArrayList<>();
        for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                out.add(ChunkPos.pack(cx, cz));
            }
        }
        return out;
    }

    JsonObject json() {
        JsonObject o = new JsonObject();
        o.add("de", Json.xyz(minX, minY, minZ));
        o.add("a", Json.xyz(maxX, maxY, maxZ));
        JsonArray t = new JsonArray();
        t.add(sx());
        t.add(sy());
        t.add(sz());
        o.add("taille", t);
        return o;
    }

    @Override
    public String toString() {
        return "[" + minX + "," + minY + "," + minZ + " → " + maxX + "," + maxY + "," + maxZ + "]";
    }

    /**
     * Parcours de la boîte chunk par chunk, reprenable d'un tick à l'autre : dans une colonne de
     * chunk, y puis z puis x. {@link #chunk()} dit à quel chunk appartient la position courante,
     * pour que l'appelant vérifie qu'il est chargé AVANT d'y toucher.
     */
    final class Curseur implements Parcours {
        private final List<Long> chunks = chunks();
        private int c = -1;
        private int x0, x1, z0, z1;
        private int x, y, z;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private boolean fini;

        Curseur() {
            colonneSuivante();
        }

        private void colonneSuivante() {
            c++;
            if (c >= chunks.size()) {
                fini = true;
                return;
            }
            long k = chunks.get(c);
            int cx = ChunkPos.getX(k);
            int cz = ChunkPos.getZ(k);
            x0 = Math.max(minX, cx << 4);
            x1 = Math.min(maxX, (cx << 4) + 15);
            z0 = Math.max(minZ, cz << 4);
            z1 = Math.min(maxZ, (cz << 4) + 15);
            x = x0;
            y = minY;
            z = z0;
        }

        public boolean fini() {
            return fini;
        }

        public long chunk() {
            return chunks.get(c);
        }

        /** Les chunks suivants, pour les demander d'avance. */
        public List<Long> aVenir(int n) {
            return chunks.subList(Math.min(chunks.size(), c + 1), Math.min(chunks.size(), c + 1 + n));
        }

        /** Position courante (valide tant que {@link #fini()} est faux). */
        public BlockPos.MutableBlockPos courant() {
            return pos.set(x, y, z);
        }

        public void avancer() {
            if (++x > x1) {
                x = x0;
                if (++z > z1) {
                    z = z0;
                    if (++y > maxY) {
                        colonneSuivante();
                    }
                }
            }
        }
    }
}
