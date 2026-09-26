package fr.clubcitrouille.lanterne.chantier;

import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Les clichés : l'état complet d'une boîte (blocs et blocs-entités) mis de côté sous un nom, et la
 * comparaison du monde actuel à un cliché — ou à un journal d'annulation, qui a la même forme.
 */
final class Cliche {
    private Cliche() {}

    /** Prendre un cliché : un relevé de toute la boîte, air compris. */
    static final class Prise extends Lectures.Blocs {
        private final String nom;
        private final Releve releve = new Releve();

        Prise(ServerLevel level, Boite boite, String nom) {
            super("cliche", level, boite, false, false, false);
            this.nom = Journal.nomSur(nom);
        }

        @Override
        void lu(BlockPos.MutableBlockPos p, BlockState s) {
            releve.ajouter(p, s, Etats.sauver(level, p, s));
        }

        @Override
        Resultat conclure() throws Exception {
            CompoundTag m = new CompoundTag();
            m.putString("dim", level.dimension().identifier().toString());
            m.putIntArray("boite", new int[] {boite.minX(), boite.minY(), boite.minZ(), boite.maxX(), boite.maxY(), boite.maxZ()});
            Journal.ecrireCliche(nom, releve, m);
            JsonObject o = mesures();
            o.addProperty("cliche", nom);
            o.add("boite", boite.json());
            o.addProperty("blocs", releve.n);
            o.addProperty("bloc_entites", releve.nbt.size());
            o.addProperty("palette", releve.palette.etats.size());
            return Resultat.ok(o);
        }
    }

    /**
     * Comparer le monde à un relevé : chaque position notée, son état et (si demandé) les données de
     * son bloc-entité. Zéro différence après une annulation, c'est la preuve qu'elle était exacte.
     */
    static final class Comparaison extends Travail {
        private final Releve releve;
        private final String reference;
        private final boolean avecNbt;
        private final int limite;
        private final Releve.Liste parcours;
        private long chunkCourant;
        private LevelChunk chunk;
        private int compares;
        private int etatsDifferents;
        private int nbtDifferents;
        private final JsonArray exemples = new JsonArray();

        Comparaison(ServerLevel level, Releve releve, String reference, boolean avecNbt, int limite) {
            super("diff", level);
            this.releve = releve;
            this.reference = reference;
            this.avecNbt = avecNbt;
            this.limite = limite;
            this.parcours = releve.parcours();
        }

        @Override
        boolean avancer(long echeance) {
            Releve.Liste p = parcours;
            chunkCourant = Long.MIN_VALUE;
            int k = 0;
            while (!p.fini()) {
                if ((++k & 63) == 0 && System.nanoTime() >= echeance) {
                    return false;
                }
                if (p.chunk() != chunkCourant) {
                    chunk = pret(level, p);
                    if (chunk == null) {
                        return false;
                    }
                    chunkCourant = p.chunk();
                }
                BlockPos.MutableBlockPos pos = p.courant();
                int r = p.rang();
                BlockState attendu = releve.etat(r);
                BlockState actuel = chunk.getBlockState(pos);
                compares++;
                if (actuel != attendu) {
                    etatsDifferents++;
                    noter(pos, attendu, actuel, null);
                } else if (avecNbt && actuel.hasBlockEntity()) {
                    CompoundTag a = releve.nbt(r);
                    CompoundTag b = Etats.sauver(level, pos, actuel);
                    if (!Objects.equals(a, b)) {
                        nbtDifferents++;
                        noter(pos, attendu, actuel, "donnees du bloc-entite");
                    }
                }
                p.avancer();
            }
            return true;
        }

        private void noter(BlockPos pos, BlockState attendu, BlockState actuel, String quoi) {
            if (exemples.size() >= limite) {
                return;
            }
            JsonObject o = new JsonObject();
            o.add("p", Json.xyz(pos));
            o.addProperty("reference", Etats.texte(attendu));
            o.addProperty("actuel", Etats.texte(actuel));
            if (quoi != null) {
                o.addProperty("ecart", quoi);
            }
            exemples.add(o);
        }

        @Override
        Resultat conclure() {
            JsonObject o = mesures();
            o.addProperty("reference", reference);
            o.addProperty("compares", compares);
            o.addProperty("differences", etatsDifferents + nbtDifferents);
            o.addProperty("etats_differents", etatsDifferents);
            o.addProperty("nbt_differents", nbtDifferents);
            o.addProperty("identique", etatsDifferents + nbtDifferents == 0);
            o.add("exemples", exemples);
            return Resultat.ok(o);
        }
    }
}
