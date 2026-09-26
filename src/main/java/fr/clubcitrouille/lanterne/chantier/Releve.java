package fr.clubcitrouille.lanterne.chantier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Un relevé : des positions, l'état de chacune, et les données de ses blocs-entités.
 *
 * <p>C'est à la fois le journal d'annulation (l'état AVANT chaque écriture) et le cliché d'une boîte
 * (l'état à un instant donné) : même forme, même fichier, même relecture — comparer un cliché ou
 * vérifier une annulation, c'est la même opération.
 *
 * <p>Sur disque : un NBT compressé, palette en chaînes de commande (pas en numéros de registre, qui
 * changent d'une liste de mods à l'autre), positions en {@code long} de {@link BlockPos#asLong}.
 */
final class Releve {
    final Etats.Palette palette = new Etats.Palette();
    long[] pos = new long[1024];
    int[] idx = new int[1024];
    int n;
    final Int2ObjectOpenHashMap<CompoundTag> nbt = new Int2ObjectOpenHashMap<>();

    /** Palette relue d'un fichier (les états n'y sont reconstruits qu'à la lecture). */
    private List<BlockState> paletteLue;

    void ajouter(BlockPos p, BlockState etat, @Nullable CompoundTag donnees) {
        if (n == pos.length) {
            int t = n * 2;
            pos = Arrays.copyOf(pos, t);
            idx = Arrays.copyOf(idx, t);
        }
        pos[n] = p.asLong();
        idx[n] = palette.id(etat);
        if (donnees != null) {
            nbt.put(n, donnees);
        }
        n++;
    }

    BlockState etat(int i) {
        return paletteLue != null ? paletteLue.get(idx[i]) : palette.etats.get(idx[i]);
    }

    @Nullable CompoundTag nbt(int i) {
        return nbt.get(i);
    }

    CompoundTag versNbt(CompoundTag meta) {
        CompoundTag t = new CompoundTag();
        t.put("meta", meta);
        ListTag pal = new ListTag();
        List<BlockState> etats = paletteLue != null ? paletteLue : palette.etats;
        for (BlockState e : etats) {
            pal.add(StringTag.valueOf(Etats.texte(e)));
        }
        t.put("palette", pal);
        t.put("pos", new LongArrayTag(Arrays.copyOf(pos, n)));
        t.put("idx", new IntArrayTag(Arrays.copyOf(idx, n)));
        ListTag be = new ListTag();
        for (var e : nbt.int2ObjectEntrySet()) {
            CompoundTag c = new CompoundTag();
            c.putInt("i", e.getIntKey());
            c.put("nbt", e.getValue());
            be.add(c);
        }
        t.put("be", be);
        return t;
    }

    static Releve depuisNbt(CompoundTag t) {
        Releve r = new Releve();
        ListTag pal = t.getListOrEmpty("palette");
        r.paletteLue = new ArrayList<>(pal.size());
        for (int i = 0; i < pal.size(); i++) {
            r.paletteLue.add(Etats.lire(pal.getStringOr(i, "minecraft:air")).etat());
        }
        r.pos = t.getLongArray("pos").orElse(new long[0]);
        r.idx = t.getIntArray("idx").orElse(new int[0]);
        r.n = Math.min(r.pos.length, r.idx.length);
        ListTag be = t.getListOrEmpty("be");
        for (int i = 0; i < be.size(); i++) {
            CompoundTag c = be.getCompoundOrEmpty(i);
            r.nbt.put(c.getIntOr("i", -1), c.getCompoundOrEmpty("nbt"));
        }
        return r;
    }

    /**
     * Les rangs du relevé regroupés chunk par chunk, dans l'ordre de première apparition : c'est
     * l'ordre de parcours d'une restauration ou d'une comparaison. Stable à l'intérieur d'un chunk,
     * pour qu'une porte posée bas-puis-haut soit restaurée bas-puis-haut.
     */
    Liste parcours() {
        return Liste.grouper(pos, n);
    }

    /** Un parcours de positions éparses, regroupées par chunk. {@link #rang()} renvoie au rang d'origine. */
    static final class Liste implements Parcours {
        private final long[] pos;
        private final int[] ordre;
        private final long[] chunks;
        private final int[] debuts;
        private int i;
        private int c;
        private final BlockPos.MutableBlockPos courant = new BlockPos.MutableBlockPos();

        private Liste(long[] pos, int[] ordre, long[] chunks, int[] debuts) {
            this.pos = pos;
            this.ordre = ordre;
            this.chunks = chunks;
            this.debuts = debuts;
        }

        static Liste grouper(long[] pos, int n) {
            Long2IntLinkedOpenHashMap compte = new Long2IntLinkedOpenHashMap();
            for (int k = 0; k < n; k++) {
                compte.addTo(ChunkPos.pack(BlockPos.of(pos[k])), 1);
            }
            long[] chunks = compte.keySet().toLongArray();
            int[] debuts = new int[chunks.length + 1];
            Long2IntLinkedOpenHashMap place = new Long2IntLinkedOpenHashMap();
            int s = 0;
            for (int c = 0; c < chunks.length; c++) {
                debuts[c] = s;
                place.put(chunks[c], s);
                s += compte.get(chunks[c]);
            }
            debuts[chunks.length] = s;
            int[] ordre = new int[n];
            for (int k = 0; k < n; k++) {
                long ch = ChunkPos.pack(BlockPos.of(pos[k]));
                int p = place.get(ch);
                ordre[p] = k;
                place.put(ch, p + 1);
            }
            return new Liste(pos, ordre, chunks, debuts);
        }

        int taille() {
            return ordre.length;
        }

        @Override
        public boolean fini() {
            return i >= ordre.length;
        }

        @Override
        public long chunk() {
            while (i >= debuts[c + 1]) {
                c++;
            }
            return chunks[c];
        }

        @Override
        public List<Long> aVenir(int k) {
            chunk();
            List<Long> out = new ArrayList<>(k);
            for (int j = c + 1; j < chunks.length && out.size() < k; j++) {
                out.add(chunks[j]);
            }
            return out;
        }

        @Override
        public BlockPos.MutableBlockPos courant() {
            return courant.set(pos[ordre[i]]);
        }

        @Override
        public int rang() {
            return ordre[i];
        }

        @Override
        public void avancer() {
            i++;
        }
    }
}
