package fr.clubcitrouille.lanterne.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import fr.clubcitrouille.lanterne.core.Cadastre;

/**
 * Le cadastre branché sur le placeur de pièces Jigsaw.
 *
 * <h2>Deux points d'accroche, et pas un de plus</h2>
 *
 * <p>Tout le travail que ce module remplace tient dans <b>deux appels</b> de
 * {@code JigsawPlacement$Placer.tryPlacingChildren}, et ils sont uniques dans la méthode :
 *
 * <pre>
 * Shapes.joinIsNotEmpty(libre, Shapes.create(AABB.of(boîte).deflate(0.25)), ONLY_SECOND)   // ligne 431
 * Shapes.joinUnoptimized(libre, Shapes.create(AABB.of(boîte)), ONLY_FIRST)                 // ligne 435
 * </pre>
 *
 * <p>On les détourne, et rien d'autre. Voir {@link Cadastre} pour ce que le registre garantit et
 * pourquoi sa réponse est <em>identique</em>, non « équivalente ».
 *
 * <h2>Ce qu'on ne vise PAS, et pourquoi</h2>
 *
 * <p>Le mod de référence accroche aussi {@code JigsawPlacement.lambda$addPieces$2} — le corps de la
 * lambda qui bâtit la région libre de départ — pour y substituer sa propre forme. Ce nom existe bien
 * en 26.2 (vérifié au désassembleur sur {@code minecraft-patched-26.2.0.88.jar}), mais il est
 * <b>fragile par construction</b> : le suffixe numérique est attribué par le compilateur dans
 * l'ordre d'apparition des lambdas du fichier source. Ajouter une lambda plus haut dans
 * {@code addPieces} — ce que n'importe quel correctif de Mojang peut faire sans le savoir — renumérote
 * celle-ci et casse le mixin, sans qu'aucune signature ne change.
 *
 * <p>On n'en a pas besoin. La région libre de départ est <b>adoptée à la première question</b> : au
 * moment où {@code joinIsNotEmpty} est appelé pour la première fois sur une forme donnée, cette
 * forme <em>est</em> la région libre exacte — vanilla vient de la construire et ne l'a pas encore
 * modifiée. La prendre alors pour départ est donc juste sans qu'on ait à savoir d'où elle vient.
 *
 * <p>Même raisonnement pour la seconde région, celle d'une pièce source dont un jigsaw pointe vers
 * l'intérieur d'elle-même : vanilla la pose par {@code sourceFree.setValue(Shapes.create(...))}, et
 * on l'adopte à la question suivante. Aucun {@code @Local}, aucun nom de variable locale, aucun
 * ordinal de lambda : les deux cibles de ce fichier sont des <b>signatures complètes</b>, que
 * {@code tools/verifie_mixins.py} sait confronter au bytecode.
 *
 * <h2>Le registre vit sur le placeur, et c'est ce qui le rend sûr entre fils</h2>
 *
 * <p>La génération de terrain tourne sur un pool de travail : plusieurs structures se placent en même
 * temps sur des fils différents. Un {@code Placer} est créé pour une structure et n'est vu que par
 * un fil ; ranger la table sur lui la confine à ce fil sans verrou, et elle meurt avec lui. Une table
 * statique aurait demandé un verrou et une purge — deux choses qu'on n'a pas les moyens de payer sur
 * un cœur unique.
 *
 * <p>Elle est créée <b>paresseusement</b> plutôt qu'à la construction : Mixin fusionne les
 * initialiseurs de champ dans les constructeurs de la classe cible, et un placement de structure qui
 * ne rencontre jamais de pièce candidate n'a pas à payer une table pour rien.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class JigsawPlacerMixin {
    @Unique
    private Map<VoxelShape, Cadastre.Region> lanterne$plots;

    /**
     * « Cette pièce tient-elle dans ce qui reste libre ? »
     *
     * <p>La boîte à tester est relue sur la forme que vanilla vient de construire, et non sur la
     * variable locale d'où elle sort. C'est plus robuste <em>et</em> plus juste : la boîte d'origine
     * est mutable et vanilla l'agrandit parfois juste avant l'appel ({@code encapsulate}, pour la
     * rallonge des pièces plates). Lire la forme, c'est lire la question réellement posée.
     */
    @Redirect(
            method = "tryPlacingChildren(Lnet/minecraft/world/level/levelgen/structure/"
                    + "PoolElementStructurePiece;Lorg/apache/commons/lang3/mutable/MutableObject;IZ"
                    + "Lnet/minecraft/world/level/LevelHeightAccessor;"
                    + "Lnet/minecraft/world/level/levelgen/RandomState;"
                    + "Lnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;"
                    + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty("
                            + "Lnet/minecraft/world/phys/shapes/VoxelShape;"
                            + "Lnet/minecraft/world/phys/shapes/VoxelShape;"
                            + "Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
    private boolean lanterne$fits(VoxelShape free, VoxelShape candidate, BooleanOp operation) {
        // L'opération est vérifiée plutôt que supposée : la décomposition du cadastre ne vaut que
        // pour ONLY_SECOND, c'est-à-dire pour la question « le candidat déborde-t-il du libre ? ».
        // Si un autre mod venait à en poser une autre, vanilla reprend la main sans discuter.
        if (operation != BooleanOp.ONLY_SECOND || candidate.isEmpty()) {
            return Shapes.joinIsNotEmpty(free, candidate, operation);
        }
        if (this.lanterne$plots == null) {
            this.lanterne$plots = Cadastre.plots();
        }
        Cadastre.Region plot = Cadastre.region(this.lanterne$plots, free);
        if (plot == null) {
            return Shapes.joinIsNotEmpty(free, candidate, operation);
        }
        // Le sens est inversé : vanilla demande « déborde-t-il ? », le registre répond « tient-il ? ».
        return !plot.fits(candidate, candidate.bounds());
    }

    /**
     * « Retire la place de cette pièce du libre. »
     *
     * <p>On rend la forme de départ <b>inchangée</b>, et c'est tout l'intérêt : la case
     * {@code MutableObject} de vanilla garde une forme de vanilla, valide et cohérente avec
     * elle-même, au lieu de recevoir un objet qui mentirait sur sa propre géométrie. Ce que cette
     * case ne dit plus, c'est le <em>moment</em> — elle décrit la région libre du départ et non celle
     * d'après la dernière pièce. {@code Cadastre.Region.materialise()} la reconstruit à l'identique
     * pour qui en aurait besoin.
     *
     * <p>Si la table n'existe pas encore, c'est que {@code joinIsNotEmpty} n'a jamais pris cette
     * région en charge — module éteint, opération inattendue, forme de départ partagée. On laisse
     * alors vanilla soustraire, faute de quoi la forme de la case deviendrait fausse au lieu d'être
     * seulement datée.
     */
    @Redirect(
            method = "tryPlacingChildren(Lnet/minecraft/world/level/levelgen/structure/"
                    + "PoolElementStructurePiece;Lorg/apache/commons/lang3/mutable/MutableObject;IZ"
                    + "Lnet/minecraft/world/level/LevelHeightAccessor;"
                    + "Lnet/minecraft/world/level/levelgen/RandomState;"
                    + "Lnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;"
                    + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;joinUnoptimized("
                            + "Lnet/minecraft/world/phys/shapes/VoxelShape;"
                            + "Lnet/minecraft/world/phys/shapes/VoxelShape;"
                            + "Lnet/minecraft/world/phys/shapes/BooleanOp;)"
                            + "Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape lanterne$occupy(VoxelShape free, VoxelShape taken, BooleanOp operation) {
        Map<VoxelShape, Cadastre.Region> plots = this.lanterne$plots;
        if (plots == null || operation != BooleanOp.ONLY_FIRST || taken.isEmpty()) {
            return Shapes.joinUnoptimized(free, taken, operation);
        }
        Cadastre.Region plot = plots.get(free);
        if (plot == null) {
            return Shapes.joinUnoptimized(free, taken, operation);
        }
        plot.file(taken.bounds());
        return free;
    }
}
