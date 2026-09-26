package fr.clubcitrouille.lanterne.content.trieur;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ContainerOrHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.transfer.resource.ResourceStack;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.mixin.HopperCooldownAccessor;

/**
 * Le ticker propre du Trieur (26.3.141) : même horloge qu'un entonnoir, mais une PILE par opération,
 * et la priorité au Trieur du dessous.
 *
 * <h2>Pourquoi ne plus hériter du tick vanilla</h2>
 *
 * <p>Jusqu'à 26.3.140, {@link Trieur#getTicker} rendait {@code HopperBlockEntity::pushItemsTick}, et
 * le Trieur transférait comme un entonnoir : un objet toutes les huit ticks (2,5/s), au mieux seize
 * par lot via {@code core.Bulk} quand ce module était allumé. Deux défauts en jeu réel, remontés par
 * le joueur qui a construit une vraie salle de tri :
 *
 * <ol>
 * <li><b>La lenteur.</b> Vider un coffre plein prend des minutes.</li>
 * <li><b>La fuite.</b> Dans une ligne d'entonnoirs au-dessus d'une rangée de Trieurs, chaque maillon
 * de la ligne pousse vers l'avant AVANT que le Trieur du dessous n'ait aspiré : un objet que le
 * Trieur voulait file au maillon suivant, puis au suivant, puis au coffre de trop-plein.</li>
 * </ol>
 *
 * <h2>Ce que fait ce ticker</h2>
 *
 * <ul>
 * <li><b>La même horloge.</b> {@code cooldownTime} est LU dans le vrai champ vanilla (voir {@link
 * HopperCooldownAccessor}), décrémenté, et reposé à huit ticks après chaque opération réussie —
 * exactement {@code pushItemsTick}. Un entonnoir vanilla qui pousse dans un Trieur vide continue donc
 * de lui poser sa recharge comme à n'importe quel entonnoir.</li>
 * <li><b>En vrac.</b> Une opération déplace jusqu'à {@link Settings#trieurDebit()} objets (64 par
 * défaut), dans un sens comme dans l'autre : ressource par ressource (une pile de cailloux part d'un
 * bloc), et tant que ce budget n'est pas épuisé on enchaîne la ressource suivante — sans quoi un coffre
 * d'outils et d'armes, objets NON empilables, retombait à un objet par opération (mesuré : 50 ticks
 * pour quatre épées au premier essai). À 1, on retrouve le rythme vanilla. Vanilla lui-même fait déjà
 * du vrac en ramassant au sol : une pile entière entre en un seul transfert.</li>
 * <li><b>Par l'API de transfert NeoForge.</b> Source (face du bas du bloc au-dessus) et destination
 * (face opposée du bloc devant) sont des {@code ResourceHandler<ItemResource>} obtenus par {@code
 * Capabilities.Item.BLOCK} — coffres doubles, tonneaux, boîtes de Shulker (faces respectées),
 * fours, autres Trieurs, conteneurs moddés — et chaque transfert est une {@link Transaction} : rien
 * ne se perd ni ne se duplique si la destination n'accepte qu'une partie. Repli sur la recherche
 * vanilla ({@code getContainerOrHandlerAt} : entités conteneurs comme le wagonnet à coffre) quand
 * aucune capacité n'existe.</li>
 * <li><b>Le filtre à l'extraction.</b> On ne prend au-dessus QUE ce que ce Trieur accepte ({@link
 * TrieurBlockEntity#accepte}) — l'insertion dans ce Trieur repasse de toute façon par {@code
 * canPlaceItem}, la seule porte.</li>
 * <li><b>Le ramassage au sol</b> reste celui de vanilla quand aucun conteneur n'est au-dessus
 * ({@code HopperBlockEntity.addItem(Container, ItemEntity)}, filtre compris).</li>
 * </ul>
 *
 * <h2>La priorité au tri (F2)</h2>
 *
 * <p>Un Trieur qui n'est PAS orienté vers le bas offre d'abord chaque objet qu'il pousse au Trieur
 * situé directement SOUS lui. Trois cas :
 *
 * <ul>
 * <li>le Trieur du dessous l'accepte (selon SON filtre) et a de la place : l'objet descend ;</li>
 * <li>il l'accepte mais il est plein : l'objet ATTEND ici — il ne file jamais vers l'avant, sinon un
 * coffre de tri plein laisserait fuir son tri au bout de la ligne ; les autres cases continuent de
 * circuler ;</li>
 * <li>il ne l'accepte pas, ou il n'y a pas de Trieur dessous : l'objet part vers {@code facing},
 * comme d'habitude.</li>
 * </ul>
 *
 * <p>Conséquence : une ligne de Trieurs en liste noire vide (qui acceptent tout) posée au-dessus
 * d'une rangée de Trieurs-filtres orientés vers le bas ne laisse plus jamais filer un objet trié. Une
 * ligne d'entonnoirs VANILLA, elle, garde son comportement vanilla : c'est pour ça que la salle de
 * tri conseillée remplace la ligne d'entonnoirs par une ligne de Trieurs.
 *
 * <h2>Le coût à vide, pour un serveur mono-cœur</h2>
 *
 * <p>Un entonnoir vanilla qui n'a rien déplacé retente au tick suivant, et chaque tentative refait
 * deux recherches de conteneur. Ici, les recherches passent par un {@code BlockCapabilityCache} (une
 * lecture de champ tant que les blocs voisins ne changent pas), et un Trieur qui n'a RIEN pu déplacer
 * attend {@link #ATTENTE_A_VIDE} ticks avant de réessayer — une recharge courte, toujours inférieure
 * aux huit ticks vanilla, donc jamais « personnalisée » : un entonnoir qui pousse dans ce Trieur vide
 * la remplace par sa recharge normale, comme en vanilla. Un Trieur vide sous un coffre vide ne coûte
 * alors qu'une lecture de cache et un parcours des cases de la source, un tick sur quatre.
 *
 * <p>Le ticker vanilla exact reste celui d'un Trieur désactivé par configuration ({@code trieur_actif
 * = false}) : voir {@link #tick}, première ligne.
 */
