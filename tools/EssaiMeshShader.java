// -*- coding: utf-8 -*-
// Epreuve autonome des mesh shaders (VK_EXT_mesh_shader), HORS Minecraft.
//
// Cette epreuve ne touche a AUCUN fichier du mod, AUCUN VkDevice du jeu, AUCUNE fenetre. Elle
// cree son propre VkInstance jetable (detruit a la fin) et n'utilise le compilateur shaderc que
// pour du texte -> SPIR-V, sans jamais creer de pipeline ni soumettre de commande. Le risque pour
// une partie en cours est donc nul : ce programme est un processus separe, independant du client
// Minecraft, exactement comme tools/EssaiNgx.java l'a ete pour NGX.
//
// Elle repond aux deux questions qui decident, a elles seules, si un pipeline mesh shader pour le
// terrain de ce moteur est meme envisageable — AVANT d'investir des semaines a le construire :
//
//   A. Le compilateur GLSL->SPIR-V EMBARQUE avec ce depot (lwjgl-shaderc, celui-la meme que
//      GlslCompiler.java utilise pour CHAQUE nuanceur du jeu) sait-il seulement produire du
//      SPIR-V pour un stage mesh/task (#extension GL_EXT_mesh_shader) ? GlslCompiler.compileToSpv
//      ne le fait jamais aujourd'hui — verifie par javap, voir notes/mesh-shaders-terrain-faisabilite.md
//      — donc cette epreuve compile en dehors de lui, avec le meme shaderc, pour isoler la question
//      "l'outil le permet-il" de la question "le moteur le cable-t-il".
//
//   B. CETTE machine (le pilote Vulkan reellement installe) expose-t-elle vraiment
//      VK_EXT_mesh_shader avec les bits meshShader/taskShader actifs, et avec quelles limites
//      (max sommets/primitives par meshlet, taille de groupe de travail) ? La mission du 19-20
//      septembre 2026 a deja confirme par bytecode que VulkanFeatureSetsMixin DETECTE et ACTIVE
//      cette extension a la creation du device du jeu — cette epreuve verifie la meme chose de
//      maniere independante, par une instance jetable distincte, sans faire confiance a une seule
//      lecture de bytecode pour une decision d'ingenierie de plusieurs semaines.
//
//   Compiler (JDK 17+ suffit, LWJGL 3.4.3 — versions deja en cache Gradle de ce depot) :
//
//     javac -cp lwjgl-3.4.3.jar;lwjgl-vulkan-3.4.3.jar;lwjgl-shaderc-3.4.3.jar -d out EssaiMeshShader.java
//
//   Executer (les jars de "natives" doivent etre sur le classpath : LWJGL en extrait le contenu
//   tout seul au demarrage, pas besoin de -Djava.library.path) :
//
//     java -cp out;lwjgl-3.4.3.jar;lwjgl-3.4.3-natives-windows.jar;lwjgl-vulkan-3.4.3.jar;lwjgl-shaderc-3.4.3.jar;lwjgl-shaderc-3.4.3-natives-windows.jar EssaiMeshShader
//
//   Sur cette machine (chemins releves dans le cache Gradle de ce depot, LWJGL 3.4.3) :
//
//     C:\Users\trufa\.gradle\caches\modules-2\files-2.1\org.lwjgl\lwjgl\3.4.3\...\lwjgl-3.4.3.jar
//     C:\Users\trufa\.gradle\caches\modules-2\files-2.1\org.lwjgl\lwjgl\3.4.3\...\lwjgl-3.4.3-natives-windows.jar
//     C:\Users\trufa\.gradle\caches\modules-2\files-2.1\org.lwjgl\lwjgl-vulkan\3.4.3\...\lwjgl-vulkan-3.4.3.jar
//     C:\Users\trufa\.gradle\caches\modules-2\files-2.1\org.lwjgl\lwjgl-shaderc\3.4.3\...\lwjgl-shaderc-3.4.3.jar
//     C:\Users\trufa\.gradle\caches\modules-2\files-2.1\org.lwjgl\lwjgl-shaderc\3.4.3\...\lwjgl-shaderc-3.4.3-natives-windows.jar
//
// Le resultat REEL de cette execution (pas suppose) est reporte dans
// notes/mesh-shaders-terrain-faisabilite.md, avec l'horodatage de la machine ou elle a tourne.

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

import static org.lwjgl.util.shaderc.Shaderc.*;

public final class EssaiMeshShader {

    /** Le controle negatif : un fragment shader ordinaire, deja supporte par GlslCompiler aujourd'hui.
     *  S'il echoue, l'epreuve elle-meme est cassee — pas le mesh shading. */
    private static final String NUANCEUR_FRAGMENT_CONTROLE =
        "#version 460\n" +
        "layout(location = 0) in vec3 vCouleur;\n" +
        "layout(location = 0) out vec4 fragCouleur;\n" +
        "void main() {\n" +
        "    fragCouleur = vec4(vCouleur, 1.0);\n" +
        "}\n";

