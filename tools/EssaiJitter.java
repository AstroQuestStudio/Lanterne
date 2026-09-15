// -*- coding: utf-8 -*-
// Epreuve autonome du decalage sous-pixellaire, HORS Minecraft.
//
// Elle existe pour une raison precise : personne ne verra jamais une image de ce mod pendant
// qu'il est ecrit. Un decalage de projection qui se trompe de SIGNE ne plante pas, n'ecrit
// rien au journal, et se contente de faire trainer l'image au lieu de l'affiner — on cherche
// des heures. La seule facon de trancher sans oeil est de PROJETER un point et de mesurer de
// combien de pixels il a bouge.
//
// Sept epreuves, dont la cinquieme est la seule qui compte vraiment.
//
//   javac -cp joml.jar -d out EssaiJitter.java ../src/main/java/fr/clubcitrouille/lanterne/client/upscale/Jitter.java
//   java  -cp out;joml.jar EssaiJitter
//
// Le jar de JOML se trouve dans le cache Gradle :
//   ~/.gradle/caches/modules-2/files-2.1/org.joml/joml/*/*/joml-*.jar

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import fr.clubcitrouille.lanterne.client.upscale.Jitter;

public final class EssaiJitter {
    private static int rates;

    static void verifie(String quoi, boolean bon, String detail) {
        System.out.printf("%-58s %s %s%n", quoi, bon ? "OK  " : "RATE", detail);
        if (!bon) {
            rates++;
        }
    }

    public static void main(String[] args) throws Exception {
        Method halton = Jitter.class.getDeclaredMethod("halton", int.class, int.class);
        halton.setAccessible(true);

        // 1 et 2. Les valeurs de reference de la suite. Si celles-ci sont fausses, tout l'est.
        float[] base2 = {0.5f, 0.25f, 0.75f, 0.125f, 0.625f, 0.375f, 0.875f, 0.0625f};
        float[] base3 = {1 / 3f, 2 / 3f, 1 / 9f, 4 / 9f, 7 / 9f, 2 / 9f, 5 / 9f, 8 / 9f};
        boolean bon2 = true;
        boolean bon3 = true;
        for (int i = 0; i < 8; i++) {
            if (Math.abs((float) halton.invoke(null, i + 1, 2) - base2[i]) > 1e-6) {
                bon2 = false;
            }
            if (Math.abs((float) halton.invoke(null, i + 1, 3) - base3[i]) > 1e-6) {
                bon3 = false;
            }
        }
        verifie("Halton base 2, huit premiers termes", bon2, "");
        verifie("Halton base 3, huit premiers termes", bon3, "");

        // 3. La periode suit le facteur d'echelle, entre plancher et plafond.
        Jitter j = new Jitter();
        j.retune(1280, 1920);
        verifie("Periode a 1,5x", j.period() == 18, "= " + j.period());
        j.retune(1920, 1920);
        verifie("Periode a 1x, plancher", j.period() == 8, "= " + j.period());
        j.retune(480, 1920);
        verifie("Periode a 4x, plafond", j.period() == 64, "= " + j.period());

        // 4. Bornes, rebouclage, et l'indice qui ne vaut jamais zero.
        j.retune(1280, 1920);
        boolean borne = true;
        boolean jamaisZero = true;
        boolean[] vus = new boolean[j.period()];
        for (int i = 0; i < j.period() * 3; i++) {
            j.advance();
            if (j.offsetX() < -0.5f || j.offsetX() >= 0.5f
                    || j.offsetY() < -0.5f || j.offsetY() >= 0.5f) {
                borne = false;
            }
            if (j.index() == 0) {
                jamaisZero = false;
            }
            vus[j.index() - 1] = true;
        }
        boolean couvre = true;
        for (boolean v : vus) {
            couvre &= v;
        }
        verifie("Decalage borne dans [-0,5 ; 0,5[", borne, "");
        verifie("Indice jamais nul — halton(0) vaut 0 dans toute base", jamaisZero, "");
        verifie("Les " + j.period() + " indices sont tous visites", couvre, "");

        // Discrepance : deux sondes au meme endroit, c'est une image perdue.
        j.reset();
        Set<String> sondes = new HashSet<>();
        boolean distinct = true;
        for (int i = 0; i < j.period(); i++) {
            j.advance();
            distinct &= sondes.add(j.offsetX() + "/" + j.offsetY());
        }
        verifie("Aucune sonde repetee sur une periode", distinct,
                sondes.size() + " sondes distinctes");

        // 5. CELLE-CI. On projette un point, on mesure son deplacement en pixels, et il doit
        // valoir exactement le decalage demande — en valeur ET en signe.
        int largeur = 1280;
        int hauteur = 720;
        Matrix4f projection = new Matrix4f()
                .perspective((float) Math.toRadians(70), (float) largeur / hauteur, 0.05f, 1000f);
        Vector4f point = new Vector4f(3.7f, -1.2f, -25f, 1f);

        Vector4f avant = projection.transform(new Vector4f(point));
        float xAvant = (avant.x / avant.w * 0.5f + 0.5f) * largeur;
        float yAvant = (avant.y / avant.w * 0.5f + 0.5f) * hauteur;

        j.reset();
        j.advance();
        float dx = j.offsetX();
        float dy = j.offsetY();
        Matrix4f decalee = j.applyTo(new Matrix4f(projection), largeur, hauteur);
        Vector4f apres = decalee.transform(new Vector4f(point));
        float xApres = (apres.x / apres.w * 0.5f + 0.5f) * largeur;
        float yApres = (apres.y / apres.w * 0.5f + 0.5f) * hauteur;

        System.out.printf("   demande dx=%+.6f dy=%+.6f px | mesure dx=%+.6f dy=%+.6f px%n",
                dx, dy, xApres - xAvant, yApres - yAvant);
        verifie("applyTo deplace de exactement offsetX pixels",
                Math.abs(xApres - xAvant - dx) < 1e-3, "");
        verifie("applyTo deplace de exactement offsetY pixels",
                Math.abs(yApres - yAvant - dy) < 1e-3, "");

        // 6. La profondeur n'est pas touchee. En 26.2 elle est INVERSEE : y toucher par
        // megarde ferait disparaitre le monde, pas trembler l'image.
        verifie("La profondeur ne bouge pas",
                Math.abs(apres.z / apres.w - avant.z / avant.w) < 1e-6, "");

        // 7. Un decalage d'ECRAN ne depend pas de la distance. S'il en dependait, ce serait
        // un cisaillement de la scene et non un deplacement de la grille d'echantillonnage.
        Vector4f loin = new Vector4f(3.7f, -1.2f, -400f, 1f);
        Vector4f la = projection.transform(new Vector4f(loin));
        Vector4f lb = decalee.transform(new Vector4f(loin));
        float bougeLoin = (lb.x / lb.w - la.x / la.w) * 0.5f * largeur;
        verifie("Le decalage est uniforme quelle que soit la distance",
                Math.abs(bougeLoin - dx) < 1e-3,
                String.format("a 400 blocs : %+.6f px", bougeLoin));

        System.out.println(rates == 0 ? "\nTOUT PASSE" : "\n" + rates + " ECHEC(S)");
        System.exit(rates == 0 ? 0 : 1);
    }
}
