package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Census;

/**
 * L'observateur simulé : un joueur pour le monde, personne pour le réseau.
 *
 * <h2>Le blocage qu'il lève</h2>
 *
 * <p>Le premier banc de ce mod a rendu un verdict sans appel : <b>perte, ×0,65</b>. Et il avait
 * raison sur les chiffres tout en mesurant la mauvaise chose — une ligne du rapport disait
 * « entités dans le monde : 0 » alors qu'on venait d'en créer cinq mille.
 *
 * <p>L'explication tient à la façon dont Minecraft décide de simuler : <b>seuls les chunks
 * maintenus par un ticket vivent</b>. Un serveur sans joueur n'en pose aucun, les chunks se
 * déchargent, et les entités partent avec eux. Le banc n'avait donc mesuré que le coût du
 * recensement sur un monde vide — une information utile, mais pas celle qu'on cherchait.
 *
 * <p>Conclusion : <b>on ne peut rien mesurer sans joueur</b>, et faire venir un humain à chaque
 * essai revient à ne jamais mesurer.
 *
 * <h2>Ce que c'est, exactement</h2>
 *
 * <p>Pas une fausse connexion, pas un client automatisé : un simple porteur de tickets. La classe
 * pose autour de sa position le ticket {@link TicketType#PLAYER_SIMULATION}, qui est <b>celui-là
 * même qu'un vrai joueur pose</b>. Le serveur ne fait donc aucune différence — il charge les
 * chunks, tick les entités, fait tourner les fermes, exactement comme si quelqu'un était là.
 *
 * <p>Ce qu'il ne fait pas : aucune connexion réseau, aucune entité joueur, aucun rendu. C'est
 * précisément ce qui permet d'en poser cent sans coûter cent clients.
 *
 * <h2>Pourquoi cela dépasse le banc</h2>
 *
 * <p>Cent observateurs répartis sur une carte reproduisent la charge d'un serveur à cent joueurs sur
 * une machine qui n'en héberge aucun. C'est le seul moyen honnête de répondre à « combien de joueurs
 * cette machine tient-elle ? » avant d'avoir cent joueurs à décevoir — et donc le seul moyen de
 * travailler pour une cible qu'on n'a pas encore.
 */
public final class Phantom {
    /**
     * Rayon des tickets, en chunks.
     *
     * <p>Aligné sur ce qu'un joueur réel obtient : la distance de simulation, et non la distance de
     * vue. Charger plus rendrait la mesure plus lourde que la réalité, charger moins la rendrait
     * flatteuse — les deux la rendraient inutile.
     */
    private final int radius;
    private double x;
    private double z;
    private ChunkPos held;

    private static final List<Phantom> CROWD = new ArrayList<>();

    private Phantom(double x, double z, int radius) {
        this.x = x;
        this.z = z;
        this.radius = radius;
    }

    // ------------------------------------------------------------------ la foule

    /**
     * Peuple le monde d'observateurs répartis sur une grille.
     *
     * <p>La grille, plutôt qu'un tas : cent joueurs au même endroit partagent leurs chunks et ne
     * chargent donc presque rien, ce qui donnerait une mesure absurdement optimiste. Répartis, ils
     * chargent chacun leur région — c'est la situation coûteuse, et c'est celle qu'il faut mesurer.
     *
     * @param spacing distance entre deux observateurs, en blocs
     */
    public static void summon(ServerLevel level, int count, int spacing, int radius) {
        dismiss(level);

        int side = (int) Math.ceil(Math.sqrt(count));
        double origin = -(side - 1) * spacing / 2d;

        for (int i = 0; i < count; i++) {
            double px = origin + (i % side) * spacing;
            double pz = origin + (i / side) * spacing;
            Phantom phantom = new Phantom(px, pz, radius);
            phantom.hold(level);
            CROWD.add(phantom);
        }

        publish();
        Lanterne.LOG.info("{} observateur(s) posé(s), espacés de {} blocs, rayon {} chunks.",
                CROWD.size(), spacing, radius);
    }

    /**
     * Transmet les positions au recensement.
     *
     * <p>Le tableau est réalloué à chaque publication, ce qui est sans importance : cela n'arrive
     * qu'à la création de la foule ou à un déplacement de chunk, jamais dans un chemin chaud.
     */
    public static void publish() {
        double[] flat = new double[CROWD.size() * 2];
        for (int i = 0; i < CROWD.size(); i++) {
            flat[i * 2] = CROWD.get(i).x;
            flat[i * 2 + 1] = CROWD.get(i).z;
        }
        Census.setProbes(flat, CROWD.size());
    }

    /** Retire tous les observateurs et rend leurs chunks au serveur. */
    public static void dismiss(ServerLevel level) {
        for (Phantom phantom : CROWD) {
            phantom.release(level);
        }
        CROWD.clear();
        publish();
    }

    public static List<Phantom> crowd() {
        return CROWD;
    }

    public static int count() {
        return CROWD.size();
    }

    // ------------------------------------------------------------------ un observateur

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    /**
     * Déplace l'observateur, en reprenant ses tickets là où il arrive.
     *
     * <p>Un joueur immobile ne charge jamais de nouveau chunk : il ne déclenche donc ni génération,
     * ni chargement depuis le disque, qui sont pourtant deux des postes les plus lourds d'un
     * serveur. Faire marcher les observateurs est ce qui rend la charge réaliste.
     */
    public void moveTo(ServerLevel level, double newX, double newZ) {
        this.x = newX;
        this.z = newZ;
        ChunkPos wanted = new ChunkPos(
                net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(newX)),
                net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(newZ)));
        if (wanted.equals(held)) {
            return; // toujours dans le même chunk : les tickets en place conviennent
        }
        release(level);
        hold(level);
        publish();
    }

    /**
     * Pose les tickets — les <b>deux</b>, comme un vrai joueur.
     *
     * <h2>Le champ de bits qui ressemble à un niveau</h2>
     *
     * <p>Trois bancs de suite ont rendu « entités dans le monde : 0 » avec un observateur qui ne
     * posait que {@link TicketType#PLAYER_SIMULATION}. La déclaration en donne la raison, à
     * condition de savoir lire son troisième paramètre :
     *
     * <pre>
     * PLAYER_SIMULATION = register("player_simulation", 0L, 12);
     * PLAYER_LOADING    = register("player_loading",    0L, 2);
     *
     * public boolean doesLoad() { return (this.flags &amp; 2) != 0; }
     * </pre>
     *
     * <p>Ce n'est pas un niveau de chunk, c'est un <b>champ de bits</b>. Douze vaut huit plus
     * quatre : le bit deux — celui qui autorise le chargement — <b>en est absent</b>.
     * {@code PLAYER_SIMULATION} ne charge donc rien, il se contente de faire vivre ce qui est déjà
     * là. Les vaches étaient créées dans des chunks inexistants et disparaissaient dans le tick.
     *
     * <p>Un joueur réel pose les deux tickets, et c'est pourquoi cela fonctionne pour lui. Le
     * symptôme visible était éloquent une fois qu'on le regardait : la première vache apparaissait à
     * {@code y = -64}, parce que {@code getHeight} sur un chunk absent rend le fond du monde.
     */
    private void hold(ServerLevel level) {
        held = new ChunkPos(
                net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(x)),
                net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(z)));
        level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_LOADING, held, radius);
        level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_SIMULATION, held, radius);
    }

    private void release(ServerLevel level) {
        if (held != null) {
            level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_SIMULATION, held, radius);
            level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_LOADING, held, radius);
            held = null;
        }
    }
}
