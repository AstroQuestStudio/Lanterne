package fr.clubcitrouille.lanterne.core;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Le reflet : une entité côté client ne pousse personne, elle ne fait que refléter le serveur.
 *
 * <h2>MC-228976, vérifié au bytecode sur ce moteur, pas supposé</h2>
 *
 * <p>{@code javap -p -c -constants} sur {@code LivingEntity.class} du jar 26.3 patché montre que
 * {@code aiStep()} appelle {@code pushEntities()} sans aucune condition de côté :
 *
 * <pre>
 *    806: aload_0
 *    807: invokevirtual #3508   // Method pushEntities:()V
 * </pre>
 *
 * <p>— juste après {@code checkAutoSpinAttack}, dans le même bloc que le test de gel et le
 * {@code profiler.push("push")} du jeu. Rien ne garde ce chemin pour le serveur seul, contrairement
 * à la cramming damage un peu plus loin dans la même méthode, qui lit bien
 * {@code instanceof ServerLevel} avant d'agir. Et {@code pushEntities()} lui-même appelle
 * {@code level().getPushableEntities(this, getBoundingBox())} — une recherche spatiale dans la
 * section de chunk — <b>avant même</b> de savoir si le résultat sert à quoi que ce soit :
 *
 * <pre>
 *    0: aload_0
 *    1: invokevirtual #368   // Method level:()Lnet/minecraft/world/level/Level;
 *    ...
 *    9: invokevirtual #3534  // Method Level.getPushableEntities(Entity, AABB):List
 * </pre>
 *
 * <p>C'est très exactement le bogue Mojira MC-228976 : « Entity collision is run on render
 * thread ». Chaque créature chargée côté client — vue ou non, à trois blocs ou à cent cinquante —
 * refait donc chaque tick la recherche de voisines poussables et la boucle de poussée, sur le fil
 * de rendu, pour un résultat que la prochaine synchronisation réseau écrase de toute façon.
 *
 * <h2>Ce que {@link Shove} et {@link Jam} ne couvrent PAS</h2>
 *
 * <p>{@code LivingEntityMixin} tronque déjà cette recherche et court-circuite déjà la bousculade
 * des amas immobiles — mais les deux se retirent explicitement du côté client
 * ({@code self.level().isClientSide()} retourne sans agir), parce que leurs tables ne sont pas
 * synchronisées et ne doivent être touchées que par le fil serveur — voir le Javadoc de
 * {@link Jam}. Résultat : sur le client, {@code Shove.pushables} retombe sur
 * {@code level.getPushableEntities(pusher, box)}, la recherche <b>vanilla complète</b>, non
 * plafonnée — exactement celle que {@code Shove} existe pour éviter côté serveur. Le client
 * paie donc aujourd'hui le poste que {@code EntitySection.getEntities} pèse pour 24 % du travail
 * serveur mesuré par {@code Shove}, sans qu'aucun module de ce dépôt ne l'allège.
 *
 * <h2>Pourquoi annuler l'appel entier, et pas seulement l'alléger</h2>
 *
 * <p>Le résultat de {@code pushEntities()} côté client ne sert à rien d'observable : ce n'est pas
 * lui qui décide où une entité apparaît à l'écran. Une entité qui n'est pas le joueur local est
 * positionnée par interpolation vers ce que le serveur envoie ({@code lerpTo}) ; la poussée
 * locale ne fait qu'ajouter un tremblement de prédiction que la prochaine mise à jour réseau
 * corrige de toute façon, en général au tick suivant. Le supprimer entièrement — ni recherche, ni
 * boucle de poussée — plutôt que de le plafonner comme {@link Shove} le fait côté serveur, est
 * donc exact et non une approximation : rien n'est perdu que le jeu n'aurait de toute façon jamais
 * montré durablement.
 *
 * <p>C'est le même principe que celui d'un mod de référence sur ce bogue précis (33 millions de
 * téléchargements cumulés à ce jour), qui annule {@code pushEntities} sans condition côté client.
 * Ce module ne copie pas son code — il est écrit pour NeoForge 1.18, une base incompatible avec
 * celle-ci — mais en reprend le raisonnement, vérifié indépendamment sur CE moteur.
 *
 * <h2>Ce que ce module ne touche pas</h2>
 *
 * <p>{@code Entity.isInWall()}, que le même mod de référence annule aussi côté client, est déjà
 * gardé par {@code instanceof ServerLevel} dans {@code livingEntityBaseTick} sur ce moteur
 * (vérifié au même désassemblage) : Mojang a fermé cette partie du bogue depuis. Rien à faire là.
 *
 * <h2>Non mesuré en jeu, donc éteint par défaut n'est pas la règle ici — et pourquoi</h2>
 *
 * <p>Contrairement à {@code Horizon} ou {@code Guet}, ce module reste <b>allumé</b> par défaut,
 * bien qu'aucun client réel n'ait pu le mesurer depuis ce dépôt. La différence tient à la nature
 * du changement : {@code Horizon} et {@code Guet} risquent de faire disparaître ou de mal ordonner
 * quelque chose à l'écran si le raisonnement se révèle incomplet — un risque qui ne se juge qu'à
 * l'œil. Ici, annuler {@code pushEntities()} ne peut, par construction, qu'empêcher une créature
 * cliente de déplacer une autre créature cliente de quelques millièmes de bloc — un mouvement déjà
 * écrasé par le prochain paquet de position. Le pire cas mesurable est un léger chevauchement
 * visuel temporaire dans un tas dense, pas une régression de justesse.
 */
public final class Reflet {
    private Reflet() {}

    /** Appels à {@code pushEntities} évités côté client depuis le dernier {@link #resetCounters}. */
    private static final AtomicLong SKIPPED = new AtomicLong();

    /**
     * Faut-il annuler cette poussée ? Appelé depuis {@code LivingEntityMixin}, sur l'entité qui
     * s'apprêtait à chercher ses voisines.
     *
     * <p>Ne fait qu'un test de côté et un compteur : aucune table, aucune allocation, aucun état
     * partagé entre fils. C'est délibéré — voir la note de {@link Jam} sur la course de données
     * qu'un état partagé provoquerait ici en solo, où le fil client et le fil du serveur intégré
     * appellent tous deux ce point pour la même créature.
     */
    public static boolean skip(boolean clientSide) {
        if (!clientSide) {
            return false;
        }
        SKIPPED.incrementAndGet();
        return true;
    }

    /** Un résumé lisible pour Radiographie. */
    public static String report() {
        long skipped = SKIPPED.get();
        return String.format(Locale.ROOT,
                "reflet : %d bousculade(s) cliente(s) evitee(s) (recherche + poussee, MC-228976)",
                skipped);
    }

    public static void resetCounters() {
        SKIPPED.set(0L);
    }
}
