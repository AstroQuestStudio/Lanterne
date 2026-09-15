// -*- coding: utf-8 -*-
// Epreuve autonome de la matrice de reprojection, HORS Minecraft.
//
// Elle repond a la question dont depend tout remonteur temporel, et a laquelle on ne peut pas
// repondre en lisant le code :
//
//   si je prends un point du monde, que je le projette a l'image N, puis que je lui applique la
//   matrice de reprojection, est-ce que je retombe EXACTEMENT ou il etait a l'image N-1 ?
//
// C'est verifiable sans voir une seule image : on choisit un point, deux poses de camera, et on
// compare deux nombres. Un remonteur dont la reprojection est fausse ne plante pas et n'ecrit rien
// au journal : il fabrique des trainees, et on cherche des jours.
//
// L'epreuve 3 est la plus importante : elle fait echouer EXPRES la formule naive
// (vueProj(n-1) x inverse(vueProj(n))), celle qu'on ecrit spontanement et qui est fausse en 26.2
// parce que la matrice de vue de Minecraft ne contient QUE la rotation. Si un jour cette epreuve-la
// se met a passer, c'est que Mojang a remis la camera dans la matrice de vue, et que Reproject
// peut etre simplifie.
//
//   javac -cp joml.jar -d out EssaiReproject.java ../src/main/java/fr/clubcitrouille/lanterne/client/upscale/Reproject.java
//   java  -cp out;joml.jar EssaiReproject

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import fr.clubcitrouille.lanterne.client.upscale.Reproject;

public final class EssaiReproject {
    private static int rates;

    static void verifie(String quoi, boolean bon, String detail) {
        System.out.printf("%-56s %s %s%n", quoi, bon ? "OK  " : "RATE", detail);
        if (!bon) {
            rates++;
        }
    }

    /** Coordonnees normalisees d'un point du monde, camera a (cx,cy,cz) tournee par vueRot. */
    static Vector3f ndc(Matrix4f projection, Matrix4f viewRotation,
            double cx, double cy, double cz, double px, double py, double pz) {
        // Le monde est dessine RELATIVEMENT a la camera : c'est la soustraction qui remplace la
        // translation absente de la matrice de vue.
        Vector4f relative = new Vector4f((float) (px - cx), (float) (py - cy), (float) (pz - cz), 1f);
        Matrix4f viewProj = new Matrix4f(projection).mul(viewRotation);
        Vector4f clip = viewProj.transform(relative);
        return new Vector3f(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w);
    }

    public static void main(String[] args) {
        Matrix4f projection = new Matrix4f()
                .perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);

        // Deux poses : la camera avance ET tourne, ce qui est le cas courant et celui ou une
        // erreur se voit. Une camera immobile ferait passer n'importe quelle formule.
        Matrix4f rotA = new Matrix4f().rotateY((float) Math.toRadians(12))
                .rotateX((float) Math.toRadians(-4));
        Matrix4f rotB = new Matrix4f().rotateY((float) Math.toRadians(17))
                .rotateX((float) Math.toRadians(-6));
        double ax = 120.5, ay = 70.25, az = -430.75;
        double bx = 121.9, by = 70.55, bz = -429.10;

        // Un point du monde quelconque, devant la camera dans les deux poses.
        double px = 131.0, py = 72.0, pz = -445.0;

        Vector3f attendu = ndc(projection, rotA, ax, ay, az, px, py, pz);   // image N-1
        Vector3f courant = ndc(projection, rotB, bx, by, bz, px, py, pz);   // image N

        Reproject reproject = new Reproject();

        // 1. La toute premiere image n'a rien derriere elle.
        boolean first = reproject.advance(projection, rotA, ax, ay, az);
        verifie("Premiere image : pas de reprojection utilisable", !first, "");
        verifie("primed() vrai apres la premiere image", reproject.primed(), "");

        // 2. Deuxieme image : la matrice devient utilisable.
        boolean second = reproject.advance(projection, rotB, bx, by, bz);
        verifie("Deuxieme image : reprojection utilisable", second, "");

