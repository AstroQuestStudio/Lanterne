package fr.clubcitrouille.lanterne.client.reglage;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * Les réglages de performance de Lanterne : la liste, leurs valeurs, et leur effet.
 *
 * <h2>Le principe</h2>
 *
 * <p>Chaque réglage a un effet réel, vérifié, et une valeur par défaut qui ne change rien à l'image
 * (sauf « distance des particules », fixée à 128 blocs, où une particule ne fait plus que quelques
 * pixels). Ce n'est pas une case décorative : chacun est lu par un hook du jeu — voir
 * {@code mixin/reglage/*} — et chaque hook commence par tester un booléen mis en cache ici. Quand
 * rien ne s'écarte du défaut, ce test est le seul coût.
 *
 * <h2>Pourquoi des valeurs en cache</h2>
 *
 * <p>Les hooks des particules sont appelés des milliers de fois par seconde. Lire une valeur de
 * configuration à chaque appel serait un coût pour rien : {@link #refresh()} recalcule les champs
 * ci-dessous une fois, quand la configuration change (chargement du fichier, écran, commande).
 *
 * <h2>Salle blanche</h2>
 *
 * <p>Ce fichier est écrit à partir des options vanilla de Minecraft 26.3 et de la conception de
 * Lanterne. Il ne reprend rien de Sodium.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Reglages {
    /** Ce que le joueur perd à l'image en écartant le réglage de sa valeur d'origine. */
    public enum Perte {
        AUCUNE("aucune"), FAIBLE("faible"), MOYENNE("moyenne"), FORTE("forte");

        private final String texte;

        Perte(String texte) {
            this.texte = texte;
        }

        public String texte() {
            return this.texte;
        }
    }

    /** L'onglet où le réglage s'affiche. */
    public enum Famille {
        PARTICULES_METEO("Particules & météo", "densité, distance, pluie, ciel"),
        ENTITES_BLOCS("Entités & blocs", "distances d'affichage, textures animées"),
        PERFORMANCES("Performances", "FPS hors focus, inactivité");

        private final String titre;
        private final String sousTitre;

        Famille(String titre, String sousTitre) {
            this.titre = titre;
            this.sousTitre = sousTitre;
        }

        public String titre() {
            return this.titre;
        }

        public String sousTitre() {
            return this.sousTitre;
        }
    }

    /** Une catégorie de particules : le groupe auquel une densité s'applique. */
    private static final byte CAT_AUTRE = 0, CAT_EXPLOSION = 1, CAT_FUMEE = 2, CAT_EAU = 3, CAT_FEUILLES = 4, CAT_MAGIE = 5;

    /**
     * Un réglage : un entier (un booléen vaut 0 ou 1), ses valeurs permises, et tout ce qu'il faut
     * pour l'afficher.
     */
    public static final class Reglage {
        private final String id;
        private final Famille famille;
        private final String nom;
        private final Perte perte;
        private final String quoi;
        private final String gain;
        private final ModConfigSpec.IntValue entier;
        private final ModConfigSpec.BooleanValue booleen;
        private final int[] niveaux;
        private final String unite;
        private final String zero;

        private Reglage(String id, Famille famille, String nom, Perte perte, String quoi, String gain,
                ModConfigSpec.IntValue entier, ModConfigSpec.BooleanValue booleen, int[] niveaux,
                String unite, String zero) {
            this.id = id;
            this.famille = famille;
            this.nom = nom;
            this.perte = perte;
            this.quoi = quoi;
            this.gain = gain;
            this.entier = entier;
            this.booleen = booleen;
            this.niveaux = niveaux;
            this.unite = unite;
            this.zero = zero;
        }

        public String id() {
            return this.id;
        }

        public Famille famille() {
            return this.famille;
        }

        public String nom() {
            return this.nom;
        }

        public Perte perte() {
            return this.perte;
        }

        public String quoi() {
            return this.quoi;
        }

        public String gain() {
            return this.gain;
        }

        public boolean chargee() {
            return ClientConfig.SPEC.isLoaded();
        }

        public int valeur() {
            if (!chargee()) {
                return defaut();
            }
            return this.booleen != null ? (this.booleen.get() ? 1 : 0) : this.entier.get();
        }

        public int defaut() {
            return this.booleen != null ? (this.booleen.getDefault() ? 1 : 0) : this.entier.getDefault();
        }

        public boolean estDefaut() {
            return valeur() == defaut();
        }

        /** Écrit la valeur (bornée à la plus proche permise) sans sauvegarder. */
        public void poser(int valeur) {
            if (!chargee()) {
                return;
            }
            int proche = plusProche(valeur);
            if (this.booleen != null) {
                this.booleen.set(proche != 0);
            } else {
                this.entier.set(proche);
            }
        }

        public void reinitialiser() {
            poser(defaut());
        }

        private int plusProche(int valeur) {
            int meilleur = this.niveaux[0];
            for (int candidat : this.niveaux) {
                if (Math.abs(candidat - valeur) < Math.abs(meilleur - valeur)) {
                    meilleur = candidat;
                }
            }
            return meilleur;
        }

        /** Un cran : +1 ou -1, en bouclant pour un interrupteur et en butée pour un nombre. */
        public void cran(int sens) {
            int actuel = plusProche(valeur());
            int index = 0;
            for (int i = 0; i < this.niveaux.length; i++) {
                if (this.niveaux[i] == actuel) {
                    index = i;
                    break;
                }
            }
            int suivant = this.booleen != null || this.niveaux.length <= 4
                    ? Math.floorMod(index + sens, this.niveaux.length)
                    : Math.max(0, Math.min(this.niveaux.length - 1, index + sens));
            poser(this.niveaux[suivant]);
            appliquer();
        }

        public int[] niveaux() {
            return this.niveaux;
        }

        public String texte(int valeur) {
            if (this.booleen != null) {
                return valeur != 0 ? "actif" : "coupé";
            }
            if (valeur == 0 && this.zero != null) {
                return this.zero;
            }
            return valeur + this.unite;
        }

        public String texte() {
            return texte(valeur());
        }
    }

    private static final List<Reglage> TOUS = new ArrayList<>();
    private static final Map<String, Reglage> PAR_ID = new LinkedHashMap<>();

    private static final int[] POURCENT = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
    private static final int[] DISTANCES = {0, 16, 32, 48, 64, 96, 128, 160, 192, 256};
    private static final int[] NIVEAUX_PLAQUES = {0, 8, 16, 24, 32, 48, 64};
    private static final int[] BOOL = {0, 1};
    private static final int[] TEXTURES = {0, 1, 2, 3};

    public static final Reglage DENSITE;
    public static final Reglage EXPLOSIONS;
    public static final Reglage FUMEE;
    public static final Reglage EAU;
    public static final Reglage FEUILLES;
    public static final Reglage MAGIE;
    public static final Reglage DISTANCE_PARTICULES;
    public static final Reglage METEO;
    public static final Reglage ASTRES;
    public static final Reglage ETOILES;
    public static final Reglage BLOCS_ENTITES;
    public static final Reglage PLAQUES;
    public static final Reglage TEXTURES_ANIMEES;
    public static final Reglage FPS_HORS_FOCUS;
    public static final Reglage CADRES;
    public static final Reglage ARMOR_STANDS;

    private static final int[] NIVEAUX_FPS = {0, 5, 10, 15, 30, 60, 120, 240};

    static {
        Famille pm = Famille.PARTICULES_METEO;
        Famille eb = Famille.ENTITES_BLOCS;
        Famille pf = Famille.PERFORMANCES;
        DENSITE = ajouter("particules_densite", pm, "Densité des particules", Perte.FAIBLE,
                "Part de toutes les particules qui sont créées. 100 % = vanilla.",
                "gros gain dans les scènes chargées (explosions, feux, pluie)",
                ClientConfig.PARTICULES_DENSITE, null, POURCENT, " %", null);
        EXPLOSIONS = ajouter("particules_explosions", pm, "Explosions & feux d'artifice", Perte.FAIBLE,
                "Densité des particules d'explosion, de flash et de feu d'artifice.",
                "utile sur les serveurs avec TNT et feux d'artifice",
                ClientConfig.PARTICULES_EXPLOSIONS, null, POURCENT, " %", null);
        FUMEE = ajouter("particules_fumee", pm, "Fumées", Perte.FAIBLE,
                "Densité des fumées : feux de camp, torches, chaudrons, nuages de poussière.",
                "utile près des feux de camp et des fours nombreux",
                ClientConfig.PARTICULES_FUMEE, null, POURCENT, " %", null);
        EAU = ajouter("particules_eau", pm, "Eau & bulles", Perte.FAIBLE,
                "Densité des éclaboussures, bulles et gouttes.",
                "utile sous la pluie, près des cascades et des fermes de poissons",
                ClientConfig.PARTICULES_EAU, null, POURCENT, " %", null);
        FEUILLES = ajouter("particules_feuilles", pm, "Feuilles & pétales", Perte.FAIBLE,
                "Densité des feuilles et pétales qui tombent des arbres.",
                "utile dans les forêts de cerisiers et de chênes pâles",
                ClientConfig.PARTICULES_FEUILLES, null, POURCENT, " %", null);
        MAGIE = ajouter("particules_magie", pm, "Magie, redstone & portails", Perte.FAIBLE,
                "Densité des particules de redstone, d'enchantement, de portail et d'effets.",
                "utile près des portails et des tables d'enchantement",
                ClientConfig.PARTICULES_MAGIE, null, POURCENT, " %", null);
        DISTANCE_PARTICULES = ajouter("particules_distance", pm, "Distance des particules", Perte.AUCUNE,
                "Au-delà de cette distance (en blocs), une particule n'est pas créée. 0 = illimitée.",
                "nul à 128 blocs (moins de quatre pixels) ; gros gain avec les foules de particules",
                ClientConfig.PARTICULES_DISTANCE, null, DISTANCES, " blocs", "illimitée");
        METEO = ajouter("meteo_dessinee", pm, "Pluie & neige dessinées", Perte.MOYENNE,
                "Dessine les gouttes et les flocons. Coupé : la météo continue, sans l'animation.",
                "gain net sous la pluie, surtout avec un shaderpack",
                null, ClientConfig.METEO_DESSINEE, BOOL, "", null);
        ASTRES = ajouter("ciel_soleil_lune", pm, "Soleil & lune", Perte.FORTE,
                "Dessine le soleil et la lune dans le ciel.",
                "léger ; à couper seulement pour un ciel épuré",
                null, ClientConfig.CIEL_ASTRES, BOOL, "", null);
        ETOILES = ajouter("ciel_etoiles", pm, "Étoiles", Perte.FORTE,
                "Dessine les étoiles la nuit.",
                "léger ; à couper seulement pour un ciel épuré",
                null, ClientConfig.CIEL_ETOILES, BOOL, "", null);
        BLOCS_ENTITES = ajouter("blocs_entites_distance", eb, "Distance des blocs animés", Perte.FAIBLE,
                "Coffres, panneaux, lits, bannières : au-delà de cette distance, ils ne sont pas dessinés"
                        + " comme blocs animés. 0 = vanilla.",
                "utile dans les bases pleines de coffres et de panneaux",
                ClientConfig.BLOCS_ENTITES_DISTANCE, null, DISTANCES, " blocs", "vanilla");
        PLAQUES = ajouter("plaques_distance", eb, "Distance des plaques de nom", Perte.FAIBLE,
                "Au-delà de cette distance, la plaque de nom d'une entité n'est pas affichée. 0 = 64.",
                "léger ; utile sur les serveurs avec beaucoup de joueurs et d'entités nommées",
                ClientConfig.PLAQUES_DISTANCE, null, NIVEAUX_PLAQUES, " blocs", "vanilla");
        TEXTURES_ANIMEES = ajouter("textures_animees", eb, "Fluidité des textures animées", Perte.MOYENNE,
                "Eau, lave, feu, portails : 0 toutes les images, 1 une sur deux, 2 une sur quatre,"
                        + " 3 figées. Le rythme est gardé, seules des images intermédiaires sont sautées.",
                "réduit les envois vers le GPU ; utile sur petite config",
                ClientConfig.TEXTURES_ANIMEES, null, TEXTURES, "", null);
        CADRES = ajouter("cadres_distance", eb, "Distance des cadres & peintures", Perte.FAIBLE,
                "Au-delà de cette distance (en blocs), les cadres d'objets et les peintures ne sont pas"
                        + " dessinés. 0 = vanilla.",
                "utile dans les bases pleines de cadres (magasins, galeries)",
                ClientConfig.CADRES_DISTANCE, null, DISTANCES, " blocs", "vanilla");
        ARMOR_STANDS = ajouter("armor_stands_distance", eb, "Distance des supports d'armure", Perte.FAIBLE,
                "Au-delà de cette distance (en blocs), les armor stands ne sont pas dessinés. 0 = vanilla.",
                "utile dans les salles d'exposition et les fermes à armor stands",
                ClientConfig.ARMOR_STANDS_DISTANCE, null, DISTANCES, " blocs", "vanilla");
        FPS_HORS_FOCUS = ajouter("fps_hors_focus", pf, "FPS hors focus", Perte.AUCUNE,
                "Images par seconde maximales quand la fenêtre est visible mais n'a pas le focus."
                        + " Vanilla ne bride que la fenêtre réduite. 0 = pas de limite.",
                "invisible pour le joueur ; gros gain quand plusieurs jeux ou applications tournent",
                ClientConfig.FPS_HORS_FOCUS, null, NIVEAUX_FPS, " FPS", "illimité");
    }

    private static Reglage ajouter(String id, Famille famille, String nom, Perte perte, String quoi,
            String gain, ModConfigSpec.IntValue entier, ModConfigSpec.BooleanValue booleen,
            int[] niveaux, String unite, String zero) {
        Reglage reglage = new Reglage(id, famille, nom, perte, quoi, gain, entier, booleen, niveaux,
                unite, zero);
        TOUS.add(reglage);
        PAR_ID.put(id, reglage);
        return reglage;
    }

    private Reglages() {}

    public static List<Reglage> tous() {
        return TOUS;
    }

    public static List<Reglage> deLaFamille(Famille famille) {
        List<Reglage> liste = new ArrayList<>();
        for (Reglage reglage : TOUS) {
            if (reglage.famille() == famille) {
                liste.add(reglage);
            }
        }
        return liste;
    }

    public static Reglage parId(String id) {
        return PAR_ID.get(id);
    }

    // --- Les valeurs en cache, lues par les hooks -----------------------------------------------

    /** Vrai si au moins un réglage de particules s'écarte de « rien à faire ». Sinon le hook sort. */
    public static volatile boolean particulesActives;
    private static volatile int densiteGlobale = 100;
    private static volatile int densiteExplosions = 100;
    private static volatile int densiteFumee = 100;
    private static volatile int densiteEau = 100;
    private static volatile int densiteFeuilles = 100;
    private static volatile int densiteMagie = 100;
    private static volatile double particulesDistanceCarre;

    public static volatile boolean meteoCoupee;
    public static volatile boolean astresCoupes;
    public static volatile boolean etoilesCoupees;
    public static volatile double blocsDistanceCarre;
    public static volatile double plaquesDistance;
    public static volatile int texturesMode;
    public static volatile int fpsHorsFocus;
    public static volatile double cadresDistanceCarre;
    public static volatile double armorStandsDistanceCarre;

    private static int compteurTextures;

    /** Recalcule le cache depuis la configuration. Appelé quand elle change. */
    public static void refresh() {
        if (!ClientConfig.SPEC.isLoaded()) {
            return;
        }
        densiteGlobale = DENSITE.valeur();
        densiteExplosions = EXPLOSIONS.valeur();
        densiteFumee = FUMEE.valeur();
        densiteEau = EAU.valeur();
        densiteFeuilles = FEUILLES.valeur();
        densiteMagie = MAGIE.valeur();
        int distance = DISTANCE_PARTICULES.valeur();
        particulesDistanceCarre = distance <= 0 ? 0d : (double) distance * distance;
        particulesActives = densiteGlobale < 100 || densiteExplosions < 100 || densiteFumee < 100
                || densiteEau < 100 || densiteFeuilles < 100 || densiteMagie < 100 || distance > 0;
        meteoCoupee = METEO.valeur() == 0;
        astresCoupes = ASTRES.valeur() == 0;
        etoilesCoupees = ETOILES.valeur() == 0;
        int blocs = BLOCS_ENTITES.valeur();
        blocsDistanceCarre = blocs <= 0 ? 0d : (double) blocs * blocs;
        int plaques = PLAQUES.valeur();
        plaquesDistance = plaques <= 0 ? 0d : plaques;
        texturesMode = TEXTURES_ANIMEES.valeur();
        fpsHorsFocus = FPS_HORS_FOCUS.valeur();
        int cadres = CADRES.valeur();
        cadresDistanceCarre = cadres <= 0 ? 0d : (double) cadres * cadres;
        int supports = ARMOR_STANDS.valeur();
        armorStandsDistanceCarre = supports <= 0 ? 0d : (double) supports * supports;
    }

    /**
     * Cette entité est-elle trop loin pour son type ? Un cadre, une peinture ou un support d'armure.
     * Le test de type ne se fait que si une des deux distances est active.
     */
    public static boolean entiteTropLoin(net.minecraft.world.entity.Entity entite,
            double cameraX, double cameraY, double cameraZ) {
        double limite;
        if (entite instanceof net.minecraft.world.entity.decoration.ItemFrame
                || entite instanceof net.minecraft.world.entity.decoration.painting.Painting) {
            limite = cadresDistanceCarre;
        } else if (entite instanceof net.minecraft.world.entity.decoration.ArmorStand) {
            limite = armorStandsDistanceCarre;
        } else {
            return false;
        }
        if (limite <= 0d) {
            return false;
        }
        double dx = entite.getX() - cameraX;
        double dy = entite.getY() - cameraY;
        double dz = entite.getZ() - cameraZ;
        return dx * dx + dy * dy + dz * dz > limite;
    }

    /**
     * La limite d'images par seconde quand la fenêtre est visible sans focus : vanilla ne bride que la
     * fenêtre réduite (10 images par seconde). Renvoie la limite à appliquer, ou {@code limite} tel quel.
     */
    public static int limiteHorsFocus(int limite) {
        int voulue = fpsHorsFocus;
        if (voulue <= 0) {
            return limite;
        }
        var fenetre = Minecraft.getInstance().getWindow();
        if (fenetre == null || fenetre.isFocused() || fenetre.isIconified()) {
            return limite;
        }
        return Math.min(limite, voulue);
    }

    /** Sauvegarde le fichier et met le cache à jour : à appeler après une série de {@code poser}. */
    public static void appliquer() {
        if (!ClientConfig.SPEC.isLoaded()) {
            return;
        }
        refresh();
        ClientConfig.SPEC.save();
    }

    @SubscribeEvent
    static void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == ClientConfig.SPEC) {
            refresh();
        }
    }

    // --- Les décisions des hooks ----------------------------------------------------------------

    /**
     * Cette particule doit-elle être créée ?
     *
     * <p>Deux tests, dans l'ordre du moins cher au plus cher : la distance au joueur (trois
     * soustractions), puis la densité (un tirage). La catégorie est trouvée une fois par type de
     * particule et gardée dans une table à identités.
     */
    public static boolean creerParticule(ParticleOptions options, double x, double y, double z) {
        double limite = particulesDistanceCarre;
        if (limite > 0d) {
            Minecraft client = Minecraft.getInstance();
            if (client.gameRenderer != null) {
                var camera = client.gameRenderer.mainCamera().position();
                double dx = x - camera.x;
                double dy = y - camera.y;
                double dz = z - camera.z;
                if (dx * dx + dy * dy + dz * dz > limite) {
                    return false;
                }
            }
        }
        int part = densiteGlobale;
        if (part < 100 || densiteExplosions < 100 || densiteFumee < 100 || densiteEau < 100
                || densiteFeuilles < 100 || densiteMagie < 100) {
            byte categorie = categorie(options.getType());
            part = part * densiteDeLaCategorie(categorie) / 100;
            if (part < 100 && (part <= 0 || ThreadLocalRandom.current().nextInt(100) >= part)) {
                return false;
            }
        }
        return true;
    }

    private static int densiteDeLaCategorie(byte categorie) {
        return switch (categorie) {
            case CAT_EXPLOSION -> densiteExplosions;
            case CAT_FUMEE -> densiteFumee;
            case CAT_EAU -> densiteEau;
            case CAT_FEUILLES -> densiteFeuilles;
            case CAT_MAGIE -> densiteMagie;
            default -> 100;
        };
    }

    private static final Map<ParticleType<?>, Byte> CATEGORIES = new IdentityHashMap<>();

    private static byte categorie(ParticleType<?> type) {
        Byte connu;
        synchronized (CATEGORIES) {
            connu = CATEGORIES.get(type);
        }
        if (connu != null) {
            return connu;
        }
        var cle = BuiltInRegistries.PARTICLE_TYPE.getKey(type);
        String chemin = cle == null ? "" : cle.getPath();
        byte trouve = classer(chemin);
        synchronized (CATEGORIES) {
            CATEGORIES.put(type, trouve);
        }
        return trouve;
    }

    /** Range une particule par son identifiant. Exposé pour les tests et la documentation. */
    public static byte classer(String chemin) {
        if (chemin.contains("falling_dust")) {
            return CAT_AUTRE;
        }
        if (chemin.contains("explosion") || chemin.contains("firework") || chemin.contains("flash")) {
            return CAT_EXPLOSION;
        }
        if (chemin.contains("smoke") || chemin.contains("poof") || chemin.contains("cloud")
                || chemin.contains("ash")) {
            return CAT_FUMEE;
        }
        if (chemin.contains("leaf") || chemin.contains("leaves") || chemin.contains("petal")) {
            return CAT_FEUILLES;
        }
        if (chemin.contains("water") || chemin.contains("bubble") || chemin.contains("splash")
                || chemin.contains("rain") || chemin.contains("dripping") || chemin.contains("fishing")
                || chemin.contains("dolphin") || chemin.contains("current_down")) {
            return CAT_EAU;
        }
        if (chemin.contains("dust") || chemin.contains("enchant") || chemin.contains("witch")
                || chemin.contains("portal") || chemin.contains("effect") || chemin.contains("end_rod")
                || chemin.contains("totem") || chemin.contains("glow") || chemin.contains("soul")
                || chemin.contains("sculk") || chemin.contains("vault") || chemin.contains("trial")
                || chemin.contains("ominous")) {
            return CAT_MAGIE;
        }
        return CAT_AUTRE;
    }

    /** Ce bloc animé est-il trop loin pour être dessiné ? */
    public static boolean blocTropLoin(net.minecraft.core.BlockPos position) {
        double limite = blocsDistanceCarre;
        if (limite <= 0d) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.gameRenderer == null) {
            return false;
        }
        var camera = client.gameRenderer.mainCamera().position();
        double dx = position.getX() + 0.5d - camera.x;
        double dy = position.getY() + 0.5d - camera.y;
        double dz = position.getZ() + 0.5d - camera.z;
        return dx * dx + dy * dy + dz * dz > limite;
    }

    /** Faut-il sauter l'envoi des images d'animation de cette passe ? */
    public static boolean sauterAnimationTextures() {
        int mode = texturesMode;
        if (mode == 0) {
            return false;
        }
        if (mode >= 3) {
            return true;
        }
        int pas = mode == 1 ? 2 : 4;
        return (++compteurTextures % pas) != 0;
    }

    // --- Préréglages, export et import ----------------------------------------------------------

    /** Les préréglages : nom, puis paires (id, valeur). Tout id absent revient à son défaut. */
    public static final Map<String, Map<String, Integer>> PREREGLAGES = new LinkedHashMap<>();

    static {
        PREREGLAGES.put("qualite_max", paires(
                "particules_distance", 0));
        PREREGLAGES.put("equilibre", paires(
                "particules_distance", 128));
        PREREGLAGES.put("fps_max", paires(
                "particules_densite", 50,
                "particules_distance", 64,
                "particules_fumee", 50,
                "particules_eau", 40,
                "particules_explosions", 60,
                "blocs_entites_distance", 48,
                "cadres_distance", 64,
                "fps_hors_focus", 15,
                "plaques_distance", 24,
                "textures_animees", 1,
                "meteo_dessinee", 1));
        PREREGLAGES.put("petite_config", paires(
                "particules_densite", 30,
                "particules_distance", 32,
                "particules_fumee", 30,
                "particules_eau", 30,
                "particules_explosions", 40,
                "particules_feuilles", 30,
                "blocs_entites_distance", 32,
                "cadres_distance", 48,
                "armor_stands_distance", 48,
                "fps_hors_focus", 10,
                "plaques_distance", 16,
                "textures_animees", 2,
                "meteo_dessinee", 1));
    }

    /** Une liste « id, valeur, id, valeur… » en table ordonnée (Map.of plafonne à dix paires). */
    private static Map<String, Integer> paires(Object... liste) {
        Map<String, Integer> table = new LinkedHashMap<>();
        for (int i = 0; i + 1 < liste.length; i += 2) {
            table.put((String) liste[i], (Integer) liste[i + 1]);
        }
        return table;
    }

    /** Applique un préréglage : d abord tout revient au défaut, puis ses valeurs sont posées. */
    public static boolean appliquerPrereglage(String nom) {
        Map<String, Integer> valeurs = PREREGLAGES.get(nom);
        if (valeurs == null || !ClientConfig.SPEC.isLoaded()) {
            return false;
        }
        for (Reglage reglage : TOUS) {
            reglage.reinitialiser();
        }
        for (Map.Entry<String, Integer> entree : valeurs.entrySet()) {
            Reglage reglage = PAR_ID.get(entree.getKey());
            if (reglage != null) {
                reglage.poser(entree.getValue());
            }
        }
        appliquer();
        return true;
    }

    public static void restaurer() {
        if (!ClientConfig.SPEC.isLoaded()) {
            return;
        }
        for (Reglage reglage : TOUS) {
            reglage.reinitialiser();
        }
        appliquer();
    }

    /** Une ligne « id=valeur;id=valeur » : tout ce qui s'écarte du défaut, et rien d'autre. */
    public static String exporter() {
        StringBuilder texte = new StringBuilder("lanterne-reglages;");
        for (Reglage reglage : TOUS) {
            if (!reglage.estDefaut()) {
                texte.append(reglage.id()).append('=').append(reglage.valeur()).append(';');
            }
        }
        return texte.toString();
    }

    /** Relit une ligne exportée ; renvoie le nombre de réglages posés, ou -1 si le format est faux. */
    public static int importer(String texte) {
        if (texte == null || !texte.startsWith("lanterne-reglages;") || !ClientConfig.SPEC.isLoaded()) {
            return -1;
        }
        for (Reglage reglage : TOUS) {
            reglage.reinitialiser();
        }
        int poses = 0;
        for (String morceau : texte.substring("lanterne-reglages;".length()).split(";")) {
            int egal = morceau.indexOf('=');
            if (egal <= 0) {
                continue;
            }
            Reglage reglage = PAR_ID.get(morceau.substring(0, egal).trim());
            if (reglage == null) {
                continue;
            }
            try {
                reglage.poser(Integer.parseInt(morceau.substring(egal + 1).trim()));
                poses++;
            } catch (NumberFormatException illisible) {
                // Un morceau illisible est ignoré : le reste de la ligne reste valable.
            }
        }
        appliquer();
        return poses;
    }
}
