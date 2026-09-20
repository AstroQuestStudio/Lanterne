package net.irisshaders.iris;

/**
 * Ecrit a la main plutot que genere par le plugin Gradle com.github.gmazzo.buildconfig (qu'Iris
 * utilise dans son propre build.gradle.kts) - integrer ce plugin dans Lanterne pour ces 4
 * constantes seules n'en valait pas la peine. Memes valeurs par defaut que celles d'Iris (voir
 * common/build.gradle.kts : buildConfigField(...) dans le clone de travail iris-src).
 */
public final class BuildConfig {
	private BuildConfig() {}

	public static final boolean IS_SHARED_BETA = false;
	public static final boolean ACTIVATE_RENDERDOC = false;
	public static final String BETA_TAG = "";
	public static final int BETA_VERSION = 0;
}
