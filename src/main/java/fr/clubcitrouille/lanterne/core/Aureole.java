package fr.clubcitrouille.lanterne.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * L'auréole : la portée, la verticalité et les sorts d'une balise (beacon), au-delà de vanilla.
 *
 * <h2>Ce que vanilla fait déjà, lu au {@code javap} sur le jar réellement compilé</h2>
 *
 * <p>{@code BeaconBlockEntity.applyEffects} calcule une portée horizontale de {@code niveau*10+10}
 * blocs (50 au niveau 4), puis construit une boîte centrée sur la balise, gonflée de cette portée
 * sur les trois axes ({@code AABB.inflate}), <b>puis étirée vers le haut de la hauteur entière du
 * monde</b> ({@code AABB.expandTowards(0, level.getHeight(), 0)}, qui n'agit que vers le haut). Le
 * dessus est donc <b>déjà</b> pratiquement sans plafond aujourd'hui ; seuls le dessous et
 * l'horizontale restent bornés à cette portée. Ce module ne « débloque » donc pas une verticalité
 * qui n'existait pas : il retire le plafond du dessous, resté vanilla.
 *
 * <p>Les matériaux de pyramide valides sont le tag data-driven {@code minecraft:beacon_base_blocks}
 * ({@code BeaconBlockEntity.updateBase}, testé par {@code BlockState.is(TagKey)}), pas une liste
 * codée en dur — et dans ce moteur ce tag contient <b>déjà</b> {@code netherite_block} en plus des
 * quatre blocs vanilla habituels (voir {@code data/minecraft/tags/block/beacon_base_blocks.json}
 * dans le jar patché). Aucune extension de tag n'a donc été nécessaire : {@link #multiplierFor}
 * se contente de récompenser un matériau déjà accepté.
 *
 * <h2>Le maillon le plus faible, pas le plus généreux</h2>
 *
 * <p>{@link #pyramidMultiplier} balaie les mêmes couches que {@code updateBase} (jusqu'à quatre,
 * bornées par {@code levels}) et retient le <b>plus faible</b> multiplicateur rencontré. Un joueur
 * qui glisserait un seul bloc de fer dans une pyramide de diamant ne doit pas toucher le bonus du
 * diamant pour le prix du fer.
 *
 * <h2>Un second balayage, assumé</h2>
 *
 * <p>{@code updateBase} balaie déjà ces mêmes couches à CHAQUE tick d'une balise active, pour savoir
 * si la pyramide tient toujours. {@link #pyramidMultiplier} en refait un second, faute d'un moyen
 * moins coûteux de connaître le matériau sans toucher au comptage de niveaux lui-même. Sur un petit
 * serveur coopératif — quelques balises actives au plus, jamais des centaines — le coût (au plus
 * cent soixante-quatre lectures de bloc par balise et par tick) reste sans commune mesure avec ce que
 * ce mod traque ailleurs (centaines de villageois, milliers d'entités). Ne pas dupliquer ce
 * balayage demanderait de faire porter le résultat de {@code updateBase} à travers un mixin
 * supplémentaire sur {@code tick}, pour un gain qui ne se mesurerait pas ici.
 */
public final class Aureole {
    private Aureole() {}

    /** Formule vanilla exacte — voir la Javadoc de classe. Sert de repli quand le module est éteint. */
    private static double vanillaRange(int levels) {
        return levels * 10 + 10;
    }

    public static boolean active() {
        return Config.BEACON_OVERHAUL.get();
    }

    /** Portée de base au niveau donné, avant matériau — lue en config, un palier par niveau. */
    public static int baseRange(int levels) {
        return switch (levels) {
            case 1 -> Config.BEACON_RANGE_LEVEL_1.get();
            case 2 -> Config.BEACON_RANGE_LEVEL_2.get();
            case 3 -> Config.BEACON_RANGE_LEVEL_3.get();
            default -> Config.BEACON_RANGE_LEVEL_4.get();
        };
    }

    /** Le multiplicateur configuré pour un bloc de pyramide, ou 1 pour tout bloc non reconnu. */
    public static double multiplierFor(Block block) {
        if (block == Blocks.NETHERITE_BLOCK) {
            return Config.BEACON_MULT_NETHERITE.get();
        }
        if (block == Blocks.DIAMOND_BLOCK) {
            return Config.BEACON_MULT_DIAMOND.get();
        }
        if (block == Blocks.EMERALD_BLOCK) {
            return Config.BEACON_MULT_EMERALD.get();
        }
        if (block == Blocks.GOLD_BLOCK) {
            return Config.BEACON_MULT_GOLD.get();
        }
        if (block == Blocks.IRON_BLOCK) {
            return Config.BEACON_MULT_IRON.get();
        }
        // Un bloc que le tag accepterait sans que ce module le connaisse (ajout d'un autre mod,
        // ou datapack qui étend le tag) : ni bonus ni pénalité plutôt qu'une exception.
        return 1.0d;
    }

    /** Pure : le pire des multiplicateurs rencontrés, ou 1 si rien n'a été trouvé. */
    public static double weakest(List<Double> found) {
        double min = Double.MAX_VALUE;
        for (double value : found) {
            min = Math.min(min, value);
        }
        return found.isEmpty() ? 1.0d : min;
    }

    /** Pure : la portée mise à l'échelle par le matériau. Séparée de la lecture de configuration
     *  pour rester vérifiable sans monde ni serveur de mod chargé. */
    public static double scaledRange(double base, double multiplier) {
        return base * multiplier;
    }

    /**
     * Rebalaie la pyramide (mêmes couches que {@code updateBase}) pour en tirer le multiplicateur
     * le plus faible parmi les blocs réellement en place.
     */
    public static double pyramidMultiplier(Level level, BlockPos pos, int levels) {
        List<Double> found = new ArrayList<>();
        int layers = Math.min(levels, 4);
        for (int layer = 1; layer <= layers; layer++) {
            int y = pos.getY() - layer;
            for (int x = pos.getX() - layer; x <= pos.getX() + layer; x++) {
                for (int z = pos.getZ() - layer; z <= pos.getZ() + layer; z++) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (state.is(BlockTags.BEACON_BASE_BLOCKS)) {
                        found.add(multiplierFor(state.getBlock()));
                    }
                }
            }
        }
        return weakest(found);
    }

    /** La portée horizontale réellement appliquée — vanilla exacte si le module est éteint. */
    public static double horizontalRange(Level level, BlockPos pos, int levels) {
        if (!active()) {
            return vanillaRange(levels);
        }
        double multiplier = pyramidMultiplier(level, pos, levels);
        return scaledRange(baseRange(levels), multiplier);
    }

    /**
     * La boîte d'effet réellement appliquée.
     *
     * <p>Éteint, ou avec la verticalité illimitée désactivée : même forme que vanilla — symétrique
     * de la portée autour de la balise, puis étirée vers le haut de la hauteur du monde — mais avec
     * LA portée de ce module (qui reste supérieure à vanilla dès que le module est actif). Le
     * dessous, seul, distingue les deux réglages.
     */
    public static AABB effectArea(Level level, BlockPos pos, double horizontalRange) {
        double x = pos.getX() + 0.5d;
        double z = pos.getZ() + 0.5d;
        double minY;
        double maxY;
        if (active() && Config.BEACON_VERTICAL_UNLIMITED.get()) {
            minY = level.getMinY();
            maxY = level.getMaxY();
        } else {
            minY = pos.getY() - horizontalRange;
            maxY = pos.getY() + horizontalRange + level.getHeight();
        }
        return new AABB(x - horizontalRange, minY, z - horizontalRange,
                x + horizontalRange, maxY, z + horizontalRange);
    }

    // --- Les sorts supplémentaires ---------------------------------------------

    /**
     * Le palier vanilla (1 à 4) requis pour un effet AJOUTÉ par ce module, ou 0 s'il n'est ni
     * reconnu ni activé en configuration. Les paliers 1 à 3 se choisissent en effet principal ; le
     * palier 4 ne se choisit qu'en effet secondaire — exactement la même règle que vanilla applique
     * déjà à Régénération, dans {@code BeaconBlockEntity.validateEffects}, que ce module ne touche
     * pas : lui donner le même palier suffit à le faire respecter sans le réécrire.
     */
    public static int extraEffectTier(Holder<MobEffect> effect) {
        if (!active() || effect == null) {
            return 0;
        }
        if (effect.equals(MobEffects.NIGHT_VISION)) {
            return Config.BEACON_EFFECT_NIGHT_VISION.get() ? 1 : 0;
        }
        if (effect.equals(MobEffects.WATER_BREATHING)) {
            return Config.BEACON_EFFECT_WATER_BREATHING.get() ? 2 : 0;
        }
        if (effect.equals(MobEffects.SLOW_FALLING)) {
            return Config.BEACON_EFFECT_SLOW_FALLING.get() ? 2 : 0;
        }
        if (effect.equals(MobEffects.FIRE_RESISTANCE)) {
            return Config.BEACON_EFFECT_FIRE_RESISTANCE.get() ? 3 : 0;
        }
        if (effect.equals(MobEffects.LUCK)) {
            return Config.BEACON_EFFECT_LUCK.get() ? 3 : 0;
        }
        if (effect.equals(MobEffects.SATURATION)) {
            return Config.BEACON_EFFECT_SATURATION.get() ? 4 : 0;
        }
        if (effect.equals(MobEffects.DOLPHINS_GRACE)) {
            return Config.BEACON_EFFECT_DOLPHINS_GRACE.get() ? 4 : 0;
        }
        if (effect.equals(MobEffects.HEALTH_BOOST)) {
            return Config.BEACON_EFFECT_HEALTH_BOOST.get() ? 4 : 0;
        }
        if (effect.equals(MobEffects.ABSORPTION)) {
            return Config.BEACON_EFFECT_ABSORPTION.get() ? 4 : 0;
        }
        return 0;
    }

    /**
     * Les effets ajoutés par ce module, actuellement activés, pour un palier donné (1 à 4) — dans
     * l'ordre où {@link #extraEffectTier} les reconnaît. Sert à bâtir la grille de boutons de
     * {@code client.screen.Phare} ; ne dit rien des effets vanilla du même palier, qui restent ceux
     * de {@code BeaconBlockEntity.BEACON_EFFECTS}.
     */
    public static List<Holder<MobEffect>> extraEffectsAt(int tier) {
        List<Holder<MobEffect>> found = new ArrayList<>();
        for (Holder<MobEffect> candidate : List.of(MobEffects.NIGHT_VISION, MobEffects.WATER_BREATHING,
                MobEffects.SLOW_FALLING, MobEffects.FIRE_RESISTANCE, MobEffects.LUCK,
                MobEffects.SATURATION, MobEffects.DOLPHINS_GRACE, MobEffects.HEALTH_BOOST,
                MobEffects.ABSORPTION)) {
            if (extraEffectTier(candidate) == tier) {
                found.add(candidate);
            }
        }
        return found;
    }

    /**
     * Élargit {@code VALID_EFFECTS} le temps d'un appel, pour que {@code loadEffect} (relecture NBT)
     * et {@code filterEffect} n'invalident pas un effet ajouté par ce module — exactement le même
     * filet de sécurité que vanilla applique déjà si un tag ou un effet disparaît d'une version à
     * l'autre. Reconstruit à chaque appel plutôt que mis en cache : ces deux méthodes ne tournent
     * qu'au chargement d'une balise ou à la validation d'un choix de joueur, jamais par tick.
     */
    public static Set<Holder<MobEffect>> widenedValidEffects(Set<Holder<MobEffect>> vanilla) {
        if (!active()) {
            return vanilla;
        }
        Set<Holder<MobEffect>> widened = new HashSet<>(vanilla);
        for (int tier = 1; tier <= 4; tier++) {
            widened.addAll(extraEffectsAt(tier));
        }
        return widened;
    }
}
