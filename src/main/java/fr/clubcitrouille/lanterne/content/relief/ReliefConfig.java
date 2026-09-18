package fr.clubcitrouille.lanterne.content.relief;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * RELIEF : la carte 3D du serveur, vue du panel web — son propre fichier, comme la boutique et le
 * projecteur avant lui. {@code Config.SPEC} occupe déjà le fichier {@code SERVER} par défaut.
 *
 * <p>Désactivable pour une raison précise : sur un petit hébergement, balayer les régions et écrire
 * des tuiles sur disque est un coût que tout le monde ne veut pas payer, même borné par tick. Éteint,
 * ce module n'exécute plus une seule instruction — voir {@link Relief#tick}.
 */
public final class ReliefConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ACTIVE;
    public static final ModConfigSpec.IntValue BUDGET_MS;
    public static final ModConfigSpec.IntValue RESCAN_TICKS;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "RELIEF : génère en tâche de fond les données de la carte 3D (façon BlueMap) que le",
                "panel web affiche. Lit les régions déjà écrites sur disque, n'en génère aucune",
                "nouvelle. Budget par tick, comme PRÉGÉN : ne vole jamais de temps au jeu.");
        ACTIVE = BUILDER.comment(
                "Coupe tout le module si faux. Aucun coût, aucune tuile écrite ou mise à jour.")
                .define("actif", true);
        BUDGET_MS = BUILDER.comment(
                "Millisecondes maximum passées à décoder des chunks par tick serveur. Une valeur",
                "basse ralentit la mise à jour de la carte mais reste invisible en jeu ; une valeur",
                "haute rafraîchit plus vite mais empiète davantage sur le budget du tick (50 ms).")
                .defineInRange("budget_ms_par_tick", 2, 1, 20);
        RESCAN_TICKS = BUILDER.comment(
                "Ticks entre deux balayages des dossiers de régions à la recherche de fichiers",
                "modifiés depuis la dernière tuile écrite. 200 (défaut) : dix secondes.")
                .defineInRange("balayage_ticks", 200, 20, 12000);
        SPEC = BUILDER.build();
    }

    private ReliefConfig() {}

    public static void apply(ModConfigEvent event) {
        // Rien à recalculer : les ModConfigSpec.*Value se relisent eux-mêmes à chaque .get().
    }
}
