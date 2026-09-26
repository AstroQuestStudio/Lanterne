package fr.clubcitrouille.lanterne.content.reseau;

import java.util.Locale;
import java.util.Map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.content.trieur.Motif;
import fr.clubcitrouille.lanterne.core.Config;

/**
 * L'appel au Guichet par écrit (26.3.142) : {@code /lanterne appel 64 cobblestone}, {@code /lanterne
 * appel 1 épée !or}, et {@code /lanterne salle} pour voir ce que le Guichet voisin a relevé.
 *
 * <h2>Pourquoi une commande, en plus de l'écran</h2>
 *
 * <p>L'écran du Guichet demande une pile par clic. Une commande dit la quantité exacte, accepte un MOTIF
 * de Trieur (même syntaxe, même code : {@link Motif}) — « épée !or » trouve l'épée la mieux fournie qui
 * n'est pas en or — et se tape sans ouvrir d'écran. Elle sert aussi à l'administration : par RCON,
 * {@code execute positioned <x y z> run lanterne salle} dit ce qu'un Guichet voit, sans client.
 *
 * <h2>Aucune portée magique</h2>
 *
 * <p>La commande agit sur le Guichet le plus proche À {@link #PORTEE} BLOCS de la position de la
 * source — pour un joueur, lui-même. Pas d'appel à distance : on vient au Guichet, comme on vient à un
 * coffre. Ouverte à tous les joueurs, pour cette raison exacte ; livraison dans le coffre de collecte
 * du Guichet, jamais dans l'inventaire.
 */
public final class Appel {
    /** Distance maximale joueur → Guichet, en blocs (pavé). */
    static final int PORTEE = 6;

    private Appel() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lanterne")
                .then(Commands.literal("appel")
                        .then(Commands.argument("quantite", IntegerArgumentType.integer(1, Reseaux.DEMANDE_MAX))
                                .then(Commands.argument("objet", StringArgumentType.greedyString())
                                        .executes(context -> appeler(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "quantite"),
                                                StringArgumentType.getString(context, "objet"))))))
                .then(Commands.literal("salle")
                        .executes(context -> salle(context.getSource()))));
    }

    /** Le Guichet le plus proche de {@code centre}, à {@link #PORTEE} blocs au plus, ou {@code null}. */
    static @Nullable GuichetBlockEntity guichetProche(ServerLevel level, BlockPos centre) {
        GuichetBlockEntity meilleur = null;
        double distance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-PORTEE, -PORTEE, -PORTEE),
                centre.offset(PORTEE, PORTEE, PORTEE))) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof GuichetBlockEntity guichet) {
                double d = pos.distSqr(centre);
                if (d < distance) {
                    distance = d;
                    meilleur = guichet;
                }
            }
        }
        return meilleur;
    }

    private static int appeler(CommandSourceStack source, int quantite, String texte) {
        if (!Config.RESEAU_ACTIF.get()) {
            source.sendFailure(Component.literal("Le Réseau est désactivé sur ce serveur."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        GuichetBlockEntity guichet = guichetProche(level, BlockPos.containing(source.getPosition()));
        if (guichet == null) {
            source.sendFailure(Component.literal("Aucun Guichet à " + PORTEE + " blocs."));
            return 0;
        }
        Map<Item, Integer> stock = guichet.stock(level);
        Item item = choisir(stock, texte);
        if (item == null) {
            source.sendFailure(Component.literal("Rien en stock ne correspond à « " + texte.trim() + " »."));
            return 0;
        }
        String nom = item.getDefaultInstance().getHoverName().getString();
        int livre = guichet.livrer(level, item, quantite);
        if (livre < 0) {
            source.sendFailure(Component.literal("Aucun coffre de collecte à côté du Guichet."));
            return 0;
        }
        int restant = stock.getOrDefault(item, 0) - livre;
        if (livre == 0) {
            source.sendFailure(Component.literal("Coffre de collecte plein : 0 × " + nom + " livré."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Livré : " + livre + " × " + nom
                + (livre < quantite ? " (sur " + quantite + " demandés)" : "")
                + " — il en reste " + restant + "."), false);
        return livre;
    }

    /**
     * L'objet visé par {@code texte}, parmi ce qui est EN STOCK : un identifiant exact d'abord
     * ({@code cobblestone}, {@code minecraft:cobblestone}), sinon l'objet le mieux fourni qui
     * correspond au motif — « cobblestone » seul désigne donc la pierre taillée et non ses dalles,
     * « épée » l'épée dont on a le plus.
     */
    static @Nullable Item choisir(Map<Item, Integer> stock, String texte) {
        String brut = texte.trim();
        if (brut.isEmpty()) {
            return null;
        }
        Identifier id = Identifier.tryParse(brut.toLowerCase(Locale.ROOT).replace(' ', '_'));
        if (id != null) {
            Item exact = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (exact != null && stock.getOrDefault(exact, 0) > 0) {
                return exact;
            }
        }
        Motif motif = Motif.compiler(brut);
        if (motif.estVide()) {
            return null;
        }
        Item meilleur = null;
        int quantite = 0;
        for (Map.Entry<Item, Integer> entree : stock.entrySet()) {
            if (entree.getValue() > quantite
                    && motif.correspond(BuiltInRegistries.ITEM.wrapAsHolder(entree.getKey()), null)) {
                meilleur = entree.getKey();
                quantite = entree.getValue();
            }
        }
        return meilleur;
    }

    private static int salle(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        GuichetBlockEntity guichet = guichetProche(level, BlockPos.containing(source.getPosition()));
        if (guichet == null) {
            source.sendFailure(Component.literal("Aucun Guichet à " + PORTEE + " blocs."));
            return 0;
        }
        long debut = System.nanoTime();
        SalleDeTri.Releve releve = SalleDeTri.relever(level, guichet.getBlockPos(),
                Config.RESEAU_PORTEE_SALLE.get());
        long micro = (System.nanoTime() - debut) / 1000L;
        Map<Item, Integer> stock = guichet.stock(level);
        long total = 0;
        for (int n : stock.values()) {
            total += n;
        }
        GuichetBlockEntity.Collecte collecte = guichet.collecte();
        BlockPos g = guichet.getBlockPos();
        long objets = total;
        source.sendSuccess(() -> Component.literal("Guichet " + g.getX() + " " + g.getY() + " " + g.getZ()
                + " : " + releve.trieurs() + " Trieur(s), " + releve.stockages().size()
                + " moitié(s) de coffre relevée(s) en " + micro + " µs ; " + stock.size() + " type(s), "
                + objets + " objet(s) ; collecte : "
                + (collecte == null ? "AUCUNE" : collecte.positions().size() + " bloc(s)") + "."), false);
        return releve.stockages().size();
    }
}
