package fr.clubcitrouille.lanterne.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * Le même gradient, appliqué aux paquets.
 *
 * <h2>Où meurt une connexion</h2>
 *
 * <p>{@code ServerEntity.sendChanges()} est appelé pour chaque entité suivie, à chaque tick, et
 * décide d'envoyer une position selon :
 *
 * <pre>
 * if (this.tickCount % this.updateInterval == 0 || entity.needsSync || entity.getEntityData().isDirty())
 * </pre>
 *
 * <p>Pour la plupart des créatures, {@code updateInterval} vaut trois : <b>sept paquets par seconde
 * et par entité</b>. Mille entités autour d'un joueur — ce qui n'a rien d'extraordinaire sur une
 * ferme — font sept mille paquets par seconde à destination de ce seul joueur.
 *
 * <p>Le processeur du serveur n'en souffre pas beaucoup. La connexion du joueur, si. Et c'est
 * exactement le symptôme décrit : le jeu « rame » sur une mauvaise ligne alors que le serveur se
 * porte bien, parce que le goulot n'est pas là où on le cherche.
 *
 * <h2>Pourquoi ce n'est pas de la triche visuelle</h2>
 *
 * <p>Le client <b>interpole</b> les positions qu'il reçoit : entre deux paquets, il continue le
 * mouvement de lui-même. Espacer les envois ne fige donc pas les créatures, cela réduit la
 * fréquence de correction de leur trajectoire devinée.
 *
 * <p>À dix blocs, cette correction se voit — une vache qui change de direction doit le faire
 * franchement. À cent cinquante blocs, elle occupe trois pixels et l'interpolation suffit largement.
 * C'est le même raisonnement que pour la simulation, appliqué à une autre ressource.
 *
 * <h2>Rien n'est perdu, seulement retardé</h2>
 *
 * <p>Le point qui rend la chose sûre : les drapeaux qui forcent un envoi — {@code needsSync}, les
 * données marquées sales — <b>ne sont pas consommés par un envoi sauté</b>. Ils restent levés, et
 * partiront au prochain passage. Une créature qui perd de la vie ou change d'état ne voit pas
 * l'information disparaître : elle la voit arriver quelques ticks plus tard.
 */
public final class NetworkThrottle {
    /**
     * Facteur d'espacement supplémentaire.
     *
     * <p>Un paquet coûte moins cher qu'un tick de simulation, et ses conséquences sont plus
     * visibles : on est donc plus prudent ici que dans {@link EntityThrottle}. Le niveau de détail
     * décide déjà d'un espacement ; on n'en ajoute que la moitié.
     */
    private static final int SOFTENING = 2;

    private NetworkThrottle() {}

    /**
     * Faut-il envoyer les changements de cette entité à ce tick ?
     *
     * @param entity   l'entité suivie
     * @param gameTime le tick courant du monde, pour l'étalement
     */
    public static boolean shouldSend(Entity entity, long gameTime) {
        if (!Settings.network() || mustAlwaysSend(entity)) {
            return true;
        }

        // La cadence a déjà été calculée pour cette entité, dans ce tick, par le tour de simulation.
        // On la relit : deux champs contre une lecture de table et une formule. Voir PaceMixin.
        int simulation = entity instanceof fr.clubcitrouille.lanterne.core.PaceHolder holder
                ? holder.lanterne$paceAt(gameTime)
                : 0;
        if (simulation == 0) {
            // L'entité n'est pas passée par le tour de simulation à ce tick — cas d'une entité que le
            // jeu diffuse sans la ticker. On calcule alors, comme avant.
            simulation = Cadence.forEntity(entity, Census.distanceOf(entity), TickBudget.pressure());
        }
        if (simulation <= 2) {
            return true; // à portée de vue utile, on n'économise rien sur le dos du joueur
        }

        // La moitié de la cadence de simulation, et jamais moins souvent qu'un envoi sur douze : un
        // paquet coûte moins cher qu'un tick, et son absence se voit davantage. On espace, on ne
        // coupe pas.
        int period = Math.min(12, Math.max(2, simulation / SOFTENING));
        return (gameTime + entity.getId()) % period == 0;
    }

    /**
     * Ce dont la position doit rester exacte à tout instant.
     *
     * <p>La liste est celle de {@link EntityThrottle}, pour une raison de fond : une entité dont on
     * ne peut pas espacer la simulation est une entité dont la trajectoire compte, et une
     * trajectoire qui compte doit être transmise. Les deux questions n'en font qu'une.
     *
     * <p>S'y ajoute le joueur lui-même — sa position est le seul paquet qu'aucune économie ne
     * justifie jamais.
     */
    private static boolean mustAlwaysSend(Entity entity) {
        return entity instanceof Player
                || entity instanceof Projectile
                || entity instanceof PrimedTnt
                || entity instanceof FallingBlockEntity
                || entity.isVehicle();
    }
}
