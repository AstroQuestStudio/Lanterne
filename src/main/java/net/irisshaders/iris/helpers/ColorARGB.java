package net.irisshaders.iris.helpers;

/** Empaquetage de couleurs ARGB (alpha en poids fort, puis rouge, vert, bleu). Voir {@link ColorABGR}. */
public final class ColorARGB {
	private ColorARGB() {}

	public static int pack(int r, int g, int b, int a) {
		return (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
	}
}
