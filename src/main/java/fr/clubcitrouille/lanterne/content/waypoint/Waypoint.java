package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * Un repère : un nom, un lieu, une couleur, et la décision de le partager ou non.
 *
 * <h2>Ce que ce système refuse de faire, et c'est son point de départ</h2>
 *
 * <p>Les mods de carte offrent presque tous la téléportation vers un repère. C'est commode pour un
 * opérateur, et cela <b>détruit la survie</b> : plus rien ne coûte de distance, donc plus rien ne
 * coûte de temps, donc le monde cesse d'être grand.
 *
 * <p>Ce système ne téléporte pas. Il ne le fera pas. Un repère dit <em>où c'est</em> et <em>à quelle
 * distance</em> ; le chemin reste à faire. C'est la seule façon qu'un outil de repérage soit utile en
 * survie plutôt que de la remplacer.
 *
 * <h2>Pourquoi un nombre limité</h2>
 *
 * <p>Un carnet sans limite devient une liste de courses : on y jette tout, on n'y retrouve rien, et
 * le choix de ce qui mérite un repère — qui est la partie intéressante — disparaît. Cinq par joueur
 * par défaut, réglable : assez pour la base, la ferme, le portail, le village, et un projet en cours.
 *
 * <h2>Le partage</h2>
 *
 * <p>Un repère partagé devient visible de tous, sans cesser d'appartenir à celui qui l'a posé — lui
 * seul peut le renommer, le déplacer ou le retirer. C'est ce qui permet à un serveur entre amis
 * d'avoir des repères communs — le spawn, la mine, la base du groupe — sans qu'aucun ne soit
 * orphelin.
 *
 * @param owner     le joueur qui l'a posé
 * @param ownerName son nom, retenu pour l'afficher sans avoir à résoudre l'identifiant hors ligne
 * @param name      ce que le joueur a écrit
 * @param dimension le monde où il se trouve
 * @param pos       l'endroit
 * @param colour    la teinte, en 0xRRGGBB
 * @param shared    visible de tous, ou de son seul propriétaire
 */
public record Waypoint(UUID owner, String ownerName, String name, Identifier dimension,
                       BlockPos pos, int colour, boolean shared) {

    /** Longueur maximale d'un nom. Au-delà, l'interface ne le montrerait pas en entier. */
    public static final int NAME_LIMIT = 24;

    public static final Codec<Waypoint> CODEC = RecordCodecBuilder.create(spec -> spec.group(
            UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Waypoint::owner),
            Codec.STRING.fieldOf("owner_name").forGetter(Waypoint::ownerName),
            Codec.STRING.fieldOf("name").forGetter(Waypoint::name),
            Identifier.CODEC.fieldOf("dimension").forGetter(Waypoint::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(Waypoint::pos),
            Codec.INT.fieldOf("colour").forGetter(Waypoint::colour),
            Codec.BOOL.fieldOf("shared").forGetter(Waypoint::shared)
    ).apply(spec, Waypoint::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, Waypoint> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, Waypoint::owner,
                    ByteBufCodecs.stringUtf8(32), Waypoint::ownerName,
                    ByteBufCodecs.stringUtf8(NAME_LIMIT), Waypoint::name,
                    Identifier.STREAM_CODEC, Waypoint::dimension,
                    BlockPos.STREAM_CODEC, Waypoint::pos,
                    ByteBufCodecs.INT, Waypoint::colour,
                    ByteBufCodecs.BOOL, Waypoint::shared,
                    Waypoint::new);

    /**
     * Les teintes proposées, dans l'ordre du carrousel de l'interface.
     *
     * <p>Huit, et pas seize : au-delà, deux couleurs voisines cessent d'être distinguables d'un coup
     * d'œil sur une pastille de six pixels, et le choix devient une corvée au lieu d'un repère.
     */
    public static final int[] PALETTE = {
        0xFFC857, // ambre — la couleur du mod
        0x6FCF97, // vert
        0x56CCF2, // bleu clair
        0x9B7EDE, // violet
        0xEB5757, // rouge
        0xF2994A, // orange
        0xE8E8E8, // blanc
        0x828282, // gris
    };

    public Waypoint withName(String other) {
        return new Waypoint(owner, ownerName, other, dimension, pos, colour, shared);
    }

    public Waypoint withColour(int other) {
        return new Waypoint(owner, ownerName, name, dimension, pos, other, shared);
    }

    public Waypoint withShared(boolean other) {
        return new Waypoint(owner, ownerName, name, dimension, pos, colour, other);
    }

    /** Vrai si ce joueur a le droit de modifier ce repère : son propriétaire, et lui seul. */
    public boolean ownedBy(UUID who) {
        return owner.equals(who);
    }
}
