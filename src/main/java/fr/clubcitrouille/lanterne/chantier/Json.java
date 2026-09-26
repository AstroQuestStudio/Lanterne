package fr.clubcitrouille.lanterne.chantier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

/** Petits accès JSON tolérants : une clé absente prend sa valeur par défaut, un mauvais type est refusé. */
final class Json {
    private Json() {}

    static JsonArray xyz(int x, int y, int z) {
        JsonArray t = new JsonArray();
        t.add(x);
        t.add(y);
        t.add(z);
        return t;
    }

    static JsonArray xyz(BlockPos p) {
        return xyz(p.getX(), p.getY(), p.getZ());
    }

    static String texte(JsonObject o, String cle, String defaut) {
        JsonElement e = o.get(cle);
        if (e == null || e.isJsonNull()) {
            return defaut;
        }
        if (!e.isJsonPrimitive()) {
            throw new Refus(400, "« " + cle + " » doit être une chaîne");
        }
        return e.getAsString();
    }

    static String texteRequis(JsonObject o, String cle) {
        String t = texte(o, cle, null);
        if (t == null || t.isBlank()) {
            throw new Refus(400, "« " + cle + " » requis");
        }
        return t;
    }

    static boolean bool(JsonObject o, String cle, boolean defaut) {
        JsonElement e = o.get(cle);
        if (e == null || e.isJsonNull()) {
            return defaut;
        }
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            throw new Refus(400, "« " + cle + " » doit être un booléen");
        }
    }

    static int entier(JsonObject o, String cle, int defaut, int min, int max) {
        JsonElement e = o.get(cle);
        if (e == null || e.isJsonNull()) {
            return defaut;
        }
        int v;
        try {
            v = e.getAsInt();
        } catch (RuntimeException ex) {
            throw new Refus(400, "« " + cle + " » doit être un entier");
        }
        if (v < min || v > max) {
            throw new Refus(400, "« " + cle + " » hors bornes [" + min + ", " + max + "]");
        }
        return v;
    }

    static double reel(JsonObject o, String cle, double defaut, double min, double max) {
        JsonElement e = o.get(cle);
        if (e == null || e.isJsonNull()) {
            return defaut;
        }
        double v;
        try {
            v = e.getAsDouble();
        } catch (RuntimeException ex) {
            throw new Refus(400, "« " + cle + " » doit être un nombre");
        }
        if (!(v >= min && v <= max)) {
            throw new Refus(400, "« " + cle + " » hors bornes [" + min + ", " + max + "]");
        }
        return v;
    }

    /** {@code [x, y, z]} en nombres réels, ou {@code null} si la clé est absente. */
    static double @org.jspecify.annotations.Nullable [] reels(JsonObject o, String cle) {
        JsonElement e = o.get(cle);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) {
            throw new Refus(400, "« " + cle + " » attendu sous la forme [x, y, z]");
        }
        JsonArray t = e.getAsJsonArray();
        try {
            return new double[] {t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble()};
        } catch (RuntimeException ex) {
            throw new Refus(400, "« " + cle + " » : trois nombres attendus");
        }
    }
}
