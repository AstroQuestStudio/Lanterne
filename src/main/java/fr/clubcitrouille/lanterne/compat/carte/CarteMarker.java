package fr.clubcitrouille.lanterne.compat.carte;

import java.util.Optional;

import net.minecraft.resources.Identifier;

/**
 * Un point à montrer sur une carte web, neutre vis-à-vis de l'implémentation de carte (BlueMap
 * aujourd'hui, une autre demain). Voir {@link MarkerSource}.
 *
 * @param id identifiant stable de ce point — sert de clé sur la carte, et de cible pour
 *         {@code linkedMarkerId}.
 * @param label le nom affiché.
 * @param dimension le monde où se trouve le point.
 * @param x coordonnée monde, axe X.
 * @param y coordonnée monde, axe Y (hauteur).
 * @param z coordonnée monde, axe Z.
 * @param colour la teinte, en 0xRRGGBB.
 * @param linkedMarkerId l'identifiant d'un autre point de la MÊME source vers lequel tracer un trait,
 *         s'il y a lieu — voir {@code Gate#target()} pour l'exemple qui a motivé ce champ.
 */
public record CarteMarker(String id, String label, Identifier dimension, double x, double y, double z,
        int colour, Optional<String> linkedMarkerId) {
}
