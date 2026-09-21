package fr.clubcitrouille.lanterne.client.bilan;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;

/**
 * Le SEUL endroit du bilan qui touche des classes d'Iris. Chargée uniquement quand Iris est actif
 * (voir {@link Bilan}) : si Iris est inerte (sans Sodium), ses classes ne doivent pas être tirées, et
 * tout accès est de toute façon entouré d'un {@code catch (Throwable)}.
 */
final class BilanIris {
    private BilanIris() {}

    static String packName() {
        return Iris.getCurrentPackName();
    }

    static boolean shadersEnabled() {
        return Iris.getIrisConfig().areShadersEnabled();
    }

    static boolean packInUse() {
        return Iris.isPackInUseQuick();
    }

    static int shadowDistance() {
        return IrisVideoSettings.shadowDistance;
    }
}
