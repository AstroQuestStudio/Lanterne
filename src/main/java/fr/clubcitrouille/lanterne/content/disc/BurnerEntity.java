package fr.clubcitrouille.lanterne.content.disc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jspecify.annotations.Nullable;

/**
 * Ce que le graveur retient : un disque, et où en est la gravure.
 *
 * <h2>La règle qui prime sur toutes les autres</h2>
 *
 * <p><b>Le disque vierge revient toujours.</b> Quoi qu'il arrive — lien mort, conversion ratée,
 * serveur sans sillon libre, joueur déconnecté au milieu, bloc cassé pendant la gravure — l'objet que
 * le joueur a mis dedans lui est rendu. Un mod qui fait disparaître un objet perd la confiance de
 * celui qui l'utilise, et il ne la retrouve pas.
 *
 * <p>C'est pourquoi le vierge <b>n'est jamais consommé au lancement</b> de la gravure : il reste dans
 * le bloc, et n'est remplacé qu'à la seconde exacte où le disque gravé est prêt. Entre les deux, tout
 * échec se contente de rendre {@link #burning} à moins un et le joueur retrouve son bien au clic
 * suivant. Il n'y a aucun instant où l'objet n'existe nulle part.
 *
 * <h2>Pourquoi l'état est dans le bloc et non dans une table</h2>
 *
 * <p>Une table statique « ce joueur grave à cette position » serait plus simple à écrire et fausse :
 * elle ne survivrait pas à un redémarrage, et un serveur arrêté pendant une gravure aurait mangé le
 * disque. Ici tout est sérialisé avec le chunk — l'objet, le titre visé, l'empreinte, le compte à
 * rebours. Un serveur redémarré reprend la gravure là où elle en était.
 */
public class BurnerEntity extends BlockEntity {
    /** Le disque présent : vierge au départ, gravé à l'arrivée. */
    private ItemStack held = ItemStack.EMPTY;

    /** Ticks restants, ou moins un si rien ne se grave. */
    private int burning = -1;

    /** L'empreinte du morceau en cours de gravure. */
    private String hash = "";

    /** Le titre voulu. */
    private String title = "";

    /** Le nom du graveur, pour le registre des sillons. */
    private String author = "";

    /** La durée réelle du morceau, en secondes. */
    private float seconds;

    public BurnerEntity(BlockPos pos, BlockState state) {
        super(Groove.BURNER_ENTITY.get(), pos, state);
    }

    public boolean empty() {
        return this.held.isEmpty();
    }

    public boolean busy() {
        return this.burning >= 0;
    }

    /** Un disque gravé attend-il d'être repris ? */
    public boolean holdsFinished() {
        return !this.held.isEmpty() && !this.held.is(Groove.BLANK.get());
    }

    public String pendingHash() {
        return this.hash;
    }

    public void load(ItemStack blank) {
        this.held = blank;
        this.mark(false);
    }

    /** Rend au joueur ce qu'il y a dedans. */
    public void eject(Player player) {
        if (this.held.isEmpty()) {
            return;
        }
        ItemStack out = this.held;
        this.held = ItemStack.EMPTY;
        if (!player.getInventory().add(out)) {
            player.drop(out, false);
        }
        this.mark(false);
        if (this.level != null) {
            this.level.playSound(null, this.getBlockPos(), SoundEvents.ITEM_FRAME_REMOVE_ITEM,
                    SoundSource.BLOCKS, 0.8F, 1.1F);
        }
    }

    /**
     * Lance la gravure.
     *
     * <p>Appelé seulement quand les octets sont déjà chez le serveur : le compte à rebours ne sert
     * qu'à donner du poids au geste, il ne couvre aucun travail réel. C'est volontaire — un compte à
     * rebours qui attendrait un téléchargement serait faux dès que le réseau varie, et le joueur
     * verrait une barre qui ment.
     */
    public void begin(String hash, String title, String author, float seconds) {
        this.hash = hash;
        this.title = title;
        this.author = author;
        this.seconds = seconds;
        this.burning = fr.clubcitrouille.lanterne.content.painting.Studio.discBurn() * 20;
        this.mark(true);
        if (this.level != null) {
            this.level.playSound(null, this.getBlockPos(), SoundEvents.GRINDSTONE_USE,
                    SoundSource.BLOCKS, 0.7F, 0.8F);
        }
    }

