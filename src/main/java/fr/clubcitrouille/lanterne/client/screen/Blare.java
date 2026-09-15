package fr.clubcitrouille.lanterne.client.screen;

import java.util.concurrent.CompletableFuture;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import fr.clubcitrouille.lanterne.content.screen.Screens;

/**
 * Le son d'un écran, tel que le moteur sonore le voit.
 *
 * <h2>Pourquoi il n'y a PAS de mixin, alors qu'il en fallait un pour les disques</h2>
 *
 * <p>C'est la bonne surprise de ce chantier, et elle méritait d'être cherchée avant d'écrire quoi que
 * ce soit. Les disques passent par {@code mixin.SoundDecodeMixin} parce que leur source est un
 * <b>fichier</b> : {@code SoundBufferLibrary} décide du décodeur d'après le chemin, sans regarder les
 * octets, et il faut donc intercepter cette décision.
 *
 * <p>Ici la source n'est pas un fichier. Et NeoForge ajoute à {@code SoundInstance} une méthode
 * {@code getStream(SoundBufferLibrary, Sound, boolean)} <b>qu'on a le droit de redéfinir</b> — c'est
 * elle que {@code SoundEngine} appelle, et non {@code SoundBufferLibrary} directement. Une instance
 * de son peut donc fournir son propre flux, sans toucher à une classe de vanilla et sans rien
 * partager avec les autres sons du jeu.
 *
 * <p>Le gain n'est pas seulement cosmétique : un mixin de plus sur le chemin sonore aurait été un
 * mixin de plus à vérifier à chaque version de Minecraft, sur une classe qu'un autre chantier de ce
 * dépôt occupe déjà.
 *
 * <h2>L'atténuation est coupée, et remplacée par la nôtre</h2>
 *
 * <p>Le moteur sonore décroît en carré inverse, ce qui est juste physiquement et mauvais pour un
 * écran : un cinéma s'entend du fond de la salle, et une décroissance physique le rendrait
 * inaudible à dix blocs pour être assourdissant à un. {@code Feed.loudness} applique une droite
 * jusqu'à la portée réglée, et c'est {@link #volume} qui la porte ici.
 *
 * <p>Il faut donc couper celle de vanilla, faute de quoi les deux se multiplieraient et le réglage
 * de portée n'aurait presque plus d'effet.
 */
public final class Blare extends AbstractTickableSoundInstance {
    private final Airwave wave;
    private final BlockPos anchor;

    private volatile float wanted;
    private volatile boolean done;

    Blare(BlockPos pos, Airwave wave) {
        super(Screens.SCREEN_SOUND.get(), SoundSource.RECORDS, RandomSource.create());
        this.wave = wave;
        this.anchor = pos;
        this.x = pos.getX() + 0.5d;
        this.y = pos.getY() + 0.5d;
        this.z = pos.getZ() + 0.5d;
        this.volume = 0f;
        // En boucle : le flux n'a pas de fin tant que la bobine vit, et un son non bouclé serait
        // arrêté par le moteur dès la première trame courte. C'est Airwave qui décide de la fin, en
        // rendant faux une seule fois.
        this.looping = true;
        this.delay = 0;
        this.attenuation = Attenuation.NONE;
        // « RECORDS » plutôt que « MASTER » : un joueur qui baisse le curseur « jukebox » dans ses
        // options s'attend à faire taire un écran, et c'est le seul curseur qui désigne quelque
        // chose qu'un bloc joue.
    }

    /**
     * Le flux, fourni par nous et non par le gestionnaire de tampons.
     *
     * <p>Le {@code Sound} passé est celui que la définition déclare — un silence d'une seconde, qui
     * n'existe que pour que le chargeur de sons ne rejette pas l'évènement. On ne s'en sert pas.
     */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound,
            boolean looping) {
        return CompletableFuture.completedFuture(this.wave);
    }

    /** Le volume voulu, calculé par {@code Feed.loudness} et posé à chaque tour de ronde. */
    void volume(float value) {
        this.wanted = value;
    }

    /** Coupe le son proprement. Le moteur le retirera à son prochain tour. */
    void finish() {
        this.done = true;
    }

    public BlockPos anchor() {
        return this.anchor;
    }

    @Override
    public void tick() {
        if (this.done) {
            stop();
            return;
        }
        // Un lissage, et pas une affectation. Le volume suit la distance du joueur, qui change à
        // chaque pas ; l'appliquer brutalement produit un crépitement à chaque tick sur un son
        // continu — le défaut classique d'un volume piloté par la position.
        this.volume += (this.wanted - this.volume) * 0.25f;
    }

    @Override
    public boolean canStartSilent() {
        // Sans cela, un écran dont on part avant le premier tick ne démarrerait jamais : le moteur
        // écarte à la construction tout son de volume nul, et le nôtre commence forcément à zéro.
        return true;
    }
}
