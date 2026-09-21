package fr.clubcitrouille.lanterne.mixin;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Cache du découpage des textes traduisibles SANS arguments.
 *
 * <h2>Le défaut, mesuré</h2>
 *
 * <p>Enregistrement JFR du fil de rendu (Lanterne 26.3.108 + Sodium + Iris + JourneyMap, shaders
 * actifs, RTX 3080 Laptop) : 44 % des octets alloués par le fil de rendu viennent de
 * {@code TranslatableContents.decomposeTemplate}. {@code TranslatableContents.decompose()} ne garde
 * son résultat que DANS l'instance : un mod qui recrée {@code Component.translatable(clé)} à chaque
 * image (JourneyMap : un par libellé, à chaque comparaison de son tri) fait reparser le même modèle
 * de traduction en boucle.
 *
 * <h2>Le correctif</h2>
 *
 * <p>Sans argument, le résultat du découpage ne dépend QUE de la clé, du texte de repli et de la
 * langue : on le partage entre instances. Avec arguments, {@code decomposeTemplate} y insère les
 * arguments : on n'y touche pas. La liste partagée est une {@code ImmutableList} construite par
 * vanilla, donc sûre à partager entre fils. Le cache est vidé dès que Minecraft installe une NOUVELLE
 * instance de {@link Language} (changement de langue, rechargement des ressources) et plafonné pour
 * ne jamais grossir sans limite (clés dynamiques).
 *
 * <p>Interrupteur : {@code -Dlanterne.traduction=off}. Aucun effet sur le rendu ni sur le texte
 * produit ; complète {@link JourneyMapTraductionMixin}, qui évite en plus la recréation du composant.
 */
@Mixin(TranslatableContents.class)
public abstract class TranslatableContentsMixin {
    @Unique
    private static final boolean LANTERNE_ACTIF =
            !"off".equalsIgnoreCase(System.getProperty("lanterne.traduction", "on"));

    /** Au-delà, on vide le cache plutôt que de le laisser grossir (clés dynamiques). */
    @Unique
    private static final int LANTERNE_LIMITE = 16_384;

    @Unique
    private static final ConcurrentHashMap<String, List<FormattedText>> lanterne$decoupes =
            new ConcurrentHashMap<>();

    @Unique
    private static volatile Language lanterne$langue;

    @Shadow
    private String key;
    @Shadow
    private String fallback;
    @Shadow
    private Object[] args;
    @Shadow
    private Language decomposedWith;
    @Shadow
    private List<FormattedText> decomposedParts;

    /** Vrai si vanilla va réellement découper (et non retourner tout de suite). */
    @Unique
    private boolean lanterne$travail;

    @Unique
    private String lanterne$cle() {
        return this.fallback == null ? this.key : this.key + '\0' + this.fallback;
    }

    @Inject(method = "decompose()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void lanterne$lire(CallbackInfo ci) {
        this.lanterne$travail = false;
        if (!LANTERNE_ACTIF || this.args == null || this.args.length != 0 || this.key == null) {
            return;
        }
        Language actuelle = Language.getInstance();
        if (actuelle == this.decomposedWith) {
            return; // déjà découpé pour cette langue : vanilla retourne tout de suite
        }
        if (actuelle != lanterne$langue) {
            lanterne$decoupes.clear();
            lanterne$langue = actuelle;
            this.lanterne$travail = true;
            return;
        }
        List<FormattedText> connu = lanterne$decoupes.get(this.lanterne$cle());
        if (connu != null) {
            this.decomposedWith = actuelle;
            this.decomposedParts = connu;
            ci.cancel();
            return;
        }
        this.lanterne$travail = true;
    }

    @Inject(method = "decompose()V", at = @At("RETURN"), require = 0)
    private void lanterne$ecrire(CallbackInfo ci) {
        if (!this.lanterne$travail) {
            return;
        }
        this.lanterne$travail = false;
        Language actuelle = Language.getInstance();
        if (this.decomposedParts == null || this.decomposedWith != actuelle
                || actuelle != lanterne$langue) {
            return;
        }
        if (lanterne$decoupes.size() >= LANTERNE_LIMITE) {
            lanterne$decoupes.clear();
        }
        lanterne$decoupes.put(this.lanterne$cle(), this.decomposedParts);
    }
}
