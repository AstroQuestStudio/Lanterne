package fr.clubcitrouille.lanterne.core;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

/**
 * La bousculade : arrêter de <em>chercher</em>, et pas seulement d'agir.
 *
 * <h2>Le plafond qui ne plafonnait que la moitié du travail</h2>
 *
 * <p>Ce mod tronquait déjà la liste des voisines à pousser au-delà d'un certain nombre. C'était le bon
 * geste, et il ne portait que sur la <b>seconde</b> moitié de la dépense.
 *
 * <p>Le profileur de l'épreuve de l'enclos l'a montré sans ambiguïté : {@code
 * EntitySection.getEntities} pèse <b>vingt-quatre pour cent du travail réel du serveur</b>, mod actif.
 * C'est-à-dire que le poste le plus lourd du serveur était celui qu'on croyait avoir traité.
 *
 * <p>La raison se lit dans le jeu :
 *
 * <pre>
 * public AbortableIterationConsumer.Continuation getEntities(AABB bb, AbortableIterationConsumer&lt;T&gt; entities) {
 *     for (T entity : this.storage) {
 *         if (entity.getBoundingBox().intersects(bb) &amp;&amp; entities.accept(entity).shouldAbort()) {
 * </pre>
 *
 * <p>Le parcours porte sur <b>tout le contenu de la section</b>, et non sur ce qui est dans la boîte.
 * Mille vaches dans une même section, c'est mille tests d'intersection par requête — et une requête
 * par vache et par tick, soit <b>un million de tests d'intersection par tick</b>. Tronquer le
 * résultat à vingt-six n'en évitait pas un seul.
 *
 * <h2>Le jeu savait s'arrêter, on ne le lui demandait pas</h2>
 *
 * <p>Le type de retour le dit : {@code AbortableIterationConsumer}. Le parcours est interruptible
 * depuis toujours ; il suffit de rendre {@code ABORT}. Vanilla ne s'en sert pas ici parce qu'il veut
 * la liste complète — mais nous, nous avons déjà décidé de n'en garder qu'une partie.
 *
 * <p>Et c'est ce qui rend cette optimisation particulière : elle <b>ne change rien du tout</b>. La
 * troncature retenait les vingt-six premières de l'ordre de parcours ; l'interruption retient les
 * vingt-six premières du même ordre de parcours. Même ensemble, même résultat, un huitième du coût.
 * Ce n'est pas un compromis de plus, c'est le même compromis enfin payé à son juste prix.
 *
 * <h2>Deux limites, et pourquoi il en faut deux</h2>
 *
 * <p>La tentation serait d'interrompre au plafond lui-même — vingt-six. Ce serait une régression
 * silencieuse : en deçà du seuil de foule, ce mod rend la liste <b>intacte</b>, et deux ou trois
 * cochons qui se bousculent doivent continuer de le faire exactement comme d'habitude.
 *
 * <p>On interrompt donc <em>au-delà</em> du seuil de foule. Si le parcours s'arrête, c'est qu'il y a
 * foule, et on tronque ; s'il va au bout, on avait déjà la liste complète et on la rend telle quelle.
 * La distinction est gratuite et elle préserve la garantie.
 *
 * <h2>L'écrasement, qui a failli être cassé</h2>
 *
 * <p>Le comptage des dégâts d'entassement lit <b>cette même liste</b>. Tronquer sous le seuil
 * {@code max_entity_cramming} supprimerait une mécanique du jeu sans que rien ne le signale — et les
 * fermes qui reposent sur ces dégâts cesseraient de produire.
 *
 * <p>La règle est réglable, et certains serveurs la montent à quarante. Les deux limites suivent donc
 * sa valeur réelle au lieu d'une constante choisie une fois : on lit la règle, et l'on s'assure de
 * rester au-dessus. Un plafond en dur aurait fonctionné sur la configuration par défaut et cassé
 * discrètement les autres, ce qui est la pire forme d'échec pour un mod d'optimisation.
 */
public final class Shove {
    /**
     * Nombre de voisines réellement poussées dans une foule.
     *
     * <p>Au-delà de quelques poussées, une de plus ne change pas la position finale : les forces
     * s'annulent et la créature est coincée de toute façon. Paper plafonne ainsi depuis des années
     * sur des dizaines de milliers de serveurs.
     */
    private static final int PUSHED = 26;

    /**
     * En deçà, il n'y a pas foule et l'on ne touche à rien.
     *
     * <p>Même valeur que le seuil de densité : la même situation, jugée de la même façon.
     */
    private static final int CROWD = 32;

    private Shove() {}

    /**
     * Les voisines à pousser, cherchées avec un plafond.
     *
     * <p>Rend exactement ce que rendait la troncature, sans le parcours qu'elle laissait faire.
     */
    public static List<Entity> pushables(Level level, Entity pusher, AABB box) {
        if (!Settings.collisions() || !(level instanceof ServerLevel server)) {
            return level.getPushableEntities(pusher, box);
        }

        int cramming = server.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        // Rester au-dessus de la règle d'entassement, sinon c'est le comptage des dégâts d'écrasement
        // qu'on fausse — une mécanique du jeu, pas un détail de rendu.
        int keep = Math.max(PUSHED, cramming + 2);
        int scan = Math.max(CROWD, cramming) + 1;

        var sections = sectionsOf(server);
        if (sections == null) {
            return level.getPushableEntities(pusher, box);
        }

        var selector = EntitySelector.pushableBy(pusher);
        List<Entity> found = new ArrayList<>(scan);

        // On passe par le stockage de sections et non par l'interface publique : c'est le seul chemin
        // à la fois interruptible et sans enveloppe. Voir EntityGetterAccessor, qui raconte les deux
        // autres et ce qu'ils coûtaient.
        sections.getEntities(box, candidate -> {
            if (candidate != pusher && selector.test(candidate)) {
                found.add(candidate);
                if (found.size() >= scan) {
                    return AbortableIterationConsumer.Continuation.ABORT;
                }
            }
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });

        // Les segments du dragon de l'End sont stockés à part et ne figurent dans aucune section.
        // Le jeu les ajoute à la main dans « getEntities » ; les omettre ferait traverser le dragon,
        // ce qui est très exactement le genre de dégât invisible qu'on refuse.
        for (var part : level.dragonParts()) {
            if (part != pusher && part.getParent() != pusher && selector.test(part)
                    && box.intersects(part.getBoundingBox())) {
                found.add(part);
            }
        }

        // Le parcours est allé au bout : la liste est complète, le jeu garde ses droits.
        if (found.size() < scan) {
            return found;
        }
        // Il s'est interrompu : il y a foule. On rend une vue, sans rien allouer de plus.
        return found.subList(0, Math.min(keep, found.size()));
    }

    /**
     * Le stockage de sections du monde, s'il est de la forme attendue.
     *
     * <p>Rien n'oblige un mod tiers à laisser en place l'implémentation d'origine. Si elle a été
     * remplacée, on rend {@code null} et l'appelant retombe sur le chemin du jeu : plus lent, mais
     * juste. Un mod d'optimisation qui suppose une implémentation et la trouve changée doit céder le
     * pas, pas jeter une exception au milieu du tick.
     */
    @SuppressWarnings("unchecked")
    private static net.minecraft.world.level.entity.EntitySectionStorage<Entity> sectionsOf(
            ServerLevel server) {
        var getter = server.getEntities();
        if (getter instanceof net.minecraft.world.level.entity.LevelEntityGetterAdapter) {
            return ((fr.clubcitrouille.lanterne.mixin.EntityGetterAccessor<Entity>) getter)
                    .lanterne$sections();
        }
        return null;
    }
}
