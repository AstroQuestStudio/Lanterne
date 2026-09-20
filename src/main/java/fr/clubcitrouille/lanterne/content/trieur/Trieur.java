package fr.clubcitrouille.lanterne.content.trieur;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.content.Contents;

/**
 * Le Trieur : un entonnoir qui n'accepte que ce qu'on lui a montré.
 *
 * <h2>Un bloc à part, jamais un remplacement</h2>
 *
 * <p>Ce mod possède déjà, dans {@link fr.clubcitrouille.lanterne.core.Bulk} et
 * {@link fr.clubcitrouille.lanterne.mixin.HopperBulkMixin}, un entonnoir qui transfère par lots de
 * seize en payant la recharge exacte — et {@link fr.clubcitrouille.lanterne.mixin.HopperMixin}
 * (désactivé) documente en détail pourquoi le TIMING d'un entonnoir est le point qu'on ne touche
 * jamais : une horloge à entonnoir compte les objets un par un, et le moindre décalage se voit. Le
 * Trieur ne change donc rien au timing. Il ne remplace même pas le hopper vanilla — c'est un bloc
 * neuf, enregistré séparément, qui ne touche à aucun entonnoir déjà posé sur ce serveur.
 *
 * <h2>Où passe le filtre, et pourquoi c'est suffisant</h2>
 *
 * <p>Vérifié au {@code javap} sur {@code HopperBlockEntity} (jar client 26.3 réel, classes en
 * clair) : que l'objet soit poussé dedans par un autre entonnoir, ou aspiré depuis le dessus, les
 * deux chemins passent par la méthode PRIVÉE {@code canPlaceItemInContainer}, qui appelle en premier
 * {@code container.canPlaceItem(slot, stack)} — une méthode d'interface ({@code Container}),
 * {@code default} et donc surchargeable, pas une méthode statique. {@link TrieurBlockEntity}
 * surcharge exactement celle-là. Aucun mixin n'est nécessaire : la méthode était déjà l'unique porte
 * d'entrée, vanilla l'a seulement laissée ouverte.
 *
 * <h2>La vitesse Bulk, sans dupliquer une ligne</h2>
 *
 * <p>{@code HopperBulkMixin} cible {@code HopperBlockEntity.class} au bytecode. Une sous-classe
 * hérite du bytecode fusionné par Mixin comme de n'importe quelle méthode Java : {@link
 * TrieurBlockEntity} — qui étend {@code HopperBlockEntity} — reçoit donc le transfert par lots
 * gratuitement, du seul fait d'hériter. C'est pour cette raison, et aucune autre, que ce bloc étend
 * {@code HopperBlock} plutôt que de réimplémenter une forme et un comportement d'entonnoir de zéro.
 *
 * <h2>Ce que ce bloc n'a pas besoin de faire</h2>
 *
 * <p>{@code HopperBlock.useWithoutItem} teste déjà {@code instanceof HopperBlockEntity} puis appelle
 * {@code player.openMenu(blockEntity)} — vérifié au {@code javap}, désassemblage complet de la
 * méthode. Cet appel est polymorphe : {@code BaseContainerBlockEntity.createMenu(int, Inventory,
 * Player)} (l'implémentation de {@code MenuProvider}) invoque en retour {@code this.createMenu(id,
 * inventory)}, la méthode protégée à deux arguments que {@link TrieurBlockEntity} surcharge pour
 * renvoyer un {@link TrieurMenu}. Nul besoin donc de surcharger {@code use}/{@code useWithoutItem}
 * ici : le clic vanilla ouvre déjà le bon menu, simplement parce que le bloc-entité en dessous en
 * renvoie un autre.
 *
 * <h2>Ce que ce bloc DOIT faire, et que le plan d'origine ne disait pas</h2>
 *
 * <p>{@code HopperBlock.getTicker} ne compare le type qu'on lui passe qu'à UNE seule constante,
 * {@code BlockEntityTypes.HOPPER} — vérifié au {@code javap}, désassemblage complet. Un Trieur, dont
 * le bloc-entité s'annonce sous son propre type (voir la javadoc de {@link TrieurBlockEntity} pour
 * la raison, elle aussi trouvée au {@code javap}), ne matcherait donc JAMAIS ce test et ne tickerait
 * plus du tout si cette méthode n'était pas refaite ici, contre {@link Contents#TRIEUR_ENTITY}.
 */
public class Trieur extends HopperBlock {
    public Trieur(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TrieurBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        return level.isClientSide() ? null
                : createTickerHelper(type, Contents.TRIEUR_ENTITY.get(), HopperBlockEntity::pushItemsTick);
    }
}
