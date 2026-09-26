package fr.clubcitrouille.lanterne.content.trieur;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.content.Contents;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Ce que le Trieur ajoute à un entonnoir : dix-huit cases de filtre, et un sens de lecture.
 *
 * <h2>Le filtre, sémantique exacte</h2>
 *
 * <p>Liste blanche VIDE = n'accepte RIEN. Un Trieur qu'on vient de poser n'est donc utile qu'une fois
 * configuré — c'est le comportement le plus sûr pour un outil de tri : un Trieur oublié refuse tout
 * plutôt que de laisser passer n'importe quoi dans une chaîne qu'on croyait filtrée. Liste noire VIDE
 * = accepte TOUT, symétriquement : rien n'est bloqué tant qu'on n'a rien désigné.
 *
 * <p>Une case de filtre contient soit un GABARIT (un objet : désigne le même objet, composants
 * ignorés — même règle que {@code ItemStack.isSameItem}), soit un MOTIF (une étiquette renommée :
 * voir {@link Motif} pour la syntaxe). Un objet est « désigné » s'il correspond à au moins une case.
 * Une étiquette NON renommée reste un gabarit ordinaire : elle désigne les étiquettes.
 *
 * <h2>Le filtre compilé, et quand il se recompile</h2>
 *
 * <p>{@link #crible()} analyse les dix-huit cases une fois — tableau d'objets, tableau de motifs déjà
 * compilés — et garde le résultat jusqu'au prochain changement du filtre : tout {@code setItem} sur
 * le conteneur passe par son {@code setChanged}, qui l'invalide ; le chargement ({@link
 * #loadAdditional}), qui écrit directement dans la liste, l'invalide à la main. Le test d'un objet ne
 * relit donc jamais un nom d'étiquette ni ne découpe un motif pendant le tick.
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
 *
 * <h2>Format de sauvegarde (inchangé depuis les neuf cases)</h2>
 *
 * <p>Les clés vanilla du hopper ({@code Items}, {@code TransferCooldown}), plus {@code liste_blanche}
 * (booléen) et {@code filtre} — un objet {@code {Items:[{Slot:0b,id:...,count:1}, ...]}} écrit par
 * {@code ContainerHelper}. Un ancien Trieur à neuf cases se charge tel quel : les cases 9 à 17 restent
 * vides. Un motif n'est qu'une étiquette avec {@code minecraft:custom_name} : aucune clé nouvelle.
 */
public class TrieurBlockEntity extends HopperBlockEntity {
    public static final int FILTER_SLOTS = 18;

    private static final String WHITELIST_KEY = "liste_blanche";
    private static final String FILTER_KEY = "filtre";

    /**
     * Dix-huit cases, jamais consommées : voir {@link TrieurMenu} pour la case qui les ramène toujours
     * à un seul exemplaire. {@code setChanged} est relayé au bloc-entité pour que poser ou retirer un
     * gabarit déclenche bien une sauvegarde — {@code SimpleContainer} seul ne sait pas qu'il vit dans
     * un bloc-entité — et invalide le filtre compilé.
     */
    private final SimpleContainer filtre = new SimpleContainer(FILTER_SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            TrieurBlockEntity.this.crible = null;
            TrieurBlockEntity.this.setChanged();
        }
    };

    /** Liste blanche par défaut : voir la javadoc de classe pour pourquoi c'est le choix sûr. */
    private boolean whitelist = true;

    /** Le filtre compilé ; {@code null} = à recompiler. Voir la javadoc de classe. */
    private @Nullable Crible crible;

    /** Le prédicat du filtre, alloué une fois : passé à chaque aspiration par {@link TrieurTransfert}. */
    final Predicate<ItemResource> accepteRessource = this::accepte;

    // Poignées de transfert du ticker (voir TrieurTransfert). Jamais sauvegardées.
    private @Nullable ResourceHandler<ItemResource> poignee;
    private @Nullable BlockCapabilityCache<ResourceHandler<ItemResource>, Direction> cacheHaut;
    private @Nullable BlockCapabilityCache<ResourceHandler<ItemResource>, Direction> cacheDevant;
    private @Nullable Direction sensDevant;

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
     * <p>C'est la SEULE porte : l'entonnoir vanilla qui pousse dedans ({@code tryMoveInItem}), celui qui
     * aspire depuis le dessus, l'enveloppe NeoForge ({@code VanillaContainerWrapper.isValid}) qu'utilisent
     * les tuyaux des mods et le ticker propre du Trieur — tous appellent {@code canPlaceItem}.
     *
     * <p>{@link Settings#trieur()} coupe UNIQUEMENT ce filtrage, jamais le bloc ni son transfert : un
     * Trieur désactivé par configuration se comporte comme un entonnoir ordinaire, exactement la règle
     * documentée dans {@code core.Config} pour {@code trieur_actif}.
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return super.canPlaceItem(slot, stack) && accepte(stack);
    }

    /** Le filtre seul, sans la porte vanilla : vrai si ce Trieur laisse entrer cet objet. */
    public boolean accepte(ItemStack stack) {
        return accepte(stack.typeHolder(), stack.get(DataComponents.CUSTOM_NAME));
    }

    /** Même test sur une ressource de l'API de transfert, sans allouer de pile. */
    public boolean accepte(ItemResource resource) {
        return accepte(resource.typeHolder(), resource.get(DataComponents.CUSTOM_NAME));
    }

    /**
     * Le cœur du filtre. {@link Settings#trieur()} éteint = tout passe (voir {@link #canPlaceItem}).
     * Liste blanche : désigné ⇒ accepté. Liste noire : désigné ⇒ refusé.
     */
    public boolean accepte(Holder<Item> item, @Nullable Component nomPerso) {
        return !Settings.trieur() || this.whitelist == crible().designe(item, nomPerso);
    }

    /** Le filtre compilé, recompilé paresseusement après un changement. */
    Crible crible() {
        Crible c = this.crible;
        if (c == null) {
            c = Crible.compiler(this.filtre);
            this.crible = c;
        }
        return c;
    }

    /** Les dix-huit cases, analysées une fois : objets des gabarits d'un côté, motifs compilés de l'autre. */
    record Crible(Item[] gabarits, Motif[] motifs) {
        static Crible compiler(SimpleContainer filtre) {
            List<Item> gabarits = new ArrayList<>();
            List<Motif> motifs = new ArrayList<>();
            for (ItemStack contenu : filtre.getItems()) {
                if (contenu.isEmpty()) {
                    continue;
                }
                if (Motif.estMotif(contenu)) {
                    Motif motif = Motif.compiler(Motif.texte(contenu));
                    if (!motif.estVide()) {
                        motifs.add(motif);
                    }
                } else if (!gabarits.contains(contenu.getItem())) {
                    gabarits.add(contenu.getItem());
                }
            }
            return new Crible(gabarits.toArray(new Item[0]), motifs.toArray(new Motif[0]));
        }

        boolean designe(Holder<Item> item, @Nullable Component nomPerso) {
            Item valeur = item.value();
            for (Item gabarit : this.gabarits) {
                if (gabarit == valeur) {
                    return true;
                }
            }
            for (Motif motif : this.motifs) {
                if (motif.correspond(item, nomPerso)) {
                    return true;
                }
            }
            return false;
        }
    }

    // --- Poignées de transfert (voir TrieurTransfert) --------------------------

    /**
     * Ce Trieur vu par l'API de transfert : l'enveloppe NeoForge standard d'un conteneur vanilla, la
     * même que celle que {@link TrieurCablage} expose aux autres blocs. Son insertion passe par
     * {@link #canPlaceItem}, donc par le filtre — impossible d'y entrer sans y avoir droit.
     */
    ResourceHandler<ItemResource> poignee() {
        ResourceHandler<ItemResource> p = this.poignee;
        if (p == null) {
            p = VanillaContainerWrapper.of(this);
            this.poignee = p;
        }
        return p;
    }

    /**
     * La capacité d'objets du bloc au-dessus, vue par sa face du bas. {@link BlockCapabilityCache} :
     * NeoForge l'invalide tout seul quand ce bloc change — aucune recherche de bloc-entité à chaque
     * tick, c'est tout le coût à vide d'un Trieur. Le cache se retire de lui-même une fois ce Trieur
     * cassé ({@code isRemoved}).
     */
    @Nullable ResourceHandler<ItemResource> capaciteHaut(ServerLevel level) {
        if (this.cacheHaut == null) {
            this.cacheHaut = BlockCapabilityCache.create(Capabilities.Item.BLOCK, level,
                    this.worldPosition.above(), Direction.DOWN, () -> !this.isRemoved(), () -> {});
        }
        return this.cacheHaut.getCapability();
    }

    /** Même chose vers l'avant ({@code facing}), vue par la face qui regarde ce Trieur. */
    @Nullable ResourceHandler<ItemResource> capaciteDevant(ServerLevel level, Direction facing) {
        if (this.cacheDevant == null || this.sensDevant != facing) {
            this.cacheDevant = BlockCapabilityCache.create(Capabilities.Item.BLOCK, level,
                    this.worldPosition.relative(facing), facing.getOpposite(), () -> !this.isRemoved(), () -> {});
            this.sensDevant = facing;
        }
        return this.cacheDevant.getCapability();
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        this.cacheHaut = null;
        this.cacheDevant = null;
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
        input.child(FILTER_KEY).ifPresent(child -> {
            // clear() d'une NonNullList à valeur par défaut remet chaque case à vide sans la retirer :
            // un « /data merge » qui réécrit le filtre ne garde pas les anciennes cases absentes.
            this.filtre.getItems().clear();
            ContainerHelper.loadAllItems(child, this.filtre.getItems());
        });
        // loadAllItems écrit dans la liste sans passer par setItem : l'invalidation se fait ici.
        this.crible = null;
    }
}