        // 3. LE test. On applique la matrice aux coordonnees normalisees courantes.
        Vector4f out = new Matrix4f(reproject.matrix())
                .transform(new Vector4f(courant.x, courant.y, courant.z, 1f));
        Vector3f obtenu = new Vector3f(out.x / out.w, out.y / out.w, out.z / out.w);

        float ecartX = Math.abs(obtenu.x - attendu.x);
        float ecartY = Math.abs(obtenu.y - attendu.y);
        System.out.printf("   attendu (%+.6f, %+.6f)  obtenu (%+.6f, %+.6f)%n",
                attendu.x, attendu.y, obtenu.x, obtenu.y);
        verifie("Reprojection exacte en X", ecartX < 1e-4, String.format("ecart %.2e", ecartX));
        verifie("Reprojection exacte en Y", ecartY < 1e-4, String.format("ecart %.2e", ecartY));
        // En pixels, sur une fenetre de 1920 de large : l'erreur doit etre tres inferieure au pixel.
        float pixels = ecartX * 0.5f * 1920f;
        verifie("Erreur tres inferieure au pixel (1920 px)", pixels < 0.05f,
                String.format("%.4f px", pixels));

        // 4. CONTRE-EPREUVE : la formule naive, sans la translation de camera, DOIT echouer.
        // Si elle passait, c'est que le test ne teste rien.
        Matrix4f naive = new Matrix4f(projection).mul(rotA)
                .mul(new Matrix4f(projection).mul(rotB).invert());
        Vector4f naif = naive.transform(new Vector4f(courant.x, courant.y, courant.z, 1f));
        float naifX = naif.x / naif.w;
        float ecartNaif = Math.abs(naifX - attendu.x) * 0.5f * 1920f;
        System.out.printf("   formule naive (sans translation) : %.2f px d'erreur%n", ecartNaif);
        verifie("La formule naive echoue bien (contre-epreuve)", ecartNaif > 1f,
                String.format("%.2f px", ecartNaif));

        // 5. Camera parfaitement immobile : la reprojection doit etre l'identite a l'oeil nu.
        Reproject fixe = new Reproject();
        fixe.advance(projection, rotA, ax, ay, az);
        fixe.advance(projection, rotA, ax, ay, az);
        Vector4f immobile = new Matrix4f(fixe.matrix())
                .transform(new Vector4f(attendu.x, attendu.y, attendu.z, 1f));
        float dxImmobile = Math.abs(immobile.x / immobile.w - attendu.x);
        verifie("Camera immobile : reprojection neutre", dxImmobile < 1e-5,
                String.format("ecart %.2e", dxImmobile));

        // 6. reset() doit vraiment oublier.
        reproject.reset();
        verifie("reset() oublie l'image precedente", !reproject.primed(), "");
        verifie("La premiere image apres reset n'est pas utilisable",
                !reproject.advance(projection, rotA, ax, ay, az), "");

        // 7. Un point LOIN de l'origine : c'est la que la double precision compte. Si la position
        // de la camera etait reduite en float AVANT la soustraction, ce test-ci echouerait.
        double fx = 1_000_000.5, fy = 70.25, fz = -430.75;
        double gx = 1_000_001.9, gy = 70.55, gz = -429.10;
        double qx = 1_000_011.0, qy = 72.0, qz = -445.0;
        Vector3f attenduLoin = ndc(projection, rotA, fx, fy, fz, qx, qy, qz);
        Vector3f courantLoin = ndc(projection, rotB, gx, gy, gz, qx, qy, qz);
        Reproject loin = new Reproject();
        loin.advance(projection, rotA, fx, fy, fz);
        loin.advance(projection, rotB, gx, gy, gz);
        Vector4f sortieLoin = new Matrix4f(loin.matrix())
                .transform(new Vector4f(courantLoin.x, courantLoin.y, courantLoin.z, 1f));
        float ecartLoin = Math.abs(sortieLoin.x / sortieLoin.w - attenduLoin.x) * 0.5f * 1920f;
        verifie("Exact a un million de blocs de l'origine", ecartLoin < 0.5f,
                String.format("%.4f px", ecartLoin));

        System.out.println(rates == 0 ? "\nTOUT PASSE" : "\n" + rates + " ECHEC(S)");
        System.exit(rates == 0 ? 0 : 1);
    }
}
