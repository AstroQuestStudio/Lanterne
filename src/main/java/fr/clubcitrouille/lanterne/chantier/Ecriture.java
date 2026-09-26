package fr.clubcitrouille.lanterne.chantier;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Toute écriture du chantier : une source de positions ({@link Plan}), un état voulu par position,
 * et un relevé de ce qui était là avant, pour pouvoir le remettre.
 *
 * <h2>Les deux modes</h2>
 *
 * <ul>
 *   <li><b>rapide</b> (défaut) — {@code UPDATE_CLIENTS | UPDATE_SKIP_ALL_SIDEEFFECTS}, exactement le
 *       {@code /setblock … strict} de vanilla : aucune mise à jour de voisin, aucune forme recalculée,
 *       aucun {@code onPlace}, aucun drop. Ce qu'on pose est ce qui reste, à l'état près — l'eau ne
 *       coule pas, le sable ne tombe pas, un escalier ne se raccorde pas tout seul.</li>
 *   <li><b>voisins</b> — le {@code /setblock} ordinaire : pose sans effet de bloc-entité, puis
 *       {@code updateNeighboursOnBlockSet}. Les voisins réagissent (raccords, redstone, eau). Ce que
 *       les voisins changent EUX-MÊMES hors des positions écrites n'entre pas au journal.</li>
 * </ul>
 *
 * <p>Dans les deux cas le contenu d'un coffre remplacé ne tombe pas au sol : il est dans le journal
 * d'annulation, et c'est l'annulation qui le rend.
 */
final class Ecriture extends Travail {
    enum Mode {
        RAPIDE(Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS),
        VOISINS(Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);

        final int drapeaux;

        Mode(int drapeaux) {
            this.drapeaux = drapeaux;
        }

        static Mode lire(JsonObject corps) {
            String m = Json.texte(corps, "mode", "rapide");
            return switch (m) {
                case "rapide" -> RAPIDE;
                case "voisins" -> VOISINS;
                default -> throw new Refus(400, "mode inconnu « " + m + " » (rapide | voisins)");
            };
        }
    }

    /** D'où viennent les positions, et ce qu'on veut à chacune. */
    interface Plan {
        /** Phase préalable (le clonage y lit sa source). Vrai quand le plan est prêt. */
        default boolean preparer(Ecriture e, long echeance) {
            return true;
        }

        Parcours parcours();

        /** L'état voulu ici, ou {@code null} pour ne pas y toucher. */
        Etats.@Nullable Voulu voulu(BlockState actuel, BlockPos pos, int rang);

        /** Enveloppe des positions écrites (garde des joueurs, journal). */
        Boite zone();

        JsonObject details();
    }

    private final Plan plan;
    private final Mode mode;
    private final boolean seulementAir;
    private final int rayonJoueurs;
    private final @Nullable String annule;
    private final JsonObject requete;

    private boolean prepare;
    private Parcours parcours;
    private final Releve releve = new Releve();
    private long chunkCourant;
    private LevelChunk chunk;
    int parcourus;
    int modifies;
    int dejaEnPlace;
    int ecartes;

    Ecriture(String nature, ServerLevel level, Plan plan, JsonObject corps, @Nullable String annule) {
        super(nature, level);
        this.plan = plan;
        this.mode = Mode.lire(corps);
        this.seulementAir = Json.bool(corps, "seulement_air", false);
        this.rayonJoueurs = Json.entier(corps, "rayon_joueurs", Chantier.RAYON_JOUEURS.get(), 0, 4096);
        this.annule = annule;
        this.requete = new JsonObject();
        requete.addProperty("mode", mode.name().toLowerCase());
        requete.addProperty("seulement_air", seulementAir);
    }

