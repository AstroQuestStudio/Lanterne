package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayerGameMode;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le bloc qui ne casse pas quand le serveur est en retard.
 *
 * <h2>Le symptôme, et sa cause exacte</h2>
 *
 * <p>On mine vite, et le bloc ne part pas : il reste là, ou réapparaît après coup. Ce n'est ni le
 * client, ni la latence, ni le matériel. C'est une divergence d'horloge, et elle tient en trois
 * lignes de {@code ServerPlayerGameMode} :
 *
 * <pre>
 * this.gameTicks++;                                              // une fois par tick SERVEUR
 * int ticksSpentDestroying = this.gameTicks - this.destroyProgressStart;
 * float destroyProgress = state.getDestroyProgress(…) * (ticksSpentDestroying + 1);
 * if (destroyProgress &gt;= 0.7F) { destroyAndAck(…); }
 * </pre>
 *
 * <p>Le serveur mesure la progression du minage en <b>ticks serveur</b>. Le client, lui, la mesure en
 * <b>ses propres ticks</b>, qui tournent à vingt par seconde quoi qu'il arrive.
 *
 * <p>Un serveur à quinze ticks par seconde accumule donc quinze unités de progression là où le client
 * en accumule vingt, pour la même seconde de bouton maintenu. <b>Vingt-cinq pour cent de progression
 * en moins</b> — souvent juste sous le seuil de sept dixièmes. Le client annonce « c'est cassé », le
 * serveur répond « non », et renvoie le bloc.
 *
 * <h2>La correction : compter le temps qui passe, pas les tours qu'on a faits</h2>
 *
 * <p>On fait avancer le compteur à l'<b>horloge réelle</b>, à raison d'une unité toutes les cinquante
 * millisecondes. Les deux bouts mesurent alors la même chose, et tombent d'accord — quel que soit le
 * nombre de ticks que le serveur a réellement réussi à faire.
 *
 * <h2>Ce que cette correction n'est pas</h2>
 *
 * <p><b>Ce n'est pas un assouplissement du seuil.</b> Les sept dixièmes restent. Un client qui
 * mentirait sur sa vitesse de minage serait refusé exactement comme avant : la référence reste
 * l'horloge du serveur, jamais une affirmation du client. On corrige une unité de mesure, pas une
 * tolérance.
 *
 * <p><b>Ce n'est pas non plus un remplacement des autres modules.</b> Le reste du mod fait que le
 * serveur ne prend pas de retard ; celui-ci fait que le symptôme n'apparaît pas <em>même quand il en
 * prend</em> — une panne de courant, un ramassage mémoire, un autre mod qui bloque le fil. Les deux
 * sont nécessaires, et pour des raisons différentes.
 *
 * <h2>Le cas du gel prolongé</h2>
 *
 * <p>Si le serveur se fige cinq secondes, le compteur bondit de cent unités, et un bloc en cours de
 * minage s'achève d'un coup au dégel. C'est le comportement <b>juste</b> : le joueur a bel et bien
 * tenu le bouton pendant cinq secondes, et c'est exactement ce que son client a compté. Vanilla, lui,
 * aurait perdu ces cinq secondes.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class MiningMixin {
    @Shadow
    private int gameTicks;

    /** Instant du premier tick observé, origine de l'horloge de ce joueur. */
    @Unique
    private long lanterne$origin;

    @Inject(method = "tick", at = @At("HEAD"))
    private void lanterne$countRealTime(CallbackInfo callback) {
        if (!Settings.mining()) {
            return;
        }
        long now = System.nanoTime();
        if (lanterne$origin == 0L) {
            lanterne$origin = now;
            return;
        }
        // Vanilla fait « gameTicks++ » juste après ce point d'accroche. On pose donc la valeur
        // DIMINUÉE DE UN, pour que son incrément tombe exactement sur le compte voulu. Poser la
        // valeur finale ici la ferait dépasser d'une unité à chaque tick, et le minage deviendrait
        // deux fois trop rapide — une correction pire que le défaut.
        long elapsed = (now - lanterne$origin) / 50_000_000L;
        this.gameTicks = (int) Math.max(0L, elapsed) - 1;
    }
}
