package fr.clubcitrouille.lanterne.content.waypoint;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Le Cœur de repère : le bloc qui donne une identité à un cadre d'obsidienne.
 *
 * <h2>Pourquoi un bloc de cadre, et pas un bloc posé devant</h2>
 *
 * <p>Un socle à côté du portail aurait été plus simple à écrire et plus mauvais à jouer : il aurait pu
 * être déplacé, dupliqué, oublié, et surtout il n'aurait rien coûté à la construction. En occupant une
 * case du cadre, le cœur <b>fait partie de l'ouvrage</b> — on le voit en passant, on sait tout de suite
 * qu'un portail n'est pas ordinaire, et le casser casse le portail, ce qui est la seule conséquence
 * qu'un joueur devine sans qu'on la lui explique.
 *
 * <p>Il se déclare {@code isPortalFrame}, et c'est tout ce qu'il faut pour que l'algorithme de cadre de
 * vanilla l'accepte comme de l'obsidienne : un joueur qui possède déjà un portail du Nether n'a qu'un
 * bloc à échanger.
 *
 * <h2>Deux gestes, et ils ne se marchent pas dessus</h2>
 *
 * <ul>
 *   <li><b>Briquet</b> — sur le cœur, ou dans le cadre comme pour un portail du Nether : le portail
 *       s'allume. Voir {@link Gates#onRightClick} pour le second cas, qui se joue avant ce fichier.</li>
 *   <li><b>Main nue, ou n'importe quoi d'autre</b> — l'écran du portail s'ouvre : nom, teinte, partage,
 *       et le choix de la destination.</li>
 * </ul>
 *
 * <p>Le cœur n'est pas un bloc-entité. Il n'a rien à retenir : tout ce qu'on sait d'un portail vit dans
 * {@link Atlas}, avec les repères, et le cœur n'est que l'endroit du monde auquel cette fiche est
 * attachée. Un bloc-entité aurait dupliqué cette information, donc permis qu'elle diverge.
 */
public class Heart extends Block {
    public static final MapCodec<Heart> CODEC = simpleCodec(Heart::new);

    public Heart(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /**
     * Le cœur vaut de l'obsidienne aux yeux du cadre.
     *
     * <p>{@code PortalShape} de vanilla n'interroge pas le bloc mais cette extension de NeoForge. En la
     * rendant vraie, on obtient gratuitement deux choses : le cadre est reconnu par le même code que
     * celui du Nether, et — puisque ce cadre porte alors un cœur — {@link Gates} peut refuser qu'il
     * devienne un portail du Nether ordinaire.
     */
    @Override
    public boolean isPortalFrame(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE)) {
            if (level instanceof ServerLevel server && player instanceof ServerPlayer who
                    && Gates.kindleAt(server, pos, who)) {
                level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS,
                        1.0F, level.getRandom().nextFloat() * 0.4F + 0.8F);
                if (!player.hasInfiniteMaterials()) {
                    if (stack.is(Items.FIRE_CHARGE)) {
                        stack.shrink(1);
                    } else {
                        stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
                    }
                }
            }
            return InteractionResult.SUCCESS;
        }
        // L'écran s'ouvre CÔTÉ CLIENT, comme le carnet : le client connaît déjà la liste des portails
        // qu'il a le droit de voir, et rien de ce qu'il fera dans cet écran ne sera cru sur parole —
        // chaque action repart au serveur, qui vérifie la propriété. Ouvrir n'a pas à être sûr ; ouvrir
        // a à être immédiat.
        if (level.isClientSide()) {
            Gates.OPENER.accept(pos);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Casser le cœur supprime le portail, et c'est irréversible.
     *
     * <p>C'est la seule règle de destruction à retenir, et elle est volontairement plus brutale que
     * celle du cadre : un cadre cassé <em>éteint</em>, un cœur cassé <em>efface</em>. Sans quoi une
     * fiche resterait dans l'atlas à désigner un endroit où il n'y a plus rien, et la liste des
     * destinations se remplirait de portes qui ne mènent nulle part.
     */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
                                               boolean movedByPiston) {
        Gates.forget(level, pos);
    }
}
