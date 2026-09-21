package net.irisshaders.iris.shadows;

import net.irisshaders.iris.Iris;

/**
 * Cache de la passe d'ombres (Club Citrouille, salle blanche : principe public de « mise en cache de shadow map »,
 * voir NOTES-TECHNIQUES-RENDU-TERRAIN.md section 6 leviers 1 et 2).
 *
 * <h2>Pourquoi c'est exact</h2>
 *
 * <p>La matrice « modèle-vue » d'ombre est la rotation du soleil suivie d'un décalage qui ancre la carte sur une
 * cellule d'une grille de {@code intervalSize} blocs (voir {@link ShadowMatrices#snapModelViewToGrid}). Ce que la carte
 * contient ne dépend donc que : de la cellule de grille, de l'angle du soleil, de la distance d'ombre et de la
 * résolution — pas de la position exacte de la caméra dans la cellule. Les uniformes {@code shadowModelView} sont
 * recalculés à chaque image à partir de la caméra courante ; tant que la cellule et l'angle (quantifié, voir
 * {@link #quantizeShadowAngle}) sont ceux de la carte rendue, la carte en cache reste échantillonnée avec la bonne
 * matrice.
 *
 * <h2>Ce qu'on ne fige pas trop longtemps</h2>
 *
 * <p>Le monde bouge (blocs, entités, chargement de sections) : la carte est donc refaite au plus tard toutes les
 * {@link #MAX_AGE_MS} millisecondes — 30 Hz par défaut, ce qui reste fluide pour l'ombre d'un joueur ou d'un
 * animal —, et immédiatement dès que la cellule, l'angle, la distance ou la résolution changent.
 *
 * <p>Désactivé par défaut : {@code -Dlanterne.shadowopt=true} pour l'activer, {@code -Dlanterne.shadowopt.ms=N} pour
 * changer l'âge maximal (0 = jamais de réutilisation).
 */
public final class ShadowMapCache {
	/** Actif seulement sur demande tant que le gain n'est pas prouvé sur toutes les configurations. */
	public static final boolean ENABLED = Boolean.getBoolean("lanterne.shadowopt");

	/** Âge maximal d'une carte réutilisée, en millisecondes. */
	public static final long MAX_AGE_MS = Long.getLong("lanterne.shadowopt.ms", 33L);

	/** Nombre de pas de quantification de l'angle du soleil sur un tour complet (≈ 0,088° par pas). */
	private static final float ANGLE_STEPS = 4096.0f;

	private static long lastKey;
	private static long lastRefreshNanos;
	private static boolean valid;
	private static long reused;
	private static long rendered;
	private static long lastReportNanos = System.nanoTime();

	private ShadowMapCache() {
	}

	/** Angle du soleil pour l'ombre, quantifié quand le cache est actif (rendu et uniformes doivent concorder). */
	public static float quantizeShadowAngle(float shadowAngle) {
		if (!ENABLED) {
			return shadowAngle;
		}
		float q = Math.round(shadowAngle * ANGLE_STEPS) / ANGLE_STEPS;
		return q >= 1.0f ? q - 1.0f : q;
	}

	/**
	 * Clé de la carte : cellule de grille, angle, distance, résolution. Les flottants sont comparés bit à bit,
	 * exactement comme le décalage de grille les produit.
	 */
	public static long key(double cameraX, double cameraY, double cameraZ, float intervalSize, float shadowAngle,
						   int shadowDistance, int resolution) {
		long h = 1125899906842597L;
		if (Math.abs(intervalSize) == 0.0F) {
			// Pas d'accrochage : la carte suit la caméra au bit près, aucune réutilisation possible en mouvement.
			h = 31 * h + Double.doubleToLongBits(cameraX);
			h = 31 * h + Double.doubleToLongBits(cameraY);
			h = 31 * h + Double.doubleToLongBits(cameraZ);
		} else {
			float ox = (float) cameraX % intervalSize;
			float oy = (float) cameraY % intervalSize;
			float oz = (float) cameraZ % intervalSize;
			h = 31 * h + Float.floatToIntBits((float) cameraX - ox);
			h = 31 * h + Float.floatToIntBits((float) cameraY - oy);
			h = 31 * h + Float.floatToIntBits((float) cameraZ - oz);
		}
		h = 31 * h + Float.floatToIntBits(shadowAngle);
		h = 31 * h + shadowDistance;
		h = 31 * h + resolution;
		return h;
	}

	/** Vrai si la carte en cache peut être réutilisée pour cette image (la passe d'ombres est alors sautée). */
	public static boolean canReuse(long key) {
		if (!ENABLED || MAX_AGE_MS <= 0L || !valid || key != lastKey) {
			return false;
		}
		long ageNanos = System.nanoTime() - lastRefreshNanos;
		boolean reuse = ageNanos < MAX_AGE_MS * 1_000_000L;
		if (reuse) {
			reused++;
		}
		return reuse;
	}

	/** À appeler à la fin d'une passe d'ombres complète. */
	public static void markRendered(long key) {
		if (!ENABLED) {
			return;
		}
		lastKey = key;
		lastRefreshNanos = System.nanoTime();
		valid = true;
		rendered++;
		long now = lastRefreshNanos;
		if (now - lastReportNanos > 10_000_000_000L) {
			long total = Math.max(1L, reused + rendered);
			Iris.logger.info("[OMBRES] cache de shadow map : {} images réutilisées, {} rendues ({} % évitées) ; "
				+ "âge maximal {} ms.", reused, rendered, (reused * 100L) / total, MAX_AGE_MS);
			reused = 0;
			rendered = 0;
			lastReportNanos = now;
		}
	}

	/** Oublie la carte (changement de monde, de pack, de résolution). */
	public static void invalidate() {
		valid = false;
	}
}
