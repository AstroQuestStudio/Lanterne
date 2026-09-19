package fr.clubcitrouille.lanterne.lab;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * L'établi : ce que coûte vraiment {@code RecipeManager.getRecipeFor} sur les VRAIES recettes de
 * ce serveur, avec et sans l'indice que {@link fr.clubcitrouille.lanterne.core.Raccourci} lui
 * fournit désormais.
 *
 * <h2>Ce que ce banc mesure précisément, et ce qu'il ne mesure pas</h2>
 *
 * <p>Il n'ouvre aucune table à craft, ne connecte aucun joueur, ne passe pas par le réseau : il
 * appelle directement {@code RecipeManager.getRecipeFor(RecipeType.CRAFTING, entrée, niveau,
 * indice)} — exactement l'appel que {@code RaccourciCraftingMixin} redirige, à la place exacte où
 * {@code javap} a montré que {@code CraftingMenu}/{@code InventoryMenu} passent toujours
 * {@code null}. C'est délibéré : le coût qui varie entre « avec » et « sans » est entièrement dans
 * cette méthode, et l'isoler évite qu'un coût fixe de menu ou de réseau, identique des deux côtés,
 * ne dilue le rapport.
 *
 * <p>Le nombre de recettes n'est pas inventé : il est relevé EN DIRECT sur
 * {@code server.getRecipeManager().getRecipes()}, filtré à {@code RecipeType.CRAFTING} — c'est
 * donc le vrai compte de ce serveur au moment du banc (vanilla de ce moteur + les recettes propres
 * de Lanterne), pas un chiffre relu une fois puis recopié.
 *
 * <h2>Conformité avant vitesse</h2>
 *
 * <p>Les deux bras doivent rendre EXACTEMENT la même recette à chaque relevé — {@code
 * RecipeManager.getRecipeFor} REVÉRIFIE toujours {@code matches()} sur l'indice avant de lui faire
 * confiance (code vanilla, pas de ce module), donc un indice ne peut par construction changer le
 * résultat, seulement son coût. Un seul écart invaliderait le banc avant même de regarder une
 * horloge.
 */
public final class Etabli {
    private static final int ITERATIONS = 4000;
    private static final int READINGS = 8;
    private static final int WARMUP = 2;
    private static final int SETTLE_TICKS = 5;

    private enum Step { OFF, SETTLE, MEASURE_SANS, MEASURE_AVEC, DONE }

    private static Step step = Step.OFF;
    private static MinecraftServer host;
    private static int settleLeft;
    private static int reading;

    private static final long[] sansNanos = new long[READINGS];
    private static final long[] avecNanos = new long[READINGS];

    private static CraftingInput entree;
    private static RecipeHolder<CraftingRecipe> attendue;
    private static boolean conforme = true;
    private static long totalCraft;

    private Etabli() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        ServerLevel level = server.overworld();
        // Recette réelle et simple : une bûche de chêne -> des planches (minecraft:oak_planks,
        // "crafting_shapeless", ingrédient #minecraft:oak_logs) — lue dans le vrai jar patché, pas
        // supposée. Une grille 1x1 suffit : une recette shapeless ignore la position.
        entree = CraftingInput.of(1, 1, List.of(new ItemStack(Items.OAK_LOG)));

        RecipeManager recettes = server.getRecipeManager();
        Optional<RecipeHolder<CraftingRecipe>> trouve = recettes.getRecipeFor(
                RecipeType.CRAFTING, entree, level, (RecipeHolder<CraftingRecipe>) null);
        if (trouve.isEmpty()) {
            Lanterne.LOG.error("[ÉTABLI] MESURE REFUSÉE : la recette de planches de chêne n'a pas "
                    + "été trouvée sur ce serveur — rien à comparer.");
            step = Step.DONE;
            host.halt(false);
            return;
        }
        attendue = trouve.get();
        totalCraft = recettes.getRecipes().stream()
                .filter(h -> h.value().getType() == RecipeType.CRAFTING)
                .count();

        reading = 0;
        settleLeft = SETTLE_TICKS;
        step = Step.SETTLE;
        Lanterne.LOG.info("[ÉTABLI] Épreuve armée — {} recette(s) réelles de RecipeType.CRAFTING "
                + "chargées sur ce serveur, recette ciblée : {}.",
                totalCraft, attendue.id().identifier());
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        RecipeManager recettes = server.getRecipeManager();

