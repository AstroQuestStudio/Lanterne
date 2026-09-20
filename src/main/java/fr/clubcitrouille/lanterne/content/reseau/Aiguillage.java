package fr.clubcitrouille.lanterne.content.reseau;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.jspecify.annotations.Nullable;

/**
 * L'Aiguillage : relie un coffre (ou un baril, ou un shulker) déjà posé au Réseau, sans le remplacer.
 *
 * <h2>Ce que ce bloc ne fait PAS, et pourquoi — un piège moteur vérifié au {@code javap}</h2>
 *
 * <p>Le plan d'origine était de sous-classer {@code HopperBlockEntity}, pour que ce bloc fonctionne
 * EXACTEMENT comme un hopper vanilla (aspiration au-dessus, poussée en dessous) en plus d'annoncer son
 * voisin au réseau. Vérifié directement au {@code javap} sur
 * {@code neoformruntime/artifacts/minecraft_26.3_client.jar} : <b>c'est impossible dans ce moteur.</b>
 *
 * <p>{@code HopperBlockEntity} n'a qu'UN SEUL constructeur, {@code (BlockPos, BlockState)}, et son
 * bytecode montre qu'il appelle {@code super(BlockEntityTypes.HOPPER, pos, state)} — le type est codé
 * en dur, aucune variante ne permet d'y glisser un type à soi. Le constructeur de {@code BlockEntity}
 * appelle ensuite INCONDITIONNELLEMENT {@code validateBlockState(state)}, qui lit ce champ {@code type}
 * de façon PRIVÉE — un {@code getfield} direct, pas un appel virtuel à {@code getType()} — et lève
 * {@code IllegalStateException} si {@code BlockEntityTypes.HOPPER.isValid(state)} est faux. Or ce
 * prédicat de vanilla ne reconnaît que {@code Blocks.HOPPER} : sous-classer {@code HopperBlockEntity}
 * pour N'IMPORTE QUEL autre bloc plante donc À LA CONSTRUCTION, systématiquement, avant même que le
 * bloc-entité n'existe. Redéfinir {@code getType()} dans la sous-classe n'y change rien : ce contrôle
 * précis ne passe jamais par cet accesseur.
 *
 * <h2>Et ce n'est pas grave — ce bloc n'a jamais eu besoin de bouger un seul objet</h2>
 *
 * <p>Le Réseau est un registre événementiel PUR (voir {@code core.Reseau}) : aucun objet ne transite
 * jamais de hopper en hopper. Le rôle de l'Aiguillage n'a donc jamais été de POUSSER quoi que ce soit —
 * seulement d'ANNONCER un voisin. {@link AiguillageBlockEntity} n'a par conséquent ni case, ni ticker,
 * ni la moindre logique de déplacement : c'est un pur marqueur, du même genre que {@code VigilBlockEntity}
 * (« il ne fait rien, et c'est tout son intérêt ») — en réalité un résultat MEILLEUR que le plan
 * d'origine pour un mod dont l'éthique entière est qu'un bloc-entité sans ticker ne coûte rien.
 *
 * <p>Un joueur qui veut aussi le comportement physique d'un hopper (vider un four dans un coffre, par
 * exemple) pose un hopper vanilla normal À CÔTÉ de l'Aiguillage, ou sous le coffre lui-même — les deux
 * coexistent sans se gêner, le hopper vanilla n'ayant aucune idée que le réseau existe.
 */
public class Aiguillage extends BaseEntityBlock {
    public Aiguillage(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AiguillageBlockEntity(pos, state);
    }

    /**
     * Sans ceci, un {@code BaseEntityBlock} est invisible — voir {@code content.Vigil} pour la même
     * remarque, déjà faite une fois dans ce mod.
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
