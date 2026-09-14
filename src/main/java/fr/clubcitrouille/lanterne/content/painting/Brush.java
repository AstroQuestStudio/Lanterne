package fr.clubcitrouille.lanterne.content.painting;

import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import org.jspecify.annotations.Nullable;

/**
 * Le pinceau : l'unique objet du système de tableaux.
 *
 * <h2>Vierge, il pose une toile vide — et c'est le geste principal</h2>
 *
 * <p>La première version refusait de poser quoi que ce soit tant qu'on ne l'avait pas chargé par une
 * commande. C'était une erreur, et l'essai en jeu l'a montrée tout de suite : personne ne veut
 * préparer une bibliothèque avant de décorer un mur. On veut <b>accrocher un cadre vide, puis le
 * remplir</b>, comme dans la vraie vie.
 *
 * <p>Un pinceau sans encre pose donc une toile de {@value #BLANK_SIDE} blocs de côté, <b>tout de
 * suite et sans qu'aucun écran ne s'interpose</b> : on voit son emprise sur le mur, sa taille, son
 * cadre. Un clic droit dessus ouvre ensuite l'atelier, où l'image se choisit par un lien, par le
 * sélecteur de fichiers du système, ou dans la bibliothèque du serveur.
 *
 * <p>L'atelier s'ouvrait d'abord automatiquement à la pose. C'était une étape de trop : elle
 * empêchait d'accrocher plusieurs toiles à la suite, et elle retardait le seul retour qui compte
 * vraiment — voir la toile sur le mur.
 *
 * <h2>Chargé, il reste un presse-papier</h2>
 *
 * <p>Le clic du milieu sur un tableau accroché rend un pinceau chargé de la même image et de la même
 * taille : on duplique une fresque d'un mur à l'autre sans repasser par l'atelier. C'est ce qu'un
 * objet qui porte son état permet et qu'un écran seul ne permet pas — il se donne, il se range dans
 * un coffre, il se perd à la mort.
 *
 * <h2>Ce que le pinceau porte</h2>
 *
 * <p>Un composant de données — {@link Ink} — qui contient l'empreinte de l'image et la taille
 * voulue. Rien d'autre : pas de pixels, pas de nom, pas d'auteur. Le nom affiché est retrouvé dans
 * le catalogue au moment de l'infobulle, ce qui garantit qu'un tableau renommé sur le serveur
 * s'affiche sous son nouveau nom sans qu'aucun objet n'ait à être mis à jour.
 *
 * <p>Un pinceau dont l'empreinte est vide n'est pas invalide : c'est le pinceau ordinaire, celui
 * qu'on fabrique et qui pose des toiles vierges.
 */
public class Brush extends Item {
    /**
     * Côté, en blocs, d'une toile posée vierge.
     *
     * <p>Deux sur deux : assez grand pour qu'on voie qu'il s'agit d'un tableau et pas d'un panneau,
     * assez petit pour tenir sur presque tous les murs d'une maison de survie. La taille se règle
     * ensuite dans l'atelier, et elle s'ajuste d'elle-même aux proportions de l'image choisie.
     */
    public static final int BLANK_SIDE = 2;

    public Brush(Properties properties) {
        super(properties);
    }

