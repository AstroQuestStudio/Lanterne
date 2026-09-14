package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * La toile : un tableau personnalisé accroché à un mur.
 *
 * <h2>Entité ou bloc-entité — et pourquoi c'est une entité</h2>
 *
 * <p>La question mérite d'être tranchée explicitement, parce que les deux réponses sont défendables
 * et que ce projet a choisi l'autre pour la Lanterne de Veille. Quatre raisons, par ordre
 * d'importance :
 *
 * <ol>
 *   <li><b>Un tableau couvre plusieurs blocs.</b> Un tableau de quatre sur trois, en bloc-entité,
 *       c'est soit douze blocs et douze bloc-entités — douze fois le coût de sérialisation, de
 *       recherche et de rendu pour une seule image — soit un bloc unique dont la boîte de rendu
 *       déborde de son chunk, ce qui casse l'élagage par chunk du moteur et fait disparaître le
 *       tableau selon l'angle de la caméra. Les deux sont mauvais. Une entité a une boîte
 *       arbitraire, et c'est exactement le besoin.</li>
 *   <li><b>Poser un tableau ne doit pas reconstruire un chunk.</b> La géométrie d'un chunk est
 *       calculée une fois puis conservée ; un bloc-entité porteur de géométrie forcerait une
 *       reconstruction à chaque pose, à chaque casse, et — si l'image arrivait par le réseau après
 *       coup — à chaque arrivée d'image. Une entité est dessinée hors du maillage : rien de tout
 *       cela n'a lieu.</li>
 *   <li><b>Le jeu fournit déjà la mécanique.</b> {@link HangingEntity} apporte l'accrochage au mur,
 *       la vérification périodique du support, la chute quand le mur disparaît, le paquet
 *       d'apparition qui porte l'orientation. Réécrire cela sur un bloc-entité serait recopier du
 *       code du jeu pour en obtenir moins.</li>
 *   <li><b>Le rendu se regroupe de lui-même.</b> Toutes les toiles partagent une seule texture — la
 *       {@link Mosaic} — donc un seul type de rendu, donc un seul lot de dessin. Vingt tableaux à
 *       l'écran coûtent un changement de texture, pas vingt. C'est la raison d'être de la mosaïque,
 *       et elle ne fonctionne que parce que les tableaux passent tous par le même chemin d'entité.</li>
 * </ol>
 *
 * <p>Le prix payé est réel et il faut le dire : une entité est tickée. Ce tick est celui de
 * {@code BlockAttachedEntity} — une vérification de support toutes les cent tickées, et rien
 * d'autre. C'est moins qu'un item posé au sol, et cela n'a jamais été le poste de dépense d'un
 * serveur.
 *
 * <h2>Ce que l'entité porte, et ce qu'elle ne porte pas</h2>
 *
 * <p>Elle porte une <b>empreinte</b>, pas une image. Trente-deux caractères, synchronisés par le
 * mécanisme d'entité ordinaire. Les pixels voyagent par un tout autre chemin, une seule fois par
 * image et par client, et n'ont rien à voir avec le nombre de tableaux posés : mille toiles de la
 * même image ne transmettent cette image qu'une fois.
 *
 * <p>Si l'empreinte est inconnue du client — image pas encore arrivée, ou serveur qui ne la possède
 * plus — la toile se dessine dans la couleur de son dos. Elle ne disparaît pas et ne plante pas :
 * un tableau vide est un défaut visible et compréhensible, une entité qui manque est un mystère.
 */
public class Canvas extends HangingEntity {
    /** L'empreinte de l'image. Voir {@link Plate#hash()}. */
    private static final EntityDataAccessor<String> DATA_HASH =
            SynchedEntityData.defineId(Canvas.class, EntityDataSerializers.STRING);

    /** Largeur en blocs. */
    private static final EntityDataAccessor<Integer> DATA_WIDTH =
            SynchedEntityData.defineId(Canvas.class, EntityDataSerializers.INT);

    /** Hauteur en blocs. */
    private static final EntityDataAccessor<Integer> DATA_HEIGHT =
            SynchedEntityData.defineId(Canvas.class, EntityDataSerializers.INT);

    /**
     * L'encadrement, par son identifiant. Voir {@link Trim}.
     *
     * <p>Un entier plutôt que l'énumération : un sérialiseur de données d'entité doit exister pour
     * le type transporté, et en écrire un pour cinq valeurs coûterait plus que de transporter le
     * numéro. La conversion est faite au seul endroit qui en a besoin, le rendu.
     */
    private static final EntityDataAccessor<Integer> DATA_TRIM =
            SynchedEntityData.defineId(Canvas.class, EntityDataSerializers.INT);

    /** Demi-épaisseur de la toile, en blocs — la même qu'un tableau de vanilla. */
    public static final float HALF_DEPTH = 0.03125F;

