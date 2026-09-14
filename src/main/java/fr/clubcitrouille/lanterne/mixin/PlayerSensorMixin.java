package fr.clubcitrouille.lanterne.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.player.Player;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le capteur de joueurs, sans ses trois pipelines de flux.
 *
 * <h2>Ce que vanilla fait, par créature et par relève</h2>
 *
 * <p>{@code PlayerSensor.doTick} construit <b>trois</b> pipelines de flux successifs, dont le
 * premier comporte un tri :
 *
 * <pre>
 * level.players().stream().filter().filter().sorted().collect()   // + un TRI
 * players.stream().filter().collect()
 * visiblePlayers.stream().filter().toList()
 * </pre>
 *
 * <p>Chaque pipeline alloue son objet de flux, ses maillons, ses lambdas capturantes et sa liste de
 * sortie. Ce capteur équipe <b>toute créature à cerveau</b> — villageois, piglins, axolotls,
 * gardiens — et se relève toutes les quelques ticks. Sur six cents villageois, cela fait mille huit
 * cents pipelines par relève.
 *
 * <h2>Pourquoi c'est ce capteur-là et pas un autre</h2>
 *
 * <p>Le profileur a désigné la machinerie de flux comme premier poste du serveur — vingt-quatre
 * pour cent du temps dans {@code AbstractPipeline}. Restait à savoir d'où elle venait : trois
 * tentatives sur le cerveau lui-même n'avaient rien rendu, parce que 26.1 en a déjà retiré les
 * flux. Les <b>capteurs</b>, eux, en ont gardé — et {@code PlayerSensor} en a trois, là où les
 * autres en ont un.
 *
 * <p>{@code TimSort.binarySort} figurait d'ailleurs dans le tout premier profil de ce projet, sans
 * qu'on sache alors quoi trier. C'était ce tri-ci.
 *
 * <h2>Ce qui est strictement conservé</h2>
 *
 * <ul>
 *   <li><b>L'ordre.</b> Les joueurs restent triés par distance croissante — c'est ce qui définit
 *       « le plus proche », et le reste du cerveau en dépend.</li>
 *   <li><b>Les quatre mémoires</b>, écrites dans le même ordre et avec les mêmes valeurs, y compris
 *       le {@code null} quand aucune liste ne convient.</li>
 *   <li><b>Les filtres</b>, appliqués dans le même ordre : les spectateurs d'abord, la distance
 *       ensuite, puis la visibilité, puis l'attaquabilité.</li>
 * </ul>
 *
 * <p>Seule la façon de parcourir change. Le tri emploie la même comparaison, sur une liste ordinaire
 * plutôt qu'à travers un flux.
 */
@Mixin(net.minecraft.world.entity.ai.sensing.PlayerSensor.class)
public abstract class PlayerSensorMixin extends Sensor<LivingEntity> {
    /**
     * La portée de suivi, redéclarée plutôt qu'appelée de l'extérieur.
     *
     * <p>{@code getFollowDistance} est {@code protected} : un transtypage vers {@code PlayerSensor}
     * ne suffit pas à l'atteindre depuis notre paquet. {@code @Shadow} la rend visible au mixin,
     * qui est fusionné dans la classe cible et y a donc les mêmes droits qu'elle.
     *
     * <p>Point important : ce sont les sous-classes qui la redéfinissent — le piglin, par exemple,
     * voit plus loin. Passer par l'appel virtuel préserve ces réglages ; recopier la valeur de la
     * classe de base les aurait tous écrasés.
     */
    @Shadow
    protected abstract double getFollowDistance(LivingEntity body);

    @Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
    private void lanterne$senseWithoutStreams(ServerLevel level, LivingEntity body,
            CallbackInfo callback) {
        if (!Settings.senses()) {
            return;
        }

        double range = this.getFollowDistance(body);
        List<Player> near = new ArrayList<>();
        for (Player player : level.players()) {
            if (EntitySelector.NO_SPECTATORS.test(player) && body.closerThan(player, range)) {
                near.add(player);
            }
        }
        // Le tri de vanilla, à l'identique : distance au carré, croissante.
        near.sort((left, right) -> Double.compare(body.distanceToSqr(left), body.distanceToSqr(right)));

        List<Player> visible = new ArrayList<>();
        for (Player player : near) {
            if (isEntityTargetable(level, body, player)) {
                visible.add(player);
            }
        }
        List<Player> attackable = new ArrayList<>();
        for (Player player : visible) {
            if (isEntityAttackable(level, body, player)) {
                attackable.add(player);
            }
        }

        Brain<?> brain = body.getBrain();
        brain.setMemory(MemoryModuleType.NEAREST_PLAYERS, near);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_PLAYER,
                visible.isEmpty() ? null : visible.get(0));
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYERS, attackable);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER,
                attackable.isEmpty() ? null : attackable.get(0));
        callback.cancel();
    }
}
