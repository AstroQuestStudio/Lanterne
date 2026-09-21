package net.irisshaders.iris.vertices;

/**
 * Le chemin « terrain vanilla » d'Iris : quand Sodium est ABSENT et que la propriété
 * {@code -Dlanterne.irisvanilla=true} est posée, le terrain est maillé par le mailleur vanilla de
 * Minecraft 26.3 ({@code SectionCompiler}) et non par Sodium.
 *
 * <p>Deux besoins, portés ici :
 * <ul>
 *   <li>{@link #enabled()} : ce chemin est-il actif ? (jamais quand Sodium est là : Iris s'y greffe
 *       comme avant, sans aucun changement de comportement) ;</li>
 *   <li>le contexte du bloc en cours de maillage, par fil : le mailleur tourne sur des fils de
 *       fond, et chaque quad doit savoir de quel bloc il vient ({@code mc_Entity}, {@code at_midBlock}).</li>
 * </ul>
 */
public final class VanillaTerrainContext {
	private static final String SODIUM_MARKER = "net/caffeinemc/mods/sodium/client/SodiumClientMod.class";
	private static final boolean ENABLED_BY_PROPERTY = Boolean.getBoolean("lanterne.irisvanilla");
	private static volatile Boolean sodiumPresent;

	/** Le bloc (ou fluide) en cours de maillage sur CE fil. */
	public static final class Block {
		public int id = -1;
		public byte renderType = -1;
		public byte emission;
		/** Coordonnées LOCALES à la section (0..15), comme les sommets écrits par le mailleur vanilla. */
		public int x, y, z;
		public boolean active;
	}

	private static final ThreadLocal<Block> BLOCK = ThreadLocal.withInitial(Block::new);
	private static final ThreadLocal<Boolean> MESHING = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private VanillaTerrainContext() {}

	/** Sodium est-il chargé ? Détection par ressource : {@code ModList} n'existe pas encore aux mixins. */
	public static boolean sodiumPresent() {
		Boolean cached = sodiumPresent;
		if (cached == null) {
			boolean found = false;
			try {
				found = VanillaTerrainContext.class.getClassLoader().getResource(SODIUM_MARKER) != null;
			} catch (Throwable ignored) {
			}
			sodiumPresent = found;
			cached = found;
		}
		return cached;
	}

	/** Le chemin terrain vanilla est-il actif ? Faux dès que Sodium est présent. */
	public static boolean enabled() {
		return ENABLED_BY_PROPERTY && !sodiumPresent();
	}

	public static boolean isMeshing() {
		return MESHING.get();
	}

	public static void setMeshing(boolean meshing) {
		MESHING.set(meshing);
		if (!meshing) {
			BLOCK.get().active = false;
		}
	}

	public static Block current() {
		return BLOCK.get();
	}

	public static void begin(int id, byte renderType, byte emission, int x, int y, int z) {
		Block b = BLOCK.get();
		b.id = id;
		b.renderType = renderType;
		b.emission = emission;
		b.x = x;
		b.y = y;
		b.z = z;
		b.active = true;
	}

	public static void end() {
		BLOCK.get().active = false;
	}
}
