package fr.clubcitrouille.lanterne.content.trieur;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import fr.clubcitrouille.lanterne.content.Contents;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Ce que le Trieur ajoute à un entonnoir : neuf gabarits, et un sens de lecture.
 *
 * <h2>Le filtre, sémantique exacte</h2>
 *
 * <p>Liste blanche VIDE = n'accepte RIEN. Un Trieur qu'on vient de poser n'est donc utile qu'une fois
 * configuré — c'est le comportement le plus sûr pour un outil de tri : un Trieur oublié refuse tout
 * plutôt que de laisser passer n'importe quoi dans une chaîne qu'on croyait filtrée. Liste noire VIDE
 * = accepte TOUT, symétriquement : rien n'est bloqué tant qu'on n'a rien désigné.
 *
 * <h2>Deux pièges trouvés au {@code javap}, absents du plan d'origine</h2>
 *
 * <p>Le constructeur à deux arguments de {@code HopperBlockEntity} — le seul qui existe, vérifié par
 * désassemblage complet — passe TOUJOURS {@code BlockEntityTypes.HOPPER} à {@code BlockEntity}, quelle
 * que soit la sous-classe qui l'appelle. Deux conséquences, et deux surcharges pour les couvrir :
 *
 * <ul>
 * <li>{@code BlockEntity} appelle {@code this.validateBlockState(state)} DANS SON PROPRE
 * constructeur, en {@code invokevirtual} — donc sur la version la plus dérivée, même avant que le
 * corps du constructeur de cette classe ne s'exécute. Cette validation compare le bloc réel
 * ({@link Contents#TRIEUR}) à l'ensemble de blocs valides de {@code BlockEntityTypes.HOPPER}, qui ne
 * contient que {@code Blocks.HOPPER} : sans {@link #isValidBlockState} ci-dessous, poser un Trieur
 * lèverait une {@code IllegalStateException} à l'instant même où le bloc-entité se construit.</li>
 * <li>{@code BlockEntity.saveId} écrit l'identifiant de sauvegarde via {@code this.getType()}, lui
 * aussi en {@code invokevirtual}. Sans {@link #getType} ci-dessous, un Trieur sauvegardé s'écrirait
 * sous l'identifiant {@code minecraft:hopper} et reviendrait, au chargement suivant, sous la forme
 * d'un {@code HopperBlockEntity} vanilla ordinaire — filtre et mode perdus, silencieusement, à chaque
 * redémarrage du serveur.</li>
 * </ul>
 *
 * <p>Les deux méthodes sont volontairement permissives ({@code isValidBlockState} rend toujours vrai) :
 * le seul bloc qui construit jamais cette classe est {@link Trieur}, donc la validation que
 * {@code BlockEntityType} ferait à notre place serait de toute façon triviale.
 */
public class TrieurBlockEntity extends HopperBlockEntity {
    public static final int FILTER_SLOTS = 9;

    private static final String WHITELIST_KEY = "liste_blanche";
    private static final String FILTER_KEY = "filtre";

    /**
     * Neuf gabarits, jamais consommés : voir {@link TrieurMenu} pour la case qui les ramène toujours
     * à un seul exemplaire. {@code setChanged} est relayé au bloc-entité pour que poser ou retirer un
     * gabarit déclenche bien une sauvegarde — {@code SimpleContainer} seul ne sait pas qu'il vit dans
     * un bloc-entité.
     */
    private final SimpleContainer filtre = new SimpleContainer(FILTER_SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            TrieurBlockEntity.this.setChanged();
        }
    };

    /** Liste blanche par défaut : voir la javadoc de classe pour pourquoi c'est le choix sûr. */
    private boolean whitelist = true;

    public TrieurBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    public SimpleContainer filtre() {
        return this.filtre;
    }

    public boolean whitelist() {
        return this.whitelist;
    }

    public void setWhitelist(boolean whitelist) {
        if (this.whitelist != whitelist) {
            this.whitelist = whitelist;
            this.setChanged();
        }
    }

    /** Voir la javadoc de classe : contourne une validation qui comparerait ce bloc à un hopper vanilla. */
    @Override
    public boolean isValidBlockState(BlockState state) {
        return true;
    }

    /** Voir la javadoc de classe : sans cette redéfinition, la sauvegarde se ferait sous le mauvais type. */
    @Override
    public BlockEntityType<?> getType() {
        return Contents.TRIEUR_ENTITY.get();
    }

    /**
     * Le filtre lui-même. {@code super.canPlaceItem} reste la porte d'entrée vanilla ; on n'y ajoute
     * qu'une condition, jamais une soustraction — un Trieur ne peut donc jamais accepter ce qu'un
     * entonnoir vanilla aurait refusé.
     *
     * <p>{@link Settings#trieur()} coupe UNIQUEMENT ce filtrage, jamais le bloc ni son transfert : un
     * Trieur désactivé par configuration se comporte comme un entonnoir ordinaire, exactement la règle
     * documentée dans {@code core.Config} pour {@code trieur_actif}.
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return super.canPlaceItem(slot, stack) && (!Settings.trieur() || passesFilter(stack));
    }

    private boolean passesFilter(ItemStack stack) {
        return this.whitelist == matchesAnyTemplate(stack);
    }

    private boolean matchesAnyTemplate(ItemStack stack) {
        for (ItemStack template : this.filtre.getItems()) {
            if (!template.isEmpty() && ItemStack.isSameItem(template, stack)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new TrieurMenu(id, inventory, this);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.lanterne.trieur");
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean(WHITELIST_KEY, this.whitelist);
        ContainerHelper.saveAllItems(output.child(FILTER_KEY), this.filtre.getItems());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.whitelist = input.getBooleanOr(WHITELIST_KEY, true);
        input.child(FILTER_KEY).ifPresent(child -> ContainerHelper.loadAllItems(child, this.filtre.getItems()));
    }
}