public final class TrieurTransfert {
    /** Recharge après une opération réussie : celle de vanilla. */
    public static final int RECHARGE = HopperBlockEntity.MOVE_ITEM_SPEED;

    /** Recharge après une tentative infructueuse. Voir la javadoc de classe. */
    public static final int ATTENTE_A_VIDE = 4;

    private TrieurTransfert() {}

    /** Le ticker serveur du Trieur. Voir la javadoc de classe. */
    public static void tick(Level level, BlockPos pos, BlockState state, TrieurBlockEntity trieur) {
        if (!Settings.trieur() || !(level instanceof ServerLevel server)) {
            HopperBlockEntity.pushItemsTick(level, pos, state, trieur);
            return;
        }
        HopperCooldownAccessor horloge = (HopperCooldownAccessor) trieur;
        int recharge = horloge.lanterne$cooldownTime() - 1;
        trieur.setCooldown(recharge);
        horloge.lanterne$setTickedGameTime(level.getGameTime());
        if (recharge > 0) {
            return;
        }
        trieur.setCooldown(0);
        if (!state.getValue(HopperBlock.ENABLED)) {
            // Redstone : vanilla ne bouge rien non plus, et ne pose aucune recharge.
            return;
        }
        int debit = Settings.trieurDebit();
        boolean bouge = false;
        if (!trieur.isEmpty()) {
            bouge = pousser(server, pos, state, trieur, debit);
        }
        if (!plein(trieur)) {
            bouge |= aspirer(server, pos, trieur, debit);
        }
        if (bouge) {
            trieur.setCooldown(RECHARGE);
            trieur.setChanged();
        } else {
            trieur.setCooldown(ATTENTE_A_VIDE);
        }
    }

    /**
     * Un objet au sol qui entre dans le bloc. Vanilla ({@code HopperBlockEntity.entityInside}) passe par
     * {@code tryMoveItems}, qui POUSSE aussi vers l'avant au passage — la seule porte par laquelle un
     * Trieur aurait encore pu contourner la priorité au Trieur du dessous. Ici, on ne fait que ramasser ;
     * la poussée attend le tick, et sa règle.
     */
    public static void entiteDedans(Level level, BlockPos pos, BlockState state, Entity entity,
            TrieurBlockEntity trieur) {
        if (!(entity instanceof ItemEntity item) || item.getItem().isEmpty()
                || !entity.getBoundingBox().move(-pos.getX(), -pos.getY(), -pos.getZ())
                        .intersects(trieur.getSuckAabb())) {
            return;
        }
        if (((HopperCooldownAccessor) trieur).lanterne$cooldownTime() > 0
                || !state.getValue(HopperBlock.ENABLED) || plein(trieur)) {
            return;
        }
        if (HopperBlockEntity.addItem(trieur, item)) {
            trieur.setCooldown(RECHARGE);
            trieur.setChanged();
        }
    }

    // --- Pousser -----------------------------------------------------------------

    private static boolean pousser(ServerLevel level, BlockPos pos, BlockState state, TrieurBlockEntity trieur,
            int debit) {
        Direction facing = state.getValue(HopperBlock.FACING);
        TrieurBlockEntity dessous = null;
        if (facing != Direction.DOWN && level.getBlockEntity(pos.below()) instanceof TrieurBlockEntity t
                && !t.isRemoved()) {
            dessous = t;
        }
        ResourceHandler<ItemResource> devant = null;
        boolean devantCherche = false;
        int reste = debit;
        for (int slot = 0; slot < trieur.getContainerSize() && reste > 0; slot++) {
            ItemStack stack = trieur.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (dessous != null && dessous.accepte(stack)) {
                // Réservé au Trieur du dessous. S'il est plein, l'objet attend ici : il ne file pas.
                reste -= verser(trieur, slot, stack, dessous.poignee(), reste);
                continue;
            }
            if (!devantCherche) {
                devant = cible(level, trieur, facing);
                devantCherche = true;
                if (devant == null && dessous == null) {
                    return false;
                }
            }
            if (devant != null) {
                reste -= verser(trieur, slot, stack, devant, reste);
            }
        }
        return reste < debit;
    }