    /**
     * Le motif d'Immersive Paintings, tant qu'on n'a pas su à quelle image il correspond.
     *
     * <h2>Une information qu'on refuse de jeter</h2>
     *
     * <p>Une toile héritée est chargée avec le chunk, et le chunk peut être chargé <b>avant</b> que
     * l'import de la bibliothèque héritée n'ait fini — il tourne sur un fil de fond et prend
     * plusieurs secondes. Si l'on se contentait alors de constater qu'on ne connaît pas le motif, la
     * toile serait sauvegardée sans lui, et le lien serait rompu <em>définitivement</em> : rien ne
     * permettrait plus jamais de retrouver quelle image ce tableau portait.
     *
     * <p>Le motif est donc conservé tel quel, réécrit à la sauvegarde tant qu'il n'est pas résolu,
     * et réessayé périodiquement. Une seule chose est irréversible ici — perdre l'information — et
     * c'est justement celle qu'on empêche.
     */
    private @Nullable String legacyMotive;

    public Canvas(EntityType<? extends Canvas> type, Level level) {
        super(type, level);
    }

    public Canvas(Level level, BlockPos pos, Direction direction, String hash, int width, int height,
            Trim trim) {
        super(Easel.CANVAS.get(), level, pos);
        this.entityData.set(DATA_HASH, hash);
        this.entityData.set(DATA_WIDTH, clamp(width));
        this.entityData.set(DATA_HEIGHT, clamp(height));
        this.entityData.set(DATA_TRIM, trim.id());
        this.setDirection(direction);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_HASH, "");
        entityData.define(DATA_WIDTH, 1);
        entityData.define(DATA_HEIGHT, 1);
        entityData.define(DATA_TRIM, Trim.BOIS.id());
    }

    /**
     * Recalcule la boîte quand la taille change, et seulement alors.
     *
     * <p>L'empreinte n'y est pas : changer l'image ne change pas la géométrie. C'est une différence
     * volontaire avec le mod de référence, qui déduit la taille de l'image et doit donc recalculer
     * la boîte à chaque arrivée de motif — avec le risque, qu'il documente lui-même, que l'entité se
     * déplace visuellement pendant le suivi. Ici la taille est décidée à la pose et ne bouge plus.
     */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if ((DATA_WIDTH.equals(accessor) || DATA_HEIGHT.equals(accessor)) && this.pos != null) {
            this.recalculateBoundingBox();
        }
    }

    /**
     * Qui a posé cette toile, ou {@code null} si on l'ignore.
     *
     * <p>Non synchronisé : le client n'a aucune décision à prendre avec cette information, et la
     * question « ai-je le droit » est tranchée par le serveur au moment du clic, avant même que
     * l'écran ne s'ouvre. L'envoyer aurait été divulguer sans raison l'identifiant d'un joueur.
     */
    private @Nullable UUID owner;

    public String hash() {
        return this.entityData.get(DATA_HASH);
    }

    /**
     * Change l'image et la taille. Serveur uniquement.
     *
     * <p>L'ordre est celui de {@link #readAdditionalSaveData} et pour la même raison : la taille
     * d'abord, parce que c'est elle qui commande la boîte, et le motif hérité est abandonné puisque
     * la toile a désormais une image décidée.
     */
    public void dress(String hash, int width, int height, Trim trim) {
        this.legacyMotive = null;
        this.entityData.set(DATA_WIDTH, clamp(width));
        this.entityData.set(DATA_HEIGHT, clamp(height));
        this.entityData.set(DATA_HASH, hash);
        this.entityData.set(DATA_TRIM, trim.id());
        this.recalculateBoundingBox();
    }

    /**
     * Ce joueur peut-il modifier cette toile ?
     *
     * <h2>Libre par défaut, et ce n'est pas de la négligence</h2>
     *
     * <p>N'importe qui peut déjà <b>casser</b> un tableau de vanilla et en accrocher un autre. Une
     * toile qu'on ne pourrait pas modifier mais qu'on pourrait détruire protégerait donc le contenu
     * sans protéger l'objet — c'est-à-dire rien, avec un pas de plus à faire.
     *
     * <p>Le serveur qui veut vraiment protéger ses murs pose {@code edition_libre = false} : seuls
     * celui qui a posé la toile et les opérateurs peuvent alors la changer. Une toile posée avant
     * que le mod ne retienne les propriétaires n'a pas de propriétaire : elle reste modifiable, parce
     * que la verrouiller au premier venu serait pire que de ne rien verrouiller.
     */
    public boolean mayEdit(Player player) {
        if (Studio.freeEdit() || this.owner == null || this.owner.equals(player.getUUID())) {
            return true;
        }
        return player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public void claim(UUID who) {
        this.owner = who;
    }

    /**
     * Le clic droit ouvre l'atelier.
     *
     * <p>Rien n'est fait côté client : {@code interact} y est appelé aussi, et y ouvrir l'écran
     * court-circuiterait le contrôle des droits. Le client rend {@code SUCCESS} pour que la main du
     * joueur s'anime, et attend que le serveur lui dise d'ouvrir — voir {@link PaintingOpen}.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (this.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer server) {
            Easel.openFor(server, this);
        }
        return InteractionResult.SUCCESS;
    }

    public int blockWidth() {
        return this.entityData.get(DATA_WIDTH);
    }

    public int blockHeight() {
        return this.entityData.get(DATA_HEIGHT);
    }

    public Trim trim() {
        return Trim.byId(this.entityData.get(DATA_TRIM));
    }

    private static int clamp(int side) {
        return Math.clamp(side, 1, 16);
    }

    /**
     * La boîte, calquée sur celle du tableau de vanilla.
     *
     * <p>Le décalage de {@code 0.46875} colle la toile au mur en laissant un trente-deuxième de
     * bloc — c'est ce qui évite que la face avant du tableau et la face du mur soient exactement
     * coplanaires, situation où la carte graphique n'a aucun moyen de décider laquelle dessiner et
     * où l'on voit apparaître des taches clignotantes.
     *
     * <p>Le décalage d'un demi-bloc pour les tailles paires est ce qui fait qu'un tableau de deux
     * blocs de large se centre sur une <em>jointure</em> et non sur un bloc. Sans cela, un tableau
     * pair serait décentré d'un demi-bloc et la moitié des constructions deviendraient impossibles à
     * aligner.
     */
    @Override
    protected AABB calculateBoundingBox(BlockPos pos, Direction direction) {
        double shiftToWall = 0.46875d;
        Vec3 againstWall = Vec3.atCenterOf(pos).relative(direction, -shiftToWall);
        int width = this.blockWidth();
        int height = this.blockHeight();
        double sideways = width % 2 == 0 ? 0.5d : 0.0d;
        double upwards = height % 2 == 0 ? 0.5d : 0.0d;
        Direction left = direction.getCounterClockWise();
        Vec3 centre = againstWall.relative(left, sideways).relative(Direction.UP, upwards);
        Direction.Axis axis = direction.getAxis();
        double xSize = axis == Direction.Axis.X ? 0.0625d : width;
        double ySize = height;
        double zSize = axis == Direction.Axis.Z ? 0.0625d : width;
        return AABB.ofSize(centre, xSize, ySize, zSize);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.store("facing", Direction.LEGACY_ID_CODEC_2D, this.getDirection());
        super.addAdditionalSaveData(output);
        output.putString("lanterne_hash", this.hash());
        output.putInt("lanterne_w", this.blockWidth());
        output.putInt("lanterne_h", this.blockHeight());
        output.putInt("lanterne_trim", this.trim().id());
        if (this.owner != null) {
            output.store("lanterne_owner", net.minecraft.core.UUIDUtil.CODEC, this.owner);
        }
        if (this.legacyMotive != null) {
            // Voir le Javadoc de « legacyMotive » : tant que le motif n'est pas résolu, il est
            // réécrit sous le nom que le mod d'origine lui donnait. Le prochain démarrage pourra
            // donc réessayer, indéfiniment s'il le faut.
            output.putString("Motive", this.legacyMotive);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        Direction direction = readFacing(input);
        super.readAdditionalSaveData(input);
        this.owner = input.read("lanterne_owner", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        this.entityData.set(DATA_TRIM, Trim.byId(input.getIntOr("lanterne_trim", Trim.BOIS.id())).id());
        String hash = input.getStringOr("lanterne_hash", "");
        int width = input.getIntOr("lanterne_w", 0);
        int height = input.getIntOr("lanterne_h", 0);

        if (hash.isEmpty()) {
            // Aucune donnée de Lanterne : c'est une toile héritée d'Immersive Paintings, que
            // l'alias de registre vient de faire atterrir ici. Voir Relic.
            String motive = input.getStringOr("Motive", "");
            if (!motive.isEmpty()) {
                this.legacyMotive = motive;
                Relic.Heir heir = Relic.lookup(motive);
                if (heir != null) {
                    hash = heir.hash();
                    width = heir.width();
                    height = heir.height();
                    this.legacyMotive = null;
                }
            }
        }

        // La taille AVANT l'orientation : « setDirection » recalcule la boîte, et il la calculerait
        // à partir d'un un-sur-un si la taille n'était pas déjà en place. La toile serait alors
        // posée correctement mais tiendrait dans un seul bloc jusqu'au prochain changement de
        // donnée — c'est-à-dire, en pratique, pour toujours.
        this.entityData.set(DATA_WIDTH, clamp(width <= 0 ? 1 : width));
        this.entityData.set(DATA_HEIGHT, clamp(height <= 0 ? 1 : height));
        this.entityData.set(DATA_HASH, hash);
        this.setDirection(direction);
    }

    /**
     * L'orientation, écrite de deux façons possibles.
     *
     * <p>Lanterne écrit {@code facing} comme vanilla — un indice à deux dimensions, donc forcément
     * horizontal. Immersive Paintings écrit {@code Facing}, un indice à trois dimensions, parce que
     * ses graffitis se posent aussi au plafond. Une toile ne sait s'accrocher qu'à un mur, et
     * {@code HangingEntity.setDirection} <b>lève une exception</b> sur un axe vertical : lui passer
     * un « haut » hérité ferait planter le chargement du chunk, ce qui est la pire façon possible
     * d'échouer une migration. On se rabat donc sur le nord, ce qui donne un tableau mal orienté
     * mais un monde qui s'ouvre.
     */
    private static Direction readFacing(ValueInput input) {
        Direction own = input.read("facing", Direction.LEGACY_ID_CODEC_2D).orElse(null);
        if (own != null) {
            return own;
        }
        Direction foreign = input.getInt("Facing")
                .map(Direction::from3DDataValue)
                .orElse(Direction.SOUTH);
        return foreign.getAxis().isHorizontal() ? foreign : Direction.NORTH;
    }

    /**
     * Le tick de {@code BlockAttachedEntity}, plus une seule question de plus.
     *
     * <p>La question n'est posée que si {@link #legacyMotive} n'est pas nul, c'est-à-dire pour les
     * seules toiles héritées non encore résolues, c'est-à-dire pendant les quelques secondes que dure
     * l'import de la bibliothèque au démarrage. Pour toutes les autres toiles — la totalité, passé
     * ce délai — ce tick est un test de nullité, et rien d'autre.
     */
    @Override
    public void tick() {
        super.tick();
        if (this.legacyMotive == null || !(this.level() instanceof ServerLevel)) {
            return;
        }
        // Une fois par seconde suffit largement, et le décalage par identifiant évite que toutes
        // les toiles d'un même chunk n'interrogent la table au même tick.
        if ((this.tickCount + this.getId()) % 20 != 0) {
            return;
        }
        Relic.Heir heir = Relic.lookup(this.legacyMotive);
        if (heir == null) {
            return;
        }
        this.legacyMotive = null;
        this.entityData.set(DATA_WIDTH, clamp(heir.width() <= 0 ? this.blockWidth() : heir.width()));
        this.entityData.set(DATA_HEIGHT, clamp(heir.height() <= 0 ? this.blockHeight() : heir.height()));
        this.entityData.set(DATA_HASH, heir.hash());
    }

    /**
     * Le paquet d'apparition porte l'orientation dans son champ libre.
     *
     * <p>C'est la solution de vanilla, et il y a une raison de ne pas s'en écarter : au moment où le
     * client crée l'entité, les données synchronisées ne sont pas encore appliquées. Sans
     * l'orientation dans le paquet lui-même, la toile existerait pendant une tickée tournée vers le
     * sud, sa boîte serait calculée de travers, et le premier élagage de rendu pourrait l'écarter.
     */
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity) {
        return new ClientboundAddEntityPacket(this, this.getDirection().get3DDataValue(), this.getPos());
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        this.setDirection(Direction.from3DDataValue(packet.getData()));
    }

    @Override
    public void dropItem(ServerLevel level, @Nullable Entity causedBy) {
        if (!level.getGameRules().get(GameRules.ENTITY_DROPS)) {
            return;
        }
        this.playSound(SoundEvents.PAINTING_BREAK, 1.0F, 1.0F);
        if (causedBy instanceof Player player && player.hasInfiniteMaterials()) {
            return;
        }
        this.spawnAtLocation(level, this.getPickResult());
    }

    @Override
    public void playPlacementSound() {
        this.playSound(SoundEvents.PAINTING_PLACE, 1.0F, 1.0F);
    }

    /**
     * Cueillir un tableau rend un pinceau chargé de la même image et de la même taille.
     *
     * <p>C'est ce qui rend la fonctionnalité utilisable sans passer par une commande à chaque fois :
     * on pose, on cueille au clic du milieu, on repose ailleurs. Le pinceau est l'unique objet du
     * système, et il porte tout son état.
     */
    @Override
    public ItemStack getPickResult() {
        return Brush.charged(this.hash(), this.blockWidth(), this.blockHeight(), this.trim());
    }

    @Override
    public void snapTo(double x, double y, double z, float yRot, float xRot) {
        this.setPos(x, y, z);
    }

    @Override
    public Vec3 trackingPosition() {
        return Vec3.atLowerCornerOf(this.pos);
    }
}
