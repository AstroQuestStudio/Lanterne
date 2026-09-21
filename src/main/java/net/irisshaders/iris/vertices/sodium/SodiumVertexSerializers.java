package net.irisshaders.iris.vertices.sodium;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializerRegistry;
import net.irisshaders.iris.vertices.IrisVertexFormats;

/**
 * Club Citrouille : isole de Iris.java (net/irisshaders/iris/Iris.java, methode onRenderSystemInit).
 * Un simple "if (isModLoaded(sodium))" autour de ces "new XxxVertexSerializer()" ne suffit PAS a
 * empecher un NoClassDefFoundError quand Sodium est absent : verifie en jeu (lanceur client jetable,
 * Sodium retire du dossier mods/) - la JVM resout la classe referencee par l'instruction NEW au
 * chargement/verification de la methode englobante, pas seulement quand la branche if est reellement
 * executee. Ces quatre classes implementent l'interface Sodium VertexSerializer ; simplement etre
 * NOMMEES dans le bytecode d'une methode de Iris.java (meme dans un bloc jamais atteint a l'execution)
 * suffit a declencher la resolution de leur supertype absent. Le seul correctif fiable est de les
 * isoler dans leur propre classe, jamais chargee du tout tant que l'appelant ne l'a pas explicitement
 * referencee - exactement le meme motif que CadenceBlueMap pour BlueMapAPI.
 */
public final class SodiumVertexSerializers {
	private SodiumVertexSerializers() {
	}

	public static void registerAll() {
		VertexSerializerRegistry.instance().registerSerializer(DefaultVertexFormat.ENTITY, IrisVertexFormats.TERRAIN, new EntityToTerrainVertexSerializer());
		VertexSerializerRegistry.instance().registerSerializer(IrisVertexFormats.ENTITY, IrisVertexFormats.TERRAIN, new IrisEntityToTerrainVertexSerializer());
		VertexSerializerRegistry.instance().registerSerializer(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR, IrisVertexFormats.GLYPH, new GlyphExtVertexSerializer());
		VertexSerializerRegistry.instance().registerSerializer(DefaultVertexFormat.ENTITY, IrisVertexFormats.ENTITY, new ModelToEntityVertexSerializer());
	}
}
