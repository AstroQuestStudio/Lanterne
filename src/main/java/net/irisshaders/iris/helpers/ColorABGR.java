package net.irisshaders.iris.helpers;

/**
 * Couleur 32 bits en ordre ABGR (octet de poids fort = alpha), l'ordre de {@code NativeImage}.
 * Ecrit pour Lanterne (aucune dependance a un mod de rendu tiers) : de simples decalages de bits.
 */
public final class ColorABGR {
	private ColorABGR() {
	}

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
		return color >>> 24;
	}
}
