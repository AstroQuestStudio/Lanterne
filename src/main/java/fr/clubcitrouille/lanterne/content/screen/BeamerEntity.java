package fr.clubcitrouille.lanterne.content.screen;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

/**
 * Ce qu'un projecteur retient : la même séance qu'un écran, plus une visée.
 *
 * <h2>Deux nombres, et pourquoi pas trois</h2>
 *
 * <p>La portée dit à quelle distance l'image se pose ; l'envergure dit quelle largeur elle y fait.
 * La hauteur ne se règle pas — elle se déduit de la forme réelle de la vidéo, parce qu'un projecteur
 * qui déformerait l'image à la demande serait un projecteur cassé. Le cadrage se règle, lui, par
 * {@link Feed.Shape}, qui est le bon endroit pour cela et le même pour les deux blocs.
 *
 * <p>Il n'y a pas non plus de réglage d'inclinaison. Le bloc se pose dans une des six directions,
 * comme un piston ou un observateur, et vise droit devant. Un angle libre aurait demandé deux
 * curseurs, une prévisualisation et une convention de repère ; le joueur obtient exactement le même
 * résultat en posant le bloc où il veut, ce qu'il sait déjà faire.
 *
 * <h2>Sans ticker, comme l'écran</h2>
 *
 * <p>Le cône, la surface visée et la position de lecture se calculent au moment de dessiner, à partir
 * de l'état et de l'horloge. Rien de tout cela ne demande à être avancé par le serveur. Voir
 * {@link PanelEntity} pour l'argument complet, qui est le même mot pour mot.
 */
public class BeamerEntity extends BlockEntity implements Stage {
    /** Portée par défaut, en blocs. De quoi traverser une pièce sans réglage. */
    private static final int REACH = 8;

    /** Envergure par défaut, en blocs. Un écran de salon. */
    private static final int SPAN = 5;

    private int reach = REACH;
    private int span = SPAN;

    private Feed feed = Feed.BLANK;
    private Clock clock = Clock.STOPPED;

    private UUID owner;
    private String ownerName = "";

    public BeamerEntity(BlockPos pos, BlockState state) {
        super(Screens.BEAMER_ENTITY.get(), pos, state);
    }

    public int reach() {
        return this.reach;
    }

    public int span() {
        return this.span;
    }

    public Direction facing() {
        return getBlockState().getValue(Beamer.FACING);
    }

    /**
     * Change la visée, bornée par ce que l'administrateur autorise.
     *
     * <p>Le bornage est ici et non chez l'appelant : un projecteur ne doit pas pouvoir dépasser sa
     * portée maximale par un chemin d'appel qu'on aurait oublié de vérifier. Le seul endroit qui
     * écrit ces deux nombres est donc aussi le seul qui les vérifie.
     */
    public void aim(int wantedReach, int wantedSpan) {
        this.reach = Math.clamp(wantedReach, 2, Booth.throwCap());
        // L'envergure ne peut pas dépasser la portée : une image de trente blocs de large posée à
        // deux blocs du projecteur voudrait dire un cône plus large que long, c'est-à-dire un angle
        // de plus de cent quatre-vingts degrés. La géométrie n'existe pas, et le rendu la dessinerait
        // retournée sur elle-même.
        this.span = Math.clamp(wantedSpan, 1, Math.max(1, Math.min(32, this.reach * 2)));
        announce();
    }

    // --- Stage ----------------------------------------------------------------

    @Override
    public Feed feed() {
        return this.feed;
    }

    @Override
    public Clock clock() {
        return this.clock;
    }

    @Override
    public void setShow(Feed wanted, Clock beat) {
        this.feed = wanted.sane();
        this.clock = beat;
        announce();
    }

    @Override
    public BlockPos stagePos() {
        return this.worldPosition;
    }

    @Override
    public String ownerName() {
        return this.ownerName;
    }

    @Override
    public boolean projector() {
        return true;
    }

    @Override
    public boolean mayCommand(Player player) {
        if (player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
            return true;
        }
        return switch (this.feed.lock()) {
            case TOUS -> true;
            case OPERATEUR -> false;
            case POSEUR -> this.owner == null || this.owner.equals(player.getUUID());
        };
    }

    public void claim(Player player) {
        this.owner = player.getUUID();
        this.ownerName = player.getGameProfile().name();
        setChanged();
    }

    // --- Persistance et diffusion ---------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("portee", this.reach);
        output.putInt("envergure", this.span);
        output.store("source", Feed.CODEC, this.feed);
        output.store("horloge", Clock.CODEC, this.clock);
        output.putString("poseur_nom", this.ownerName);
        if (this.owner != null) {
            output.putString("poseur", this.owner.toString());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.reach = Math.max(2, input.getIntOr("portee", REACH));
        this.span = Math.max(1, input.getIntOr("envergure", SPAN));
        this.feed = input.read("source", Feed.CODEC).orElse(Feed.BLANK).sane();
        this.clock = input.read("horloge", Clock.CODEC).orElse(Clock.STOPPED);
        this.ownerName = input.getStringOr("poseur_nom", "");
        Optional<String> stored = input.getString("poseur");
        this.owner = stored.map(text -> {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException corrupted) {
                return null;
            }
        }).orElse(null);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    private void announce() {
        setChanged();
        Level level = getLevel();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * L'emprise du faisceau, du bloc jusqu'à l'image.
     *
     * <p>Un projecteur est le seul bloc de ce paquet dont le rendu sort franchement de son cube : le
     * cône et l'image visée peuvent se trouver à trente-deux blocs de là. Sans cette boîte, tourner
     * la tête ferait disparaître l'image projetée alors qu'elle reste en plein champ — et l'on
     * chercherait longtemps du côté du décodeur.
     */
    public AABB beamBox() {
        BlockPos target = this.worldPosition.relative(facing(), this.reach);
        double margin = this.span;
        return new AABB(this.worldPosition).minmax(new AABB(target)).inflate(margin);
    }
}
