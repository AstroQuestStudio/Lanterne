package fr.clubcitrouille.lanterne.perf;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applique un mixin de {@code lanterne.perf.mixins.json} seulement si son module est activé
 * ({@link PerfConfig}). Un module désactivé ne modifie aucune classe du jeu.
 */
public final class PerfMixinPlugin implements IMixinConfigPlugin {
    /** Nom simple du mixin -> identifiant du module (un module peut regrouper plusieurs mixins). */
    private static final Map<String, String> MODULE = Map.of(
            "MixinScissorState", "batching_groupes",
            "MixinRenderTypeFeatureRenderer_Group", "batching_groupes",
            "MixinTextFeatureRenderer_GlyphRenderer", "texte_recherche_rapide",
            "MixinRenderTypes", "texte_sans_tri_translucide",
            "RenderTypeFeatureRendererInvoker", "texte_recherche_rapide");

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        String module = MODULE.get(simple);
        return module != null && PerfConfig.enabled(module);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
