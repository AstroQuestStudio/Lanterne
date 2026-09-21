package net.irisshaders.iris.shadows;

/**
 * Etat partage de la passe d'ombres, dans une classe qui n'importe RIEN d'un moteur de terrain tiers.
 *
 * <p>Avant, ces deux valeurs vivaient dans {@code ShadowRenderer}, dont le chargement resout des
 * types Sodium : lire {@code ShadowRenderer.ACTIVE} depuis n'importe ou (creation d'une passe de
 * rendu, par exemple) faisait donc echouer le client sans Sodium (NoClassDefFoundError
 * {@code SodiumChunkSection}, constate en lancement reel). Les lecteurs passent ici ; le moteur
 * d'ombres y ecrit.
 */
public final class ShadowFlags {
	/** Vrai pendant que la passe d'ombres est en train de rendre. */
	public static volatile boolean active = false;
	/** Distance de rendu des ombres de la derniere passe, en chunks. */
	public static volatile int renderDistance;

	private ShadowFlags() {
	}
}
