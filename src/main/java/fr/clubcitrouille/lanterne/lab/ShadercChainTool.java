package fr.clubcitrouille.lanterne.lab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;

import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.texture.TextureStage;

/**
 * Outil AUTONOME (sans client Minecraft) : rejoue la chaine {@code #include -> jcpp -> patchVanilla ->
 * UniformReblocker} sur un vrai fichier de shaderpack et ECRIT les GLSL produits, pour pouvoir les
 * donner a shaderc dans un autre processus (voir {@link ShadercCompileTool}).
 *
 * <p>Usage : {@code java -cp <jar lanterne + libs> fr.clubcitrouille.lanterne.lab.ShadercChainTool
 * <shadersRoot> <vertexPath> <fragmentPath> <outDir> [zZeroToOne=true]} — jamais actif en jeu.
 */
public final class ShadercChainTool {
    private ShadercChainTool() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String vertexPath = args[1];
        String fragmentPath = args[2];
        Path out = Path.of(args[3]);
        System.setProperty("lanterne.zzerotoone", args.length > 4 ? args[4] : "true");
        Files.createDirectories(out);
        Throwable[] error = new Throwable[1];
        Thread worker = new Thread(null, () -> {
            try {
                run(root, vertexPath, fragmentPath, out);
            } catch (Throwable t) {
                error[0] = t;
            }
        }, "chaine-glsl", 512L * 1024 * 1024);
        worker.start();
        worker.join();
        if (error[0] != null) {
            error[0].printStackTrace();
            System.exit(2);
        }
    }

    private static void run(Path root, String vertexPath, String fragmentPath, Path out) throws Exception {
        AbsolutePackPath vAbs = AbsolutePackPath.fromAbsolutePath(vertexPath);
        AbsolutePackPath fAbs = AbsolutePackPath.fromAbsolutePath(fragmentPath);
        IncludeGraph graph = new IncludeGraph(root, ImmutableList.of(vAbs, fAbs), false);
        if (!graph.getFailures().isEmpty()) {
            throw new IllegalStateException("include: " + graph.getFailures());
        }
        IncludeProcessor processor = new IncludeProcessor(graph);
        String vFlat = String.join("\n", processor.getIncludedFile(vAbs));
        String fFlat = String.join("\n", processor.getIncludedFile(fAbs));
        Files.writeString(out.resolve("vert_flat.glsl"), vFlat);
        Files.writeString(out.resolve("frag_flat.glsl"), fFlat);
        System.out.println("flat: vertex " + vFlat.length() + " frag " + fFlat.length());
        String vPre = JcppProcessor.glslPreprocessSource(vFlat, List.of(new StringPair("VERTEX_SHADER", "")));
        String fPre = JcppProcessor.glslPreprocessSource(fFlat, List.of(new StringPair("FRAGMENT_SHADER", "")));
        Files.writeString(out.resolve("vert_pre.glsl"), vPre);
        Files.writeString(out.resolve("frag_pre.glsl"), fPre);
        System.out.println("jcpp: vertex " + vPre.length() + " frag " + fPre.length());
        Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap = Object2ObjectMaps.emptyMap();
        ShaderAttributeInputs inputs = new ShaderAttributeInputs(true, true, false, true, false);
        Map<PatchShaderType, String> patched = TransformPatcher.patchVanilla("chain", vPre, null, null, null, fPre,
                AlphaTest.ALWAYS, false, false, false, inputs, textureMap, Set.of());
        String vp = patched.get(PatchShaderType.VERTEX);
        String fp = patched.get(PatchShaderType.FRAGMENT);
        Files.writeString(out.resolve("vert_patched.glsl"), vp);
        Files.writeString(out.resolve("frag_patched.glsl"), fp);
        System.out.println("patchVanilla: vertex " + vp.length() + " frag " + fp.length());
        String vr = UniformReblocker.reblock(vp, "LanterneVertexUBO", 0);
        String fr = UniformReblocker.reblock(fp, "LanterneFragmentUBO", 0);
        Files.writeString(out.resolve("vert_reblocked.glsl"), vr);
        Files.writeString(out.resolve("frag_reblocked.glsl"), fr);
        System.out.println("reblock: vertex " + vr.length() + " frag " + fr.length());
    }
}
