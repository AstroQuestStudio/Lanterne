package fr.clubcitrouille.lanterne.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le balai : ce qu'un serveur peut supprimer sans que personne ne s'en plaigne.
 *
 * <h2>Un nettoyeur se juge sur ce qu'il refuse d'effacer</h2>
 *
 * <p>Tous les mods de nettoyage font la même chose facile — supprimer les objets au sol — et c'est la
 * partie qui ne pose aucun problème. Ce qui distingue un bon nettoyeur d'un mauvais tient
 * entièrement à sa <b>liste d'exclusions</b> : un serveur où l'on perd un animal apprivoisé, un
 * villageois de commerce ou un cheval nommé n'a pas gagné en performance, il a perdu ses joueurs.
 *
 * <p>Ce balai refuse donc, dans l'ordre :
 *
 * <ul>
 *   <li><b>Tout ce qui porte un nom.</b> Un joueur qui a pris une étiquette et l'a posée sur une
 *       créature a dit exactement ce qu'il fallait dire : celle-ci compte.</li>
 *   <li><b>Tout ce qui est apprivoisé, monté, attaché ou persistant.</b> Un loup, un cheval sellé,
 *       une créature en laisse, une créature marquée comme persistante par le jeu.</li>
 *   <li><b>Tout ce qui n'est pas une créature.</b> Cadres, supports d'armure, bateaux, wagonnets,
 *       tableaux : ce sont des constructions, pas de la charge.</li>
 *   <li><b>Les villageois, quels qu'ils soient.</b> Ils sont chers, et c'est justement pourquoi on
 *       est tenté de les effacer. Ils sont aussi le commerce d'un serveur entier.</li>
 *   <li><b>Ce qui est près d'un joueur.</b> Faire disparaître un tas d'objets sous les yeux de celui
 *       qui vient de le faire tomber est la seule faute qu'un joueur ne pardonne pas.</li>
 *   <li><b>Ce qui est sur la liste blanche.</b> Minerais et armure par défaut, modifiable sans
 *       recompiler — voir {@link Config#BROOM_ITEM_WHITELIST}. Un objet précieux tombé au sol reste
 *       précieux même sans nom et même loin d'un joueur.</li>
 * </ul>
 *
 * <h2>Pourquoi ce mod en a un, alors qu'il passe son temps à dire que supprimer n'est pas optimiser</h2>
 *
 * <p>Le socle de ce mod ne retire jamais rien au jeu : il fait le même travail pour moins cher. Le
 * balai fait l'inverse — il supprime. C'est un <b>aveu</b>, et il est rangé parmi les ajouts, éteint
 * par défaut, pour cette raison.
 *
 * <p>Il existe néanmoins, parce qu'un administrateur qui en a besoin en installera un de toute façon,
 * et qu'un nettoyeur écrit avec les exclusions ci-dessus vaut mieux qu'un nettoyeur générique branché
 * à côté. La fusion des objets au sol — voir {@link Gather} — réduit d'ailleurs le besoin d'y
 * recourir : soixante-quatre objets devenus une pile n'ont plus besoin d'être effacés.
 */
public final class Broom {
    /**
     * Rayon de sauvegarde autour de chaque joueur, en blocs.
     *
     * <p>Seize : la portée à laquelle un joueur voit ses objets tomber et va les ramasser. En deçà,
     * on efface sous ses yeux.
     */
    private static final double SHELTER = 16.0d;

    /** Ce qu'un passage du balai a emporté. */
    public record Haul(int items, int mobs, int arrows) {
        public int total() {
            return items + mobs + arrows;
        }

        public String describe() {
            return String.format(Locale.ROOT, "%d objet(s) au sol, %d créature(s) hostile(s), "
                    + "%d projectile(s)", items, mobs, arrows);
        }
    }

    private static long swept;

    private Broom() {}

    /**
     * Passe le balai sur un monde.
     *
     * @param items    effacer les objets au sol
     * @param mobs     effacer les créatures hostiles sans propriétaire
     * @param arrows   effacer les projectiles plantés
     */
    public static Haul sweep(ServerLevel level, boolean items, boolean mobs, boolean arrows) {
        List<Entity> doomed = new ArrayList<>();
        int wipedItems = 0;
        int wipedMobs = 0;
        int wipedArrows = 0;

        for (Entity soul : level.getAllEntities()) {
            if (!eligible(level, soul)) {
                continue;
            }
            if (items && soul instanceof ItemEntity itemSoul) {
                if (whitelisted(itemSoul.getItem())) {
                    continue;
                }
                doomed.add(soul);
                wipedItems++;
            } else if (arrows && soul instanceof Projectile) {
                doomed.add(soul);
                wipedArrows++;
            } else if (mobs && soul instanceof Mob creature && hostile(creature)) {
                doomed.add(soul);
                wipedMobs++;
            }
        }

        for (Entity soul : doomed) {
            soul.discard();
        }
        swept += doomed.size();
        return new Haul(wipedItems, wipedMobs, wipedArrows);
    }

    /**
     * Cette entité peut-elle être effacée ?
     *
     * <p>Les refus sont dans l'en-tête de la classe. Ils sont volontairement larges : un nettoyeur
     * qui hésite laisse une entité de trop, ce qui coûte un tick ; un nettoyeur qui tranche efface
     * une monture, ce qui coûte un joueur.
     */
    private static boolean eligible(ServerLevel level, Entity soul) {
        if (soul.isRemoved() || soul instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }
        // Un nom, c'est une déclaration d'intention. On s'y arrête toujours.
        if (soul.hasCustomName()) {
            return false;
        }
        if (soul.isVehicle() || soul.isPassenger()) {
            return false;
        }
        if (soul instanceof Mob creature) {
            if (creature.isPersistenceRequired() || creature.isLeashed()) {
                return false;
            }
            if (creature instanceof net.minecraft.world.entity.TamableAnimal tamed && tamed.isTame()) {
                return false;
            }
            if (creature instanceof net.minecraft.world.entity.npc.villager.Villager
                    || creature instanceof net.minecraft.world.entity.npc.wanderingtrader
                            .WanderingTrader) {
                return false;
            }
        }
        // Sous les yeux d'un joueur, on ne touche à rien.
        for (var watcher : level.players()) {
            if (watcher.distanceToSqr(soul) < SHELTER * SHELTER) {
                return false;
            }
        }
        return true;
    }

    /** Une créature hostile, au sens du jeu : celles que le spawner compte comme monstres. */
    private static boolean hostile(Mob creature) {
        return creature.getType().getCategory() == MobCategory.MONSTER;
    }

    /**
     * Cet objet est-il protégé par la liste blanche ?
     *
     * <p>Chaque entrée est soit un identifiant d'objet exact, soit un tag préfixé de {@code #}. La
     * liste par défaut couvre les minerais et l'armure — voir {@link Config#BROOM_ITEM_WHITELIST} —
     * mais reste modifiable sans toucher au code : c'est tout l'intérêt.
     */
    private static boolean whitelisted(ItemStack stack) {
        for (String raw : Config.BROOM_ITEM_WHITELIST.get()) {
            boolean isTag = raw.startsWith("#");
            Identifier id = Identifier.tryParse(isTag ? raw.substring(1) : raw);
            if (id == null) {
                continue;
            }
            boolean match = isTag
                    ? stack.is(holder -> holder.is(ItemTags.create(id)))
                    : stack.is(holder -> holder.is(id));
            if (match) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prévient les joueurs, avec le compte à rebours.
     *
     * <p>Passe par la barre d'action, pas le chat : elle remplace toujours son propre message
     * précédent au lieu de s'empiler, ce qui est exactement le comportement voulu pour un compte à
     * rebours qui se resserre. La couleur suit l'urgence — doré au large, rouge quand ça presse, gras
     * dans les toutes dernières secondes.
     */
    public static void warn(MinecraftServer server, int seconds) {
        ChatFormatting[] style = seconds >= 60
                ? new ChatFormatting[] {ChatFormatting.GOLD}
                : seconds >= 10
                        ? new ChatFormatting[] {ChatFormatting.RED}
                        : new ChatFormatting[] {ChatFormatting.RED, ChatFormatting.BOLD};
        var text = Component.literal(String.format(Locale.ROOT,
                        "Nettoyage du sol dans %d seconde%s.", seconds, seconds > 1 ? "s" : ""))
                .withStyle(style);
        var packet = new ClientboundSetActionBarTextPacket(text);
        for (var soul : server.getPlayerList().getPlayers()) {
            soul.connection.send(packet);
        }
    }

    /** Annonce le résultat. */
    public static void announce(MinecraftServer server, Haul haul) {
        var text = Component.literal("Nettoyage : " + haul.describe() + ".")
                .withStyle(ChatFormatting.GRAY);
        for (var soul : server.getPlayerList().getPlayers()) {
            soul.sendSystemMessage(text);
        }
        Lanterne.LOG.info("[BALAI] {}", haul.describe());
    }

    public static long sweptTotal() {
        return swept;
    }
}
