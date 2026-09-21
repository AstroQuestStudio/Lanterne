package fr.clubcitrouille.lanterne.render.terrain;

/**
 * Où vit une section de terrain dans la mémoire GPU, pour une passe donnée : un décalage dans le tampon
 * partagé de sommets et un dans celui d'index, avec le nombre d'index à dessiner.
 *
 * <p>Type de données pur (pas de référence à un objet GPU) : les tampons eux-mêmes restent ceux de
 * vanilla ({@code UberGpuBuffer}), on ne fait que décrire une plage dedans.
 *
 * @param sectionKey position de la section, {@code SectionPos.asLong}
 * @param vertexByteOffset décalage de la section dans le tampon partagé de sommets
 * @param firstIndex premier index à dessiner
 * @param indexCount nombre d'index
 * @param baseVertex sommet de base ajouté aux index
 */
public record SectionSlice(long sectionKey, long vertexByteOffset, int firstIndex, int indexCount, int baseVertex) {}
