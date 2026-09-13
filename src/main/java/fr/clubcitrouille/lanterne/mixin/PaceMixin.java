package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.world.entity.Entity;

/**
 * La cadence d'une entité, retenue le temps d'un tick.
 *
 * <h2>Le mod se retrouvait dans son propre profil</h2>
 *
 * <p>Le profil de la charge de référence, mod actif, désigne au deuxième rang :
 *
 * <pre>
 * 16,5 %  PalettedContainer.get
 *  5,7 %  ServerEntity.handler$…$lanterne$throttleUpdates
 * </pre>
 *
 * <p>Ce second poste est le <b>nôtre</b>. {@code NetworkThrottle.shouldSend} refait, pour décider s'il
 * faut envoyer un paquet, le calcul que {@code EntityThrottle.shouldTick} vient d'achever dans le même
 * tick pour la même entité : une lecture de table pour la distance, puis la formule de cadence.
 *
 * <p>Le serveur parcourt ses entités deux fois par tick, à deux endroits sans rapport — une fois pour
 * les simuler, une fois pour les diffuser. Le calcul, lui, ne dépend que de la position et du tick :
 * il donnera forcément la même réponse.
 *
 * <h2>Un champ plutôt qu'une table</h2>
 *
 * <p>La tentation serait une table indexée par identifiant, comme le mod en tient déjà plusieurs. Ce
 * serait remplacer un calcul par une recherche — un hachage, un accès mémoire indirect — pour un gain
 * qui pourrait très bien être nul. Ce projet a déjà retiré deux modules pour cette exacte raison.
 *
 * <p>Deux champs posés sur l'entité elle-même coûtent, eux, un accès direct. Douze octets par entité,
 * ce qui reste modeste au regard des centaines qu'elle occupe déjà.
 *
 * <p>Le tick est mémorisé avec la valeur : une cadence retenue au tick précédent ne vaut plus rien, et
 * la relire serait pire que de la recalculer. {@code -1} marque l'absence de valeur — jamais
 * {@code Long.MIN_VALUE}, sur lequel ce projet a perdu six bancs à cause d'un débordement.
 */
@Mixin(Entity.class)
public abstract class PaceMixin implements fr.clubcitrouille.lanterne.core.PaceHolder {
    @Unique
    private int lanterne$pace;

    @Unique
    private long lanterne$pacedAt = -1L;

    @Override
    public int lanterne$paceAt(long tick) {
        return lanterne$pacedAt == tick ? lanterne$pace : 0;
    }

    @Override
    public void lanterne$rememberPace(int period, long tick) {
        lanterne$pace = period;
        lanterne$pacedAt = tick;
    }
}
