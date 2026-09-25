package fr.clubcitrouille.lanterne.mixin;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.tags.TagKey;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Un ensemble nommé (tag) inconnu du côté qui décode ne doit plus expulser le joueur.
 *
 * <p>{@code ByteBufCodecs.holderSet(...)} décode un ensemble encodé par tag avec
 * {@code registry.get(tagKey).orElseThrow()} (classe anonyme {@code ByteBufCodecs$36}, ligne 728 du
 * source patché 26.3.0.7-beta, vérifié par javap). Le serveur encode par tag TOUT {@code HolderSet}
 * nommé, y compris un ensemble orphelin qu'aucun datapack n'a lié — et il n'envoie aux clients que
 * les tags réellement liés ({@code TagNetworkSerialization} : {@code registry.getTags()}). Un
 * ingrédient de recette portant un tel ensemble faisait donc échouer le décodage de
 * {@code recipe_book_add} chez le client, et l'expulsait (vécu le 20/09 sur le VPS, et le 25/09 sur
 * un simple craft au shift-clic). Voir {@code core.RecettesDiag} pour trouver l'ensemble coupable.
 *
 * <p>Ici : si le tag est absent, un ensemble nommé VIDE le remplace — l'ingrédient s'affiche vide
 * dans le livre de recettes, le craft lui-même reste tranché par le serveur. Aucun effet quand le
 * tag existe. Journalisé une fois par tag.
 */
@Mixin(targets = "net.minecraft.network.codec.ByteBufCodecs$36")
public abstract class TagInconnuMixin {
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    @Redirect(
            method = "decode(Lnet/minecraft/network/RegistryFriendlyByteBuf;)Lnet/minecraft/core/HolderSet;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/Registry;get(Lnet/minecraft/tags/TagKey;)Ljava/util/Optional;"),
            require = 0)
    private Optional<HolderSet.Named<Object>> lanterne$tagOrEmpty(Registry<Object> registry, TagKey<Object> key) {
        Optional<HolderSet.Named<Object>> found = registry.get(key);
        if (found.isPresent()) {
            return found;
        }
        if (SEEN.add(key.registry().identifier() + "#" + key.location())) {
            Lanterne.LOG.warn("[RECETTES] tag inconnu ici : {} #{} — ensemble vide à la place au lieu "
                    + "d'expulser (voir [RECETTES] côté serveur pour la recette fautive).",
                    key.registry().identifier(), key.location());
        }
        return Optional.of(HolderSet.emptyNamed(registry, key));
    }
}