    /** Un task shader minimal : emet un seul groupe de travail mesh. */
    private static final String NUANCEUR_TASK =
        "#version 460\n" +
        "#extension GL_EXT_mesh_shader : require\n" +
        "layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;\n" +
        "void main() {\n" +
        "    EmitMeshTasksEXT(1u, 1u, 1u);\n" +
        "}\n";

    /** Un mesh shader minimal : un unique triangle, un meshlet a lui seul. */
    private static final String NUANCEUR_MESH =
        "#version 460\n" +
        "#extension GL_EXT_mesh_shader : require\n" +
        "layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;\n" +
        "layout(triangles, max_vertices = 3, max_primitives = 1) out;\n" +
        "layout(location = 0) out vec3 vCouleur[];\n" +
        "void main() {\n" +
        "    SetMeshOutputsEXT(3, 1);\n" +
        "    gl_MeshVerticesEXT[0].gl_Position = vec4(-0.5, -0.5, 0.0, 1.0);\n" +
        "    gl_MeshVerticesEXT[1].gl_Position = vec4( 0.5, -0.5, 0.0, 1.0);\n" +
        "    gl_MeshVerticesEXT[2].gl_Position = vec4( 0.0,  0.5, 0.0, 1.0);\n" +
        "    vCouleur[0] = vec3(1.0, 0.0, 0.0);\n" +
        "    vCouleur[1] = vec3(0.0, 1.0, 0.0);\n" +
        "    vCouleur[2] = vec3(0.0, 0.0, 1.0);\n" +
        "    gl_PrimitiveTriangleIndicesEXT[0] = uvec3(0, 1, 2);\n" +
        "}\n";

    public static void main(String[] args) {
        System.out.println("=== Partie A : le compilateur (shaderc embarque par ce depot) sait-il produire du SPIR-V mesh/task ? ===");
        boolean compilateurOk = essaiCompilation();

        System.out.println();
        System.out.println("=== Partie B : CETTE carte / CE pilote exposent-ils reellement VK_EXT_mesh_shader ? ===");
        boolean materielOk = essaiMateriel();

        System.out.println();
        System.out.println("=== Verdict ===");
        System.out.println("Compilateur (toolchain shaderc de ce depot) : " + (compilateurOk ? "OK" : "ECHEC"));
        System.out.println("Materiel (cette machine)                    : " + (materielOk ? "OK" : "ECHEC ou absent"));
        System.out.println(
            (compilateurOk && materielOk)
                ? "=> Rien, au niveau outil/materiel, n'empeche un pipeline mesh shader sur cette machine."
                : "=> Au moins un prealable manque sur cette machine — voir le detail ci-dessus avant d'investir dans le moteur."
        );
    }

    // ------------------------------------------------------------------
    // Partie A — compilation seule, aucun device, aucun pipeline.
    // ------------------------------------------------------------------

