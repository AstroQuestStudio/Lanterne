package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Un portail de repère : un cadre allumé, une identité, et la destination qu'on lui a donnée.
 *
 * <h2>Pourquoi un portail peut téléporter alors qu'un repère ne le fera jamais</h2>
 *
 * <p>Un repère est gratuit : on l'écrit, il existe. Lui donner le pouvoir de téléporter reviendrait à
 * offrir la distance, et la distance est la seule chose qui rende un monde grand.
 *
 * <p>Un portail, lui, <b>ne se pose pas</b>. Il se construit : dix blocs d'obsidienne, donc un seau
 * d'eau, un voyage au Nether ou une pioche en diamant ; un {@linkplain Gates#HEART Cœur de repère}, qui
 * coûte deux perles de l'Ender, un œil, de l'améthyste et de l'or. Et surtout, il ne relie que des
 * portails <b>déjà bâtis et déjà allumés</b> — c'est-à-dire des endroits où quelqu'un est allé à pied.
 *
 * <p>La distance n'est donc pas supprimée : elle est <b>payée une fois</b>. C'est toute la différence
 * avec une commande de téléportation, et c'est la raison pour laquelle ce système existe alors que le
 * carnet refuse toujours, lui, de vous déplacer.
 *
 * <h2>L'identité vit dans le cœur, pas dans les blocs de portail</h2>
 *
 * <p>Casser un bloc du cadre éteint le portail ; le rallumer lui rend son nom, sa couleur et les liens
 * qui pointaient vers lui. Casser le <b>cœur</b>, en revanche, le supprime pour de bon. C'est la seule
 * règle à retenir, et elle est délibérée : réparer un cadre après un creeper ne doit pas coûter
 * l'identité d'une destination que d'autres joueurs ont peut-être inscrite dans leurs propres portails.
 *
 * @param owner     le joueur qui l'a allumé ; lui seul peut le renommer, le relier ou le rendre privé
 * @param ownerName son nom, retenu pour l'afficher sans résoudre l'identifiant hors ligne
 * @param name      ce que le joueur a écrit, ou un nom automatique le temps qu'il en choisisse un
 * @param dimension le monde où se trouve le cadre
 * @param heart     la position du cœur : l'identité physique du portail
 * @param axis      l'axe horizontal du cadre, pour orienter l'arrivée comme vanilla le fait
 * @param min       le coin bas de l'intérieur du cadre — la nappe de blocs de portail
 * @param max       le coin haut du même intérieur
 * @param colour    la teinte, en 0xRRGGBB, reprise de la palette des repères
 * @param shared    public, donc destination offerte à tous ; ou réservé à son propriétaire
 * @param lit       le cadre porte-t-il en ce moment ses blocs de portail
 * @param target    le portail où il mène, s'il mène quelque part
 */
