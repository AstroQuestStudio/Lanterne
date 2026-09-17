package fr.clubcitrouille.lanterne.core;

import net.minecraft.nbt.CompoundTag;

/**
 * La porte de service vers {@code IOWorker$PendingStore}.
 *
 * <p>Cette classe imbriquée de vanilla est {@code private static}, donc invisible par son nom
 * depuis n'importe quel autre fichier — y compris un mixin qui cible {@code IOWorker} lui-même :
 * le compilateur refuse de nommer un type privé d'une classe qu'on ne compile pas soi-même, quelle
 * que soit la fusion que Mixin fera ensuite au chargement.
 *
 * <p>{@link fr.clubcitrouille.lanterne.mixin.IOWorkerPendingStoreMixin} cible cette classe imbriquée
 * par son nom binaire, en chaîne — {@code targets = "...IOWorker$PendingStore"} — ce qui ne passe
 * jamais par le compilateur Java et échappe donc à la restriction. Il implémente cette interface, et
 * {@link fr.clubcitrouille.lanterne.mixin.IOWorkerMixin} n'a plus besoin de connaître le type réel :
 * un cast vers cette interface suffit.
 */
public interface PendingCopy {
    /** Copie de la donnée en attente d'écriture, ou {@code null} si elle vaut déjà {@code null}. */
    CompoundTag lanterne$copy();
}