    private static boolean essaiCompilation() {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) {
            System.out.println("  shaderc_compiler_initialize() a echoue (bibliotheque native introuvable ?).");
            return false;
        }
        try {
            boolean fragmentOk = compileUn(compiler, "controle.fsh", NUANCEUR_FRAGMENT_CONTROLE,
                shaderc_glsl_fragment_shader, "fragment (controle negatif, deja supporte par GlslCompiler)");
            boolean tacheOk = compileUn(compiler, "sonde.tsh", NUANCEUR_TASK,
                shaderc_glsl_task_shader, "task (GL_EXT_mesh_shader)");
            boolean meshOk = compileUn(compiler, "sonde.msh", NUANCEUR_MESH,
                shaderc_glsl_mesh_shader, "mesh (GL_EXT_mesh_shader)");
            return fragmentOk && tacheOk && meshOk;
        } finally {
            shaderc_compiler_release(compiler);
        }
    }

    private static boolean compileUn(long compiler, String nomFichier, String source, int genre, String description) {
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
        long resultat = shaderc_compile_into_spv(compiler, source, genre, nomFichier, "main", options);
        shaderc_compile_options_release(options);
        try {
            int statut = shaderc_result_get_compilation_status(resultat);
            if (statut == shaderc_compilation_status_success) {
                long taille = shaderc_result_get_length(resultat);
                System.out.println("  [OK] " + description + " -> " + taille + " octets de SPIR-V.");
                return true;
            } else {
                System.out.println("  [ECHEC] " + description + " (statut shaderc=" + statut + ") :");
                String erreur = shaderc_result_get_error_message(resultat);
                System.out.println("    " + (erreur == null ? "(pas de message)" : erreur.replace("\n", "\n    ")));
                return false;
            }
        } finally {
            shaderc_result_release(resultat);
        }
    }

    // ------------------------------------------------------------------
    // Partie B — instance Vulkan jetable, introspection de VkPhysicalDevice seulement.
    // Aucun VkDevice, aucune swapchain, aucun VkPipeline, aucune commande soumise.
    // ------------------------------------------------------------------

    private static boolean essaiMateriel() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .apiVersion(VK12.VK_API_VERSION_1_2);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo);

            PointerBuffer pInstance = stack.mallocPointer(1);
            int rc = VK10.vkCreateInstance(createInfo, null, pInstance);
            if (rc != VK10.VK_SUCCESS) {
                System.out.println("  vkCreateInstance a echoue, code " + rc + ".");
                return false;
            }
            VkInstance instance = new VkInstance(pInstance.get(0), createInfo);
            try {
                return interrogeDevices(instance, stack);
            } finally {
                VK10.vkDestroyInstance(instance, null);
            }
        } catch (Throwable t) {
            System.out.println("  Exception inattendue lors de la sonde materielle : " + t);
            return false;
        }
    }

    private static boolean interrogeDevices(VkInstance instance, MemoryStack stackParent) {
        boolean auMoinsUnSupporte = false;
        try (MemoryStack stack = stackParent.push()) {
            IntBuffer count = stack.mallocInt(1);
            VK10.vkEnumeratePhysicalDevices(instance, count, null);
            int n = count.get(0);
            if (n == 0) {
                System.out.println("  Aucun VkPhysicalDevice enumere par cette instance.");
                return false;
            }
            PointerBuffer devices = stack.mallocPointer(n);
            VK10.vkEnumeratePhysicalDevices(instance, count, devices);

            for (int i = 0; i < n; i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devices.get(i), instance);
                try (MemoryStack s2 = stack.push()) {
                    VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(s2);
                    VK10.vkGetPhysicalDeviceProperties(pd, props);
                    String nom = props.deviceNameString();

                    // Tampon d'extensions alloue sur le tas (pas sur la MemoryStack, 64 Kio par
                    // defaut) : un GPU discret moderne annonce facilement 200+ extensions, ce qui
                    // deborderait une pile deja partagee avec les autres structures de cette passe.
                    IntBuffer extCount = s2.mallocInt(1);
                    VK10.vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, extCount, null);
                    int en = extCount.get(0);
                    VkExtensionProperties.Buffer exts = VkExtensionProperties.calloc(en);
                    boolean supporte;
                    try {
                        VK10.vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, extCount, exts);
                        supporte = false;
                        for (int e = 0; e < en; e++) {
                            if (EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME.equals(exts.get(e).extensionNameString())) {
                                supporte = true;
                                break;
                            }
                        }
                    } finally {
                        exts.free();
                    }

                    if (!supporte) {
                        System.out.println("  " + nom + " : VK_EXT_mesh_shader ABSENT de la liste d'extensions du pilote (" + en + " extensions annoncees) — non interroge (meme discipline que Sonde.java, jamais de pNext sur une extension non annoncee).");
                        continue;
                    }

                    VkPhysicalDeviceMeshShaderFeaturesEXT feat = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(s2)
                        .sType(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT);
                    VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(s2)
                        .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                        .pNext(feat);
                    VK11.vkGetPhysicalDeviceFeatures2(pd, features2);

                    VkPhysicalDeviceMeshShaderPropertiesEXT propsMesh = VkPhysicalDeviceMeshShaderPropertiesEXT.calloc(s2)
                        .sType(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_PROPERTIES_EXT);
                    VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(s2)
                        .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                        .pNext(propsMesh);
                    VK11.vkGetPhysicalDeviceProperties2(pd, properties2);

                    System.out.println("  " + nom + " : VK_EXT_mesh_shader present dans la liste d'extensions du pilote.");
                    System.out.println("    meshShader=" + feat.meshShader()
                        + " taskShader=" + feat.taskShader()
                        + " meshShaderQueries=" + feat.meshShaderQueries()
                        + " multiviewMeshShader=" + feat.multiviewMeshShader());
                    System.out.println("    maxMeshOutputVertices=" + propsMesh.maxMeshOutputVertices()
                        + " maxMeshOutputPrimitives=" + propsMesh.maxMeshOutputPrimitives()
                        + " maxMeshWorkGroupInvocations=" + propsMesh.maxMeshWorkGroupInvocations()
                        + " maxPreferredMeshWorkGroupInvocations=" + propsMesh.maxPreferredMeshWorkGroupInvocations()
                        + " maxMeshOutputMemorySize=" + propsMesh.maxMeshOutputMemorySize()
                        + " maxTaskWorkGroupInvocations=" + propsMesh.maxTaskWorkGroupInvocations()
                        + " maxPreferredTaskWorkGroupInvocations=" + propsMesh.maxPreferredTaskWorkGroupInvocations());

                    if (feat.meshShader()) {
                        auMoinsUnSupporte = true;
                    }
                }
            }
        }
        return auMoinsUnSupporte;
    }

    private EssaiMeshShader() {}
}
