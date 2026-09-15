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

    /**
     * Instant qui sert d'origine à l'horloge de ce joueur.
     *
     * <h2>Pourquoi ce n'est pas « l'instant du premier tick »</h2>
     *
     * <p>C'était sa première définition, et elle portait un défaut qu'aucun banc ne pouvait voir,
     * parce qu'il ne se déclenche qu'au moment où le module <b>s'allume</b> — jamais quand il est
     * allumé depuis le début.
     *
     * <p>L'origine était posée au premier tick <em>où le module a la main</em>. Si le module
     * s'allume en cours de partie — un rechargement de configuration, l'écran de réglages, un banc
     * qui appelle {@code Settings.setEnabled} — le compteur de vanilla a déjà couru : il vaut le
     * nombre de ticks écoulés depuis l'arrivée du joueur, soit des dizaines de milliers. Le tick
     * suivant le ramenait à <b>un</b>.
     *
     * <p>Un compteur qui recule est un poison, et il ne s'évacue pas : la progression se lit
     * {@code gameTicks - destroyProgressStart}, donc <b>négative</b>, donc le bloc est refusé ; le
     * serveur le range alors en destruction différée, dont la progression est négative elle aussi
     * et n'atteindra jamais un. Or cette place est <b>unique</b> — {@code if (!hasDelayedDestroy)}
     * — et son tick passe avant celui du cassage en cours. Un seul recul, et plus rien ne se casse
     * jusqu'à ce que le compteur ait regagné tout le terrain perdu : vingt minutes de partie,
     * vingt minutes de forêt incassable.
     *
     * <p>L'origine est donc <b>calée sur le compteur de vanilla</b>, et recalée à chaque tick où le
     * module n'a pas la main. Allumer ou éteindre le module devient alors sans effet sur la valeur
     * du compteur : seule sa <em>façon d'avancer</em> change, ce qui est tout ce qu'on voulait.
     */
    @Unique
    private long lanterne$origin;

    /** Faux tant que l'origine n'a jamais été posée. {@code System.nanoTime()} peut valoir zéro. */
    @Unique
    private boolean lanterne$anchored;

    @Inject(method = "tick", at = @At("HEAD"))
    private void lanterne$countRealTime(CallbackInfo callback) {
        long now = System.nanoTime();
        if (!Settings.mining() || !lanterne$anchored) {
            // Caler l'origine de sorte que l'horloge reprenne EXACTEMENT là où le compteur de
            // vanilla en est. Tant que le module dort, on refait ce calage à chaque tour : le jour
            // où il se réveille, la valeur qu'il pose est la même que celle qu'il remplace.
            lanterne$origin = now - (long) this.gameTicks * 50_000_000L;
            lanterne$anchored = true;
            return;
        }
        // Vanilla fait « gameTicks++ » juste après ce point d'accroche. On pose donc la valeur
        // DIMINUÉE DE UN, pour que son incrément tombe exactement sur le compte voulu. Poser la
        // valeur finale ici la ferait dépasser d'une unité à chaque tick, et le minage deviendrait
        // deux fois trop rapide — une correction pire que le défaut.
        long elapsed = (now - lanterne$origin) / 50_000_000L;
        // <h2>Le maximum n'est pas une précaution de style</h2>
        //
        // Il interdit au compteur de reculer, quoi qu'il arrive à l'horloge — et c'est la seule
        // propriété dont dépend tout ce qui est écrit au-dessus. On compare les deux valeurs
        // AVANT l'incrément de vanilla, donc « moins un » des deux côtés : après son « ++ », le
        // compteur vaudra le plus grand de son ancienne valeur et du temps écoulé.
        //
        // Le cas qu'il traite vraiment est le rattrapage : quand le serveur a du retard, il
        // enchaîne plusieurs tours de boucle dans la même tranche de cinquante millisecondes.
        // L'horloge, elle, n'a pas bougé. Le compteur ne bouge donc pas non plus — ce qui est
        // juste, puisque le joueur n'a pas tenu son bouton plus longtemps pour autant — mais il ne
        // recule pas davantage.
        this.gameTicks = (int) Math.max((long) this.gameTicks - 1L, elapsed - 1L);
    }
}
