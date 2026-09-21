import fr.clubcitrouille.lanterne.client.shaderopt.ConstFolder;

import java.util.Map;

/**
 * Auto-test de ConstFolder (sans JUnit) : java FolderSelfTest — sort avec un code non nul au premier échec.
 * Compile avec : javac -sourcepath src/main/java tools/shaderopt/FolderSelfTest.java
 */
public class FolderSelfTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "OK    " : "ECHEC ") + name);
        if (!ok) failures++;
    }

    static String spec(String src) {
        return ConstFolder.specialize(src, Map.of("rainStrength", "0.0", "isEyeInWater", "0")).text();
    }

    public static void main(String[] a) {
        // 1. uniform -> const
        String s1 = spec("uniform float rainStrength;\nuniform int isEyeInWater;\nvoid main() { x = rainStrength; }");
        check("uniform float devient const float", s1.contains("const float rainStrength = 0.0;"));
        check("uniform int devient const int", s1.contains("const int isEyeInWater = 0;"));

        // 2. if a condition connue : branche fausse supprimee, vraie conservee
        String s2 = spec("uniform float rainStrength;\nvoid main() {\n if (rainStrength > 0.5) { a(); } else { b(); }\n}");
        check("if faux -> branche else", s2.contains("b();") && !s2.contains("a();"));
        String s3 = spec("uniform int isEyeInWater;\nvoid main() {\n if (isEyeInWater == 0) { a(); } else { b(); }\n}");
        check("if vrai -> branche then", s3.contains("a();") && !s3.contains("b();"));

        // 3. instruction simple sans accolades
        String s4 = spec("uniform int isEyeInWater;\nvoid main() {\n if (isEyeInWater == 1) fog(); c();\n}");
        check("if sans accolades faux supprime, suite conservee", !s4.contains("fog();") && s4.contains("c();"));

        // 4. else if : chaine complete
        String s5 = spec("uniform int isEyeInWater;\nvoid main() {\n if (isEyeInWater == 1) { a(); } else if (isEyeInWater == 0) { b(); } else { c(); }\n}");
        check("else if -> b() seul", s5.contains("b();") && !s5.contains("a();") && !s5.contains("c();"));

        // 5. condition inconnue : rien ne change
        String s6 = spec("uniform float rainStrength;\nvoid main() {\n if (foo > 0.5) { a(); } else { b(); }\n}");
        check("condition inconnue conservee", s6.contains("a();") && s6.contains("b();"));

        // 6. logique a trois valeurs
        String s7 = spec("uniform float rainStrength;\nvoid main() {\n if (rainStrength > 0.5 && foo) { a(); } else { b(); }\n}");
        check("faux && inconnu -> faux", s7.contains("b();") && !s7.contains("a();"));
        String s8 = spec("uniform float rainStrength;\nvoid main() {\n if (rainStrength < 0.5 || foo) { a(); } else { b(); }\n}");
        check("vrai || inconnu -> vrai", s8.contains("a();") && !s8.contains("b();"));
        String s9 = spec("uniform float rainStrength;\nvoid main() {\n if (rainStrength < 0.5 && foo) { a(); } else { b(); }\n}");
        check("vrai && inconnu reste indetermine", s9.contains("a();") && s9.contains("b();"));

        // 5b. propagation vers une globale derivee jamais reassignee
        String s10 = spec("uniform float rainStrength;\nfloat wet = rainStrength * 2.0;\nvoid main() {\n if (wet > 1.0) { a(); } else { b(); }\n}");
        check("propagation vers globale derivee", s10.contains("b();") && !s10.contains("a();"));
        // ... mais pas si elle est reassignee
        String s11 = spec("uniform float rainStrength;\nfloat wet = rainStrength * 2.0;\nvoid main() {\n wet += 1.0;\n if (wet > 1.0) { a(); } else { b(); }\n}");
        check("globale reassignee : pas de propagation", s11.contains("a();") && s11.contains("b();"));

        // 7. fonction devenue inutile supprimee : le cas denseFogColor[isEyeInWater - 2] (regression reelle de BSL)
        String bsl = "uniform int isEyeInWater;\nvec3 denseFogColor[2];\n"
                + "void DenseFog(inout vec3 color) {\n color = denseFogColor[isEyeInWater - 2];\n}\n"
                + "void main() {\n vec3 c = vec3(0.0);\n if (isEyeInWater >= 2) DenseFog(c);\n}\n";
        String s12 = spec(bsl);
        check("fonction devenue morte supprimee (indice constant negatif evite)",
                !s12.contains("DenseFog") && s12.contains("void main()"));

        // 8. fonction encore utilisee : conservee
        String s13 = spec("uniform int isEyeInWater;\nvoid f() { }\nvoid main() {\n f();\n if (isEyeInWater == 0) { a(); }\n}");
        check("fonction utilisee conservee", s13.contains("void f()") && s13.contains("f();"));

        // 9. sans uniform d'etat : texte inchange
        String same = "void main() { x = 1.0; }";
        check("aucun uniform d'etat : inchange", ConstFolder.specialize(same, Map.of("rainStrength", "0.0")).text().equals(same));

        System.out.println(failures == 0 ? "\nTOUS LES TESTS PASSENT" : "\n" + failures + " ECHEC(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
