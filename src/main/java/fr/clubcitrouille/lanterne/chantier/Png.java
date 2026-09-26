package fr.clubcitrouille.lanterne.chantier;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.jspecify.annotations.Nullable;

/**
 * Lecture d'un PNG en ARGB, sans AWT — pendant de {@link Rendu#png} : un serveur Linux sans
 * bibliothèques graphiques (ou un runtime réduit sans {@code java.desktop}) doit pouvoir lire les
 * textures du jeu. Couvre ce que Mojang et les mods livrent : gris, RGB, palette, gris+alpha, RGBA,
 * 1 à 16 bits, tRNS. Pas d'entrelacement (Adam7) : {@code null}, et le bloc retombe sur sa couleur
 * de carte.
 */
final class Png {
    private Png() {}

    record Image(int w, int h, int[] argb) {}

    static @Nullable Image lire(byte[] b) {
        try {
            return decoder(b);
        } catch (RuntimeException | DataFormatException e) {
            return null;
        }
    }

    private static @Nullable Image decoder(byte[] b) throws DataFormatException {
        if (b.length < 33 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(b);
        in.position(8);
        int w = 0, h = 0, prof = 0, type = 0;
        int[] palette = null;
        byte[] trns = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream(b.length);
        while (in.remaining() >= 12) {
            int n = in.getInt();
            int t = in.getInt();
            if (n < 0 || n > in.remaining() - 4) {
                return null;
            }
            int debut = in.position();
            switch (t) {
                case 0x49484452 -> { // IHDR
                    w = in.getInt(debut);
                    h = in.getInt(debut + 4);
                    prof = b[debut + 8] & 0xFF;
                    type = b[debut + 9] & 0xFF;
                    if (b[debut + 12] != 0) {
                        return null; // entrelacé
                    }
                }
                case 0x504C5445 -> { // PLTE
                    palette = new int[n / 3];
                    for (int i = 0; i < palette.length; i++) {
                        int o = debut + i * 3;
                        palette[i] = 0xFF000000 | (b[o] & 0xFF) << 16 | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF);
                    }
                }
                case 0x74524E53 -> { // tRNS
                    trns = new byte[n];
                    System.arraycopy(b, debut, trns, 0, n);
                }
                case 0x49444154 -> idat.write(b, debut, n); // IDAT
                default -> { }
            }
            if (t == 0x49454E44) { // IEND
                break;
            }
            in.position(debut + n + 4);
        }
        if (w <= 0 || h <= 0 || (long) w * h > 16L * 1024 * 1024) {
            return null;
        }
        int canaux = switch (type) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 0;
        };
        if (canaux == 0 || (type == 3 && palette == null)) {
            return null;
        }
        int bitsPx = canaux * prof;
        int ligne = (w * bitsPx + 7) / 8;
        int bpp = Math.max(1, bitsPx / 8);
        byte[] brut = new byte[(ligne + 1) * h];
        Inflater inf = new Inflater();
        try {
            inf.setInput(idat.toByteArray());
            int lu = 0;
            while (lu < brut.length && !inf.finished()) {
                int k = inf.inflate(brut, lu, brut.length - lu);
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                lu += k;
            }
        } finally {
            inf.end();
        }
        byte[] prec = new byte[ligne];
        byte[] cour = new byte[ligne];
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            int o = y * (ligne + 1);
            int filtre = brut[o] & 0xFF;
            System.arraycopy(brut, o + 1, cour, 0, ligne);
            for (int i = 0; i < ligne; i++) {
                int a = i >= bpp ? cour[i - bpp] & 0xFF : 0;
                int up = prec[i] & 0xFF;
                int c = i >= bpp ? prec[i - bpp] & 0xFF : 0;
                int x = cour[i] & 0xFF;
                x += switch (filtre) {
                    case 1 -> a;
                    case 2 -> up;
                    case 3 -> (a + up) >>> 1;
                    case 4 -> paeth(a, up, c);
                    default -> 0;
                };
                cour[i] = (byte) x;
            }
            for (int x = 0; x < w; x++) {
                argb[y * w + x] = pixel(cour, x, type, prof, palette, trns);
            }
            byte[] t = prec;
            prec = cour;
            cour = t;
        }
        return new Image(w, h, argb);
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    /** Échantillon {@code i} (0 = premier canal du pixel) ramené sur 8 bits. */
    private static int ech(byte[] l, int i, int prof) {
        return switch (prof) {
            case 8 -> l[i] & 0xFF;
            case 16 -> l[i * 2] & 0xFF;
            default -> {
                int parOctet = 8 / prof;
                int v = (l[i / parOctet] & 0xFF) >> (8 - prof * (i % parOctet + 1)) & ((1 << prof) - 1);
                yield v;
            }
        };
    }

    private static int pixel(byte[] l, int x, int type, int prof, int[] palette, byte[] trns) {
        switch (type) {
            case 3 -> {
                int i = ech(l, x, prof);
                int c = i < palette.length ? palette[i] : 0xFF000000;
                if (trns != null && i < trns.length) {
                    c = (c & 0xFFFFFF) | (trns[i] & 0xFF) << 24;
                }
                return c;
            }
            case 0 -> {
                int v = ech(l, x, prof);
                int brut = v; // avant mise à l'échelle : tRNS compare la valeur écrite
                if (prof < 8) {
                    v = v * 255 / ((1 << prof) - 1);
                }
                int a = trns != null && trns.length >= 2 && ((trns[0] & 0xFF) << 8 | (trns[1] & 0xFF)) == brut ? 0 : 255;
                return a << 24 | v << 16 | v << 8 | v;
            }
            case 2 -> {
                int r = ech(l, x * 3, prof), g = ech(l, x * 3 + 1, prof), bl = ech(l, x * 3 + 2, prof);
                int a = 255;
                if (trns != null && trns.length >= 6 && prof == 8
                        && (trns[1] & 0xFF) == r && (trns[3] & 0xFF) == g && (trns[5] & 0xFF) == bl) {
                    a = 0;
                }
                return a << 24 | r << 16 | g << 8 | bl;
            }
            case 4 -> {
                int v = ech(l, x * 2, prof);
                return ech(l, x * 2 + 1, prof) << 24 | v << 16 | v << 8 | v;
            }
            default -> {
                return ech(l, x * 4 + 3, prof) << 24 | ech(l, x * 4, prof) << 16 | ech(l, x * 4 + 1, prof) << 8
                        | ech(l, x * 4 + 2, prof);
            }
        }
    }
}
