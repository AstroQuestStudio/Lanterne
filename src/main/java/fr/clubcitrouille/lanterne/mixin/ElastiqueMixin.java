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
 * <h2>DÉSACTIVÉ sur la branche 26.3 — retiré de lanterne.mixins.json, faute d'avoir trouvé la vraie
 * cause</h2>
 *
 * <p>Au premier spawn d'un joueur, {@code lanterne$countSpeedRollback} plantait le serveur intégré :
 * {@code MixinTransformerError}, « Scanned 0 target(s) ». Deux corrections ont été tentées et ont
 * échoué <b>de façon identique</b> :
 *
 * <ol>
 *   <li>l'ancrage {@code @At(INVOKE, target = "Logger.warn(String, Object[])")} d'origine ;</li>
 *   <li>un ancrage {@code @At(CONSTANT, stringValue = "{} moved too quickly! {},{},{}")}, en
 *       supposant l'appel lui-même transformé par un passage tiers avant Mixin.</li>
 * </ol>
 *
 * <p>Les deux ont été vérifiées avant d'écrire une ligne — pas devinées. Le désassemblage
 * ({@code javap -c}) du jar de compilation ({@code minecraft-patched-26.3.0.3-beta-merged.jar})
 * montre, à l'intérieur même de {@code handleMovePlayer}, l'appel <em>et</em> la constante de texte
 * exactement sous la forme visée — une seule fois chacun, sans ambiguïté. Le patch officiel de
 * NeoForge pour ce fichier ({@code patches/.../ServerGamePacketListenerImpl.java.patch}, branche
 * {@code 26.3.x}) a aussi été lu en entier : aucune de ses modifications ne touche à cette zone du
 * code. Le seul coremod NeoForge nommé « method_redirector » présent dans le rapport de plantage a
 * été lu en entier lui aussi : il ne redirige que {@code finalizeSpawn}, rien qui touche au
 * mouvement.
 *
 * <p>Autrement dit : tout ce qui est vérifiable depuis ce laboratoire dit que la cible existe. Et
 * pourtant Mixin ne la trouve pas au chargement réel du jeu. La cause reste donc inconnue — un
 * décalage entre le jar de compilation et ce que le joueur charge réellement, ou un comportement de
 * Mixin qui échappe à cette méthode de vérification. Continuer à deviner coûterait un lancement et
 * un plantage de plus à chaque tentative ; le module est donc désactivé le temps de trouver une
 * vraie piste — voir aussi si {@code lanterne$countCoherenceRollback}, jamais atteint faute d'avoir
 * dépassé le premier échec, porte le même défaut.
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
// LANTERNE_MIXIN_DESACTIVE : absence de lanterne.mixins.json volontaire, voir la javadoc
// ci-dessus. tools/verifie_mixins.py reconnaît ce jeton et n'en fait pas un échec.
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
     * Depuis combien de temps la position de référence est-elle figée ?
     *
     * <p>L'horloge est lue ici, et non dans {@link Elastique}, pour que la loi elle-même reste une
     * fonction pure de cette durée : c'est ce qui rend l'épreuve {@code Amarre} capable de la
     * balayer borne par borne sans dépendre d'un aléa de planification.
     *
     * <p>Zéro tant qu'aucune référence n'a été figée — le retard vaut alors un, et rien n'est
     * élargi. On n'accorde pas de tolérance sur une mesure qu'on n'a pas prise.
     */
    @Unique
    private long lanterne$frozenNanos() {
        return this.lanterne$anchorNanos == 0L ? 0L : System.nanoTime() - this.lanterne$anchorNanos;
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
        boolean armed = Settings.elastique();
        if (!armed && !Elastique.observing()) {
            return vanilla;
        }
        return Elastique.widenSpeed(vanilla, this.lanterne$frozenNanos(), armed);
    }

    /** Le même contrôle, pour un joueur en élytres — le jeu lui accorde déjà le triple. */
    @ModifyConstant(method = "handleMovePlayer", constant = @Constant(floatValue = 300.0F))
    private float lanterne$flyingAllowance(float vanilla) {
        boolean armed = Settings.elastique();
        if (!armed && !Elastique.observing()) {
            return vanilla;
        }
        return Elastique.widenSpeed(vanilla, this.lanterne$frozenNanos(), armed);
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
        boolean armed = Settings.elastique();
        // Un module eteint doit couter ZERO, et pas « presque zero » : sans cette garde, tout
        // serveur paierait une lecture d'horloge par paquet de mouvement pour un module qui est
        // eteint par defaut. Le drapeau d'observation n'est leve que par l'epreuve, qui a besoin
        // d'instrumenter son bras temoin.
        if (!armed && !Elastique.observing()) {
            return vanilla;
        }
        return Elastique.widenResidual(vanilla, this.lanterne$frozenNanos(), armed);
    }

    /**
     * Le contrôle de vitesse vient de renvoyer un joueur en arrière.
     *
     * <h2>Le compteur qui regardait la mauvaise branche</h2>
     *
     * <p>Il n'y avait d'abord qu'un seul compteur, posé sur la branche de cohérence ci-dessous. Le
     * premier relevé de l'épreuve a annoncé <b>zéro</b> retour en arrière pendant que le journal du
     * serveur, lui, répétait « moved too quickly ». Les deux branches sont distinctes : celle-ci
     * téléporte et <em>sort de la méthode</em> sans jamais atteindre l'autre.
     *
     * <p>La leçon est celle que ce dépôt répète : un compteur qui ne peut pas monter ne prouve pas
     * que la chose n'arrive pas — il prouve qu'on ne la regardait pas.
     *
     * <h2>Pourquoi l'ancrage vise la chaîne du message, et non l'appel lui-même</h2>
     *
     * <p>La première version visait directement {@code warn(String, Object[])} — le désassemblage de
     * la 26.2 confirmait bien un seul appel de cette forme dans la méthode. Sur la 26.3, ce même appel
     * existe toujours, identique, dans le jar de compilation ; et pourtant l'ancrage échouait au
     * démarrage, « Scanned 0 target(s) » — le signe d'une classe transformée une fois de plus avant
     * que Mixin n'y pose le sien, par un passage que le jar de compilation seul ne montre pas.
     *
     * <p>Une constante de texte encaissé dans le bytecode résiste à ce genre de remaniement bien
     * mieux qu'une signature d'appel : rien n'a de raison de réécrire le message d'avertissement
     * lui-même, quand la forme de l'appel qui le porte peut changer d'un passage de transformation à
     * l'autre.
     */
    @Inject(method = "handleMovePlayer",
            at = @At(value = "CONSTANT", args = "stringValue={} moved too quickly! {},{},{}"))
    private void lanterne$countSpeedRollback(CallbackInfo callback) {
        Elastique.countSpeedRollback();
    }

    /**
     * Le contrôle de cohérence vient de renvoyer un joueur en arrière.
     *
     * <p>Compté même lorsque le module est éteint, et c'est délibéré : c'est le <b>témoin</b> de
     * l'épreuve. Sans un relevé du même compteur dans le bras sans protection, il n'y aurait rien à
     * comparer, et le banc ne pourrait pas échouer.
     */
    @Inject(method = "handleMovePlayer",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/level/ServerPlayer;removeLatestMovementRecording()V"))
    private void lanterne$countCoherenceRollback(CallbackInfo callback) {
        Elastique.countCoherenceRollback();
    }
}
