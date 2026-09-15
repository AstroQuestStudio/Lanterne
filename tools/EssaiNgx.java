// -*- coding: utf-8 -*-
// Epreuve autonome de la liaison NGX par Project Panama, HORS Minecraft.
//
// Elle repond a la seule question qui decidait de toute l'affaire DLSS, et a laquelle le
// depot avait repondu NON sans l'avoir posee a une machine :
//
//   le chargeur NGX que le pilote NVIDIA installe deja est-il une DLL dont Panama
//   (java.lang.foreign, definitif depuis Java 22) sait resoudre les symboles ?
//
// Si oui, aucun pont JNI, aucun C++, aucun CMake n'est necessaire pour ATTEINDRE NGX.
// Si non, il faut bel et bien un eclat de C compile contre le SDK de NVIDIA.
//
// La reponse, sur cette machine (RTX 3080 Laptop, Temurin 25.0.4.1, septembre 2026), est OUI.
// Voir notes/dlss-panama.md pour ce que cela implique et ce que cela n'implique PAS : la DLL
// du MODELE (nvngx_dlss.dll) reste absente, et les parametres NGX passent par une table
// virtuelle C++ que Panama doit parcourir a la main.
//
//   javac -d out EssaiNgx.java
//   java  -cp out EssaiNgx [chemin-de-_nvngx.dll]
//
// Sans argument, l'epreuve cherche elle-meme la DLL dans le magasin de pilotes : c'est
// exactement ce que le mod devra faire, et c'est donc aussi une epreuve de cette recherche.

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class EssaiNgx {

    /** Les seize fonctions Vulkan dont une integration DLSS a reellement besoin. */
    private static final String[] VOULUES = {
        "NVSDK_NGX_VULKAN_Init",
        "NVSDK_NGX_VULKAN_Init_Ext",
        "NVSDK_NGX_VULKAN_Init_Ext2",
        "NVSDK_NGX_VULKAN_Init_ProjectID",
        "NVSDK_NGX_VULKAN_Init_ProjectID_Ext",
        "NVSDK_NGX_VULKAN_GetCapabilityParameters",
        "NVSDK_NGX_VULKAN_AllocateParameters",
        "NVSDK_NGX_VULKAN_DestroyParameters",
        "NVSDK_NGX_VULKAN_GetParameters",
        "NVSDK_NGX_VULKAN_CreateFeature",
        "NVSDK_NGX_VULKAN_EvaluateFeature",
        "NVSDK_NGX_VULKAN_ReleaseFeature",
        "NVSDK_NGX_VULKAN_GetScratchBufferSize",
        "NVSDK_NGX_VULKAN_GetFeatureRequirements",
        "NVSDK_NGX_VULKAN_RequiredExtensions",
        "NVSDK_NGX_VULKAN_Shutdown1",
    };

    /**
     * Le controle negatif, et il compte autant que les autres.
     *
     * Ces noms-la figurent dans TOUS les exemples de NVIDIA. S'ils sont absents de la table
     * d'export, c'est qu'ils ne sont pas des fonctions C mais des methodes VIRTUELLES d'une
     * classe C++ : la difficulte se deplace de « atteindre NGX » vers « parcourir une vtable ».
     */
    private static final String[] ABSENCES_ATTENDUES = {
        "NVSDK_NGX_Parameter_SetI",
        "NVSDK_NGX_Parameter_SetF",
        "NVSDK_NGX_Parameter_GetI",
        "NVSDK_NGX_Parameter_SetVoidPointer",
    };

    public static void main(String[] args) throws Throwable {
        System.out.println("Java " + Runtime.version());
        System.out.println("Editeur de liens natif : "
                + Linker.nativeLinker().getClass().getSimpleName());

        Path dll = args.length > 0 ? Paths.get(args[0]) : trouve();
        if (dll == null) {
            System.out.println("ECHEC : _nvngx.dll introuvable. Pilote NVIDIA absent ?");
            System.exit(2);
        }
        System.out.println("Chargeur NGX : " + dll);
        if (!Files.isRegularFile(dll)) {
            System.out.println("ECHEC : ce n'est pas un fichier.");
            System.exit(2);
        }

        int manquantes = 0;
        try (Arena arena = Arena.ofConfined()) {
            // libraryLookup accepte un CHEMIN ABSOLU : le magasin de pilotes n'a donc pas
            // besoin d'etre dans le chemin de recherche du systeme, ce qui est heureux car
            // il ne l'est pas.
            SymbolLookup ngx = SymbolLookup.libraryLookup(dll, arena);

            System.out.println("\n--- ce qu'on veut trouver ---");
            for (String nom : VOULUES) {
                var trouve = ngx.find(nom);
                System.out.printf("  %-44s %s%n", nom,
                        trouve.map(m -> "OK  @0x" + Long.toHexString(m.address())).orElse("ABSENT"));
                if (trouve.isEmpty()) {
                    manquantes++;
                }
            }

            System.out.println("\n--- controle negatif : ces noms DOIVENT etre absents ---");
            for (String nom : ABSENCES_ATTENDUES) {
                boolean la = ngx.find(nom).isPresent();
                System.out.printf("  %-44s %s%n", nom,
                        la ? "PRESENT (inattendu !)" : "absent, comme prevu");
            }

            // Resoudre une adresse ne prouve pas qu'on sait APPELER. Fabriquer un
            // MethodHandle avec une signature, si.
            System.out.println("\n--- liaison d'appel ---");
            MethodHandle shutdown = Linker.nativeLinker().downcallHandle(
                    ngx.find("NVSDK_NGX_VULKAN_Shutdown1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            System.out.println("  NVSDK_NGX_VULKAN_Shutdown1 -> " + shutdown);
            // On ne l'APPELLE pas : sans VkDevice initialise, l'appel serait indefini.
        }

        System.out.println(manquantes == 0
                ? "\nVERDICT : NGX est joignable par Panama. Aucun pont JNI n'est necessaire"
                        + " pour l'atteindre."
                : "\nVERDICT : " + manquantes + " fonction(s) manquante(s) — un pont natif reste"
                        + " necessaire.");
        System.exit(manquantes == 0 ? 0 : 1);
    }

    /**
     * Ou le pilote range-t-il le chargeur ?
     *
     * Les pilotes recents ne le copient plus dans System32 : il reste dans le magasin, sous
     * nvaci.inf (« NVIDIA Application Compute Interface »), dont le suffixe change a chaque
     * version. On balaie donc, au lieu de coder un chemin en dur qui serait faux au prochain
     * pilote. System32 reste teste en premier, pour les pilotes plus anciens.
     */
    static Path trouve() {
        Path direct = Paths.get("C:/Windows/System32/_nvngx.dll");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path magasin = Paths.get("C:/Windows/System32/DriverStore/FileRepository");
        if (!Files.isDirectory(magasin)) {
            return null;
        }
        List<Path> candidats = new ArrayList<>();
        try (DirectoryStream<Path> flux = Files.newDirectoryStream(magasin, "nv*")) {
            for (Path dossier : flux) {
                Path dll = dossier.resolve("_nvngx.dll");
                if (Files.isRegularFile(dll)) {
                    candidats.add(dll);
                }
            }
        } catch (Exception ignore) {
            return null;
        }
        // Plusieurs pilotes peuvent cohabiter dans le magasin : on prend le plus recent.
        return candidats.stream()
                .max((a, b) -> {
                    try {
                        return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .orElse(null);
    }
}
