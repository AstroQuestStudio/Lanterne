package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Seize pour cent du processeur, rendus sans toucher au jeu.
 *
 * <h2>Ce que le profileur a trouvé</h2>
 *
 * <p>Sur cinq mille entités, le relevé donnait en tête, loin devant tout le reste :
 *
 * <pre>
 * 16,3 %  ThreadLocal$ThreadLocalMap.getEntryAfterMiss
 *
 * ── Qui l'appelle ──
 * 10,0 %  Profiler.stopUsing
 *  5,9 %  Profiler.get
 * </pre>
 *
 * <p>Un sixième du temps de calcul d'un serveur passe dans le <b>profileur intégré de Minecraft</b>,
 * c'est-à-dire dans un outil de mesure éteint que personne ne consulte. Ce n'est pas du travail de
 * jeu : c'est de la comptabilité sur du vide.
 *
 * <h2>D'où cela vient</h2>
 *
 * <pre>
 * public static ProfilerFiller get() {
 *     return ACTIVE_COUNT.get() == 0
 *         ? getDefaultFiller()
 *         : Objects.requireNonNullElseGet(ACTIVE.get(), Profiler::getDefaultFiller);
 * }
 * </pre>
 *
 * <p>Le serveur ouvre un {@code Profiler.use(...)} à chaque tick : {@code ACTIVE_COUNT} n'est donc
 * jamais nul pendant la simulation, et chaque appel traverse {@code ACTIVE.get()}. Or
 * {@code tickNonPassenger} appelle {@code Profiler.get()} <b>pour chaque entité</b> — cinq mille
 * recherches par tick, cent mille par seconde.
 *
 * <p>Le nom du symptôme achève le diagnostic : {@code getEntryAfterMiss} est le chemin <em>lent</em>
 * d'une variable de thread, celui qu'on emprunte quand l'entrée ne tombe pas directement dans la
 * table et qu'il faut la chercher de proche en proche. Le thread serveur en porte assez pour que ce
 * cas devienne la règle.
 *
 * <h2>Pourquoi un cache est correct, et à quelle condition</h2>
 *
 * <p>La valeur cherchée ne change que par {@code startUsing} et {@code stopUsing}. On peut donc la
 * retenir — à condition de savoir quand elle a changé, et c'est là qu'un cache naïf serait faux.
 *
 * <p>Compter les appels ne suffit pas : un cycle complet — ouverture puis fermeture — ramène
 * {@code ACTIVE_COUNT} à sa valeur initiale, et un cache indexé dessus rendrait joyeusement
 * l'ancien profileur. On compte donc les <b>changements</b> et non les utilisateurs : un compteur
 * qui ne fait que croître, incrémenté aux deux bouts. Deux états distincts ne peuvent alors jamais
 * porter le même numéro.
 *
 * <p>Le cache est également lié au thread qui l'a rempli. Deux threads ont deux profileurs, et se
 * prêter le cache de l'autre est exactement l'erreur que la variable de thread existe pour empêcher.
 *
 * <h2>Ce que cela ne change pas</h2>
 *
 * <p>La valeur rendue est identique à celle du jeu, au sens de l'identité d'objet. Quand le
 * profileur est réellement utilisé — la commande {@code /debug}, un outil externe —, le compteur de
 * génération bouge et le cache est ignoré. <b>Aucun comportement observable ne change</b> : on
 * supprime une recherche, pas une fonctionnalité.
 */
@Mixin(Profiler.class)
public abstract class ProfilerMixin {
    /**
     * Numéro de l'état courant du profileur. Ne décroît jamais.
     *
     * <p>Un simple compteur d'utilisateurs serait insuffisant : ouvrir puis fermer le ramènerait à
     * son point de départ alors que le profileur associé, lui, a changé.
     */
    private static int lanterne$generation;

    private static Thread lanterne$cachedThread;
    private static ProfilerFiller lanterne$cachedFiller;
    private static int lanterne$cachedGeneration = -1;

    @Inject(method = "startUsing", at = @At("RETURN"))
    private static void lanterne$noteStart(ProfilerFiller filler, CallbackInfo callback) {
        lanterne$generation++;
    }

    @Inject(method = "stopUsing", at = @At("RETURN"))
    private static void lanterne$noteStop(CallbackInfo callback) {
        lanterne$generation++;
    }

    /**
     * Le chemin rapide.
     *
     * <p>Deux comparaisons d'entiers et une d'identité, contre une recherche de proche en proche
     * dans la table de variables du thread. En cas de doute — thread différent, génération
     * différente — on laisse le jeu répondre et l'on retient sa réponse.
     */
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private static void lanterne$cachedGet(CallbackInfoReturnable<ProfilerFiller> callback) {
        // Soumis à l'interrupteur, comme le reste. Un mixin qui agirait dans les deux phases du banc
        // serait invisible à la comparaison : on mesurerait son effet des deux côtés, donc jamais.
        if (!Settings.profilerCache()) {
            return;
        }
        if (lanterne$cachedGeneration == lanterne$generation
                && lanterne$cachedThread == Thread.currentThread()
                && lanterne$cachedFiller != null) {
            callback.setReturnValue(lanterne$cachedFiller);
        }
    }

    @Inject(method = "get", at = @At("RETURN"))
    private static void lanterne$rememberGet(CallbackInfoReturnable<ProfilerFiller> callback) {
        lanterne$cachedThread = Thread.currentThread();
        lanterne$cachedFiller = callback.getReturnValue();
        lanterne$cachedGeneration = lanterne$generation;
    }
}
