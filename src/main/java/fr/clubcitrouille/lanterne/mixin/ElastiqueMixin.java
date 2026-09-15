package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

import fr.clubcitrouille.lanterne.core.Elastique;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les trois seuils de mouvement, mesurés à l'horloge plutôt qu'au tick.
 *
 * <p>Le raisonnement entier — pourquoi ces trois nombres et pas d'autres, pourquoi un carré, et
 * pourquoi cela n'ouvre pas la porte aux tricheurs — est dans {@link Elastique}. Ici, seulement les
 * points d'accroche et ce qui les rend fragiles.
 *
 * <h2>Pourquoi l'horloge démarre sur {@code resetPosition}</h2>
 *
 * <p>Il aurait été plus naturel de chronométrer {@code tickPlayer}. C'est pourtant
 * {@code resetPosition()} qu'il faut suivre, parce que c'est <b>exactement</b> la méthode qui fige
 * {@code firstGoodX/Y/Z} — la position de référence contre laquelle tout le reste sera jugé.
 *
 * <p>Les deux coïncident au tick près, {@code tickPlayer()} appelant {@code resetPosition()} en
 * première ligne. Mais {@code ServerPlayer} l'appelle aussi lors des téléportations et des
 * changements de dimension, et dans ces cas-là la référence change <em>sans</em> qu'un tick se soit
 * écoulé. Suivre le tick donnerait alors une fenêtre plus longue que la vraie, donc une tolérance
 * plus large que méritée. Suivre la référence elle-même ne peut pas se tromper : <b>l'horloge part
 * d'où part la mesure</b>.
 *
 * <h2>Les trois constantes, et ce qui a été vérifié avant d'écrire</h2>
 *
 * <p>{@code @ModifyConstant} vise une valeur littérale dans le bytecode. Si la même valeur y figure
 * deux fois, l'injection les prend toutes les deux — en silence, et le contrôleur de mixins ne le
 * verrait pas : il vérifie les signatures, pas les points d'ancrage.
 *
 * <p>Le désassemblage de {@code handleMovePlayer} a donc été lu avant d'écrire une ligne. Les trois
 * valeurs y figurent <b>une fois chacune</b> : {@code float 300.0f} au décalage 471,
 * {@code float 100.0f} au 477, {@code double 0.0625d} au 793. Les autres littéraux de la méthode —
 * {@code -0.5d}, {@code 0.5d}, {@code -0.03125d} — ne sont pas visés et ne partagent aucune valeur
 * avec eux.
 *
 * <h2>Le comptage des retours en arrière, et le piège qu'il a failli être</h2>
 *
 * <p>La branche du jeu qui renvoie le joueur en arrière n'a pas de nom. On la reconnaît à
 * {@code this.player.removeLatestMovementRecording()}, qui n'apparaît qu'à cet endroit de la
 * méthode : c'est un marqueur sémantique et non un ordinal, donc il survivra à un remaniement du
 * corps de la méthode.
 *
 * <p>Le piège tenait à l'écriture de la cible. Le code du jeu lit
 * {@code Entity.removeLatestMovementRecording()}, et il était tentant d'écrire {@code Entity} dans
 * la signature. Le bytecode, lui, porte
 * {@code net/minecraft/server/level/ServerPlayer.removeLatestMovementRecording:()V} — javac émet
 * l'appel sur le type <em>déclaré</em> du receveur, pas sur celui qui déclare la méthode. Une cible
 * écrite d'après le code source aurait compilé, passé le contrôleur de mixins, et <b>raté son
 * ancrage au démarrage</b>. Elle a été relevée au désassembleur, et c'est la seule façon de ne pas
 * se tromper.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ElastiqueMixin {
    /**
     * Instant du dernier gel de la position de référence, en nanosecondes.
     *
     * <p>Zéro tant que rien n'a été figé — {@link Elastique#lateness} le traite comme « aucun
     * retard », ce qui est le choix prudent : on n'élargit rien sur une mesure qu'on n'a pas.
     */
    @Unique
    private long lanterne$anchorNanos;

    @Inject(method = "resetPosition", at = @At("TAIL"))
    private void lanterne$anchor(CallbackInfo callback) {
        this.lanterne$anchorNanos = System.nanoTime();
    }

    /**
     * Le contrôle de vitesse, pour un joueur au sol.
     *
     * <p>Rendu tel quel quand le module est éteint : pas une multiplication par un, la valeur
     * d'origine elle-même. C'est ce qui permet d'affirmer, et de vérifier, qu'un serveur sain se
     * comporte comme sans le mod.
     */
    @ModifyConstant(method = "handleMovePlayer", constant = @Constant(floatValue = 100.0F))
    private float lanterne$walkingAllowance(float vanilla) {
        return Settings.elastique() ? Elastique.widenSpeed(vanilla, this.lanterne$anchorNanos) : vanilla;
    }

    /** Le même contrôle, pour un joueur en élytres — le jeu lui accorde déjà le triple. */
    @ModifyConstant(method = "handleMovePlayer", constant = @Constant(floatValue = 300.0F))
    private float lanterne$flyingAllowance(float vanilla) {
        return Settings.elastique() ? Elastique.widenSpeed(vanilla, this.lanterne$anchorNanos) : vanilla;
    }

    /**
     * Le contrôle de cohérence : l'écart toléré entre la position annoncée et celle que la physique
     * du serveur a calculée.
     *
     * <p>C'est celui-ci qui produit l'élastique, et c'est aussi le seul des trois dont
     * l'élargissement laisse passer quelque chose de réel. Son plafond est donc bien plus serré —
     * voir {@link Elastique}.
     */
    @ModifyConstant(method = "handleMovePlayer", constant = @Constant(doubleValue = 0.0625D))
    private double lanterne$coherenceAllowance(double vanilla) {
        return Settings.elastique()
                ? Elastique.widenResidual(vanilla, this.lanterne$anchorNanos)
                : vanilla;
    }

    /**
     * Un retour en arrière a tout de même eu lieu.
     *
     * <p>Compté même lorsque le module est éteint, et c'est délibéré : c'est le <b>témoin</b> de
     * l'épreuve. Sans un relevé du même compteur dans le bras sans protection, il n'y aurait rien à
     * comparer, et le banc ne pourrait pas échouer.
     */
    @Inject(method = "handleMovePlayer",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/level/ServerPlayer;removeLatestMovementRecording()V"))
    private void lanterne$countRollback(CallbackInfo callback) {
        Elastique.countRollback();
    }
}