    /**
     * Verse jusqu'à {@code debit} objets de la case {@code slot} dans {@code vers}, en une transaction.
     * On insère d'abord (la destination décide combien elle prend), puis on retire EXACTEMENT ce qui a
     * été pris — jamais une supposition, donc ni perte ni duplication sur une insertion partielle.
     *
     * @return le nombre d'objets réellement déplacés
     */
    private static int verser(TrieurBlockEntity trieur, int slot, ItemStack stack,
            ResourceHandler<ItemResource> vers, int debit) {
        ItemResource ressource = ItemResource.of(stack);
        int voulu = Math.min(debit, stack.getCount());
        int pris;
        try (Transaction tx = Transaction.openRoot()) {
            pris = ResourceHandlerUtil.insertStacking(vers, ressource, voulu, tx);
            if (pris <= 0) {
                return 0;
            }
            tx.commit();
        }
        trieur.removeItem(slot, pris);
        return pris;
    }

    private static @Nullable ResourceHandler<ItemResource> cible(ServerLevel level, TrieurBlockEntity trieur,
            Direction facing) {
        ResourceHandler<ItemResource> capacite = trieur.capaciteDevant(level, facing);
        if (capacite != null) {
            return capacite;
        }
        return envelopper(HopperBlockEntity.getContainerOrHandlerAt(level,
                trieur.getBlockPos().relative(facing), facing.getOpposite()), facing.getOpposite());
    }

    // --- Aspirer -----------------------------------------------------------------

    private static boolean aspirer(ServerLevel level, BlockPos pos, TrieurBlockEntity trieur, int debit) {
        ResourceHandler<ItemResource> source = trieur.capaciteHaut(level);
        if (source == null) {
            source = envelopper(HopperBlockEntity.getContainerOrHandlerAt(level, pos.above(), Direction.DOWN),
                    Direction.DOWN);
        }
        if (source != null) {
            try (Transaction tx = Transaction.openRoot()) {
                // Ressource par ressource : la première que ce Trieur accepte, prise dans toutes les
                // cases de la source qui la contiennent, puis la suivante tant que le budget le permet.
                // Une seule transaction racine : tout ou rien pour l'opération entière.
                int reste = debit;
                while (reste > 0) {
                    ResourceStack<ItemResource> pris = ResourceHandlerUtil.moveFirstStacking(source,
                            trieur.poignee(), trieur.accepteRessource, reste, tx);
                    if (pris == null) {
                        break;
                    }
                    reste -= pris.amount();
                }
                if (reste == debit) {
                    return false;
                }
                tx.commit();
                return true;
            }
        }
        return ramasser(level, pos, trieur);
    }

    /**
     * Le ramassage au sol, recopié de {@code HopperBlockEntity.suckInItems} (branche sans conteneur) :
     * un bloc plein au-dessus bloque, sauf s'il porte {@code #does_not_block_hoppers}.
     */
    private static boolean ramasser(ServerLevel level, BlockPos pos, TrieurBlockEntity trieur) {
        BlockPos haut = pos.above();
        BlockState etat = level.getBlockState(haut);
        if (etat.isCollisionShapeFullBlock(level, haut) && !etat.is(BlockTags.DOES_NOT_BLOCK_HOPPERS)) {
            return false;
        }
        for (ItemEntity item : HopperBlockEntity.getItemsAtAndAbove(level, trieur)) {
            if (HopperBlockEntity.addItem(trieur, item)) {
                return true;
            }
        }
        return false;
    }

    // --- Outils -----------------------------------------------------------------

    /** Un conteneur trouvé par la recherche vanilla, vu comme une poignée de transfert. */
    private static @Nullable ResourceHandler<ItemResource> envelopper(ContainerOrHandler trouve, Direction face) {
        if (trouve.itemHandler() != null) {
            return trouve.itemHandler();
        }
        Container container = trouve.container();
        if (container == null) {
            return null;
        }
        return container instanceof WorldlyContainer worldly
                ? new WorldlyContainerWrapper(worldly, face)
                : VanillaContainerWrapper.of(container);
    }

    /** Recopie de {@code HopperBlockEntity.inventoryFull}, qui est privée. */
    static boolean plein(TrieurBlockEntity trieur) {
        for (int slot = 0; slot < trieur.getContainerSize(); slot++) {
            ItemStack stack = trieur.getItem(slot);
            if (stack.isEmpty() || stack.getCount() != stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }
}
