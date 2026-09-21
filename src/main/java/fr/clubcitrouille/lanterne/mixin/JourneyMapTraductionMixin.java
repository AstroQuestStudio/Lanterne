package fr.clubcitrouille.lanterne.mixin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mémoïse {@code journeymap.client.Constants.getString(String)}.
 *
 * <h2>Le défaut, mesuré</h2>
 *
 * <p>Enregistrement JFR du fil de rendu (Lanterne 26.3.108 + Sodium + Iris + JourneyMap, shaders actifs,
 * RTX 3080 Laptop, 100 s) : les piles passant par JourneyMap représentent 49,6 % des octets alloués
 * par le fil de rendu, dont 44,2 % pour la seule méthode {@code TranslatableContents.decomposeTemplate}.
 * La chaîne d'appel est {@code ThemeLabelSource.getStrings} → un comparateur de {@code TimSort} →
 * {@code Constants.getString(String)} → {@code Component.translatable(clé).getString()} : à chaque
 * image, JourneyMap trie ses libellés et retraduit la clé à CHAQUE comparaison, donc reparse le modèle
 * de traduction (un {@code int[]} par appel).
 *
 * <h2>Le correctif</h2>
 *
 * <p>La traduction d'une clé ne change que si la langue change ou si les ressources sont rechargées.
 * Minecraft installe alors une NOUVELLE instance de {@link Language} : elle sert de témoin, le cache est
 * vidé dès qu'elle change. Un mod cible facultatif : {@link Pseudo} laisse le jeu démarrer sans
 * JourneyMap, et {@code require = 0} ne casse rien si une future version renomme la méthode.
 */
@Pseudo
@Mixin(targets = "journeymap.client.Constants", remap = false)
public abstract class JourneyMapTraductionMixin {
    @Unique
    private static final Map<String, String> lanterne$traductions = new ConcurrentHashMap<>();

    @Unique
    private static volatile Language lanterne$langue;

    @Inject(method = "getString(Ljava/lang/String;)Ljava/lang/String;", at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void lanterne$lireCache(String cle, CallbackInfoReturnable<String> retour) {
        if (cle == null) {
            return;
        }
        Language actuelle = Language.getInstance();
        if (actuelle != lanterne$langue) {
            lanterne$traductions.clear();
            lanterne$langue = actuelle;
            return;
        }
        String connue = lanterne$traductions.get(cle);
        if (connue != null) {
            retour.setReturnValue(connue);
        }
    }

    @Inject(method = "getString(Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), require = 0)
    private static void lanterne$ecrireCache(String cle, CallbackInfoReturnable<String> retour) {
        String valeur = retour.getReturnValue();
        // Avant que le client existe, JourneyMap rend la clé telle quelle : ce n'est pas une traduction.
        if (cle != null && valeur != null && Minecraft.getInstance() != null
                && Language.getInstance() == lanterne$langue) {
            lanterne$traductions.put(cle, valeur);
        }
    }
}
