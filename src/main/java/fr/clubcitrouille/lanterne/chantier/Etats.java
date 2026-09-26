package fr.clubcitrouille.lanterne.chantier;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * Les états de blocs et leurs données, dans les deux sens.
 *
 * <p>Tout passe par la syntaxe des commandes ({@code minecraft:oak_stairs[facing=east]{...}}) : c'est
 * celle que l'appelant connaît déjà, celle que {@code /setblock} accepte, et elle porte le NBT d'un
 * bloc-entité dans la même chaîne — un coffre plein tient en une entrée de palette.
 */
final class Etats {
    /** Un état voulu : l'état, et s'il y a lieu les données du bloc-entité à y charger. */
    record Voulu(BlockState etat, @Nullable CompoundTag nbt) {}

    private Etats() {}

    /** Lit un état complet, NBT compris. Sûr hors du thread serveur : les registres sont figés. */
    static Voulu lire(String texte) {
        try {
            BlockStateParser.BlockResult r = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, texte, true);
            return new Voulu(r.blockState(), r.nbt());
        } catch (CommandSyntaxException e) {
            throw new Refus(400, "état de bloc illisible « " + texte + " » : " + e.getMessage());
        }
    }

    /** Lit un état et la liste des propriétés explicitement données (pour « garder les propriétés »). */
    static BlockStateParser.BlockResult lireAvecProprietes(String texte) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, texte, true);
        } catch (CommandSyntaxException e) {
            throw new Refus(400, "état de bloc illisible « " + texte + " » : " + e.getMessage());
        }
    }

    static CompoundTag snbt(String texte) {
        try {
            return TagParser.parseCompoundFully(texte);
        } catch (CommandSyntaxException e) {
            throw new Refus(400, "SNBT illisible : " + e.getMessage());
        }
    }

    static String texte(BlockState etat) {
        return BlockStateParser.serialize(etat);
    }

    /**
     * Un motif de recherche : un état partiel ({@code minecraft:oak_stairs[half=top]}) ou une étiquette
     * ({@code #minecraft:logs}). Seules les propriétés écrites comptent.
     */
    static Predicate<BlockState> motif(String texte) {
        Either<BlockStateParser.BlockResult, BlockStateParser.TagResult> r;
        try {
            r = BlockStateParser.parseForTesting(BuiltInRegistries.BLOCK, texte, false);
        } catch (CommandSyntaxException e) {
            throw new Refus(400, "motif illisible « " + texte + " » : " + e.getMessage());
        }
        return r.map(bloc -> {
            Map<Property<?>, Comparable<?>> props = bloc.properties();
            var block = bloc.blockState().getBlock();
            return etat -> {
                if (!etat.is(block)) {
                    return false;
                }
                for (var e : props.entrySet()) {
                    if (!etat.getValue(e.getKey()).equals(e.getValue())) {
                        return false;
                    }
                }
                return true;
            };
        }, tag -> {
            Map<String, String> vagues = tag.vagueProperties();
            return etat -> {
                if (!etat.is(tag.tag())) {
                    return false;
                }
                for (var e : vagues.entrySet()) {
                    Property<?> p = etat.getBlock().getStateDefinition().getProperty(e.getKey());
                    if (p == null || !nomValeur(etat, p).equals(e.getValue())) {
                        return false;
                    }
                }
                return true;
            };
        });
    }

    static <T extends Comparable<T>> String nomValeur(BlockState etat, Property<T> p) {
        return p.getName(etat.getValue(p));
    }

    /**
     * Recopie sur {@code cible} les propriétés de {@code source} qui existent sous le même nom et
     * que l'appelant n'a pas fixées : remplacer des escaliers de chêne par du sapin garde leur sens.
     */
    static BlockState garder(BlockState source, BlockState cible, Map<Property<?>, Comparable<?>> fixees) {
        BlockState out = cible;
        for (Property<?> p : cible.getProperties()) {
            if (fixees.containsKey(p)) {
                continue;
            }
            Property<?> q = source.getBlock().getStateDefinition().getProperty(p.getName());
            if (q != null) {
                out = poser(out, p, nomValeur(source, q));
            }
        }
        return out;
    }

    private static <T extends Comparable<T>> BlockState poser(BlockState etat, Property<T> p, String valeur) {
        Optional<T> v = p.getValue(valeur);
        return v.isPresent() ? etat.setValue(p, v.get()) : etat;
    }

    /** Les données d'un bloc-entité, composants compris — ce que {@link #charger} sait relire. */
    static @Nullable CompoundTag sauver(ServerLevel level, BlockPos pos, BlockState etat) {
        if (!etat.hasBlockEntity()) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.saveWithoutMetadata(level.registryAccess());
    }

    /**
     * Charge des données dans le bloc-entité en place, et le fait savoir aux clients.
     *
     * <p>Pas de {@code be.setChanged()} en mode rapide : il appelle
     * {@code updateNeighbourForOutputSignal}, qui lit les voisins ET les voisins des voisins
     * conducteurs — donc parfois un chunk non chargé, chargé alors de force sur le thread serveur.
     * Mesuré : 30 ms par premier coffre d'un chunk, 4 s pour 50 000 blocs en bordure de zone non
     * générée. On marque seulement le chunk à sauver ; les comparateurs ne sont prévenus qu'en mode
     * voisins, où les chunks voisins sont garantis chargés ({@link Ecriture}).
     */
    static boolean charger(ServerLevel level, BlockPos pos, CompoundTag nbt, boolean voisins) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), nbt));
        if (voisins) {
            be.setChanged();
        } else {
            level.blockEntityChanged(pos);
        }
        level.getChunkSource().blockChanged(pos);
        return true;
    }

    /** Palette d'identité : un état, un numéro, dans l'ordre d'apparition. */
    static final class Palette {
        final java.util.List<BlockState> etats = new java.util.ArrayList<>();
        private final IdentityHashMap<BlockState, Integer> ids = new IdentityHashMap<>();

        int id(BlockState etat) {
            Integer i = ids.get(etat);
            if (i == null) {
                i = etats.size();
                etats.add(etat);
                ids.put(etat, i);
            }
            return i;
        }
    }
}
