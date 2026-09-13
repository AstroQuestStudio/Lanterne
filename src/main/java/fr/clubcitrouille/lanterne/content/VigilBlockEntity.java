package fr.clubcitrouille.lanterne.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Watch;

/**
 * Le bloc-entité de la Lanterne : il ne fait rien, et c'est tout son intérêt.
 *
 * <h2>Un bloc-entité sans ticker ne coûte rien</h2>
 *
 * <p>Ajouter un bloc-entité dans un mod d'optimisation demande une justification. Celui-ci n'en a
 * qu'une, et elle est suffisante : <b>il n'a pas de ticker</b>. Le jeu ne l'appelle jamais. Il existe
 * pour deux choses, et deux seulement — être sérialisé avec son chunk, et prévenir quand il arrive et
 * quand il part.
 *
 * <p>C'est ce qui donne la persistance gratuitement. Une Lanterne posée avant un redémarrage garde
 * son chunk après, sans qu'aucune donnée de monde n'ait à être écrite par ce mod, sans fichier
 * annexe, et sans code de restauration à faire fonctionner.
 *
 * <h2>L'alternative qu'on a écartée, et pourquoi</h2>
 *
 * <p>On pouvait s'en passer : {@code PalettedContainer.maybeHas} teste la <em>palette</em> d'une
 * section — seize entrées au plus — et non ses quatre mille quatre-vingt-seize cases. Demander « ce
 * chunk contient-il une Lanterne ? » aurait donc coûté vingt-quatre consultations de palette, sans
 * aucun bloc-entité.
 *
 * <p>Mais une palette garde ses <b>entrées mortes</b> : le jeu ne la resserre qu'à l'écriture sur le
 * disque. Une Lanterne cassée y resterait inscrite, et son chunk resterait gardé — des monstres
 * cesseraient d'apparaître dans un chunk où plus rien ne les en empêche, jusqu'à la prochaine
 * sauvegarde. Un bloc qu'on a retiré doit cesser d'agir <b>à l'instant où on le retire</b>.
 *
 * <p>Le bloc-entité coûte quelques octets par Lanterne posée et répond juste. C'est le bon échange.
 */
public class VigilBlockEntity extends BlockEntity {
    /**
     * Le chunk déclaré à la garde.
     *
     * <p>On retient ce qu'on a <em>déclaré</em>, au lieu de le recalculer au départ. Les deux
     * devraient donner le même résultat — et « devraient » est précisément le mot qui laisse passer
     * les comptes qui dérivent. Un bloc-entité déplacé par un piston, une position lue avant que le
     * niveau ne soit posé : il suffit d'un cas pour que la garde ne soit jamais relâchée, et le chunk
     * resterait calme pour toujours sans que rien ne l'explique.
     */
    private ChunkPos declared;

    public VigilBlockEntity(BlockPos pos, BlockState state) {
        super(Contents.VIGIL_ENTITY.get(), pos, state);
    }

    /**
     * Prend la garde.
     *
     * <p>Appelé quand le bloc-entité entre dans un monde — à la pose comme au chargement du chunk.
     * Côté client, il n'y a rien à garder : l'apparition des créatures est une affaire de serveur.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level == null || this.level.isClientSide() || !Settings.vigil()) {
            return;
        }
        this.declared = ChunkPos.containing(this.worldPosition);
        Watch.guard(this.level.dimension(), this.declared);
    }

    /**
     * Rend la garde.
     *
     * <p>Appelé à la casse du bloc <b>et</b> au déchargement du chunk. Les deux doivent relâcher : un
     * chunk déchargé n'a plus de créatures à refuser, et le garder inscrit ferait enfler la table
     * d'un serveur qui explore.
     */
    @Override
    public void setRemoved() {
        if (this.level != null && !this.level.isClientSide() && this.declared != null) {
            Watch.release(this.level.dimension(), this.declared);
            this.declared = null;
        }
        super.setRemoved();
    }
}
