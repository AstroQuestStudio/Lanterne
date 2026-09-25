package fr.clubcitrouille.lanterne.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Trouve les recettes dont un ingrédient porte un tag que les clients ne recevront jamais.
 *
 * <p>Le serveur n'envoie aux clients que les tags liés à son registre
 * ({@code TagNetworkSerialization.serializeToNetwork} → {@code registry.getTags()}), mais il encode
 * PAR TAG tout ingrédient dont l'ensemble est nommé. Un ensemble nommé orphelin (fabriqué par du code,
 * jamais lié par un datapack) part donc sur le réseau sous un nom que le client ne connaît pas :
 * c'est ce qui expulsait les joueurs sur {@code recipe_book_add}. Voir {@code mixin.TagInconnuMixin}
 * pour le filet côté décodage.
 *
 * <p>Parcours unique au démarrage, journalisé en {@code [RECETTES]} ; rien si tout est cohérent.
 */
public final class RecettesDiag {
    private RecettesDiag() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(RecettesDiag::onStarted);
    }

    private static void onStarted(ServerStartedEvent event) {
        try {
            report(event.getServer());
        } catch (Throwable failed) {
            Lanterne.LOG.warn("[RECETTES] diagnostic impossible : {}", failed.toString());
        }
    }

    public static void report(MinecraftServer server) {
        Registry<Item> items = server.registryAccess().lookupOrThrow(Registries.ITEM);
        Set<TagKey<Item>> synced = items.getTags().map(HolderSet.Named::key).collect(Collectors.toSet());
        Map<TagKey<Item>, Set<String>> orphans = new LinkedHashMap<>();
        int scanned = 0;
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            scanned++;
            List<Ingredient> ingredients;
            try {
                ingredients = holder.value().placementInfo().ingredients();
            } catch (Throwable special) {
                continue;
            }
            for (Ingredient ingredient : ingredients) {
                ingredient.getValues().unwrapKey().ifPresent(tag -> {
                    if (!synced.contains(tag)) {
                        orphans.computeIfAbsent(tag, k -> new TreeSet<>())
                                .add(holder.id().identifier().toString());
                    }
                });
            }
        }
        if (orphans.isEmpty()) {
            Lanterne.LOG.info("[RECETTES] {} recette(s) vérifiée(s) : tous les tags d'ingrédients sont "
                    + "envoyés aux clients.", scanned);
            return;
        }
        Lanterne.LOG.warn("[RECETTES] {} tag(s) d'ingrédient JAMAIS envoyé(s) aux clients — un joueur "
                + "qui débloque ces recettes sans le filet serait expulsé (recipe_book_add) :", orphans.size());
        orphans.forEach((tag, recipes) -> Lanterne.LOG.warn("[RECETTES]   #{} ← {} recette(s) : {}",
                tag.location(), recipes.size(),
                recipes.stream().limit(8).collect(Collectors.joining(", "))
                        + (recipes.size() > 8 ? ", …" : "")));
    }
}