        switch (step) {
            case SETTLE -> {
                if (--settleLeft <= 0) {
                    reading = 0;
                    step = Step.MEASURE_SANS;
                }
            }
            case MEASURE_SANS -> {
                long start = System.nanoTime();
                RecipeHolder<CraftingRecipe> vu = null;
                for (int i = 0; i < ITERATIONS; i++) {
                    Optional<RecipeHolder<CraftingRecipe>> r = recettes.getRecipeFor(
                            RecipeType.CRAFTING, entree, level, (RecipeHolder<CraftingRecipe>) null);
                    vu = r.orElse(null);
                }
                sansNanos[reading] = System.nanoTime() - start;
                if (!attendue.equals(vu)) {
                    conforme = false;
                }
                reading++;
                if (reading >= READINGS) {
                    reading = 0;
                    step = Step.MEASURE_AVEC;
                }
            }
            case MEASURE_AVEC -> {
                long start = System.nanoTime();
                RecipeHolder<CraftingRecipe> vu = null;
                for (int i = 0; i < ITERATIONS; i++) {
                    Optional<RecipeHolder<CraftingRecipe>> r =
                            recettes.getRecipeFor(RecipeType.CRAFTING, entree, level, attendue);
                    vu = r.orElse(null);
                }
                avecNanos[reading] = System.nanoTime() - start;
                if (!attendue.equals(vu)) {
                    conforme = false;
                }
                reading++;
                if (reading >= READINGS) {
                    step = Step.DONE;
                    report();
                    if (host != null) {
                        host.halt(false);
                    }
                }
            }
            default -> { }
        }
    }

    private static double median(long[] values) {
        long[] copy = new long[READINGS - WARMUP];
        System.arraycopy(values, WARMUP, copy, 0, copy.length);
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2d : copy[middle];
    }

    private static void report() {
        Lanterne.LOG.info("[ÉTABLI] ── Verdict de CONFORMITÉ (avant toute vitesse) ──");
        if (!conforme) {
            Lanterne.LOG.error("[ÉTABLI] ÉCHEC DE CONFORMITÉ : au moins un relevé n'a pas rendu la "
                    + "même recette que le premier appel. Rien n'est publié.");
            return;
        }
        Lanterne.LOG.info("[ÉTABLI] CONFORME : indice nul ou indice fourni rendent systématiquement "
                + "la même recette ({}), sur les {} relevés des deux bras.",
                attendue.id().identifier(), READINGS);

        double sansMs = median(sansNanos) / 1.0E6d;
        double avecMs = median(avecNanos) / 1.0E6d;
        double sansUs = sansMs * 1000d / ITERATIONS;
        double avecUs = avecMs * 1000d / ITERATIONS;

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[ÉTABLI] %d recette(s) réelles de RecipeType.CRAFTING chargées sur ce serveur "
                        + "(vanilla de ce moteur + Lanterne). Recette ciblée : %s.",
                totalCraft, attendue.id().identifier()));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[ÉTABLI] SANS raccourci (indice nul, %d appel(s)/relevé, %d relevés) : médiane "
                        + "%.2f ms, soit %.3f µs/appel.",
                ITERATIONS, READINGS - WARMUP, sansMs, sansUs));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[ÉTABLI] AVEC raccourci (indice = dernière recette, %d appel(s)/relevé, %d "
                        + "relevés) : médiane %.2f ms, soit %.3f µs/appel.",
                ITERATIONS, READINGS - WARMUP, avecMs, avecUs));

        double ratio = avecMs <= 0d ? 0d : sansMs / avecMs;
        Lanterne.LOG.info("[ÉTABLI] ── Verdict de VITESSE ──");
        if (ratio > 1.05d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[ÉTABLI] VERDICT : gain ×%.2f sur RecipeManager.getRecipeFor (%.0f %% de temps "
                            + "en moins par appel, sur %d recette(s) réelles de RecipeType.CRAFTING).",
                    ratio, (1d - 1d / ratio) * 100d, totalCraft));
        } else if (ratio < 0.95d) {
            Lanterne.LOG.warn(String.format(Locale.ROOT,
                    "[ÉTABLI] VERDICT : PERTE ×%.2f — plus cher avec l'indice. Ne pas l'activer par "
                            + "défaut dans cet état.", ratio));
        } else {
            Lanterne.LOG.info("[ÉTABLI] VERDICT : aucun effet mesurable (écart sous le bruit de fond).");
        }
        Lanterne.LOG.info("[ÉTABLI] Rappel : ceci mesure le mécanisme exact que RaccourciCraftingMixin "
                + "active (RecipeManager.getRecipeFor avec/sans indice, sur le VRAI RecipeManager "
                + "chargé) — pas le clic complet joueur→réseau→menu, qui ajoute d'autres coûts fixes "
                + "identiques des deux côtés. Rappel de contexte : cette recette précise se trouve "
                + "quelque part dans la liste des " + totalCraft + " recettes CRAFTING dans l'ordre "
                + "où RecipeMap les range ; une recette qui tomberait plus tôt dans cet ordre "
                + "gagnerait moins avec l'indice, une qui tombe plus tard gagnerait davantage — ce "
                + "relevé n'est ni le meilleur ni le pire cas, c'est celui d'une recette vanilla "
                + "ordinaire.");
    }
}
