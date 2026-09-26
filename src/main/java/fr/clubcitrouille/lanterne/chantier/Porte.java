package fr.clubcitrouille.lanterne.chantier;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La porte HTTP du chantier : 127.0.0.1 seulement, jeton obligatoire, JSON dans les deux sens (PNG
 * pour le rendu). Chaque route fabrique un {@link Travail}, le confie à l'{@link Atelier} et attend
 * sa fin — au plus {@code attendre} secondes, après quoi elle répond 202 avec l'identifiant, à
 * relire sur {@code GET /travail/<id>}.
 *
 * <p>Un jeton faux coûte une demi-seconde à celui qui l'envoie et laisse une ligne au journal : sur
 * une interface locale derrière SSH, c'est largement assez pour qu'un essai à l'aveugle ne mène nulle
 * part, sans rien coûter à l'usage honnête.
 */
final class Porte {
    private static HttpServer serveur;
    private static ExecutorService fils;

    private Porte() {}

    static synchronized void ouvrir(int port) {
        fermer();
        try {
            // L'adresse IPv4 écrite en toutes lettres, et non getLoopbackAddress() : NeoForge demande
            // preferIPv6Addresses, et la porte se retrouvait sur [::1] — hors de portée d'un tunnel
            // « ssh -L port:127.0.0.1:port ». Constaté au premier démarrage, pas supposé.
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
            fils = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "Lanterne-Chantier-Http");
                t.setDaemon(true);
                return t;
            });
            s.setExecutor(fils);
            s.createContext("/", Porte::traiter);
            s.start();
            serveur = s;
            Lanterne.LOG.info("[CHANTIER] API de construction ouverte sur http://127.0.0.1:{} (jeton requis).", port);
        } catch (IOException e) {
            Lanterne.LOG.error("[CHANTIER] impossible d'ouvrir le port {} : {}", port, e.toString());
        }
    }

    static synchronized void fermer() {
        if (serveur != null) {
            serveur.stop(0);
            serveur = null;
            Lanterne.LOG.info("[CHANTIER] API de construction fermée.");
        }
        if (fils != null) {
            fils.shutdownNow();
            fils = null;
        }
    }

    private static void traiter(HttpExchange ex) throws IOException {
        // Pas de try-with-resources ici : il fermerait l'échange AVANT les blocs catch, et un refus
        // partirait en connexion coupée sans réponse. Constaté sur la première boîte hors du monde.
        try {
            String chemin = ex.getRequestURI().getPath();
            if (!autorise(ex)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                Lanterne.LOG.warn("[CHANTIER] jeton refusé depuis {} pour {}", ex.getRemoteAddress(), chemin);
                JsonObject t = new JsonObject();
                t.addProperty("refus", "jeton");
                t.addProperty("chemin", chemin);
                Journal.tracer(t);
                repondre(ex, 401, Atelier.erreur("jeton absent ou faux"));
                return;
            }
            MinecraftServer mc = Chantier.serveur();
            if (mc == null) {
                repondre(ex, 503, Atelier.erreur("serveur pas prêt"));
                return;
            }
            JsonObject corps = "POST".equals(ex.getRequestMethod()) ? lireCorps(ex) : new JsonObject();
            Travail t = router(ex, mc, chemin, corps);
            if (t != null) {
                attendre(ex, Atelier.soumettre(t), Json.entier(corps, "attendre", 120, 0, 600));
            }
        } catch (Refus r) {
            repondre(ex, r.code, Atelier.erreur(r.getMessage()));
        } catch (Throwable e) {
            Lanterne.LOG.error("[CHANTIER] requête en échec", e);
            repondre(ex, 500, Atelier.erreur(e.toString()));
        } finally {
            ex.close();
        }
    }

    private static boolean autorise(HttpExchange ex) {
        String attendu = Chantier.JETON.get();
        if (attendu == null || attendu.length() < 16) {
            return false;
        }
        String donne = ex.getRequestHeaders().getFirst("Authorization");
        if (donne != null && donne.startsWith("Bearer ")) {
            donne = donne.substring(7).trim();
        } else {
            donne = ex.getRequestHeaders().getFirst("X-Jeton");
        }
        return donne != null && MessageDigest.isEqual(
                donne.getBytes(StandardCharsets.UTF_8), attendu.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject lireCorps(HttpExchange ex) throws IOException {
        long max = Chantier.CORPS_MAX_MO.get() * 1024L * 1024L;
        byte[] b;
        try (InputStream in = ex.getRequestBody()) {
            b = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 16, max + 1));
        }
        if (b.length > max) {
            throw new Refus(413, "requête de plus de " + Chantier.CORPS_MAX_MO.get() + " Mo (corps_max_mo)");
        }
        if (b.length == 0) {
            return new JsonObject();
        }
        try {
            JsonElement e = JsonParser.parseString(new String(b, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                throw new Refus(400, "objet JSON attendu");
            }
            return e.getAsJsonObject();
        } catch (RuntimeException e) {
            if (e instanceof Refus r) {
                throw r;
            }
            throw new Refus(400, "JSON illisible : " + e.getMessage());
        }
    }

    static ServerLevel monde(MinecraftServer mc, JsonObject corps, String cle) {
        String dim = Json.texte(corps, cle, "minecraft:overworld");
        ServerLevel l;
        try {
            l = mc.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
        } catch (RuntimeException e) {
            l = null;
        }
        if (l == null) {
            throw new Refus(404, "dimension inconnue « " + dim + " »");
        }
        return l;
    }

    private static Travail router(HttpExchange ex, MinecraftServer mc, String chemin, JsonObject corps) throws IOException {
        long max = Chantier.BLOCS_MAX.get();
        switch (chemin) {
            case "/etat" -> {
                repondre(ex, 200, etat(mc));
                return null;
            }
            case "/journal" -> {
                int n = 20;
                String q = ex.getRequestURI().getQuery();
                if (q != null && q.startsWith("n=")) {
                    try {
                        n = Math.max(1, Math.min(500, Integer.parseInt(q.substring(2))));
                    } catch (NumberFormatException e) {
                        throw new Refus(400, "n doit être un entier");
                    }
                }
                JsonArray a = new JsonArray();
                Journal.dernieres(n).forEach(a::add);
                JsonObject o = new JsonObject();
                o.add("operations", a);
                repondre(ex, 200, o);
                return null;
            }
            case "/cliches" -> {
                JsonArray a = new JsonArray();
                Journal.cliches().forEach(a::add);
                JsonObject o = new JsonObject();
                o.add("cliches", a);
                repondre(ex, 200, o);
                return null;
            }
            case "/verifier/transformations" -> {
                repondre(ex, 200, verifierTransformations(Json.entier(corps, "limite", 100, 1, 10_000)));
                return null;
            }
            case "/lire/blocs" -> {
                ServerLevel l = monde(mc, corps, "dim");
                return new Lectures.Blocs("lire_blocs", l, Boite.lire(corps, l, max), true,
                        Json.bool(corps, "nbt", false), !"brut".equals(Json.texte(corps, "format", "rle")));
            }
            case "/lire/bloc_entites" -> {
                ServerLevel l = monde(mc, corps, "dim");
                return new Lectures.Blocs("lire_bloc_entites", l, Boite.lire(corps, l, max), false, true, true);
            }
            case "/lire/entites" -> {
                ServerLevel l = monde(mc, corps, "dim");
                return new Lectures.Entites(l, Boite.lire(corps, l, max), Json.bool(corps, "nbt", false));
            }
            case "/lire/hauteurs" -> {
                ServerLevel l = monde(mc, corps, "dim");
                String type = Json.texte(corps, "type", "premier_non_air");
                Heightmap.Types t = null;
                if (!"premier_non_air".equals(type)) {
                    try {
                        t = Heightmap.Types.valueOf(type.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new Refus(400, "type inconnu « " + type + " » (premier_non_air | world_surface | motion_blocking | motion_blocking_no_leaves | ocean_floor)");
                    }
                }
                Boite b = Boite.lire(corps, l, Long.MAX_VALUE);
                if ((long) b.sx() * b.sz() > 4_000_000L) {
                    throw new Refus(413, "4 000 000 colonnes au plus");
                }
                return new Lectures.Hauteurs(l, b, t);
            }
            case "/ecrire/blocs" -> {
                ServerLevel l = monde(mc, corps, "dim");
                Ecriture.Plan p = Ecriture.liste(corps, max);
                Ecriture.verifierZone(p, l);
                return new Ecriture("ecrire_blocs", l, p, corps, null);
            }
            case "/ecrire/remplir" -> {
                ServerLevel l = monde(mc, corps, "dim");
                return new Ecriture("remplir", l,
                        Ecriture.remplir(Boite.lire(corps, l, max), Etats.lire(Json.texteRequis(corps, "bloc"))), corps, null);
            }
            case "/ecrire/remplacer" -> {
                ServerLevel l = monde(mc, corps, "dim");
                return new Ecriture("remplacer", l, Ecriture.remplacer(Boite.lire(corps, l, max),
                        Json.texteRequis(corps, "motif"), Json.texteRequis(corps, "par"),
                        Json.bool(corps, "garder_proprietes", true)), corps, null);
            }
            case "/ecrire/creuser" -> {
                ServerLevel l = monde(mc, corps, "dim");
                String r = Json.texte(corps, "remplissage", "minecraft:air");
                return new Ecriture("creuser", l, Ecriture.creuser(Boite.lire(corps, l, max),
                        Json.entier(corps, "epaisseur", 1, 1, 64), Etats.lire(r)), corps, null);
            }
            case "/ecrire/cloner" -> {
                ServerLevel source = monde(mc, corps, "dim_source");
                ServerLevel dest = corps.has("dim") ? monde(mc, corps, "dim") : source;
                Boite de = Boite.lire(corps, source, max);
                int[] vers = Boite.triplet(corps, "vers");
                Ecriture.Clonage c = new Ecriture.Clonage(source, de, vers,
                        Ecriture.miroir(Json.texte(corps, "miroir", "aucun")),
                        Ecriture.rotation(Json.entier(corps, "rotation", 0, -270, 270)),
                        Json.bool(corps, "copier_air", true));
                c.zone().verifier(dest, Long.MAX_VALUE);
                return new Ecriture("cloner", dest, c, corps, null);
            }
            case "/annuler" -> {
                String id = Json.texteRequis(corps, "operation");
                boolean force = Json.bool(corps, "forcer", false);
                if (!force) {
                    for (JsonObject o : Journal.dernieres(100_000)) {
                        if (id.equals(o.has("id") ? o.get("id").getAsString() : null) && o.has("annulee_par")) {
                            throw new Refus(409, "opération déjà annulée par " + o.get("annulee_par").getAsString()
                                    + " (\"forcer\": true pour la rejouer quand même)");
                        }
                    }
                }
                Releve r = Journal.lireAnnulation(id);
                CompoundMeta m = CompoundMeta.de(id);
                ServerLevel l = monde(mc, m.corps, "dim");
                Ecriture.Plan p = Ecriture.restaurer(r, m.zone, id);
                JsonObject opts = new JsonObject();
                opts.addProperty("mode", "rapide");
                opts.addProperty("rayon_joueurs", Json.entier(corps, "rayon_joueurs", Chantier.RAYON_JOUEURS.get(), 0, 4096));
                return new Ecriture("annuler", l, p, opts, id);
            }
            case "/cliche" -> {
                ServerLevel l = monde(mc, corps, "dim");
                return new Cliche.Prise(l, Boite.lire(corps, l, max), Json.texteRequis(corps, "nom"));
            }
            case "/diff" -> {
                String nom = Json.texte(corps, "cliche", null);
                String op = Json.texte(corps, "operation", null);
                boolean nbt = Json.bool(corps, "nbt", true);
                int limite = Json.entier(corps, "limite", 50, 0, 10_000);
                if (nom != null) {
                    net.minecraft.nbt.CompoundTag m = Journal.metaCliche(nom);
                    JsonObject d = new JsonObject();
                    d.addProperty("dim", m.getStringOr("dim", "minecraft:overworld"));
                    return new Cliche.Comparaison(monde(mc, d, "dim"), Journal.lireCliche(nom), "cliche:" + nom, nbt, limite);
                }
                if (op != null) {
                    CompoundMeta m = CompoundMeta.de(op);
                    return new Cliche.Comparaison(monde(mc, m.corps, "dim"), Journal.lireAnnulation(op),
                            "avant:" + op, nbt, limite);
                }
                throw new Refus(400, "« cliche » ou « operation » requis");
            }
            case "/rendu" -> {
                ServerLevel l = monde(mc, corps, "dim");
                String fond = Json.texte(corps, "fond", "d8e4ec");
                int f = "transparent".equals(fond) ? 0 : 0xFF000000 | Integer.parseInt(fond.replace("#", ""), 16);
                // Pas de plafond en volume : le rendu s'arrête au premier bloc vu, son coût suit la
                // surface de l'image (bornée dans Rendu), pas la hauteur de la boîte.
                return new Rendu(l, Boite.lire(corps, l, Long.MAX_VALUE), Rendu.vue(Json.texte(corps, "vue", "dessus")),
                        Json.entier(corps, "profondeur", 0, 0, 4096), Json.entier(corps, "echelle", 0, 0, 64),
                        Json.entier(corps, "grille", 0, 0, 4096), f);
            }
            default -> {
                if (chemin.startsWith("/travail/")) {
                    Travail t = Atelier.connu(chemin.substring("/travail/".length()));
                    if (t == null) {
                        throw new Refus(404, "travail inconnu (ou oublié)");
                    }
                    attendre(ex, t, Json.entier(corps, "attendre", 0, 0, 600));
                    return null;
                }
                throw new Refus(404, "route inconnue « " + chemin + " » — voir GET /etat pour la liste");
            }
        }
    }

    /**
     * Passe TOUS les états de blocs du registre (mods compris) aux identités que tout miroir et toute
     * rotation doivent respecter : miroir² = identité, rotation⁴ = identité, miroir x + 180° = miroir z.
     * Un bloc qui échoue sera mal cloné par {@code /ecrire/cloner} — c'est ainsi que le défaut des
     * escaliers vanilla a été trouvé. Fonctions pures : sans risque hors du thread serveur.
     */
    private static JsonObject verifierTransformations(int limite) {
        int etats = 0;
        java.util.Map<String, String> fautifs = new java.util.TreeMap<>();
        for (net.minecraft.world.level.block.Block b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            for (net.minecraft.world.level.block.state.BlockState s : b.getStateDefinition().getPossibleStates()) {
                etats++;
                String bloc = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString();
                if (fautifs.containsKey(bloc)) {
                    continue;
                }
                var fb = net.minecraft.world.level.block.Mirror.FRONT_BACK;
                var lr = net.minecraft.world.level.block.Mirror.LEFT_RIGHT;
                var no = net.minecraft.world.level.block.Mirror.NONE;
                var r0 = net.minecraft.world.level.block.Rotation.NONE;
                var r90 = net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
                var r180 = net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
                String faute = null;
                if (Ecriture.transformer(Ecriture.transformer(s, fb, r0), fb, r0) != s) {
                    faute = "miroir x deux fois ≠ identité";
                } else if (Ecriture.transformer(Ecriture.transformer(s, lr, r0), lr, r0) != s) {
                    faute = "miroir z deux fois ≠ identité";
                } else if (Ecriture.transformer(Ecriture.transformer(Ecriture.transformer(Ecriture.transformer(
                        s, no, r90), no, r90), no, r90), no, r90) != s) {
                    faute = "quatre quarts de tour ≠ identité";
                } else if (Ecriture.transformer(s, fb, r180) != Ecriture.transformer(s, lr, r0)) {
                    faute = "miroir x + 180° ≠ miroir z";
                }
                if (faute != null) {
                    fautifs.put(bloc, faute + " (ex. " + Etats.texte(s) + ")");
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("etats_verifies", etats);
        o.addProperty("blocs_fautifs", fautifs.size());
        JsonArray l = new JsonArray();
        fautifs.entrySet().stream().limit(limite).forEach(e -> l.add(e.getKey() + " : " + e.getValue()));
        o.add("fautifs", l);
        return o;
    }

    /** Les métadonnées d'un journal d'annulation (dimension, zone), relues du fichier d'opérations. */
    private record CompoundMeta(JsonObject corps, Boite zone) {
        static CompoundMeta de(String id) {
            for (JsonObject o : Journal.dernieres(100_000)) {
                if (o.has("id") && id.equals(o.get("id").getAsString())) {
                    JsonObject c = new JsonObject();
                    c.addProperty("dim", o.get("dim").getAsString());
                    JsonObject z = o.getAsJsonObject("zone");
                    int[] de = Boite.triplet(z, "de");
                    int[] a = Boite.triplet(z, "a");
                    return new CompoundMeta(c, Boite.de(de[0], de[1], de[2], a[0], a[1], a[2]));
                }
            }
            throw new Refus(404, "opération « " + id + " » absente du journal");
        }
    }

    private static void attendre(HttpExchange ex, Travail t, int secondes) throws IOException {
        Travail.Resultat r;
        try {
            r = t.fin.get(secondes, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            JsonObject o = new JsonObject();
            o.addProperty("travail", t.id);
            o.addProperty("etat", t.etat);
            o.add("avancement", t.avancement());
            o.addProperty("note", "pas fini : relire GET /travail/" + t.id);
            repondre(ex, 202, o);
            return;
        } catch (Exception e) {
            throw new Refus(500, e.toString());
        }
        if (r.png() != null && !"json".equals(ex.getRequestURI().getQuery())) {
            byte[] meta = r.json().toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("X-Chantier", java.util.Base64.getEncoder().encodeToString(meta));
            ex.sendResponseHeaders(200, r.png().length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(r.png());
            }
            return;
        }
        JsonObject j = r.json();
        if (r.png() != null) {
            j.addProperty("png_base64", java.util.Base64.getEncoder().encodeToString(r.png()));
        }
        repondre(ex, r.code(), j);
    }

    private static JsonObject etat(MinecraftServer mc) {
        JsonObject o = new JsonObject();
        o.addProperty("api", "lanterne-chantier");
        o.addProperty("version", net.neoforged.fml.ModList.get().getModContainerById(Lanterne.ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?"));
        o.addProperty("tick_moyen_ms", Math.round(mc.getAverageTickTimeNanos() / 10_000.0) / 100.0);
        long[] tt = mc.getTickTimesNanos();
        long pire = 0;
        for (long v : tt) {
            pire = Math.max(pire, v);
        }
        o.addProperty("pire_tick_100_ms", Math.round(pire / 10_000.0) / 100.0);
        o.addProperty("travaux_en_file", Atelier.enFile());
        o.addProperty("travaux_finis", Atelier.travauxFinis);
        o.addProperty("chantier_ticks_actifs", Atelier.ticksActifs);
        o.addProperty("chantier_temps_total_ms", Atelier.tempsTotal / 1_000_000L);
        o.addProperty("chantier_pire_tick_ms", Math.round(Atelier.pireTick / 10_000.0) / 100.0);
        o.addProperty("budget_ms", Chantier.BUDGET_MS.get());
        o.addProperty("blocs_max", Chantier.BLOCS_MAX.get());
        o.addProperty("rayon_joueurs", Chantier.RAYON_JOUEURS.get());
        List<ServerPlayer> joueurs;
        try {
            joueurs = new ArrayList<>(mc.getPlayerList().getPlayers());
        } catch (RuntimeException e) {
            joueurs = List.of();
        }
        o.addProperty("joueurs", joueurs.size());
        JsonArray js = new JsonArray();
        for (ServerPlayer p : joueurs) {
            JsonObject j = new JsonObject();
            j.addProperty("nom", p.getGameProfile().name());
            j.addProperty("dim", p.level().dimension().identifier().toString());
            j.add("pos", Json.xyz(p.blockPosition()));
            js.add(j);
        }
        o.add("liste_joueurs", js);
        JsonArray dims = new JsonArray();
        for (ServerLevel l : mc.getAllLevels()) {
            dims.add(l.dimension().identifier().toString());
        }
        o.add("dimensions", dims);
        JsonArray routes = new JsonArray();
        for (String r : new String[] {"GET /etat", "GET /journal?n=", "GET /cliches", "GET /travail/<id>",
                "POST /lire/blocs", "POST /lire/bloc_entites", "POST /lire/entites", "POST /lire/hauteurs",
                "POST /ecrire/blocs", "POST /ecrire/remplir", "POST /ecrire/remplacer", "POST /ecrire/creuser",
                "POST /ecrire/cloner", "POST /annuler", "POST /cliche", "POST /diff", "POST /rendu",
                "POST /verifier/transformations"}) {
            routes.add(r);
        }
        o.add("routes", routes);
        return o;
    }

    static void repondre(HttpExchange ex, int code, JsonObject o) throws IOException {
        byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }
}
