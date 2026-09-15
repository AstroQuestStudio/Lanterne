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
 * Ce qu'un bloc d'écran retient.
 *
 * <h2>Il n'a pas de ticker, et c'est la décision de conception la plus importante du paquet</h2>
 *
 * <p>Un écran qui joue donne l'impression de devoir être réveillé : avancer le temps, détecter la fin,
 * relancer la boucle. C'est une illusion complète, et y céder coûterait un appel par bloc et par tick
 * sur des murs de deux cent cinquante-six blocs.
 *
 * <p>Rien de tout cela n'a besoin du serveur. La position se <b>calcule</b> — voir {@link Clock} — donc
 * elle n'a pas à être avancée. La boucle est un modulo sur cette position, donc elle n'a pas à être
 * relancée. La fin de séance ne concerne que ce qui s'affiche, donc elle appartient au client qui
 * affiche.
 *
 * <p>Il reste exactement deux évènements côté serveur, et tous deux viennent d'un joueur qui agit :
 * un réglage change, ou un mur se recompose. Un bloc-entité qui ne réagit qu'à ces deux-là coûte
 * <b>zéro</b> par tick, quel que soit le nombre d'écrans posés. C'est le même raisonnement que celui
 * de {@code content.VigilBlockEntity}, et c'est celui qu'on attend d'un mod dont le sujet est la
 * performance.
 *
 * <h2>Maître et esclaves</h2>
 *
 * <p>Un mur de seize blocs, ce sont seize bloc-entités et <b>une</b> séance. Elle vit dans le bloc du
 * coin bas-gauche ; les quinze autres ne portent que l'adresse de ce coin et se contentent de renvoyer
 * vers lui. Ranger la séance dans chacun aurait donné seize copies à tenir d'accord, c'est-à-dire
 * seize occasions de diverger.
 *
 * <p>L'adresse est rangée en <b>écart</b> et non en position absolue. Un mur déplacé en bloc par une
 * structure ou une commande garde ainsi son assemblage, là où des positions absolues auraient désigné
 * l'endroit d'où il vient.
 */
public class PanelEntity extends BlockEntity implements Stage {
    /** L'écart vers le maître, depuis ce bloc. {@code ZERO} veut dire « le maître, c'est moi ». */
    private BlockPos toMaster = BlockPos.ZERO;

    /** Taille du mur, en blocs. N'a de sens que sur le maître. */
    private int wallWidth = 1;
    private int wallHeight = 1;

    private Feed feed = Feed.BLANK;
    private Clock clock = Clock.STOPPED;

    private UUID owner;
    private String ownerName = "";

    public PanelEntity(BlockPos pos, BlockState state) {
        super(Screens.PANEL_ENTITY.get(), pos, state);
    }

    // --- L'assemblage ---------------------------------------------------------

    /** Redevient un bloc solitaire. Voir {@link Wall#reform}, qui défait avant de refaire. */
    public void orphan() {
        this.toMaster = BlockPos.ZERO;
        this.wallWidth = 1;
        this.wallHeight = 1;
    }

    /** Prend sa place dans un mur. Le maître reçoit la taille, les autres reçoivent l'adresse. */
    public void joinWall(BlockPos master, int width, int height) {
        this.toMaster = master.subtract(this.worldPosition);
        boolean isMaster = this.toMaster.equals(BlockPos.ZERO);
        this.wallWidth = isMaster ? width : 1;
        this.wallHeight = isMaster ? height : 1;
        announce();
    }

    public BlockPos wallOrigin() {
        return this.worldPosition.offset(this.toMaster);
    }

    public boolean isMaster() {
        return this.toMaster.equals(BlockPos.ZERO);
    }

    public int wallWidth() {
        return this.wallWidth;
    }

    public int wallHeight() {
        return this.wallHeight;
    }

    public Direction facing() {
        return getBlockState().getValue(Panel.FACING);
    }

    // --- La séance ------------------------------------------------------------

    @Override
    public Feed feed() {
        return this.feed;
    }

    @Override
    public Clock clock() {
        return this.clock;
    }

    @Override
    public BlockPos stagePos() {
        return this.worldPosition;
    }

    /** Un mur n'est pas un cône. Voir {@link Stage#projector()}. */
    @Override
    public boolean projector() {
        return false;
    }

    /**
     * Pose une séance, et la fait connaître.
     *
     * <p>Les deux ensemble et jamais séparément : un client qui recevrait la source sans l'horloge
     * afficherait une image figée jusqu'au pli suivant, et l'inverse ferait courir une horloge sur
     * une source qui a changé — c'est-à-dire montrer la nouvelle vidéo à la position de l'ancienne.
     */
    @Override
    public void setShow(Feed wanted, Clock beat) {
        this.feed = wanted.sane();
        this.clock = beat;
        announce();
    }

    /** Reprend la séance d'un panneau absorbé par un mur neuf. Voir {@link Wall}. */
    public void inherit(PanelEntity other) {
        if (this.feed.idle() && !other.feed.idle()) {
            this.feed = other.feed;
            this.clock = other.clock;
        }
    }

