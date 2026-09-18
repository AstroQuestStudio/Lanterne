package fr.clubcitrouille.lanterne.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * La loterie : le tirage de bloc aléatoire, sans les billets perdants.
 *
 * <h2>Ce que {@code ServerLevel.tickChunk} fait, vérifié par {@code javap} sur le jar patché 26.3</h2>
 *
 * <p>Pour chaque section qui a quelque chose à ticker ({@code isRandomlyTicking()} — au moins un bloc
 * ou un fluide marqué), le jeu tire {@code randomTickSpeed} positions (trois par défaut), et pour
 * chacune :
 *
 * <pre>
 * BlockPos pos = getBlockRandomPos(chunkX, blockY, chunkZ, 15);      // une position UNIFORME sur 4096
 * BlockState state = section.getBlockState(pos.x-chunkX, pos.y-blockY, pos.z-chunkZ);
 * if (state.isRandomlyTicking()) state.randomTick(level, pos, random);
 * if (state.getFluidState().isRandomlyTicking()) state.getFluidState().randomTick(level, pos, random);
 * </pre>
 *
 * <p>Le tirage ne sait rien du contenu de la section : il coûte la même chose — un tirage, une lecture
 * de palette, deux tests — que la section soit couverte de blé ou qu'elle ne compte qu'un seul bloc de
 * glace au milieu de quatre mille quatre-vingt-quinze pierres. Dans ce second cas, l'écrasante majorité
 * des tirages retombent sur un bloc qui ne fait rien, après avoir payé la même lecture de palette qu'un
 * tirage qui aurait compté.
 *
 * <h2>Le remède, celui de Lithium, vérifié indépendamment</h2>
 *
 * <p>{@code LevelChunkSection} maintient déjà, vanilla, deux compteurs privés — {@code tickingBlockCount}
 * et {@code tickingFluidCount} — mis à jour à chaque {@code setBlockState}. Ce module ne les réutilise
 * pas tels quels : une position peut porter les deux à la fois (un bloc immergé qui tique et dont le
 * fluide tique aussi), et les deux compteurs comptent alors deux fois la même position. Un compteur
 * séparé, {@link Section#lanterne$total()}, compte le nombre de positions où {@link #eligible} est vrai
 * — l'union, pas la somme — et lui seul décide.
 *
 * <p>Le tirage lui-même reste celui du jeu : {@code getBlockRandomPos} est appelé sans changement,
 * consommant la même source de hasard. Ce qui change est ce qu'on en fait. Les coordonnées locales
 * tirées, x, y, z (chacune sur quatre bits, 0 à 15), sont repaquetées en un seul entier de zéro à
 * quatre mille quatre-vingt-quinze :
 *
 * <pre>rang = x | (z &lt;&lt; 4) | (y &lt;&lt; 8)</pre>
 *
 * <p>Le tirage de {@code getBlockRandomPos} est UNIFORME sur les quatre mille quatre-vingt-seize
 * positions de la section — c'est sa définition. Donc {@code rang} est uniforme sur [0, 4096). Et la
 * question « la position tirée est-elle éligible ? » devient exactement équivalente à la question
 * « rang tombe-t-il dans les {@code total} premiers rangs ? » : les deux ont la même probabilité,
 * {@code total / 4096}, parce que le nombre de positions éligibles est {@code total} quel que soit
 * l'ordre dans lequel on les énumère.
 *
 * <p><b>Ce que ce remède ne fait PAS</b> : il ne prétend pas que le rang {@code r} correspond à LA
 * position que {@code getBlockRandomPos} aurait tirée. Il n'a pas besoin de cette correspondance. Ce
 * qu'il doit préserver, et préserve, c'est une propriété plus faible et suffisante : sachant qu'un tirage
 * est un « coup », la position qu'il désigne doit être uniforme parmi les positions éligibles — exactement
 * ce que vanilla fait aussi (un tirage uniforme sur 4096, sachant qu'il tombe sur l'une des {@code total}
 * éligibles, est uniforme sur ces {@code total}-là). {@link #localise} fournit cette position en
 * retrouvant, par comptage, le rang-ème élément éligible dans un ordre fixe (y, puis z, puis x) — un ordre
 * arbitraire, mais fixe, ce qui suffit à rendre le tirage uniforme.
 *
 * <h2>Pourquoi le parcours du « coup » reste borné</h2>
 *
 * <p>Retrouver le rang-ème éligible en parcourant les 4096 positions dans l'ordre coûterait, en
 * moyenne, la moitié de la section — pire que ce qu'on économise. {@link Section#lanterne$parCouche()}
 * tient donc, à jour en permanence, le nombre d'éligibles de chacune des seize couches horizontales de
 * la section (256 positions chacune). Trouver la couche qui contient le rang cherché est immédiat (au
 * plus seize soustractions) ; il ne reste qu'à parcourir CETTE couche, deux cent cinquante-six positions
 * au plus, et seulement sur un coup — c'est-à-dire au plus {@code randomTickSpeed} fois par section et
 * par tick, et seulement quand {@link #SEUIL} est déjà franchi dans l'autre sens (section rare).
 *
 * <h2>Le seuil, et pourquoi il coupe dans les deux sens</h2>
 *
 * <p>{@link #SEUIL} vaut 384, soit 9,375 % de 4096 — la valeur que Lithium documente avoir trouvée par
 * essais successifs, reprise ici faute d'avoir eu le temps d'en établir une meilleure pour ce dépôt.
 * Au-delà, ce module <b>rend la main à vanilla sans y toucher</b> : plus la section est dense, plus les
 * tirages de vanilla touchent directement une éligible, et plus le parcours de {@link #localise} — qui,
 * lui, grandit avec la densité — coûterait cher pour rien. Le module ne s'arme donc que là où vanilla
 * perd le plus : les sections rares.
 *
 * <h2>Ce qui a été délibérément laissé à vanilla</h2>
 *
 * <p>Aucune section totalement inerte ({@code total == 0}, l'immense majorité du sous-sol) n'est
 * seulement regardée : {@code isRandomlyTicking()} — inchangé, vanilla — l'écarte avant que ce module
 * n'entre en jeu. Et aucune section dense (végétation en surface, champ cultivé) n'est modifiée : au-delà
 * du seuil, le chemin exécuté est celui de Mojang, au bit près.
 */
public final class Loterie {
    /**
     * Positions éligibles, en dessous desquelles le tirage direct de vanilla devient plus coûteux que
     * le trouver-le-rang-ème de ce module.
     *
     * <p>4096 × 0,09375 — la valeur publiée par Lithium (commentaire de {@code ServerLevelMixin},
     * catégorie {@code random_block_ticking}), obtenue par essais sur un monde plat herbeux et sur le
     * Nether. Ce dépôt ne l'a pas re-mesurée : voir le banc {@code Cheptel} pour ce que la charge
     * {@code farm} en dit sur CETTE machine.
     */
    static final int SEUIL = 384;

    /**
     * Éligibles dans la couche la PLUS chargée, au-delà desquels ce module refuse — quel que soit le
     * total de la section.
     *
     * <h2>Le billet qui a démenti {@link #SEUIL} seul</h2>
     *
     * <p>Un premier banc ({@code lab/Billet}, charge {@code FARM}) a mesuré ce module tel qu'il ne
     * regardait que {@link #SEUIL} : <b>×0,79</b> — vingt et un pour cent plus lent, et non plus
     * rapide, sur un champ de blé. {@code PalettedContainer.get} y montait de 8,35 à 13,06 ms.
     *
     * <p>La cause tient en une phrase : la terre ne répartit pas ses blocs tickables sur les seize
     * couches d'une section, elle les <b>concentre</b> dans une ou deux — l'herbe de surface, la terre
     * labourée et le blé qui pousse dessus. Une section peut ainsi porter deux cent cinquante-six
     * positions éligibles, toutes dans la MÊME couche, et rester sous {@link #SEUIL} (384) : {@link
     * #localise} y retrouve alors le rang cherché en scrutant en moyenne la moitié d'une couche pleine
     * — le travail même que ce module devait épargner, payé en plus du reste.
     *
     * <p>Ce second seuil referme la faille : au-delà de lui dans NE SERAIT-CE QU'UNE couche, ce module
     * refuse, même si le total de la section reste sous {@link #SEUIL}. Sur un module qui vaut sa
     * promesse — sections dispersées sur plusieurs couches, jamais une seule pleine — les deux seuils
     * ne se contredisent jamais : le total d'une section aussi éparse reste de toute façon sous celui
     * de sa couche la plus chargée multiplié par seize.
     *
     * <p>Quarante-huit, un huitième de couche : au-delà, un coup scruterait en moyenne vingt-quatre
     * positions avant de trouver son rang, ce qui commence à peser à plusieurs milliers de coups par
     * tick.
     *
     * <h2>Re-mesuré depuis, sur la même charge {@code FARM} : ni régression, ni gain net établi</h2>
     *
     * <p>Quatre passages du banc {@code Billet} le 18 septembre 2026, ce seuil en place, ont rendu
     * ×1,09, ×1,00, ×0,57 et un cinquième interrompu avant conclusion (processus externe, pas ce
     * module — voir les notes de session). Le premier était mesuré sur un monde déjà généré (chargé
     * depuis le disque, 10 à 12 % du temps serveur réellement travaillé) ; les deux suivants sur un
     * monde neuf, fraîchement généré (14 à 47 % travaillé selon la contention de la machine à ce
     * moment). L'écart n'est pas du seul bruit de mesure : le profil du dernier passage montre {@code
     * Loterie.tick} et {@link #coucheLaPlusChargee} apparaître comme postes mesurables à part entière
     * (respectivement 2,9 % et 2,2 % du tick), et {@code PalettedContainer.get} monter malgré tout
     * (1,52 → 2,41 ms) — signe que le second seuil, en excluant justement les sections denses de blé
     * (le cas le plus fréquent d'un champ), fait payer son propre coût de vérification à des sections
     * qui finissent de toute façon rendues à vanilla, sans toucher au gain qu'il protège par ailleurs.
     *
     * <p>Ce que ceci ferme : la régression de comptage (blocs oubliés ou sur-tickés) — voir {@link
     * #autoTest}, qui la prouve close par construction, pas par mesure de temps. Ce que ceci n'établit
     * PAS : que ce module accélère un champ de blé réel sur cette machine. {@link
     * fr.clubcitrouille.lanterne.core.Config#LOTERIE} reste à {@code false} par défaut pour cette
     * raison précise — un module correct n'est pas encore un module qui vaut sa promesse de vitesse.
     * Remesurer proprement (monde chargé identique entre passages, machine non partagée) avant d'y
     * toucher.
     */
    static final int SEUIL_COUCHE = 48;

    /** Sections traitées par ce module (en dessous du seuil). */
    private static long sectionsRares;

    /** Sections laissées à vanilla parce qu'au-dessus du seuil. */
    private static long sectionsDenses;

    /** Tirages qui sont tombés sur une position éligible. */
    private static long coups;

    /** Tirages qui n'ont touché aucune position éligible — le billet perdant qu'on n'a pas payé. */
    private static long manques;

    /**
     * Incohérences détectées entre {@link Section#lanterne$total()} et le contenu réel de la section.
     *
     * <p>Doit rester à zéro. Un module qui trouve ce compteur non nul en jeu a un bogue de comptage
     * quelque part dans {@code LoterieSectionMixin}, et doit être éteint — voir {@link #actif()}.
     */
    private static long incoherences;

    private Loterie() {}

    /** Une position bloc est-elle candidate au tirage aléatoire — bloc OU fluide ? */
    public static boolean eligible(BlockState state) {
        return state.isRandomlyTicking() || state.getFluidState().isRandomlyTicking();
    }

    public static boolean actif() {
        return Settings.enabled() && Settings.loterie() && incoherences == 0;
    }

    /**
     * Ce que {@code LoterieSectionMixin} expose sur une {@code LevelChunkSection}.
     *
     * <p>Deux nombres seulement : le total (union bloc-ou-fluide, jamais la somme des deux compteurs
     * vanilla) et sa ventilation par couche horizontale, qui borne le parcours d'un coup.
     */
    public interface Section {
        int lanterne$total();

        void lanterne$total(int total);

        int[] lanterne$parCouche();
    }

    /**
     * Répercute un changement de {@code BlockState} sur les compteurs d'une section.
     *
     * <p>Appelé une fois par {@code setBlockState}, avec l'ancien état (rendu par la méthode) et le
     * nouveau (son paramètre). Ne touche à rien si l'éligibilité n'a pas changé — le cas de
     * l'écrasante majorité des poses, casses et mises à jour de bloc.
     */
    public static void ajuste(Section section, int y, BlockState ancien, BlockState nouveau) {
        boolean avant = eligible(ancien);
        boolean apres = eligible(nouveau);
        if (avant == apres) {
            return;
        }
        int[] couches = section.lanterne$parCouche();
        int delta = apres ? 1 : -1;
        couches[y] += delta;
        section.lanterne$total(section.lanterne$total() + delta);
    }

    /**
     * Reconstruit les compteurs d'une section depuis son contenu réel.
     *
     * <p>Appelé une seule fois, à la queue de {@code recalcBlockCounts()} — c'est-à-dire au chargement
     * d'une section depuis le disque, le seul chemin qui peuple une section sans passer par
     * {@code setBlockState} position par position. Coûte un parcours complet (4096 lectures), payé une
     * fois par section chargée et non par tick.
     */
    public static void reconstruit(Section section, LevelChunkSection actual) {
        int[] couches = section.lanterne$parCouche();
        int total = 0;
        for (int y = 0; y < 16; y++) {
            int compte = 0;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (eligible(actual.getBlockState(x, y, z))) {
                        compte++;
                    }
                }
            }
            couches[y] = compte;
            total += compte;
        }
        section.lanterne$total(total);
    }

    /**
     * Prend en charge le tirage aléatoire d'une section entière, ou refuse.
     *
     * <p>Rend {@code false} sans rien avoir touché quand le module est éteint, quand la section porte
     * plus de {@link #SEUIL} positions éligibles au total, quand sa couche la plus chargée en porte
     * plus de {@link #SEUIL_COUCHE} — voir sa Javadoc, c'est le cas de l'herbe et des champs, et le
     * second seuil existe précisément parce que le premier seul ne les excluait pas — ou quand la
     * section n'a pas pu être localisée dans son propre chunk (un filet de secours qui ne devrait
     * jamais se déclencher). Dans tous ces cas, l'appelant doit exécuter la boucle de vanilla,
     * inchangée.
     *
     * <p>Rend {@code true} après avoir effectué exactement {@code cadence} tirages elle-même — le même
     * nombre que vanilla en aurait fait, ni plus ni moins.
     */
    public static boolean tick(ServerLevel level, LevelChunk chunk, LevelChunkSection section,
            int cadence) {
        if (!actif() || !(section instanceof Section compte)) {
            return false;
        }
        if (compte.lanterne$total() > SEUIL || coucheLaPlusChargee(compte.lanterne$parCouche()) > SEUIL_COUCHE) {
            sectionsDenses++;
            return false;
        }
        sectionsRares++;

        int chunkX = chunk.getPos().getMinBlockX();
        int chunkZ = chunk.getPos().getMinBlockZ();
        int blockY = blockYDe(chunk, section);
        if (blockY == Integer.MIN_VALUE) {
            return false;
        }

        RandomSource dice = level.getRandom();
        for (int i = 0; i < cadence; i++) {
            int total = compte.lanterne$total();
            if (total <= 0) {
                manques++;
                continue;
            }

            BlockPos tire = level.getBlockRandomPos(chunkX, blockY, chunkZ, 15);
            int lx = tire.getX() - chunkX;
            int ly = tire.getY() - blockY;
            int lz = tire.getZ() - chunkZ;
            int rang = lx | (lz << 4) | (ly << 8);
            if (rang >= total) {
                manques++;
                continue;
            }

            int trouve = localise(section, compte.lanterne$parCouche(), rang);
            if (trouve < 0) {
                incoherences++;
                signaleIncoherence(chunkX, chunkZ, blockY, total);
                return true; // le tick de ce passage est perdu, mais le prochain repassera par vanilla
            }

            int fx = trouve & 15;
            int fz = (trouve >> 4) & 15;
            int fy = (trouve >> 8) & 15;
            BlockPos cible = new BlockPos(chunkX + fx, blockY + fy, chunkZ + fz);
            BlockState etat = section.getBlockState(fx, fy, fz);
            if (etat.isRandomlyTicking()) {
                etat.randomTick(level, cible, dice);
            }
            FluidState fluide = etat.getFluidState();
            if (fluide.isRandomlyTicking()) {
                fluide.randomTick(level, cible, dice);
            }
            coups++;
        }
        return true;
    }

    /** La plus chargée des seize couches — voir {@link #SEUIL_COUCHE}. */
    private static int coucheLaPlusChargee(int[] couches) {
        int max = 0;
        for (int c : couches) {
            if (c > max) {
                max = c;
            }
        }
        return max;
    }

    /**
     * Retrouve la position locale du rang-ème bloc éligible d'une section, ou -1 si les compteurs
     * mentent.
     *
     * <p>Paqueté en {@code x | (z << 4) | (y << 8)} — sans rapport avec le paquetage du tirage lui-même
     * ({@link #tick}) : cette méthode n'a besoin que d'un ordre fixe pour compter, pas de celui-là en
     * particulier.
     */
    static int localise(LevelChunkSection section, int[] couches, int rang) {
        int reste = rang;
        for (int y = 0; y < 16; y++) {
            int c = couches[y];
            if (reste < c) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (eligible(section.getBlockState(x, y, z))) {
                            if (reste == 0) {
                                return x | (z << 4) | (y << 8);
                            }
                            reste--;
                        }
                    }
                }
                return -1; // la couche annonçait plus d'éligibles que le parcours n'en a trouvé
            }
            reste -= c;
        }
        return -1;
    }

    /**
     * L'altitude bloc du bas d'une section, retrouvée par comparaison de référence dans son propre
     * chunk.
     *
     * <p>Une section ne connaît pas sa propre hauteur — seul le chunk qui la contient le sait, par son
     * rang dans {@code getSections()}. Coûte au plus vingt-quatre comparaisons de référence, une fois
     * par section et par tick — négligeable à côté de ce que ce module économise par ailleurs, et bien
     * plus sûr qu'une capture de variable locale par ordinal dans un mixin sans table de variables
     * locales (le jar patché n'en porte pas : {@code javap -v} le confirme, aucun
     * {@code LocalVariableTable} n'est publié pour {@code ServerLevel.tickChunk}).
     */
    private static int blockYDe(LevelChunk chunk, LevelChunkSection section) {
        LevelChunkSection[] toutes = chunk.getSections();
        for (int i = 0; i < toutes.length; i++) {
            if (toutes[i] == section) {
                return SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            }
        }
        return Integer.MIN_VALUE;
    }

    private static void signaleIncoherence(int chunkX, int chunkZ, int blockY, int total) {
        fr.clubcitrouille.lanterne.Lanterne.LOG.error(
                "[LOTERIE] compteur incohérent en ({}, {}, {}), total annoncé {} — le module se "
                + "désarme pour cette partie. Merci de signaler ce message tel quel.",
                chunkX, blockY, chunkZ, total);
    }

    public static long sectionsRares() {
        return sectionsRares;
    }

    public static long sectionsDenses() {
        return sectionsDenses;
    }

    public static long coups() {
        return coups;
    }

    public static long manques() {
        return manques;
    }

    public static long incoherences() {
        return incoherences;
    }

    /** L'état courant, en une ligne, pour la commande d'état et l'en-tête du banc. */
    public static String describe() {
        if (!Settings.loterie()) {
            return "loterie éteinte";
        }
        long total = coups + manques;
        double taux = total <= 0 ? 0d : (100d * coups) / total;
        return String.format(java.util.Locale.ROOT,
                "%d section(s) rare(s), %d dense(s) · %d coup(s) sur %d tirage(s) (%.1f %%) · "
                + "%d incohérence(s)",
                sectionsRares, sectionsDenses, coups, total, taux, incoherences);
    }

    public static void reset() {
        sectionsRares = 0L;
        sectionsDenses = 0L;
        coups = 0L;
        manques = 0L;
        incoherences = 0L;
    }

    /**
     * Preuve exécutable que {@link #localise} ne se trompe ni par excès ni par défaut, sur trois
     * sections connues bâties dans le monde réel — pas une supposition sur le papier.
     *
     * <h2>Pourquoi dans le monde réel plutôt qu'une {@code LevelChunkSection} fabriquée à la main</h2>
     *
     * <p>En construire une hors-jeu demanderait un {@code PalettedContainerFactory} et un conteneur de
     * palette assemblés à la main, pour, au final, retester le même code que celui déjà exercé par
     * chaque pose de bloc en jeu. Le serveur réel en a déjà un tout prêt ; s'en servir teste aussi
     * {@link #ajuste}, le chemin réellement emprunté à chaque {@code setBlockState}, ce qu'une section
     * fabriquée à la main ne pourrait pas faire sans passer par {@link #reconstruit}.
     *
     * <h2>Les trois cas, et ce que chacun ferme</h2>
     *
     * <ul>
     *   <li><b>Un seul bloc qui coche parmi beaucoup de pierre</b> — l'exemple même du bilan
     *       d'arrêt : {@link #lanterne$total()} de la section doit valoir exactement 1, et
     *       {@link #localise} doit retrouver PRÉCISÉMENT ce bloc-là, jamais un des quatre mille
     *       quatre-vingt-quinze de pierre. Deux cent mille tirages sont ensuite exécutés dessus : la
     *       probabilité qu'aucun ne touche est de (4095/4096)^200000, soit environ 10^-21 — voir ce
     *       compteur bouger EST la preuve que le tirage atteint réellement le bloc éligible, pas
     *       seulement que {@code localise} sait le désigner sur le papier.</li>
     *   <li><b>Quarante éligibles dans une seule couche</b> — le scénario exact qui a produit la
     *       régression ×0,79 relatée dans la Javadoc de {@link #SEUIL_COUCHE} : concentrés plutôt que
     *       dispersés, sous {@link #SEUIL} (384) mais proches de {@link #SEUIL_COUCHE} (48). Les
     *       quarante rangs sont énumérés un par un ; l'ensemble retrouvé doit être EXACTEMENT
     *       l'ensemble posé — aucun doublon (deux rangs qui désigneraient la même position), aucun
     *       manque (une position posée qu'aucun rang ne retrouve), aucun intrus.</li>
     *   <li><b>Cinquante dans une couche</b> — au-delà de {@link #SEUIL_COUCHE} : {@link #tick} doit
     *       REFUSER cette section (rendre {@code false}, compter {@link #sectionsDenses}), pas
     *       l'accepter avec un {@link #localise} qui se mettrait à coûter cher. La soupape de sécurité
     *       elle-même est donc vérifiée, pas seulement le chemin qu'elle protège.</li>
     * </ul>
     *
     * <p>Les trois sections vivent à distance les unes des autres et de toute autre scène (voir
     * {@code lab.Scene}, {@code lab.Billet}) pour qu'aucune ne recouvre le terrain d'une autre.
     *
     * @return {@code null} si les trois cas passent, sinon la description du premier désaccord — à
     *     publier telle quelle, jamais résumée en « ça a l'air bon ».
     */
    public static String autoTest(ServerLevel level) {
        if (!actif()) {
            return "le module est éteint (Settings.enabled()/Settings.loterie() faux, ou "
                    + "incohérence déjà levée) — relancer avec LANTERNE_MODULES=tirage";
        }
        String désaccord = testUnSeulBloc(level, new BlockPos(2000, 64, 2000));
        if (désaccord != null) {
            return désaccord;
        }
        désaccord = testCoucheChargee(level, new BlockPos(2048, 64, 2000));
        if (désaccord != null) {
            return désaccord;
        }
        return testCoucheRefusee(level, new BlockPos(2096, 64, 2000));
    }

    private static void remplitPierre(ServerLevel level, BlockPos origine) {
        BlockState pierre = Blocks.STONE.defaultBlockState();
        BlockPos.MutableBlockPos curseur = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    curseur.set(origine.getX() + x, origine.getY() + y, origine.getZ() + z);
                    level.setBlock(curseur, pierre, 2);
                }
            }
        }
    }

    private static Section sectionEn(ServerLevel level, BlockPos dansLaSection) {
        LevelChunk chunk = level.getChunkAt(dansLaSection);
        LevelChunkSection section = chunk.getSection(level.getSectionIndex(dansLaSection.getY()));
        return section instanceof Section compte ? compte : null;
    }

    private static String testUnSeulBloc(ServerLevel level, BlockPos origine) {
        remplitPierre(level, origine);
        BlockPos posGlace = origine.offset(5, 7, 9);
        level.setBlock(posGlace, Blocks.ICE.defaultBlockState(), 2);

        LevelChunk chunk = level.getChunkAt(posGlace);
        LevelChunkSection sectionBrute = chunk.getSection(level.getSectionIndex(posGlace.getY()));
        if (!(sectionBrute instanceof Section compte)) {
            return "un seul bloc : la section ne porte pas l'interface Loterie.Section — mixin absent";
        }
        if (compte.lanterne$total() != 1) {
            return "un seul bloc : total annoncé " + compte.lanterne$total() + ", attendu 1 (4095 "
                    + "pierres ne cochent jamais, une seule glace doit compter)";
        }
        int attendu = 5 | (9 << 4) | (7 << 8);
        int trouve = localise(sectionBrute, compte.lanterne$parCouche(), 0);
        if (trouve != attendu) {
            return "un seul bloc : localise(rang 0) a rendu " + trouve + ", attendu " + attendu
                    + " (x=5, y=7, z=9)";
        }

        long coupsAvant = coups;
        long incoherencesAvant = incoherences;
        for (int i = 0; i < 40; i++) {
            tick(level, chunk, sectionBrute, 5000); // 40 x 5000 = 200 000 tirages cumulés
        }
        if (incoherences != incoherencesAvant) {
            return "un seul bloc : incohérence détectée pendant les 200 000 tirages de contrôle";
        }
        if (coups <= coupsAvant) {
            return "un seul bloc : 200 000 tirages sans un seul coup sur le bloc éligible — "
                    + "probabilité quasi nulle si le tirage est correct, le module rate la position";
        }
        return null;
    }

    private static String testCoucheChargee(ServerLevel level, BlockPos origine) {
        remplitPierre(level, origine);
        int y = 3;
        java.util.Set<Integer> pose = new java.util.HashSet<>();
        int compte0 = 0;
        for (int z = 0; z < 16 && compte0 < 40; z++) {
            for (int x = 0; x < 16 && compte0 < 40; x++) {
                level.setBlock(origine.offset(x, y, z), Blocks.ICE.defaultBlockState(), 2);
                pose.add(x | (z << 4) | (y << 8));
                compte0++;
            }
        }

        Section compte = sectionEn(level, origine.offset(0, y, 0));
        if (compte == null) {
            return "couche chargée : la section ne porte pas l'interface Loterie.Section";
        }
        if (compte.lanterne$total() != 40) {
            return "couche chargée : total annoncé " + compte.lanterne$total() + ", attendu 40";
        }
        if (compte.lanterne$parCouche()[y] != 40) {
            return "couche chargée : couches[" + y + "] = " + compte.lanterne$parCouche()[y]
                    + ", attendu 40";
        }

        LevelChunkSection sectionBrute = level.getChunkAt(origine).getSection(
                level.getSectionIndex(origine.getY() + y));
        java.util.Set<Integer> trouve = new java.util.HashSet<>();
        for (int rang = 0; rang < 40; rang++) {
            int pos = localise(sectionBrute, compte.lanterne$parCouche(), rang);
            if (pos < 0) {
                return "couche chargée : localise(rang " + rang + ") a rendu -1 alors que total = 40";
            }
            if (!trouve.add(pos)) {
                return "couche chargée : localise a rendu deux fois la position " + pos
                        + " pour deux rangs différents — doublon";
            }
        }
        if (!trouve.equals(pose)) {
            return "couche chargée : l'ensemble retrouvé par localise diffère de l'ensemble posé — "
                    + "pose=" + pose + " trouve=" + trouve;
        }
        return null;
    }

    private static String testCoucheRefusee(ServerLevel level, BlockPos origine) {
        remplitPierre(level, origine);
        int y = 3;
        int compte0 = 0;
        for (int z = 0; z < 16 && compte0 < 50; z++) {
            for (int x = 0; x < 16 && compte0 < 50; x++) {
                level.setBlock(origine.offset(x, y, z), Blocks.ICE.defaultBlockState(), 2);
                compte0++;
            }
        }

        LevelChunk chunk = level.getChunkAt(origine);
        LevelChunkSection sectionBrute = chunk.getSection(level.getSectionIndex(origine.getY() + y));
        long densesAvant = sectionsDenses;
        boolean pris = tick(level, chunk, sectionBrute, 3);
        if (pris) {
            return "couche pleine : le module a pris en charge une section à 50 éligibles dans une "
                    + "couche (SEUIL_COUCHE=" + SEUIL_COUCHE + "), alors qu'il doit refuser";
        }
        if (sectionsDenses != densesAvant + 1) {
            return "couche pleine : sectionsDenses n'a pas été incrémenté lors du refus";
        }
        return null;
    }
}