    /**
     * Abandonne la gravure sans toucher au disque.
     *
     * <p>Le vierge reste dedans. C'est toute la méthode, et c'est la raison d'être de la règle
     * énoncée en tête de classe : il n'y a rien à « rendre » puisqu'on n'a rien pris.
     */
    public void abandon() {
        this.burning = -1;
        this.hash = "";
        this.title = "";
        this.mark(false);
    }

    /**
     * Un tick de gravure. Serveur uniquement — voir {@code Burner.getTicker}.
     *
     * <p>Les fumées et le grincement sont émis par le serveur pour que <b>tout le monde</b> les voie
     * et les entende, pas seulement celui qui grave : une machine qui travaille est un évènement
     * public dans une base partagée.
     */
    public static void tick(Level level, BlockPos pos, BlockState state, BurnerEntity burner) {
        if (burner.burning < 0) {
            return;
        }
        burner.burning--;
        if (burner.burning % 20 == 0 && level instanceof ServerLevel server) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE,
                    pos.getX() + 0.5d, pos.getY() + 1.0d, pos.getZ() + 0.5d, 3, 0.15d, 0.05d, 0.15d, 0.01d);
            level.playSound(null, pos, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.35F, 1.6F);
        }
        if (burner.burning > 0) {
            return;
        }
        burner.finish(level, pos);
    }

    /**
     * La gravure aboutit : le vierge devient un disque.
     *
     * <p>Le sillon est choisi maintenant et non au lancement. Un sillon réservé pendant six secondes
     * serait un sillon perdu si la gravure échouait — et deux joueurs qui gravent en même temps sur
     * le même serveur se disputeraient des réservations plutôt que des sillons libres.
     */
    private void finish(Level level, BlockPos pos) {
        this.burning = -1;
        Slots.Cut cut = new Slots.Cut(this.hash, this.title, this.seconds, this.author);
        int slot = Groove.engrave(cut);
        if (slot < 0) {
            // Plus un sillon assez long. Le vierge reste ; le joueur le récupérera.
            this.abandon();
            return;
        }
        ItemStack pressed = Groove.pressed(level.registryAccess(), slot, this.title);
        if (pressed.isEmpty()) {
            Slots.release(slot);
            this.abandon();
            return;
        }
        this.held = pressed;
        this.hash = "";
        this.title = "";
        this.mark(false);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0F, 1.2F);
        Groove.proclaim(level);
    }

    /**
     * Met à jour l'état visible du bloc et demande la sauvegarde.
     *
     * <p>Les deux propriétés sont posées ensemble : {@code LOADED} change le modèle,
     * {@code WORKING} allume la machine <b>et décide de l'existence du ticker</b>. C'est pour cette
     * seconde raison qu'elle doit être écrite de façon fiable — un graveur resté à « travaille »
     * sans compte à rebours tournerait pour rien jusqu'à la fin des temps.
     */
    private void mark(boolean working) {
        this.setChanged();
        if (this.level == null) {
            return;
        }
        BlockState state = this.getBlockState()
                .setValue(Burner.LOADED, !this.held.isEmpty())
                .setValue(Burner.WORKING, working);
        if (state != this.getBlockState()) {
            this.level.setBlock(this.getBlockPos(), state, Block.UPDATE_ALL);
        }
    }

    /** Ce qui tombe quand on casse le bloc. Appelé par {@code Groove}. */
    public void spill(Level level, BlockPos pos) {
        if (!this.held.isEmpty()) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), this.held);
            this.held = ItemStack.EMPTY;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!this.held.isEmpty()) {
            output.store("held", ItemStack.CODEC, this.held);
        }
        output.putInt("burning", this.burning);
        output.putString("hash", this.hash);
        output.putString("title", this.title);
        output.putString("author", this.author);
        output.putFloat("seconds", this.seconds);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.held = input.read("held", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        this.burning = input.getIntOr("burning", -1);
        this.hash = input.getStringOr("hash", "");
        this.title = input.getStringOr("title", "");
        this.author = input.getStringOr("author", "");
        this.seconds = input.getFloatOr("seconds", 0.0f);
    }

    /** Le titre en cours, pour un message. */
    public @Nullable Component label() {
        return this.title.isEmpty() ? null : Component.literal(this.title);
    }

    /** Le composant de nom du disque gravé, utile aux essais. */
    public ItemStack peek() {
        return this.held;
    }

    /** Vrai si l'objet présent porte bien un morceau jouable. */
    public boolean playable() {
        return this.held.has(DataComponents.JUKEBOX_PLAYABLE);
    }
}
