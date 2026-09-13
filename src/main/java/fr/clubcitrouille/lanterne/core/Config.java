package fr.clubcitrouille.lanterne.core;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le fichier de réglages : un interrupteur par module, en clair.
 *
 * <h2>Pourquoi une variable d'environnement ne suffisait pas</h2>
 *
 * <p>{@code LANTERNE_MODULES} existe depuis le début et reste : c'est l'outil des bancs, qui doivent
 * pouvoir n'allumer qu'un module pour lui attribuer un gain. Mais demander à un administrateur de
 * poser une variable d'environnement sur un hébergement partagé, c'est lui demander l'impossible.
 *
 * <p>Ce fichier fait la même chose, en clair, dans {@code config/lanterne-server.toml}, avec une
 * ligne par module et un commentaire qui dit ce que chacun fait <b>et ce qu'il a rendu à la
 * mesure</b>. Un réglage dont on ne connaît pas le prix ne se règle pas : il se subit.
 *
 * <h2>Qui gagne quand les deux parlent</h2>
 *
 * <p>La variable d'environnement <b>prime</b>. Elle n'est employée que par les bancs de ce projet, et
 * un banc qui verrait ses réglages écrasés par un fichier de configuration mesurerait autre chose que
 * ce qu'on lui demande — c'est exactement le genre de faux rapport que ce projet passe son temps à
 * traquer.
 *
 * <h2>Le socle et les ajouts</h2>
 *
 * <p>Les réglages sont rangés en deux sections, et la distinction compte. Le <b>socle</b> ne change
 * rien au jeu : il enlève du travail inutile, et son seul effet visible est que le serveur va plus
 * vite. Les <b>ajouts</b> changent le jeu — l'ancre est un bloc nouveau, la fusion des orbes se voit
 * à l'œil. Un administrateur doit pouvoir prendre le premier sans le second.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOD;
    public static final ModConfigSpec.BooleanValue NETWORK;
    public static final ModConfigSpec.BooleanValue PROFILER;
    public static final ModConfigSpec.BooleanValue DENSITY;
    public static final ModConfigSpec.BooleanValue COLLISIONS;
    public static final ModConfigSpec.BooleanValue PROJECTILES;
    public static final ModConfigSpec.BooleanValue EXPLOSIONS;
    public static final ModConfigSpec.BooleanValue MIND;
    public static final ModConfigSpec.BooleanValue SAVE;
    public static final ModConfigSpec.BooleanValue JAM;
    public static final ModConfigSpec.BooleanValue SLEEP;
    public static final ModConfigSpec.BooleanValue RATIONING;
    public static final ModConfigSpec.BooleanValue SCRATCH_POS;
    public static final ModConfigSpec.BooleanValue STRICT_YIELD;

    public static final ModConfigSpec.BooleanValue CLUMP;
    public static final ModConfigSpec.BooleanValue ANCHOR;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "Lanterne - un interrupteur par optimisation.",
                "",
                "Chaque module a ete mesure SEUL, sur un banc reproductible. Les chiffres cites sont",
                "ceux de ce banc, sur la charge ou le module compte le plus : ils disent ce qu'il",
                "apporte quand il sert, pas ce qu'il apporte toujours.",
                "",
                "Tout couper laisse le jeu exactement tel que Mojang l'a ecrit.").push("socle");

        LOD = BUILDER.comment(
                "Niveau de detail : une creature lointaine reflechit moins souvent.",
                "Rien n'est degrade en deca de 32 blocs d'un joueur.",
                "Elevage de 1000 vaches, socle complet : 22,40 ms -> 4,47 ms.")
                .define("lod", true);
        DENSITY = BUILDER.comment(
                "Densite : ce qui est noye dans le nombre se distingue moins.",
                "Dans un tas de mille betes, une bete decide jusqu'a 32 fois moins souvent.",
                "La production - croissance, ponte, reproduction - reste a l'heure exacte.")
                .define("densite", true);
        COLLISIONS = BUILDER.comment(
                "Collisions d'entites : presque rien ne peut bloquer quoi que ce soit.",
                "canBeCollidedWith rend faux par defaut ; seuls le bateau, le shulker et le",
                "ghast apprivoise le redefinissent. 8000 objets au sol : x12,04.")
                .define("collisions", true);
        PROJECTILES = BUILDER.comment(
                "Projectiles : une fleche ne peut pas toucher une fleche.",
                "6000 fleches en vol : 97,25 ms -> 32,50 ms, soit x2,99.",
                "Une epreuve de tir verifie qu'une fleche touche encore une creature.")
                .define("projectiles", true);
        EXPLOSIONS = BUILDER.comment(
                "Explosions : le trace des rayons, sans ses 20 000 objets jetables par tir.",
                "6000 TNT : x1,11 pour ce module seul.")
                .define("explosions", true);
        MIND = BUILDER.comment(
                "Intelligence : les comportements composites parcourus sans streams.",
                "600 villageois : x1,08 pour ce module seul.")
                .define("intelligence", true);
        PROFILER = BUILDER.comment(
                "Cache du profileur : Profiler.get() consulte une variable de fil a chaque",
                "deplacement d'entite. 6000 TNT : x1,10 pour ce module seul.")
                .define("profileur", true);
        NETWORK = BUILDER.comment(
                "Reseau : les paquets de position espaces pour les entites lointaines.")
                .define("reseau", true);
        SCRATCH_POS = BUILDER.comment(
                "Positions reutilisees pour les tirages de blocs.",
                "Aucun effet sur le temps, et x3,01 SUR LA MEMOIRE ALLOUEE (4,08 Go -> 1,36 Go).",
                "C'est le premier contributeur du mod sur la memoire, donc sur les a-coups :",
                "sur une machine a un coeur, un ramassage ne s'execute pas en parallele, il fige.")
                .define("positions", true);
        JAM = BUILDER.comment("Court-circuit de bousculade dans les amas immobiles.")
                .define("amas", true);
        SLEEP = BUILDER.comment(
                "Sommeil des blocs-entites dont l'echeance est connue : un four qui cuit sait",
                "quand il aura fini, et n'a rien a faire d'ici la.")
                .define("sommeil", true);
        RATIONING = BUILDER.comment("Rationnement : reagir pendant le tick, et non au suivant.")
                .define("ration", true);
        SAVE = BUILDER.comment(
                "Sauvegarde en lz4 plutot qu'en deflate : 153 ms -> 43 ms pour 64 chunks (x3,54),",
                "au prix de 23 % de place en plus sur le disque.",
                "Retrocompatible : un monde ecrit en deflate se relit sans conversion.")
                .define("sauvegarde", true);
        STRICT_YIELD = BUILDER.comment(
                "Preservation stricte du rendement, pour les creatures venues de mods.",
                "Plus sur et plus lent : 14,31 ms contre 10,01 sur la meme charge.",
                "Inutile en vanilla - les compteurs y sont deja rattrapes exactement.")
                .define("rendement_strict", false);

        BUILDER.pop();

        BUILDER.comment(
                "Les ajouts changent le jeu, contrairement au socle ci-dessus.",
                "Ils sont separables : on peut vouloir la vitesse sans eux.").push("ajouts");

        CLUMP = BUILDER.comment(
                "Les orbes d'experience fusionnent 40 fois plus souvent, sur 4 blocs.",
                "4000 orbes : 63,21 ms -> 14,77 ms, soit x4,28.",
                "L'experience totale est conservee exactement - c'est verifie a chaque banc.",
                "Visible en jeu : moins d'orbes, ramassees plus vite.")
                .define("fusion_des_orbes", true);
        ANCHOR = BUILDER.comment(
                "L'Ancre de chunk garde charge le chunk ou elle est posee, et lui seul.",
                "Coupee, le bloc existe toujours et reste posable - il ne garde plus rien.",
                "Il n'est jamais retire du registre : un bloc absent devient de l'air dans les",
                "mondes ou il etait pose, et un reglage ne doit pas detruire une construction.")
                .define("ancre_de_chunk", true);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private Config() {}

    /**
     * Applique le fichier aux réglages, sauf si l'environnement a déjà parlé.
     *
     * <p>La priorité donnée à {@code LANTERNE_MODULES} n'est pas un détail d'implémentation : sans
     * elle, un banc lancé avec un seul module verrait le fichier de configuration rallumer les
     * autres, et mesurerait le mod entier en croyant mesurer une pièce.
     */
    public static void apply(ModConfigEvent event) {
        if (event.getConfig().getType() != ModConfig.Type.SERVER) {
            return;
        }
        // Au DÉCHARGEMENT, les valeurs n.existent plus : les lire lève « Cannot get config value
        // before config is loaded ». On ne réagit donc qu.au chargement et au rechargement — les deux
        // moments où le fichier a quelque chose à dire.
        if (!(event instanceof ModConfigEvent.Loading) && !(event instanceof ModConfigEvent.Reloading)) {
            return;
        }
        if (Settings.environmentSpoke()) {
            Lanterne.LOG.info("[RÉGLAGES] LANTERNE_MODULES est posé : le fichier de configuration est "
                    + "ignoré. C'est voulu — un banc doit mesurer ce qu'on lui demande.");
            return;
        }
        Settings.applyFromConfig();
        Lanterne.LOG.info("[RÉGLAGES] configuration lue — modules actifs : {}", Settings.describe());
    }
}
