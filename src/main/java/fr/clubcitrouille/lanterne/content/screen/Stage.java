package fr.clubcitrouille.lanterne.content.screen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * Ce que l'écran et le projecteur ont en commun : une séance.
 *
 * <h2>Pourquoi une interface plutôt que deux chemins parallèles</h2>
 *
 * <p>Les deux blocs diffèrent par où l'image atterrit, et par rien d'autre. Ils ont la même source,
 * la même horloge, les mêmes réglages, le même verrou, et le joueur leur adresse les mêmes gestes :
 * lire, mettre en pause, déplacer le curseur, changer le volume.
 *
 * <p>Sans cette interface, tout ce qui suit existerait en deux exemplaires : les plis, leur
 * vérification côté serveur, l'écran de réglages, et la logique cliente qui décide quoi décoder. Deux
 * exemplaires d'une vérification de permission, c'est une permission qu'on oubliera de corriger d'un
 * côté — et la porte ouverte ne sera découverte que par celui qui l'aura poussée.
 *
 * <p>Avec elle, il y a un jeu de plis, une vérification, un écran de réglages, et deux rendus. La
 * différence entre les deux blocs est reléguée là où elle est réelle : dans la géométrie.
 */
public interface Stage {
    /** La source et ses réglages. */
    Feed feed();

    /** L'ancre de l'horloge partagée. */
    Clock clock();

    /** Pose les deux ensemble, et prévient les clients. Voir {@code PanelEntity.setShow}. */
    void setShow(Feed wanted, Clock beat);

    /** Ce joueur peut-il changer ce qui s'affiche ici ? Voir {@link Feed.Lock}. */
    boolean mayCommand(Player player);

    /** Où se trouve ce bloc. Sert à retrouver l'un ou l'autre depuis un pli. */
    BlockPos stagePos();

    /** Le nom lisible de celui qui a posé le bloc, pour l'écrire dans l'interface. */
    String ownerName();

    /** Un écran est un mur, un projecteur est un cône. L'interface doit pouvoir le dire. */
    boolean projector();
}
