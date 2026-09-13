package fr.clubcitrouille.lanterne.core;

/**
 * Ce que toute entité sait dire de sa cadence, une fois le mixin correspondant appliqué.
 *
 * <h2>Pourquoi cette interface vit ici et non avec les mixins</h2>
 *
 * <p>Première tentative : la poser dans le paquet {@code mixin}, auprès de la classe qui l'implémente.
 * Le serveur a refusé de démarrer :
 *
 * <pre>
 * IllegalClassLoadError: fr.clubcitrouille.lanterne.mixin.PaceHolder is in a defined mixin package
 * </pre>
 *
 * <p>Un paquet déclaré comme paquet de mixins appartient entièrement au transformateur : tout ce qui s'y
 * trouve est censé être un mixin, et une classe ordinaire y est rejetée au chargement. Les interfaces
 * annotées {@code @Mixin} — comme les accesseurs de ce projet — n'ont pas ce problème, puisqu'elles en
 * sont.
 *
 * <p>Le contrat vit donc du côté de {@code core}, qui est aussi celui qui l'interroge. C'est plus juste
 * ainsi : le mixin fournit une capacité, {@code core} définit ce qu'il en attend.
 */
public interface PaceHolder {
    /**
     * La cadence retenue pour ce tick, ou zéro si elle ne l'a pas été.
     *
     * <p>Zéro et non moins un : l'appelant doit de toute façon distinguer « pas de valeur » de
     * « cadence un », et zéro n'est jamais une cadence valide.
     */
    int lanterne$paceAt(long tick);

    void lanterne$rememberPace(int period, long tick);
}