    // --- Le propriétaire ------------------------------------------------------

    /**
     * Note qui a posé ce bloc.
     *
     * <p>Le nom est gardé <em>en plus</em> de l'identifiant, et ce n'est pas de la redondance : c'est
     * ce qu'on écrit dans l'interface. Retrouver un pseudonyme depuis un identifiant demande une
     * requête au service de profils, qui est lente, faillible, et absente en solo.
     */
    public void claim(Player player) {
        this.owner = player.getUUID();
        this.ownerName = player.getGameProfile().name();
        setChanged();
    }

    @Override
    public String ownerName() {
        return this.ownerName;
    }

    /**
     * Ce joueur peut-il changer ce qui s'affiche ?
     *
     * <p>Un opérateur passe toujours, quel que soit le verrou. Ce n'est pas un privilège de confort :
     * sans lui, un écran réglé sur « le poseur » par un joueur qui a quitté le serveur montrerait la
     * même chose pour toujours, et il faudrait le casser pour l'éteindre.
     */
    @Override
    public boolean mayCommand(Player player) {
        // « GAMEMASTERS » est le niveau 2 de l'ancienne échelle, celui qu'on entend par « opérateur ».
        // En 26.2 les niveaux numériques ont laissé la place à des permissions nommées ; le nom dit
        // ce qu'on veut, là où « 2 » demandait de se souvenir de la table.
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

    // --- Persistance et diffusion ---------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("maitre_x", this.toMaster.getX());
        output.putInt("maitre_y", this.toMaster.getY());
        output.putInt("maitre_z", this.toMaster.getZ());
        output.putInt("largeur", this.wallWidth);
        output.putInt("hauteur", this.wallHeight);
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
        this.toMaster = new BlockPos(
                input.getIntOr("maitre_x", 0),
                input.getIntOr("maitre_y", 0),
                input.getIntOr("maitre_z", 0));
        this.wallWidth = Math.max(1, input.getIntOr("largeur", 1));
        this.wallHeight = Math.max(1, input.getIntOr("hauteur", 1));
        this.feed = input.read("source", Feed.CODEC).orElse(Feed.BLANK).sane();
        this.clock = input.read("horloge", Clock.CODEC).orElse(Clock.STOPPED);
        this.ownerName = input.getStringOr("poseur_nom", "");
        Optional<String> stored = input.getString("poseur");
        this.owner = stored.map(text -> {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException corrupted) {
                // Une sauvegarde abîmée ne doit pas empêcher le monde de se charger. Sans
                // propriétaire, le verrou « poseur » laisse passer tout le monde — c'est plus
                // ouvert qu'on ne voudrait, et infiniment moins grave qu'un chunk qui refuse
                // de se charger.
                return null;
            }
        }).orElse(null);
    }

    /**
     * Ce que le client reçoit à l'arrivée du chunk, et à chaque changement.
     *
     * <p>Tout, y compris l'assemblage : sans lui, un client qui arrive au milieu d'un mur ne saurait
     * pas lequel des seize blocs doit dessiner l'image, et les seize la dessineraient chacun en entier
     * — seize images superposées, seize décodages, et un taux d'images au sol.
     */
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    /**
     * Enregistre, et prévient ceux qui regardent.
     *
     * <p>{@code sendBlockUpdated} est ce qui déclenche l'envoi du paquet ci-dessus. L'oublier donne
     * le défaut le plus déroutant de tous les bloc-entités : tout marche en solo — où client et
     * serveur partagent l'objet — et rien ne marche en multijoueur.
     */
    private void announce() {
        setChanged();
        Level level = getLevel();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * L'emprise du mur entier.
     *
     * <h2>Pourquoi elle est calculée ici et lue ailleurs</h2>
     *
     * <p>En 26.2, c'est le <b>rendu</b> qui déclare cette boîte — {@code IBlockEntityRendererExtension}
     * — et non le bloc-entité. Le calcul, lui, n'a rien de client : il ne dépend que de l'assemblage,
     * qui vit ici. Le laisser ici évite qu'un code de rendu ait à connaître la convention d'axes des
     * murs, et évite surtout qu'elle existe en deux exemplaires.
     *
     * <p>Sans cette boîte, le jeu n'affiche un bloc-entité que si <b>son propre cube</b> est dans le
     * champ. Un mur de seize sur neuf dont le coin bas-gauche sort de l'écran cesserait d'être dessiné
     * en entier, alors qu'il en reste les cinq sixièmes sous les yeux du joueur. Le symptôme est un
     * écran qui disparaît quand on s'en approche — la meilleure façon de faire croire à un défaut de
     * rendu là où il n'y a qu'une boîte mal déclarée.
     */
    public AABB wallBox() {
        Direction axis = Wall.across(facing());
        BlockPos far = this.worldPosition
                .relative(axis, this.wallWidth - 1)
                .above(this.wallHeight - 1);
        return new AABB(this.worldPosition).minmax(new AABB(far));
    }
}
