package net.irisshaders.iris.helpers;

/**
 * Empaquetage de couleurs ABGR (alpha en poids fort, puis bleu, vert, rouge). Équivalent local
 * d'un utilitaire de couleur : écrit ici, sans Sodium, pour qu'Iris n'en dépende plus pour de
 * simples opérations sur des octets (voir AUDIT-IRIS-BESOINS-TERRAIN.md, palier 0).
 */
public final class ColorABGR {
	private ColorABGR() {}

	public static int pack(int r, int g, int b, int a) {
		return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
	}

	public static int unpackRed(int color) {
		return color & 0xFF;
	}

	public static int unpackGreen(int color) {
		return color >> 8 & 0xFF;
	}

	public static int unpackBlue(int color) {
		return color >> 16 & 0xFF;
	}

	public static int unpackAlpha(int color) {
		return color >> 24 & 0xFF;
	}
}
