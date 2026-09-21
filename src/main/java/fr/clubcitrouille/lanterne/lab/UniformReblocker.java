package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reponse a l'etape 5 de {@code RECHERCHE-VULKAN-SANS-SODIUM.md} : le GLSL produit par
 * {@code TransformPatcher.patchVanilla} declare ses uniforms non-opaques (matrices, floats, vecX...)
 * en variables globales libres — legal en GLSL "style OpenGL", interdit par la specification SPIR-V
 * pour Vulkan ({@code 'non-opaque uniforms outside a block' : not allowed when using GLSL for
 * Vulkan}). Ce fichier n'est PAS un pass {@code glsl-transformer} (Iris n'en a pas besoin pour
 * cibler OpenGL) — c'est un post-traitement texte independant, applique APRES
 * {@code TransformPatcher}, jamais avant : il ne touche a aucun fichier {@code net.irisshaders}.
 *
 * <h2>Ce que ca fait</h2>
 *
 * <p>Trouve chaque declaration {@code uniform <Type> <nom>[tableau];} de niveau ligne dont le type
 * n'est PAS un type opaque (sampler/image/atomic_uint/subpassInput — ceux-la restent des uniforms
 * "de resource" independants, c'est un mecanisme SPIR-V different, deja gere par
 * {@code auto_bind_uniforms} dans {@link ShadercProbe#compileSpirv}), les retire de leur emplacement
 * d'origine, et les regroupe dans un seul bloc {@code layout(binding=N, std140) uniform <nom> { ... };}
 * insere juste apres la ligne {@code #version}.
 *
 * <h2>Ce que ca NE fait PAS (limites honnetes)</h2>
 *
 * <ul>
 *   <li>Ne gere pas les declarations multi-noms sur une seule ligne
 *       ({@code uniform float a, b;}) — TransformPatcher n'en produit pas dans les cas testes
 *       cette nuit (une declaration par ligne), mais ce n'est pas garanti pour tout shaderpack.</li>
 *   <li>Ne reordonne pas les acces au sein du shader — un bloc UBO change la maniere dont le
 *       uniform est adresse en memoire (std140), mais le NOM reste identique donc tout le reste du
 *       code source (qui reference juste le nom) continue de compiler sans modification.</li>
 *   <li>Un seul binding pour tout le bloc — un vrai pipeline Vulkan devrait controler cet indice
 *       precisement pour eviter les collisions avec d'autres descriptor sets (textures, autres
 *       stages) ; ceci reste une preuve de compilation, pas un descriptor layout final.</li>
 * </ul>
 */
final class UniformReblocker {
    private UniformReblocker() {}

    /**
     * Type opaque = jamais dans un UBO (reste un uniform de resource independant, gere par
     * auto_bind_uniforms). Liste non exhaustive mais couvre les cas reels rencontres dans les
     * shaders "gbuffers" (sampler2D/sampler3D/samplerCube/shadow variants, images, atomic_uint).
     */
    private static final Pattern OPAQUE_TYPE = Pattern.compile(
            "^(sampler|image|isampler|usampler|isubpassInput|usubpassInput|subpassInput|atomic_uint).*");

    private static final Pattern UNIFORM_DECL = Pattern.compile(
            "^([ \\t]*)uniform\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(\\[[^\\]]*\\])?\\s*;[ \\t]*$",
            Pattern.MULTILINE);

    /**
     * Regroupe les uniforms non-opaques de {@code glsl} dans un seul bloc UBO nomme {@code blockName}
     * au binding {@code binding}. Renvoie le GLSL inchange (meme instance textuelle) si aucun
     * uniform non-opaque de niveau ligne n'est trouve — un shader qui n'a que des uniforms opaques
     * (textures) n'a jamais eu ce probleme, {@link ShadercProbe#compileSpirv} suffit deja pour lui.
     */
    static String reblock(String glsl, String blockName, int binding) {
        Matcher matcher = UNIFORM_DECL.matcher(glsl);
        List<String> members = new ArrayList<>();
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(2);
            if (OPAQUE_TYPE.matcher(type).matches()) {
                continue;
            }
            rest.append(glsl, last, matcher.start());
            last = matcher.end();
            String name = matcher.group(3);
            String array = matcher.group(4) == null ? "" : matcher.group(4);
            members.add("    " + type + " " + name + array + ";");
        }
        if (members.isEmpty()) {
            return glsl;
        }
        rest.append(glsl, last, glsl.length());

        StringBuilder block = new StringBuilder();
        block.append("layout(binding = ").append(binding).append(", std140) uniform ")
                .append(blockName).append(" {\n");
        for (String member : members) {
            block.append(member).append('\n');
        }
        block.append("};\n");

        String withoutOriginalDecls = rest.toString();
        int insertAt = insertionPoint(withoutOriginalDecls);
        return withoutOriginalDecls.substring(0, insertAt) + block + withoutOriginalDecls.substring(insertAt);
    }

    private static int insertionPoint(String glsl) {
        int versionIdx = glsl.indexOf("#version");
        if (versionIdx < 0) {
            return 0;
        }
        int newline = glsl.indexOf('\n', versionIdx);
        return newline < 0 ? glsl.length() : newline + 1;
    }
}
