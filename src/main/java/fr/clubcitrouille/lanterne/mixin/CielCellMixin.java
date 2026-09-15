package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;

import fr.clubcitrouille.lanterne.core.Ciel;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'examen d'une cellule, posé là où toute la matière est déjà réunie.
 *
 * <h2>Pourquoi le verdict se prend ici et pas dans le générateur</h2>
 *
 * <p>Tout ce qu'il faut pour répondre — les 128 densités, l'aquifère, les coordonnées du coin de la
 * cellule, sa largeur et sa hauteur — est privé à {@code NoiseChunk}. Le prendre depuis le
 * générateur demanderait autant d'accesseurs qu'il y a de champs ; un {@code @Shadow} posé sur la
 * classe qui les déclare n'en demande aucun.
 *
 * <p>Et le drapeau doit de toute façon vivre ici : la génération de terrain tourne sur un pool de
 * fils, le générateur est partagé, le {@code NoiseChunk} ne l'est pas. Voir {@code core.Ciel}.
 *
 * <h2>Ce que cet examen coûte, et à qui</h2>
 *
 * <p>Pour l'écrasante majorité des cellules — tout ce qui est au niveau du sol ou en dessous — il
 * coûte <b>une comparaison d'entiers</b> : le plancher de la cellule contre
 * {@code skipSamplingAboveY}. Le balayage des 128 densités n'a lieu que pour ce qui est déjà
 * au-dessus de la cote d'échantillonnage, et il s'arrête à la première position qui le contredit.
 *
 * <p>Le balayage monte du plancher vers le plafond, à l'inverse de {@code doFill} qui descend.
 * C'est voulu : dans une cellule qui n'est <em>pas</em> du ciel, c'est le bas qui porte la matière,
 * donc c'est en commençant par le bas qu'on abandonne le plus vite.
 */
@Mixin(NoiseChunk.class)
public abstract class CielCellMixin implements Ciel.Cell {
    @Shadow
    @Final
    private int cellWidth;

    @Shadow
    @Final
    private int cellHeight;

    @Shadow
    private int cellStartBlockX;

    @Shadow
    private int cellStartBlockY;

    @Shadow
    private int cellStartBlockZ;

    @Shadow
    private int inCellX;

    @Shadow
    private int inCellY;

    @Shadow
    private int inCellZ;

    @Shadow
    public abstract Aquifer aquifer();

    /**
     * La densité de la position courante.
     *
     * <p>Contrairement à ce que son nom laisse croire, cette méthode <b>n'interpole rien</b> :
     * {@code fullNoiseDensity} est un {@code cacheAllInCell}, et {@code selectCellYZ} vient d'y
     * ranger les 128 densités de la cellule. C'est une lecture de tableau, et c'est le nombre exact
     * que {@code getInterpolatedState} passera à {@code Aquifer.computeSubstance}.
     */
    @Shadow
    protected abstract double getInterpolatedDensity();

    /** L'état qu'aurait rendu vanilla. Employée par le seul mode d'audit. */
    @Shadow
    protected abstract @Nullable BlockState getInterpolatedState();

    /** La cellule sélectionnée est-elle de l'air de part en part ? */
    @Unique
    private boolean lanterne$sky;

    @Override
    public boolean lanterne$skyCell() {
        return this.lanterne$sky;
    }

    @Override
    public void lanterne$examineCell() {
        if (!Settings.ciel()) {
            this.lanterne$sky = false;
            return;
        }
        boolean empty = Ciel.broken() || this.lanterne$emptySky();
        this.lanterne$sky = empty;
        Ciel.examine(empty);
        if (empty && Ciel.auditing()) {
            this.lanterne$audit();
        }
    }

    /**
     * Les deux conditions, tenues sur les 128 positions de la cellule.
     *
     * <p>La densité d'abord : {@code > 0.0} est <em>exactement</em> le test que
     * {@code computeSubstance} fait pour rendre {@code null}, c'est-à-dire du solide. On le recopie
     * au sens strict, NaN compris — {@code NaN > 0.0} est faux des deux côtés.
     *
     * <p>Le fluide ensuite, et interrogé à chaque position plutôt qu'une fois par altitude. C'est
     * seize appels de trop par tranche, et c'est le prix de n'avoir <b>aucune</b> hypothèse sur le
     * sélecteur : celui de vanilla ne dépend que de {@code y}, mais l'interface lui donne les trois
     * coordonnées et rien n'y oblige. L'appel est une lambda qui rend un objet déjà construit ; la
     * comparaison finale est une comparaison de références, comme celle de {@code doFill}.
     */
    @Unique
    private boolean lanterne$emptySky() {
        if (!(this.aquifer() instanceof Aquifer.NoiseBasedAquifer noiseAquifer)) {
            // Aquifères désactivés (Nether, End) ou implantation tierce : on ne sait pas prouver
            // que computeSubstance rendra de l'air, donc on ne touche à rien.
            return false;
        }
        CielAquiferAccessor probe = (CielAquiferAccessor) noiseAquifer;
        if (this.cellStartBlockY <= probe.lanterne$skipSamplingAboveY()) {
            return false;
        }

        Aquifer.FluidPicker picker = probe.lanterne$globalFluidPicker();
        int savedX = this.inCellX;
        int savedY = this.inCellY;
        int savedZ = this.inCellZ;
        try {
            for (int y = 0; y < this.cellHeight; y++) {
                this.inCellY = y;
                int blockY = this.cellStartBlockY + y;
                for (int x = 0; x < this.cellWidth; x++) {
                    this.inCellX = x;
                    int blockX = this.cellStartBlockX + x;
                    for (int z = 0; z < this.cellWidth; z++) {
                        this.inCellZ = z;
                        if (this.getInterpolatedDensity() > 0.0) {
                            return false;
                        }
                        int blockZ = this.cellStartBlockZ + z;
                        if (picker.computeFluid(blockX, blockY, blockZ).at(blockY) != Ciel.AIR) {
                            return false;
                        }
                    }
                }
            }
        } finally {
            // Après nous, l'état du NoiseChunk est celui que selectCellYZ avait laissé.
            this.inCellX = savedX;
            this.inCellY = savedY;
            this.inCellZ = savedZ;
        }
        return true;
    }

    /**
     * Le contrôle : poser à vanilla la question à laquelle on vient de répondre à sa place.
     *
     * <p>On appelle réellement {@code getInterpolatedState} aux 128 positions et l'on vérifie que
     * chacune rend l'air. Ce n'est pas une comparaison de terrains sur un échantillon : c'est la
     * question exacte, aux positions exactes, et un seul écart suffit à condamner le module.
     *
     * <p>Ce mode refait donc tout le travail que le module épargne — il n'est là que pour prouver.
     * Voir {@code core.Ciel}.
     */
    @Unique
    private void lanterne$audit() {
        int savedX = this.inCellX;
        int savedY = this.inCellY;
        int savedZ = this.inCellZ;
        try {
            for (int y = 0; y < this.cellHeight; y++) {
                this.inCellY = y;
                for (int x = 0; x < this.cellWidth; x++) {
                    this.inCellX = x;
                    for (int z = 0; z < this.cellWidth; z++) {
                        this.inCellZ = z;
                        Ciel.check(this.getInterpolatedState() == Ciel.AIR);
                    }
                }
            }
        } finally {
            this.inCellX = savedX;
            this.inCellY = savedY;
            this.inCellZ = savedZ;
        }
    }
}