    @Override
    void demarrer() {
        if (rayonJoueurs <= 0) {
            return;
        }
        Boite z = plan.zone();
        double r2 = (double) rayonJoueurs * rayonJoueurs;
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && z.distance2(p.getX(), p.getY(), p.getZ()) < r2) {
                throw new Refus(409, "joueur « " + p.getGameProfile().name() + " » à moins de " + rayonJoueurs
                        + " blocs de la zone : écriture refusée (rayon_joueurs)");
            }
        }
    }

    @Override
    boolean avancer(long echeance) {
        if (!prepare) {
            if (!plan.preparer(this, echeance)) {
                return false;
            }
            prepare = true;
            parcours = plan.parcours();
        }
        Parcours p = parcours;
        chunkCourant = Long.MIN_VALUE; // relire (et renouveler le ticket) à chaque tick
        int k = 0;
        while (!p.fini()) {
            if ((++k & 31) == 0 && System.nanoTime() >= echeance) {
                return false;
            }
            long ch = p.chunk();
            if (ch != chunkCourant) {
                // Rayon 1 : le chunk ET ses huit voisins. Une écriture touche aux voisins (lumière,
                // anti-xray, formes, comparateurs) ; un voisin absent serait chargé de force, sur le
                // thread serveur, au milieu du tick. Voir Etats.charger pour la mesure.
                chunk = pret(level, p, 1);
                if (chunk == null) {
                    return false;
                }
                chunkCourant = ch;
            }
            BlockPos.MutableBlockPos pos = p.courant();
            BlockState actuel = chunk.getBlockState(pos);
            parcourus++;
            Etats.Voulu v = plan.voulu(actuel, pos, p.rang());
            if (v == null || (seulementAir && !actuel.isAir())) {
                ecartes++;
                p.avancer();
                continue;
            }
            if (actuel == v.etat() && v.nbt() == null) {
                dejaEnPlace++;
                p.avancer();
                continue;
            }
            BlockPos fixe = pos.immutable();
            releve.ajouter(fixe, actuel, Etats.sauver(level, fixe, actuel));
            if (actuel != v.etat()) {
                level.setBlock(fixe, v.etat(), mode.drapeaux);
                if (mode == Mode.VOISINS) {
                    level.updateNeighboursOnBlockSet(fixe, actuel);
                }
            }
            if (v.nbt() != null) {
                Etats.charger(level, fixe, v.nbt(), mode == Mode.VOISINS);
            }
            modifies++;
            p.avancer();
        }
        return true;
    }

    @Override
    JsonObject avancement() {
        JsonObject o = new JsonObject();
        o.addProperty("parcourus", parcourus);
        o.addProperty("modifies", modifies);
        o.addProperty("prepare", prepare);
        return o;
    }

    @Override
    Resultat conclure() throws Exception {
        JsonObject meta = new JsonObject();
        meta.addProperty("id", id);
        meta.addProperty("nature", nature);
        meta.addProperty("dim", level.dimension().identifier().toString());
        meta.add("zone", plan.zone().json());
        meta.add("requete", requete);
        meta.add("details", plan.details());
        if (annule != null) {
            meta.addProperty("annule", annule);
        }
        meta.addProperty("modifies", modifies);
        if (interrompu != null) {
            meta.addProperty("interrompu", interrompu);
        }
        if (modifies > 0) {
            net.minecraft.nbt.CompoundTag m = new net.minecraft.nbt.CompoundTag();
            m.putString("id", id);
            m.putString("nature", nature);
            m.putString("dim", level.dimension().identifier().toString());
            m.putString("json", meta.toString());
            Journal.ecrireAnnulation(id, releve, m);
        }
        Journal.tracer(meta.deepCopy());
        JsonObject o = mesures();
        o.addProperty("operation", id);
        o.addProperty("parcourus", parcourus);
        o.addProperty("modifies", modifies);
        o.addProperty("deja_en_place", dejaEnPlace);
        o.addProperty("ecartes", ecartes);
        o.addProperty("annulable", modifies > 0);
        o.add("zone", plan.zone().json());
        o.add("details", plan.details());
        if (interrompu != null) {
            o.addProperty("interrompu", interrompu);
        }
        if (annule != null) {
            o.addProperty("annule", annule);
        }
        return Resultat.ok(o);
    }

    // ------------------------------------------------------------------------------------------------
    // Les plans.

    /** Une liste explicite : palette + (x, y, z, indice) à plat. */
    static Plan liste(JsonObject corps, long max) {
        JsonArray pal = tableau(corps, "palette");
        Etats.Voulu[] palette = new Etats.Voulu[pal.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = Etats.lire(pal.get(i).getAsString());
        }
        JsonArray blocs = tableau(corps, "blocs");
        if (blocs.size() % 4 != 0) {
            throw new Refus(400, "« blocs » : x, y, z, indice à plat — longueur multiple de 4 attendue");
        }
        int n = blocs.size() / 4;
        if (n > max) {
            throw new Refus(413, n + " blocs, au-delà de la limite de " + max + " (blocs_max)");
        }
        long[] pos = new long[n];
        int[] idx = new int[n];
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int x = blocs.get(4 * i).getAsInt();
            int y = blocs.get(4 * i + 1).getAsInt();
            int z = blocs.get(4 * i + 2).getAsInt();
            int j = blocs.get(4 * i + 3).getAsInt();
            if (j < 0 || j >= palette.length) {
                throw new Refus(400, "indice de palette " + j + " hors bornes (bloc n°" + i + ")");
            }
            pos[i] = BlockPos.asLong(x, y, z);
            idx[i] = j;
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
            x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
        }
        Boite zone = n == 0 ? Boite.de(0, 0, 0, 0, 0, 0) : Boite.de(x0, y0, z0, x1, y1, z1);
        Releve.Liste liste = Releve.Liste.grouper(pos, n);
        return new Plan() {
            @Override
            public Parcours parcours() {
                return liste;
            }

            @Override
            public Etats.Voulu voulu(BlockState actuel, BlockPos p, int rang) {
                return palette[idx[rang]];
            }

            @Override
            public Boite zone() {
                return zone;
            }

            @Override
            public JsonObject details() {
                JsonObject o = new JsonObject();
                o.addProperty("blocs", n);
                o.addProperty("palette", palette.length);
                return o;
            }
        };
    }

    static void verifierZone(Plan plan, ServerLevel level) {
        plan.zone().verifier(level, Long.MAX_VALUE);
    }

    /** Remplir une boîte d'un état. */
    static Plan remplir(Boite b, Etats.Voulu bloc) {
        return new PlanBoite(b, "remplir", (actuel, pos) -> bloc);
    }

    /** Remplacer, dans une boîte, ce qui correspond au motif. */
    static Plan remplacer(Boite b, String motif, String vers, boolean garder) {
        Predicate<BlockState> m = Etats.motif(motif);
        BlockStateParser.BlockResult cible = Etats.lireAvecProprietes(vers);
        Map<Property<?>, Comparable<?>> fixees = cible.properties();
        Etats.Voulu fixe = new Etats.Voulu(cible.blockState(), cible.nbt());
        IdentityHashMap<BlockState, Etats.Voulu> cache = new IdentityHashMap<>();
        return new PlanBoite(b, "remplacer", (actuel, pos) -> {
            if (!m.test(actuel)) {
                return null;
            }
            if (!garder) {
                return fixe;
            }
            return cache.computeIfAbsent(actuel,
                    a -> new Etats.Voulu(Etats.garder(a, cible.blockState(), fixees), cible.nbt()));
        });
    }

    /** Vider l'intérieur d'une boîte, en gardant une coque d'épaisseur donnée. */
    static Plan creuser(Boite b, int e, Etats.Voulu remplissage) {
        if (b.sx() <= 2 * e || b.sy() <= 2 * e || b.sz() <= 2 * e) {
            throw new Refus(400, "boîte trop mince pour une coque d'épaisseur " + e + " : rien à creuser");
        }
        Boite dedans = Boite.de(b.minX() + e, b.minY() + e, b.minZ() + e, b.maxX() - e, b.maxY() - e, b.maxZ() - e);
        return new PlanBoite(dedans, "creuser", (actuel, pos) -> remplissage);
    }

    interface Regle {
        Etats.@Nullable Voulu voulu(BlockState actuel, BlockPos pos);
    }

    private record PlanBoite(Boite boite, String nom, Regle regle) implements Plan {
        @Override
        public Parcours parcours() {
            return boite.new Curseur();
        }

        @Override
        public Etats.Voulu voulu(BlockState actuel, BlockPos pos, int rang) {
            return regle.voulu(actuel, pos);
        }

        @Override
        public Boite zone() {
            return boite;
        }

        @Override
        public JsonObject details() {
            JsonObject o = new JsonObject();
            o.addProperty("plan", nom);
            o.addProperty("volume", boite.volume());
            return o;
        }
    }

    /** Remettre ce qu'un relevé a noté (annulation). */
    static Plan restaurer(Releve r, Boite zone, String id) {
        Releve.Liste liste = r.parcours();
        return new Plan() {
            @Override
            public Parcours parcours() {
                return liste;
            }

            @Override
            public Etats.Voulu voulu(BlockState actuel, BlockPos p, int rang) {
                CompoundTag nbt = r.nbt(rang);
                return new Etats.Voulu(r.etat(rang), nbt == null ? null : nbt.copy());
            }

            @Override
            public Boite zone() {
                return zone;
            }

            @Override
            public JsonObject details() {
                JsonObject o = new JsonObject();
                o.addProperty("restaure", id);
                o.addProperty("blocs", r.n);
                return o;
            }
        };
    }

    /**
     * Cloner une boîte, avec miroir puis rotation (dans cet ordre, comme les structures vanilla).
     * La source est lue en entier AVANT la première écriture : source et destination peuvent se
     * chevaucher sans que la copie ne se relise elle-même.
     */
    static final class Clonage implements Plan {
        private final ServerLevel source;
        private final Boite de;
        private final Boite vers;
        private final Mirror miroir;
        private final Rotation rotation;
        private final boolean copierAir;
        private final BlockState[] etats;
        private final Int2ObjectOpenHashMap<CompoundTag> nbt = new Int2ObjectOpenHashMap<>();
        private final IdentityHashMap<BlockState, BlockState> tournes = new IdentityHashMap<>();
        private Boite.Curseur lecture;

        Clonage(ServerLevel source, Boite de, int[] origine, Mirror miroir, Rotation rotation, boolean copierAir) {
            this.source = source;
            this.de = de;
            this.miroir = miroir;
            this.rotation = rotation;
            this.copierAir = copierAir;
            boolean quart = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
            int dx = quart ? de.sz() : de.sx();
            int dz = quart ? de.sx() : de.sz();
            this.vers = new Boite(origine[0], origine[1], origine[2],
                    origine[0] + dx - 1, origine[1] + de.sy() - 1, origine[2] + dz - 1);
            this.etats = new BlockState[(int) de.volume()];
            this.lecture = de.new Curseur();
        }

        @Override
        public boolean preparer(Ecriture e, long echeance) {
            Boite.Curseur c = lecture;
            long courantChunk = Long.MIN_VALUE;
            LevelChunk ch = null;
            int k = 0;
            while (!c.fini()) {
                if ((++k & 63) == 0 && System.nanoTime() >= echeance) {
                    return false;
                }
                if (c.chunk() != courantChunk) {
                    ch = e.pret(source, c);
                    if (ch == null) {
                        return false;
                    }
                    courantChunk = c.chunk();
                }
                BlockPos.MutableBlockPos p = c.courant();
                BlockState s = ch.getBlockState(p);
                int i = de.index(p.getX(), p.getY(), p.getZ());
                etats[i] = s;
                if (s.hasBlockEntity()) {
                    CompoundTag t = Etats.sauver(source, p, s);
                    if (t != null) {
                        nbt.put(i, t);
                    }
                }
                c.avancer();
            }
            lecture = null;
            return true;
        }

        @Override
        public Parcours parcours() {
            return vers.new Curseur();
        }

        @Override
        public Etats.Voulu voulu(BlockState actuel, BlockPos pos, int rang) {
            int u = pos.getX() - vers.minX();
            int w = pos.getZ() - vers.minZ();
            int sx = de.sx();
            int sz = de.sz();
            int xm;
            int zm;
            switch (rotation) {
                case CLOCKWISE_90 -> { xm = w; zm = sz - 1 - u; }
                case CLOCKWISE_180 -> { xm = sx - 1 - u; zm = sz - 1 - w; }
                case COUNTERCLOCKWISE_90 -> { xm = sx - 1 - w; zm = u; }
                default -> { xm = u; zm = w; }
            }
            int x = miroir == Mirror.FRONT_BACK ? sx - 1 - xm : xm;
            int z = miroir == Mirror.LEFT_RIGHT ? sz - 1 - zm : zm;
            int i = ((pos.getY() - vers.minY()) * sz + z) * sx + x;
            BlockState s = etats[i];
            if (!copierAir && s.isAir()) {
                return null;
            }
            BlockState t = tournes.computeIfAbsent(s, a -> transformer(a, miroir, rotation));
            CompoundTag donnees = nbt.get(i);
            return new Etats.Voulu(t, donnees == null ? null : donnees.copy());
        }

        @Override
        public Boite zone() {
            return vers;
        }

        @Override
        public JsonObject details() {
            JsonObject o = new JsonObject();
            o.add("source", de.json());
            o.addProperty("dim_source", source.dimension().identifier().toString());
            o.add("destination", vers.json());
            o.addProperty("miroir", miroir == Mirror.FRONT_BACK ? "x" : miroir == Mirror.LEFT_RIGHT ? "z" : "aucun");
            o.addProperty("rotation", switch (rotation) {
                case CLOCKWISE_90 -> 90;
                case CLOCKWISE_180 -> 180;
                case COUNTERCLOCKWISE_90 -> 270;
                default -> 0;
            });
            o.addProperty("copier_air", copierAir);
            return o;
        }
    }

    /**
     * Miroir puis rotation d'un état. Vanilla pour tout, SAUF les escaliers : {@code StairBlock.mirror}
     * ne permute gauche/droite que dans certains cas (FRONT_BACK face à l'axe X : les coins
     * {@code outer} oui, les {@code inner} non ; face perpendiculaire à l'axe du miroir : rien du
     * tout). Or un miroir renverse toujours la chiralité : tout coin gauche devient droit. Trouvé par
     * l'essai « miroir x + 180° = miroir z », que vanilla échoue et que ceci réussit.
     */
    static BlockState transformer(BlockState etat, Mirror miroir, Rotation rotation) {
        BlockState m;
        if (miroir != Mirror.NONE && etat.getBlock() instanceof StairBlock) {
            StairsShape forme = switch (etat.getValue(StairBlock.SHAPE)) {
                case INNER_LEFT -> StairsShape.INNER_RIGHT;
                case INNER_RIGHT -> StairsShape.INNER_LEFT;
                case OUTER_LEFT -> StairsShape.OUTER_RIGHT;
                case OUTER_RIGHT -> StairsShape.OUTER_LEFT;
                case STRAIGHT -> StairsShape.STRAIGHT;
            };
            m = etat.setValue(StairBlock.FACING, miroir.mirror(etat.getValue(StairBlock.FACING)))
                    .setValue(StairBlock.SHAPE, forme);
        } else {
            m = etat.mirror(miroir);
            if (m == etat && miroir != Mirror.NONE) {
                m = orienter(etat, miroir);
            }
        }
        return m.rotate(rotation);
    }

    /**
     * Un bloc orienté qui n'a pas surchargé {@code mirror} (l'enclume vanilla, et bien des blocs de
     * mods) reste tel quel dans un miroir : on retourne alors sa propriété {@code facing}, comme le
     * ferait n'importe quel {@code HorizontalDirectionalBlock}. Trouvé par /verifier/transformations.
     */
    @SuppressWarnings("unchecked")
    private static BlockState orienter(BlockState etat, Mirror miroir) {
        Property<?> p = etat.getBlock().getStateDefinition().getProperty("facing");
        if (p == null || p.getValueClass() != net.minecraft.core.Direction.class) {
            return etat;
        }
        Property<net.minecraft.core.Direction> f = (Property<net.minecraft.core.Direction>) p;
        net.minecraft.core.Direction d = miroir.mirror(etat.getValue(f));
        return f.getPossibleValues().contains(d) ? etat.setValue(f, d) : etat;
    }

    static Mirror miroir(String m) {
        return switch (m) {
            case "aucun", "" -> Mirror.NONE;
            case "x" -> Mirror.FRONT_BACK;
            case "z" -> Mirror.LEFT_RIGHT;
            default -> throw new Refus(400, "miroir inconnu « " + m + " » (aucun | x | z)");
        };
    }

    static Rotation rotation(int r) {
        return switch (((r % 360) + 360) % 360) {
            case 0 -> Rotation.NONE;
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new Refus(400, "rotation " + r + " : 0, 90, 180 ou 270 (sens horaire vu du dessus)");
        };
    }

    static JsonArray tableau(JsonObject corps, String cle) {
        JsonElement e = corps.get(cle);
        if (e == null || !e.isJsonArray()) {
            throw new Refus(400, "« " + cle + " » : tableau attendu");
        }
        return e.getAsJsonArray();
    }

    static Etats.Voulu air() {
        return new Etats.Voulu(Blocks.AIR.defaultBlockState(), null);
    }
}