    /**
     * La charge d'un pinceau : quelle image, et de quelle taille.
     *
     * <p>Le codec persistant et le codec réseau sont tous deux déclarés. Le premier fait que le
     * pinceau survit à une sauvegarde ; le second qu'il s'affiche correctement dans l'inventaire
     * d'un joueur en multijoueur. Oublier l'un des deux donne un défaut qui ne se voit que dans un
     * des deux modes de jeu, et c'est le genre de bogue qu'on ne trouve qu'après publication.
     */
    public record Ink(String hash, int width, int height, int trim) implements TooltipProvider {
        public static final Codec<Ink> CODEC = RecordCodecBuilder.create(builder -> builder.group(
                Codec.STRING.fieldOf("hash").forGetter(Ink::hash),
                Codec.intRange(1, 16).fieldOf("width").forGetter(Ink::width),
                Codec.intRange(1, 16).fieldOf("height").forGetter(Ink::height),
                // Facultatif : un pinceau d'avant les encadrements reste valide, et reprend
                // le bois. Un champ obligatoire aurait vide l'inventaire des joueurs qui en
                // avaient un.
                Codec.INT.optionalFieldOf("trim", Trim.BOIS.id()).forGetter(Ink::trim)
        ).apply(builder, Ink::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, Ink> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(Plate.HASH_LENGTH), Ink::hash,
                        ByteBufCodecs.VAR_INT, Ink::width,
                        ByteBufCodecs.VAR_INT, Ink::height,
                        ByteBufCodecs.VAR_INT, Ink::trim,
                        Ink::new);

        /**
         * L'infobulle : le nom de l'image, et sa taille en blocs.
         *
         * <p>Le nom vient du catalogue, pas de l'objet. Sur un client qui n'a pas encore reçu le
         * rôle d'appel — juste après la connexion — le catalogue est vide et l'on affiche
         * l'empreinte tronquée. C'est moins joli, et c'est infiniment préférable à une infobulle
         * vide qui laisserait croire que l'objet est cassé.
         */
        @Override
        public void addToTooltip(Item.TooltipContext context, Consumer<Component> consumer,
                TooltipFlag flag, DataComponentGetter components) {
            Plate plate = Easel.known(this.hash);
            String label = plate != null ? plate.name()
                    : (this.hash.isEmpty() ? "vierge" : this.hash.substring(0, Math.min(8, this.hash.length())));
            consumer.accept(Component.literal(label)
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
            consumer.accept(Component.literal(this.width + " × " + this.height + " blocs · "
                            + Trim.byId(this.trim).label())
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        }
    }

    /** Un pinceau chargé d'une image, d'une taille et d'un encadrement. */
    public static ItemStack charged(String hash, int width, int height, Trim trim) {
        ItemStack stack = new ItemStack(Easel.BRUSH.get());
        stack.set(Easel.INK.get(), new Ink(hash, Math.clamp(width, 1, 16),
                Math.clamp(height, 1, 16), trim.id()));
        return stack;
    }

    public static @Nullable Ink inkOf(ItemStack stack) {
        return stack.get(Easel.INK.get());
    }

    /**
     * Poser une toile.
     *
     * <h2>L'ordre des refus n'est pas arbitraire</h2>
     *
     * <p>Chaque vérification est placée juste avant l'opération qu'elle rend inutile, et les plus
     * bavardes sont les dernières. On refuse d'abord ce qui ne coûte rien à refuser — un pinceau
     * vierge, une face horizontale — et seulement ensuite ce qui suppose de construire l'entité,
     * de calculer sa boîte et d'interroger le monde pour savoir si elle tient.
     *
     * <p>La construction sur le client est évitée entièrement : {@code level.isClientSide()} rend
     * {@code SUCCESS} sans rien faire, ce qui anime la main du joueur pendant que le serveur décide
     * réellement. Construire des deux côtés ferait apparaître un fantôme de tableau chez le joueur
     * quand le serveur refuse.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Direction face = context.getClickedFace();
        if (!face.getAxis().isHorizontal()) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();
        Ink ink = inkOf(stack);
        // Un pinceau vierge n'est plus un refus : il pose une toile vide, qu'on remplit en cliquant
        // dessus. C'est le geste principal — voir le Javadoc de classe.
        String hash = ink == null ? "" : ink.hash();
        int width = ink == null ? BLANK_SIDE : ink.width();
        int height = ink == null ? BLANK_SIDE : ink.height();
        Trim trim = ink == null ? Trim.BOIS : Trim.byId(ink.trim());

        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos against = context.getClickedPos().relative(face);
        if (player != null && !player.mayUseItemAt(against, face, stack)) {
            return InteractionResult.FAIL;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        Canvas canvas = new Canvas(level, against, face, hash, width, height, trim);
        if (!canvas.survives()) {
            tell(player, "Pas assez de mur : il faut " + width + " × " + height
                    + " blocs pleins et dégagés.");
            return InteractionResult.CONSUME;
        }
        if (player != null) {
            canvas.claim(player.getUUID());
        }
        canvas.playPlacementSound();
        level.addFreshEntity(canvas);
        if (player == null || !player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        if (hash.isEmpty()) {
            // On n'ouvre PAS l'atelier ici. Le geste voulu est « on place et on voit » : la
            // toile doit apparaître sur le mur sans qu'un écran s'interpose, et il faut pouvoir
            // en accrocher plusieurs à la suite sans rien fermer entre deux.
            // Reste à dire comment la remplir : une ligne qui n'interrompt rien.
            tell(player, "Clic droit sur la toile pour y mettre une image.");
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Dit quelque chose au joueur, si tant est qu'il y en ait un.
     *
     * <p>Le joueur peut être absent : un distributeur, une commande, un autre mod peuvent construire
     * un {@code UseOnContext} sans personne au bout. Ce n'est pas un cas d'erreur, c'est un cas
     * normal, et le refus doit alors être silencieux plutôt que fatal.
     */
    private static void tell(@Nullable Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
        }
    }
}
