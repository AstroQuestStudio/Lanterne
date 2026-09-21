/**
 * Le moteur de rendu du terrain de Lanterne : ce qui remplace Sodium comme « moteur de terrain » sous Iris.
 *
 * <p>Ce paquet est un SQUELETTE (étape 0 du plan, voir {@code RENDU-LANTERNE-DESIGN.md}) : des interfaces
 * et des types de données documentés, rien de branché au jeu. Il est écrit « salle blanche » : aucune
 * ligne n'est reprise de Sodium (PolyForm Shield 1.0.0). Il s'appuie sur ce que Minecraft 26.3 fournit déjà
 * — tampons partagés {@code UberGpuBuffer}, dessin indirect {@code prepareChunkRendersIndirect}, backends
 * GL et Vulkan derrière {@code com.mojang.renderpearl.api} — et n'ajoute que ce qui manque : la passe
 * d'ombres, les attributs Iris et le pont vers les pipelines Iris.
 */
package fr.clubcitrouille.lanterne.render.terrain;