public record Gate(UUID id, UUID owner, String ownerName, String name, Identifier dimension,
                   BlockPos heart, Direction.Axis axis, BlockPos min, BlockPos max,
                   int colour, boolean shared, boolean lit, Optional<UUID> target) {

    /** Même limite que les repères : au-delà, l'interface ne montrerait pas le nom en entier. */
    public static final int NAME_LIMIT = Waypoint.NAME_LIMIT;

    public static final Codec<Gate> CODEC = RecordCodecBuilder.create(spec -> spec.group(
            UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(Gate::id),
            UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Gate::owner),
            Codec.STRING.fieldOf("owner_name").forGetter(Gate::ownerName),
            Codec.STRING.fieldOf("name").forGetter(Gate::name),
            Identifier.CODEC.fieldOf("dimension").forGetter(Gate::dimension),
            BlockPos.CODEC.fieldOf("heart").forGetter(Gate::heart),
            Direction.Axis.CODEC.fieldOf("axis").forGetter(Gate::axis),
            BlockPos.CODEC.fieldOf("min").forGetter(Gate::min),
            BlockPos.CODEC.fieldOf("max").forGetter(Gate::max),
            Codec.INT.fieldOf("colour").forGetter(Gate::colour),
            Codec.BOOL.fieldOf("shared").forGetter(Gate::shared),
            Codec.BOOL.fieldOf("lit").forGetter(Gate::lit),
            UUIDUtil.STRING_CODEC.optionalFieldOf("target").forGetter(Gate::target)
    ).apply(spec, Gate::new));

    /**
     * Le codec de réseau, écrit à la main.
     *
     * <p>{@code StreamCodec.composite} s'arrête à douze champs ; il y en a treize. Plutôt que de
     * tordre le record pour rentrer dans l'assembleur, on écrit les treize lectures et les treize
     * écritures — c'est plus long à lire, mais rien n'y est implicite, et l'ordre saute aux yeux.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, Gate> STREAM_CODEC = StreamCodec.of(
            (buffer, gate) -> {
                UUIDUtil.STREAM_CODEC.encode(buffer, gate.id());
                UUIDUtil.STREAM_CODEC.encode(buffer, gate.owner());
                ByteBufCodecs.stringUtf8(32).encode(buffer, gate.ownerName());
                ByteBufCodecs.stringUtf8(NAME_LIMIT).encode(buffer, gate.name());
                Identifier.STREAM_CODEC.encode(buffer, gate.dimension());
                BlockPos.STREAM_CODEC.encode(buffer, gate.heart());
                buffer.writeByte(gate.axis() == Direction.Axis.X ? 0 : 1);
                BlockPos.STREAM_CODEC.encode(buffer, gate.min());
                BlockPos.STREAM_CODEC.encode(buffer, gate.max());
                buffer.writeInt(gate.colour());
                buffer.writeBoolean(gate.shared());
                buffer.writeBoolean(gate.lit());
                buffer.writeBoolean(gate.target().isPresent());
                gate.target().ifPresent(other -> UUIDUtil.STREAM_CODEC.encode(buffer, other));
            },
            buffer -> {
                UUID id = UUIDUtil.STREAM_CODEC.decode(buffer);
                UUID owner = UUIDUtil.STREAM_CODEC.decode(buffer);
                String ownerName = ByteBufCodecs.stringUtf8(32).decode(buffer);
                String name = ByteBufCodecs.stringUtf8(NAME_LIMIT).decode(buffer);
                Identifier dimension = Identifier.STREAM_CODEC.decode(buffer);
                BlockPos heart = BlockPos.STREAM_CODEC.decode(buffer);
                Direction.Axis axis = buffer.readByte() == 0 ? Direction.Axis.X : Direction.Axis.Z;
                BlockPos min = BlockPos.STREAM_CODEC.decode(buffer);
                BlockPos max = BlockPos.STREAM_CODEC.decode(buffer);
                int colour = buffer.readInt();
                boolean shared = buffer.readBoolean();
                boolean lit = buffer.readBoolean();
                Optional<UUID> target = buffer.readBoolean()
                        ? Optional.of(UUIDUtil.STREAM_CODEC.decode(buffer))
                        : Optional.empty();
                return new Gate(id, owner, ownerName, name, dimension, heart, axis, min, max,
                        colour, shared, lit, target);
            });

    public Gate withName(String other) {
        return new Gate(id, owner, ownerName, other, dimension, heart, axis, min, max,
                colour, shared, lit, target);
    }

    public Gate withColour(int other) {
        return new Gate(id, owner, ownerName, name, dimension, heart, axis, min, max,
                other, shared, lit, target);
    }

    public Gate withShared(boolean other) {
        return new Gate(id, owner, ownerName, name, dimension, heart, axis, min, max,
                colour, other, lit, target);
    }

    public Gate withLit(boolean other) {
        return new Gate(id, owner, ownerName, name, dimension, heart, axis, min, max,
                colour, shared, other, target);
    }

    public Gate withTarget(Optional<UUID> other) {
        return new Gate(id, owner, ownerName, name, dimension, heart, axis, min, max,
                colour, shared, lit, other);
    }

    /** Le cadre vient d'être rallumé ailleurs, ou d'une autre taille : on garde l'identité, pas la forme. */
    public Gate withShape(Direction.Axis newAxis, BlockPos newMin, BlockPos newMax) {
        return new Gate(id, owner, ownerName, name, dimension, heart, newAxis, newMin, newMax,
                colour, shared, true, target);
    }

    /** Vrai si ce joueur a le droit de modifier ce portail : son propriétaire, et lui seul. */
    public boolean ownedBy(UUID who) {
        return owner.equals(who);
    }

    /** Vrai si ce joueur a le droit de le voir dans une liste et de le traverser. */
    public boolean visibleTo(UUID who) {
        return shared || ownedBy(who);
    }

    /** Ce bloc fait-il partie de la nappe de portail de ce cadre ? */
    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    /**
     * Où l'on pose les pieds en arrivant : le bas, au milieu de la largeur du cadre.
     *
     * <p>Le milieu, et non le coin : un cadre de deux blocs de large déposerait sinon le joueur à
     * cheval sur le montant, et la première chose qu'il verrait de son portail serait de l'obsidienne.
     */
    public Vec3 landing() {
        return new Vec3(
                (min.getX() + max.getX() + 1) / 2.0d,
                min.getY(),
                (min.getZ() + max.getZ() + 1) / 2.0d);
    }
}
