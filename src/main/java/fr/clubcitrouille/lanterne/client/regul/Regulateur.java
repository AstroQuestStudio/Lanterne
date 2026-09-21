package fr.clubcitrouille.lanterne.client.regul;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le régulateur adaptatif de qualité.
 *
 * <h2>Principe</h2>
 *
 * <p>Une fois par seconde environ, on regarde le temps d'image moyen et le 95e centile de la
 * fenêtre. Sous la cible ({@link RegulConfig#CIBLE_FPS}) on descend d'un palier ; avec beaucoup de
 * marge et un long calme on remonte d'un palier. Un palier est un ensemble d'options vanilla
 * (ombres des entités, nuages, particules, distance des entités, distance de rendu...) exprimées
 * comme un PLAFOND appliqué aux réglages du joueur : on ne dépasse jamais ce qu'il a choisi.
 *
 * <h2>Sans à-coups</h2>
 *
 * <ul>
 *   <li>hystérésis : descendre après 3 fenêtres mauvaises de suite, remonter après 12 bonnes ;</li>
 *   <li>délai de garde de 8 s après un changement (les chunks se reconstruisent, le temps d'image
 *       mesuré juste après n'est pas représentatif) ;</li>
 *   <li>montée bien plus lente que la descente : on préfère rester un peu bas que osciller.</li>
 * </ul>
 *
 * <h2>Ne jamais abîmer options.txt</h2>
 *
 * <p>Les réglages du joueur sont sauvegardés dans {@code config/lanterne/regul-base.properties}
 * dès qu'on modifie quoi que ce soit, et rétablis à la déconnexion, quand le régulateur est coupé
 * et — si le jeu s'est arrêté en pleine régulation — au lancement suivant.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Regulateur {
    private static final int MAX_PALIER = 8;
    private static final int DESCENTE_APRES = 3;
    private static final int MONTEE_APRES = 12;
    private static final long GARDE_NS = 8_000_000_000L;
    private static final long FENETRE_NS = 1_000_000_000L;

    // Fenêtre courante.
    private static long debutFenetre;
    private static long dernierInstant;
    private static int images;
    private static long sommeImagesNs;
    private static final long[] TAMPON = new long[1024];
    private static int nTampon;

    // Décision.
    private static int palier;
    private static int mauvaises;
    private static int bonnes;
    private static long garde;

    // Réglages du joueur (les plafonds).
    private static boolean baseCapturee;
    private static int baseRd;
    private static int baseSim;
    private static double baseEnt;
    private static boolean baseOmbres;
    private static int baseBiomes;
    private static CloudStatus baseNuages;
    private static int baseMeteo;
    private static ParticleStatus baseParticules;
    private static int baseOmbreShaders = -1;

    private static boolean restaureAuDemarrage;
    private static boolean profilEvalue;
    private static String rendererGl = "";

    private Regulateur() {}

    // ------------------------------------------------------------------ accès pour Dials / écran

    public static boolean actif() {
        return RegulConfig.SPEC.isLoaded() && RegulConfig.ACTIF.get();
    }

    public static int cible() {
        return RegulConfig.SPEC.isLoaded() ? RegulConfig.CIBLE_FPS.get() : 60;
    }

    public static int palier() {
        return palier;
    }

    public static String gpu() {
        return rendererGl;
    }

    public static String description() {
        if (!actif()) {
            return "coupé";
        }
        return palier == 0 ? "cible " + cible() + " — qualité max"
                : "cible " + cible() + " — palier " + palier + "/" + MAX_PALIER;
    }

    // ------------------------------------------------------------------ boucle

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !RegulConfig.SPEC.isLoaded()) {
            return;
        }
        long now = System.nanoTime();
        if (!restaureAuDemarrage) {
            restaureAuDemarrage = true;
            restaurerApresPlantage(mc.options);
        }
        if (!profilEvalue) {
            profilEvalue = true;
            evaluerProfil(mc);
        }
        if (!actif()) {
            if (baseCapturee) {
                restaurer(mc.options);
            }
            debutFenetre = 0;
            return;
        }
        if (!mc.isWindowActive()) {
            debutFenetre = 0; // fenêtre en arrière-plan : le limiteur vanilla fausse la mesure
            return;
        }
        if (debutFenetre == 0) {
            reinitialiserFenetre(now);
            return;
        }
        long dt = now - dernierInstant;
        dernierInstant = now;
        if (dt > 2_000_000_000L) { // gel (chargement, pause) : ne compte pas
            reinitialiserFenetre(now);
            return;
        }
        images++;
        sommeImagesNs += dt;
        if (nTampon < TAMPON.length) {
            TAMPON[nTampon++] = dt;
        }
        if (now - debutFenetre >= FENETRE_NS && images >= 5) {
            evaluerFenetre(mc, now);
            reinitialiserFenetre(now);
        }
    }

    private static void reinitialiserFenetre(long now) {
        debutFenetre = now;
        dernierInstant = now;
        images = 0;
        sommeImagesNs = 0;
        nTampon = 0;
    }

    private static void evaluerFenetre(Minecraft mc, long now) {
        if (now < garde) {
            return;
        }
        double moyenneNs = sommeImagesNs / (double) images;
        double fpsMoyen = 1e9d / moyenneNs;
        double fps95 = 1e9d / quantile95();
        int cible = cible();

        // « Mauvais » : moyenne sous la cible, ou 95e centile sous 60 % de la cible.
        boolean mauvais = fpsMoyen < cible * 0.95d || fps95 < cible * 0.6d;
        // « Bon » : beaucoup de marge sur la moyenne ET sur le centile.
        boolean bon = fpsMoyen > cible * 1.6d && fps95 > cible * 1.1d;

        if (mauvais) {
            bonnes = 0;
            if (++mauvaises >= DESCENTE_APRES && palier < MAX_PALIER) {
                changerPalier(mc, palier + 1, now, fpsMoyen);
                mauvaises = 0;
            }
        } else if (bon && palier > 0) {
            mauvaises = 0;
            if (++bonnes >= MONTEE_APRES) {
                changerPalier(mc, palier - 1, now, fpsMoyen);
                bonnes = 0;
            }
        } else {
            mauvaises = 0;
            bonnes = 0;
        }
    }

    private static double quantile95() {
        if (nTampon == 0) {
            return 1;
        }
        long[] copie = Arrays.copyOf(TAMPON, nTampon);
        Arrays.sort(copie);
        return copie[Math.min(nTampon - 1, (int) Math.ceil(nTampon * 0.95d) - 1)];
    }

    private static void changerPalier(Minecraft mc, int nouveau, long now, double fpsMoyen) {
        Options o = mc.options;
        if (!baseCapturee) {
            capturerBase(o);
        }
        Lanterne.LOG.info("[REGUL] palier {} -> {} ({} FPS mesurés, cible {}).", palier, nouveau,
                Math.round(fpsMoyen), cible());
        palier = nouveau;
        appliquer(o);
        garde = now + GARDE_NS;
    }

    // ------------------------------------------------------------------ application des paliers

    private static void appliquer(Options o) {
        int p = palier;
        int plancher = RegulConfig.DISTANCE_PLANCHER.get();

        set(o.entityShadows(), p >= 1 ? Boolean.FALSE : Boolean.valueOf(baseOmbres));
        set(o.biomeBlendRadius(), Integer.valueOf(p >= 1 ? Math.min(baseBiomes, 1) : baseBiomes));
        set(o.cloudStatus(), p >= 2 ? CloudStatus.OFF : baseNuages);
        set(o.weatherRadius(), Integer.valueOf(p >= 2 ? Math.min(baseMeteo, 5) : baseMeteo));
        set(o.particles(), plafondParticules(p));
        double ent = p >= 6 ? 0.5d : p >= 4 ? 0.75d : baseEnt;
        set(o.entityDistanceScaling(), Double.valueOf(Math.min(baseEnt, ent)));

        int rd = p >= 8 ? plancher : p >= 7 ? 6 : p >= 6 ? 8 : p >= 5 ? 10 : baseRd;
        rd = Math.max(plancher, Math.min(baseRd, rd));
        set(o.renderDistance(), Integer.valueOf(rd));
        int sim = p >= 5 ? Math.min(baseSim, Math.max(5, rd)) : baseSim;
        set(o.simulationDistance(), Integer.valueOf(sim));

        appliquerOmbresShaders(p);
        sauverBase();
    }

    private static ParticleStatus plafondParticules(int p) {
        ParticleStatus cible = p >= 7 ? ParticleStatus.MINIMAL
                : p >= 3 ? ParticleStatus.DECREASED : baseParticules;
        return cible.ordinal() >= baseParticules.ordinal() ? cible : baseParticules;
    }

    /** Distance d'ombre d'Iris : plafond décroissant (chunks), seulement si Iris est actif. */
    private static void appliquerOmbresShaders(int p) {
        try {
            if (!net.irisshaders.iris.mixin.IrisMixinPlugin.sodiumPresent()) {
                return;
            }
            if (baseOmbreShaders < 0) {
                baseOmbreShaders = net.irisshaders.iris.gui.option.IrisVideoSettings.shadowDistance;
            }
            int plafond = p >= 7 ? 4 : p >= 5 ? 6 : p >= 3 ? 8 : p >= 2 ? 12 : p >= 1 ? 16
                    : Integer.MAX_VALUE;
            net.irisshaders.iris.gui.option.IrisVideoSettings.shadowDistance =
                    Math.min(baseOmbreShaders, plafond);
        } catch (Throwable indisponible) {
            // Iris inerte ou absent : rien à réguler de ce côté.
        }
    }

    private static <T> void set(OptionInstance<T> option, T voulue) {
        try {
            if (!option.get().equals(voulue)) {
                option.set(voulue);
            }
        } catch (RuntimeException refuse) {
            Lanterne.LOG.debug("[REGUL] option refusée : {}", refuse.toString());
        }
    }

    // ------------------------------------------------------------------ base du joueur

    private static void capturerBase(Options o) {
        baseRd = o.renderDistance().get();
        baseSim = o.simulationDistance().get();
        baseEnt = o.entityDistanceScaling().get();
        baseOmbres = o.entityShadows().get();
        baseBiomes = o.biomeBlendRadius().get();
        baseNuages = o.cloudStatus().get();
        baseMeteo = o.weatherRadius().get();
        baseParticules = o.particles().get();
        baseCapturee = true;
        sauverBase();
    }

    private static void sauverBase() {
        try {
            Path f = fichierBase();
            Files.createDirectories(f.getParent());
            Properties p = new Properties();
            p.setProperty("renderDistance", String.valueOf(baseRd));
            p.setProperty("simulationDistance", String.valueOf(baseSim));
            p.setProperty("entityDistanceScaling", String.valueOf(baseEnt));
            p.setProperty("entityShadows", String.valueOf(baseOmbres));
            p.setProperty("biomeBlendRadius", String.valueOf(baseBiomes));
            p.setProperty("cloudStatus", baseNuages.name());
            p.setProperty("weatherRadius", String.valueOf(baseMeteo));
            p.setProperty("particles", baseParticules.name());
            if (baseOmbreShaders >= 0) {
                p.setProperty("shadowDistance", String.valueOf(baseOmbreShaders));
            }
            try (var out = Files.newOutputStream(f)) {
                p.store(out, "Reglages du joueur sauvegardes par le regulateur Lanterne");
            }
        } catch (IOException ignored) {
            // Sans fichier, pas de restauration après un arrêt brutal — sans autre conséquence.
        }
    }

    private static Path fichierBase() {
        return FMLPaths.CONFIGDIR.get().resolve("lanterne").resolve("regul-base.properties");
    }

    /** Si le jeu s'est arrêté en pleine régulation, rétablit les réglages du joueur avant tout. */
    private static void restaurerApresPlantage(Options o) {
        Path f = fichierBase();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try (var in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            o.renderDistance().set(Integer.parseInt(p.getProperty("renderDistance", "12")));
            o.simulationDistance().set(Integer.parseInt(p.getProperty("simulationDistance", "12")));
            o.entityDistanceScaling().set(Double.parseDouble(p.getProperty("entityDistanceScaling", "1.0")));
            o.entityShadows().set(Boolean.parseBoolean(p.getProperty("entityShadows", "true")));
            o.biomeBlendRadius().set(Integer.parseInt(p.getProperty("biomeBlendRadius", "2")));
            o.cloudStatus().set(CloudStatus.valueOf(p.getProperty("cloudStatus", "FANCY")));
            o.weatherRadius().set(Integer.parseInt(p.getProperty("weatherRadius", "10")));
            o.particles().set(ParticleStatus.valueOf(p.getProperty("particles", "ALL")));
            if (p.getProperty("shadowDistance") != null) {
                try {
                    net.irisshaders.iris.gui.option.IrisVideoSettings.shadowDistance =
                            Integer.parseInt(p.getProperty("shadowDistance"));
                } catch (Throwable inerte) {
                    // Iris indisponible.
                }
            }
            Lanterne.LOG.info("[REGUL] réglages du joueur rétablis après un arrêt en cours de régulation.");
        } catch (IOException | RuntimeException illisible) {
            Lanterne.LOG.warn("[REGUL] fichier de base illisible : {}", illisible.toString());
        }
        try {
            Files.deleteIfExists(f);
        } catch (IOException ignored) {
            // Rien.
        }
    }

    private static void restaurer(Options o) {
        if (!baseCapturee) {
            return;
        }
        palier = 0;
        appliquer(o);
        baseCapturee = false;
        baseOmbreShaders = -1;
        try {
            Files.deleteIfExists(fichierBase());
        } catch (IOException ignored) {
            // Rien.
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Minecraft mc = Minecraft.getInstance();
        if (baseCapturee) {
            restaurer(mc.options);
        }
        debutFenetre = 0;
        palier = 0;
        mauvaises = 0;
        bonnes = 0;
        garde = 0;
    }

    // ------------------------------------------------------------------ profil « petite config »

    private static void evaluerProfil(Minecraft mc) {
        try {
            String r = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            rendererGl = r == null ? "" : r;
        } catch (Throwable pasDeContexte) {
            rendererGl = "";
        }
        boolean petite = GpuProfile.petiteConfig(rendererGl);
        Lanterne.LOG.info("[REGUL] GPU détecté : « {} » — {}.", rendererGl,
                petite ? "petite config" : "GPU dédié");
        if (!RegulConfig.PROFIL_AUTO.get() || RegulConfig.PROFIL_APPLIQUE.get()) {
            return;
        }
        RegulConfig.PROFIL_APPLIQUE.set(true);
        RegulConfig.SPEC.save();
        if (!petite) {
            return;
        }
        Options o = mc.options;
        // Premier lancement sur petite config : des plafonds prudents PERSISTANTS (de vrais choix de
        // départ, que le joueur peut remonter dans les options).
        if (o.renderDistance().get() > 8) {
            o.renderDistance().set(8);
        }
        if (o.simulationDistance().get() > 8) {
            o.simulationDistance().set(8);
        }
        if (o.entityShadows().get()) {
            o.entityShadows().set(false);
        }
        if (o.biomeBlendRadius().get() > 1) {
            o.biomeBlendRadius().set(1);
        }
        if (o.cloudStatus().get() == CloudStatus.FANCY) {
            o.cloudStatus().set(CloudStatus.FAST);
        }
        if (o.particles().get() == ParticleStatus.ALL) {
            o.particles().set(ParticleStatus.DECREASED);
        }
        Lanterne.LOG.info("[REGUL] profil « petite config » appliqué (distance 8, ombres des entités coupées...).");
    }
}
