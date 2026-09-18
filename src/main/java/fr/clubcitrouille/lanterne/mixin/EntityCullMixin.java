package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Shroud;

/**
 * Le voile, posé à l'unique endroit où il économise autre chose que le dessin.
 *
 * <p>Le raisonnement complet est dans {@link Shroud}. Ce fichier ne fait que le brancher.
 *
 * <h2>Pourquoi envelopper l'appel plutôt qu'injecter dans la méthode appelée</h2>
 *
 * <p>{@code EntityRenderDispatcher.shouldRender} est appelé depuis plusieurs endroits du jeu —
 * l'aperçu d'entité d'une interface, le rendu d'un cadre, les vues hors monde. Un mixin posé sur la
 * méthode elle-même s'appliquerait à tous, y compris là où la notion de « caché par un mur » n'a
 * aucun sens et où la caméra passée n'est pas celle du joueur.
 *
 * <p>Envelopper l'appel <em>depuis {@code isEntityVisible}</em> restreint l'effet au seul chemin
 * visé. Cette méthode n'a que deux appelants dans tout le jeu : la boucle d'extraction du monde, et
 * l'afficheur de boîtes de collision du débogage. Tous deux regardent depuis la caméra du joueur,
 * là où « caché par un mur » veut dire quelque chose.
 *
 * <h2>Ce que la 26.2 a déplacé, et pourquoi il fallait le voir</h2>
 *
 * <p>Ce mixin visait {@code LevelRenderer} jusqu'en 26.1. La 26.2 a coupé le rendu en deux —
 * extraction puis soumission — et la moitié amont vit désormais dans
 * {@link LevelExtractor}, dans un paquet neuf. Le code compilait toujours : une cible de mixin est
 * une <em>chaîne de caractères</em>, que le compilateur ne relit jamais. Elle n'aurait échoué qu'au
 * démarrage du client, et le serveur dédié ne charge pas les mixins client — rien ne l'aurait
 * signalé avant qu'un joueur ne voie l'écran d'erreur.
 *
 * <p>Au passage, l'appel enveloppé a lui aussi bougé : {@code shouldRender} n'est plus appelé
 * directement depuis la boucle mais depuis {@code isEntityVisible}, qu'elle interroge.
 *
 * <h2>L'ordre des deux tests n'est pas indifférent</h2>
 *
 * <p>Le test vanilla est évalué <b>en premier</b>, et on ne lance aucun rayon s'il refuse déjà. Le
 * jeu élimine ainsi tout ce qui est hors champ — la grande majorité des entités d'un monde chargé —
 * pour un coût de quelques comparaisons de boîte, bien moindre qu'un parcours de grille. Inverser
 * les deux ferait payer un lancer de rayon à des créatures situées derrière le joueur.
 *
 * <h2>{@code @Redirect}, et non {@code @WrapOperation} — un poste du profil qui a une cause</h2>
 *
 * <p>Ce point d'accroche appelait jusqu'ici {@code @WrapOperation} (MixinExtras), qui passe
 * l'appel enveloppé par une interface générique, {@code Operation<R> { R call(Object...) }}. Le
 * bytecode réellement compilé de {@code lanterne$veil} (vérifié au {@code javap} du
 * {@code .class} produit, pas supposé) le montrait noir sur blanc : chaque appel allouait un
 * {@code Object[7]}, boîtait trois {@code double} et un {@code float} en {@code Double}/
 * {@code Float}, puis franchissait {@code Operation.call} — une méthode d'interface. Et la
 * bibliothèque MixinExtras elle-même ({@code OperationUtils}, dans le jar
 * {@code mixinextras-neoforge}) tisse cette indirection dans la classe CIBLE — ici
 * {@code LevelExtractor} — sous la forme d'un vrai {@code invokedynamic} : une nouvelle
 * {@code InvokeDynamicInsnNode} générée à l'endroit exact de l'appel remplacé.
 *
 * <p>Ce point est appelé une fois par entité, par image — jusqu'à plusieurs dizaines de milliers
 * de fois par seconde avec un troupeau dense. Une radiographie réelle, prise sur une scène de
 * grange synthétique et reproductible ({@code lab/Glass}), montrait {@code LambdaForm$MH/0x...}
 * parmi les méthodes les plus coûteuses en temps propre (3,1 % puis 2,6 % sur deux sessions
 * indépendantes), aux côtés de {@code Entity.shouldRender} — exactement la forme de coût qu'un
 * site {@code invokedynamic} chaud, lié une fois puis retraversé à chaque appel par la machinerie
 * {@code java.lang.invoke}, laisse dans un tel profil.
 *
 * <p>{@code @Redirect} (Mixin de base, pas MixinExtras) remplace la même instruction
 * {@code INVOKE} par le même genre de méthode statique — {@code tools/verifie_mixins.py} contrôle
 * sa cible de la même façon qu'il contrôlait celle de {@code @WrapOperation} — mais le
 * gestionnaire reçoit les arguments <em>typés directement</em>, sans tableau, sans boîtage, sans
 * {@code invokedynamic}. Le comportement est identique bit à bit : le gestionnaire appelle
 * l'original avec les mêmes arguments, dans le même ordre, et le résultat substitué reprend sa
 * place exacte dans le bytecode de {@code isEntityVisible} — la suite de repli du jeu vanilla
 * (passager indirect de la caméra) n'est donc ni court-circuitée ni dupliquée.
 */
@Mixin(LevelExtractor.class)
public abstract class EntityCullMixin {
    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void lanterne$openFrame(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
            LevelRenderState output, CallbackInfo callback) {
        if (Settings.shroud()) {
            Shroud.openFrame();
        }
        if (Settings.horizon()) {
            fr.clubcitrouille.lanterne.core.Horizon.openFrame();
        }
    }

    @Redirect(
            method = "isEntityVisible",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
                            + "shouldRender(Lnet/minecraft/world/entity/Entity;"
                            + "Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z"))
    private boolean lanterne$veil(EntityRenderDispatcher dispatcher, Entity entity, Frustum frustum,
            double camX, double camY, double camZ, float partialTicks) {
        if (!dispatcher.shouldRender(entity, frustum, camX, camY, camZ, partialTicks)) {
            return false;
        }
        if (Settings.shroud() && Shroud.hidden(entity, camX, camY, camZ)) {
            return false;
        }
        // <h2>L'horizon vient en dernier, et l'ordre n'est pas indifférent</h2>
        //
        // Il compte ce qu'il laisse passer, et ce compte pilote son propre rayon. Le placer avant le
        // voile lui ferait compter des créatures que le voile allait écarter de toute façon : il
        // croirait la foule plus dense qu'elle n'est, et rapprocherait sa limite pour rien — en
        // pénalisant précisément les joueurs que le voile protège déjà bien.
        //
        // Voir Horizon, et la réserve qui y est écrite en toutes lettres : ce module n'a pas été
        // mesuré, et il est éteint par défaut.
        if (Settings.horizon()
                && fr.clubcitrouille.lanterne.core.Horizon.beyond(entity, camX, camY, camZ)) {
            return false;
        }
        return true;
    }
}
