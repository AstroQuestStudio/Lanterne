package fr.clubcitrouille.lanterne;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import fr.clubcitrouille.lanterne.core.Census;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.TickBudget;
import fr.clubcitrouille.lanterne.report.Bench;
import fr.clubcitrouille.lanterne.report.SelfTest;
import fr.clubcitrouille.lanterne.report.LanterneCommand;

/**
 * Lanterne — la citrouille qui éclaire sans brûler.
 *
 * <h2>Ce que ce mod refuse de faire</h2>
 *
 * <p>Il ne cherche pas à rendre chaque système plus rapide. C'est le travail de Lithium, il est fait,
 * il est bien fait, et le refaire n'apporterait rien. Lanterne s'attaque à une autre question, que
 * personne ne pose : <b>pourquoi ce travail a-t-il lieu ?</b>
 *
 * <p>Une vache à cent cinquante blocs coûte exactement autant qu'une vache sous les yeux du joueur.
 * Un plant de blé à dix chunks est évalué vingt fois par seconde pour, statistiquement, ne rien
 * faire. Le jeu ne connaît que deux états — chargé, ou pas — et cette absence de nuance est la
 * source principale de dépense sur un serveur chargé.
 *
 * <p>Rendre ce travail deux fois plus rapide fait gagner un facteur deux. <b>Ne pas le faire fait
 * gagner un facteur dix</b>, et c'est la seule voie vers dix chunks de simulation.
 *
 * <h2>Ce qu'il ne dégradera jamais</h2>
 *
 * <p>Un mod d'optimisation qui casse une ferme a échoué, même s'il double le nombre de ticks par
 * seconde : le joueur a perdu plus qu'il n'a gagné, et il le sait. Trois garde-fous, tenus sans
 * exception :
 *
 * <ol>
 *   <li><b>Rien n'est dégradé près d'un joueur.</b> En deçà de trente-deux blocs, le jeu est le jeu.</li>
 *   <li><b>Rien n'est jamais arrêté</b>, seulement espacé. Le plancher est à une fois par seconde,
 *       ce qui suffit à toute mécanique de fond.</li>
 *   <li><b>Ce qui peut être compensé exactement l'est.</b> Une culture tickée une fois sur dix
 *       pousse avec dix fois la probabilité : même loi, même vitesse, un dixième du coût.</li>
 * </ol>
 *
 * <p>Et un quatrième, qui commande les trois autres : <b>quand le serveur va bien, le mod ne fait
 * rien</b>. La sévérité suit la charge mesurée. Un serveur au repos tourne en vanilla.
 */
@Mod(Lanterne.ID)
public final class Lanterne {
    public static final String ID = "lanterne";
    public static final Logger LOG = LogUtils.getLogger();

    /** Instant du chargement, pour dire au démarrage combien il aura duré. */
    private static final long AWOKEN = System.nanoTime();

    public Lanterne(net.neoforged.bus.api.IEventBus modBus,
            net.neoforged.fml.ModContainer container) {
        NeoForge.EVENT_BUS.register(this);
        // Le contenu s.enregistre TOUJOURS, même si son effet est coupé. Un bloc qui n.existe plus
        // dans le registre transforme en air toutes les ancres déjà posées dans les mondes des
        // joueurs — un interrupteur ne doit jamais pouvoir détruire une construction.
        fr.clubcitrouille.lanterne.content.Contents.register(modBus);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                fr.clubcitrouille.lanterne.core.Config.SPEC);
        modBus.addListener(fr.clubcitrouille.lanterne.core.Config::apply);
        // Le fichier CLIENT est enregistré des deux côtés : un serveur dédié le charge sans jamais
        // le lire, ce qui ne coûte rien, et le garder inconditionnel évite une garde de distribution
        // de plus. Ce qui compte est que les réglages de rendu ne soient plus dans le fichier du
        // SERVEUR, d'où un joueur en multijoueur n'aurait pas pu les changer.
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
                fr.clubcitrouille.lanterne.core.ClientConfig.SPEC);
        modBus.addListener(fr.clubcitrouille.lanterne.core.ClientConfig::apply);
        // Les réglages d'abord : le filtre de journal les consulte, et la première version
        // l'installait avant de les avoir lus — si bien qu'aucun réglage ne pouvait l'en empêcher.
        // L'atelier : les tableaux personnalisés et les disques personnalisés. Un seul fichier de
        // réglages pour les deux, de type COMMON — les limites d'import engagent le serveur qui les
        // fait respecter autant que le client qui prépare les fichiers. Voir content.painting.Studio.
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON,
                fr.clubcitrouille.lanterne.content.painting.Studio.SPEC);
        modBus.addListener(fr.clubcitrouille.lanterne.content.painting.Studio::apply);
        fr.clubcitrouille.lanterne.content.painting.Easel.register(modBus);
        fr.clubcitrouille.lanterne.content.disc.Groove.register(modBus);
        fr.clubcitrouille.lanterne.content.disc.Retrack.register();
        fr.clubcitrouille.lanterne.core.PortalPreload.register();
        fr.clubcitrouille.lanterne.core.Treve.register();
        fr.clubcitrouille.lanterne.core.Rumeur.register();
        // La jonction silencieuse avec ClubCitrouilleAutoMiner (mod client de vol) : voir le
        // javadoc de la classe pour ce que le javap a trouvé, et pourquoi il a fallu s'en passer.
        fr.clubcitrouille.lanterne.core.Envol.register();
        NeoForge.EVENT_BUS.addListener(
                fr.clubcitrouille.lanterne.content.painting.Easel::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(
                fr.clubcitrouille.lanterne.content.disc.Groove::onRegisterCommands);
        // La boutique et l'economie. Le fichier de reglages est de type SERVER : les regles du
        // marche engagent le serveur seul, et un client ne doit jamais pouvoir s'inventer une
        // amplitude de derive. Le nom de fichier est explicite parce que Config.SPEC occupe deja
        // le fichier SERVER par defaut.
        // La carte 3D : fichier a part pour la meme raison que la boutique juste apres —
        // Config.SPEC occupe deja le fichier SERVER par defaut.
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                fr.clubcitrouille.lanterne.content.relief.ReliefConfig.SPEC, "lanterne-carte3d.toml");
        modBus.addListener(fr.clubcitrouille.lanterne.content.relief.ReliefConfig::apply);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                fr.clubcitrouille.lanterne.content.shop.Tariff.SPEC, "lanterne-boutique.toml");
        modBus.addListener(fr.clubcitrouille.lanterne.content.shop.Tariff::apply);
        fr.clubcitrouille.lanterne.content.shop.Bazaar.register(modBus);
        NeoForge.EVENT_BUS.addListener(
                fr.clubcitrouille.lanterne.content.shop.Bazaar::onRegisterCommands);
        // La projection : l'ecran et le projecteur. Deux fichiers de reglages, parce que les deux
        // decisions n'appartiennent pas a la meme personne — la liste blanche des domaines engage
        // l'administrateur, le consentement a charger un media distant engage le joueur et son
        // adresse IP. Voir content.screen.Sieve.
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                fr.clubcitrouille.lanterne.content.screen.Booth.SPEC, "lanterne-projecteur.toml");
        modBus.addListener(fr.clubcitrouille.lanterne.content.screen.Booth::apply);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
                fr.clubcitrouille.lanterne.client.screen.Consent.SPEC,
                "lanterne-projecteur-client.toml");
        modBus.addListener(fr.clubcitrouille.lanterne.client.screen.Consent::apply);
        // Portails immersifs (compagnon optionnel, qouteall/iPortalTeam) : fichier a part, meme
        // raison que le projecteur juste au-dessus — voir client.portals.PortailsConfig et
        // notes/immersive-portals-faisabilite.md. N'installe rien : voir Portail.verifie() plus
        // bas, dans le bloc client uniquement.
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
                fr.clubcitrouille.lanterne.client.portals.PortailsConfig.SPEC,
                "lanterne-portails-client.toml");
        modBus.addListener(fr.clubcitrouille.lanterne.client.portals.PortailsConfig::apply);
        fr.clubcitrouille.lanterne.content.screen.Screens.register(modBus);
        NeoForge.EVENT_BUS.addListener(
                fr.clubcitrouille.lanterne.content.screen.Screens::onRegisterCommands);
        // Le Reseau : stockage relie, Guichet et les deux coffres dedies. S'enregistre toujours, meme
        // Config.RESEAU_ACTIF a faux — meme regle que le reste de ce constructeur.
        fr.clubcitrouille.lanterne.content.reseau.Reseaux.register(modBus);
        // La réclame de l'hébergeur s'intercepte à l'exécution de la commande, et NeoForge y publie
        // un évènement annulable — donc aucun mixin. Elle ne pouvait PAS être traitée par Hush : la
        // commande est écrite sur l'entrée standard par le démon et ne traverse jamais le journal.
        NeoForge.EVENT_BUS.addListener(fr.clubcitrouille.lanterne.core.Reclame::onCommand);
        Settings.configureFromEnvironment();
        // Pas optionnel, contrairement à install() juste après : ce n'est pas une préférence de
        // confort, c'est la correction d'un vrai plantage — voir la Javadoc de Hush pour pourquoi
        // le filtre qui suit ne peut structurellement rien contre celui-ci.
        fr.clubcitrouille.lanterne.core.Hush.preventKQueueCrash();
        fr.clubcitrouille.lanterne.core.Hush.install();
        fr.clubcitrouille.lanterne.core.Machine.appraise();
        fr.clubcitrouille.lanterne.content.waypoint.Waypoints.register(modBus);
        fr.clubcitrouille.lanterne.core.network.SouvenirNet.register(modBus);
        // La mèche : le mod se met à jour lui-même. Enregistrée inconditionnellement, des deux
        // côtés — même raison que SouvenirNet juste au-dessus, sauf que Lanterne est OBLIGATOIRE sur
        // client et serveur : voir la Javadoc de MecheNet sur pourquoi ce fichier-ci ne porte pas la
        // même garde défensive contre un client qui ignorerait le canal.
        fr.clubcitrouille.lanterne.core.network.MecheNet.register(modBus);
        // L'exemption AutoMiner de l'anti x-ray : canal d'identite sans donnee, enregistre des deux
        // cotes (voir la Javadoc de LeurreNet pour pourquoi la decision de le declarer diverge par
        // cote malgre un appel partage, exactement comme MecheNet et SouvenirNet juste au-dessus).
        fr.clubcitrouille.lanterne.core.network.LeurreNet.register(modBus);
        // Le consentement du joueur a la mise a jour automatique : fichier CLIENT, enregistre des
        // deux cotes — meme raison que ClientConfig et Consent juste en dessous.
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
                fr.clubcitrouille.lanterne.client.MecheConsent.SPEC, "lanterne-maj-client.toml");
        modBus.addListener(fr.clubcitrouille.lanterne.client.MecheConsent::apply);
        // Le Regard : l'inspection bloc/entite. Le paquet reseau s'enregistre des deux cotes —
        // meme raison que les reperes juste au-dessus — la tooltip elle-meme (client.regard.Sight)
        // ne s'enregistre que dans le bloc CLIENT ci-dessous.
        fr.clubcitrouille.lanterne.content.regard.Regard.register(modBus);
        // Les touches et les couches d'interface passent par le bus du MOD et n'existent que côté
        // client. L'appel est donc gardé : un serveur dédié ne doit jamais charger ces classes, et
        // la garde suffit — une classe n'est chargée qu'au moment où l'on s'en sert.
        if (net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()) {
            // Avant tout le reste : options.txt doit être en place avant que Minecraft.<init>
            // ne le lise, ce qui a lieu bien après la fin de cette construction — voir la Javadoc
            // de Reveil pour la preuve tirée du journal réel. L'ordre à l'intérieur de ce bloc
            // n'a donc pas d'importance pour la correction, seulement la garde de distribution.
            fr.clubcitrouille.lanterne.client.Reveil.install();
            // Juste après : capture l'état final d'options.txt (posé par Reveil, ou déjà présent)
            // avant que Minecraft.<init> ne le charge et ne le mute — voir la Javadoc de Vigie.
            fr.clubcitrouille.lanterne.client.Vigie.capture();
            fr.clubcitrouille.lanterne.client.waypoint.Compass.register(modBus);
            // Apres Compass : la touche de la boutique reutilise la categorie de
            // raccourcis qu'il declare, et NeoForge refuse un identifiant en double.
            fr.clubcitrouille.lanterne.client.shop.Bell.register(modBus);
            // Meme raison : la touche du guide illustre reutilise cette meme categorie.
            fr.clubcitrouille.lanterne.client.ponder.Hint.register(modBus);
            fr.clubcitrouille.lanterne.client.Gauge.register(modBus);
            fr.clubcitrouille.lanterne.client.regard.Sight.register(modBus);
            // Meme raison encore : la touche de zoom reutilise elle aussi cette categorie.
            fr.clubcitrouille.lanterne.client.regard.Longuevue.register(modBus);
            // Le rendu des tableaux : la mosaïque, le magot et le dessin n'existent que côté client
            // et ne doivent jamais être chargés par un serveur dédié.
            fr.clubcitrouille.lanterne.content.painting.CanvasRenderer.register(modBus);
            fr.clubcitrouille.lanterne.content.disc.Wheel.install();
            // Le rendu des ecrans, l'ecran de reglages et le decodeur. Un serveur dedie ne doit
            // nommer aucune de ces classes : voir client.screen.Projection pour le plantage exact
            // que la garde evite, et pourquoi il n'existe qu'une porte.
            fr.clubcitrouille.lanterne.client.screen.Projection.register(modBus);
            // Le Guichet : reception des instantanes de stock, et enregistrement de son ecran (ce
            // dernier via @EventBusSubscriber, comme Phare — cet appel-ci ne branche que le reseau).
            fr.clubcitrouille.lanterne.client.screen.GuichetScreen.register(modBus);
            // La lueur du Trieur et du Reseau (Aiguillage, Guichet, Coffre de depot/reception) :
            // cinq BlockEntityRenderer, une seule porte cliente — voir client.reseau.Renderers.
            fr.clubcitrouille.lanterne.client.reseau.Renderers.register(modBus);
            // Portails immersifs : detection seule, journalisee si le reglage est allume — rien
            // n'est installe. Voir client.portals.Portail et notes/immersive-portals-faisabilite.md.
            // Plus d'appel direct ici : Portail s'enregistre desormais lui-meme aupres du bus
            // d'evenements (@EventBusSubscriber) et journalise depuis FMLClientSetupEvent, pas
            // depuis ce constructeur — lire PortailsConfig.ACTIVE ici plantait a coup sur, voir le
            // Javadoc de Portail pour la trace complete.
        }
        SelfTest.arm();
        // Vérification d'environnement, et non curiosité : si Tracy est disponible,
        // Profiler.getDefaultFiller() fait une recherche ThreadLocal à chaque appel — c'est-à-dire
        // une fois par entité et par tick. Un profil mesuré dans ces conditions ne vaudrait que
        // pour elles, et toute optimisation qu'on en tirerait serait un remède sans maladie.
        LOG.info("Tracy disponible : {} — si vrai, les mesures de cet environnement sont gonflées.",
                com.mojang.jtracy.TracyClient.isAvailable());
        // Le mot d'accueil complet attend que le serveur soit prêt : avant, ni la machine ni les
        // mods ne sont connus. Ici, on se contente d'exister.
        LOG.debug("Lanterne chargée.");
    }

    /**
     * Ouvre la mesure du tick.
     *
     * <p>Le budget se mesure sur le tick <em>entier</em> du serveur, et non sur la seule simulation :
     * c'est la durée complète qui décide si le serveur tient ses vingt ticks par seconde, et donc
     * la seule qui doive commander la sévérité.
     */
    @SubscribeEvent
    public void onServerTickPre(ServerTickEvent.Pre event) {
        TickBudget.beginTick();
        fr.clubcitrouille.lanterne.core.Rationing.beginTick();
        Bench.beginTick();
        // L'épreuve de la digue compte des à-coups, donc des ticks entiers : son chronomètre doit
        // s'ouvrir ici et se fermer dans Post, comme celui du banc.
        fr.clubcitrouille.lanterne.lab.Levee.beginTick();
        // L'épreuve de l'élastique compte elle aussi des ticks entiers : c'est le dépassement des
        // cinquante millisecondes qui donne au tableau son échelle de retard.
        fr.clubcitrouille.lanterne.lab.Amarre.beginTick();
        // L'épreuve du ramasseur compte elle aussi des ticks entiers : c'est la part du tick que les
        // pauses prennent qui l'intéresse, et une pause peut tomber n'importe où dedans.
        fr.clubcitrouille.lanterne.lab.Fonte.beginTick();
        // La ventilation du cheptel chronomètre elle aussi des ticks entiers : c'est la durée
        // complète qui se multiplie par la part du profileur pour donner des millisecondes par poste.
        fr.clubcitrouille.lanterne.lab.Cheptel.beginTick();
        // Le billet chronomètre lui aussi des ticks entiers, pour la même raison que le cheptel :
        // voir sa Javadoc de classe — la charge qu'il juge n'a pas d'entités à compter.
        fr.clubcitrouille.lanterne.lab.Billet.beginTick();
        // Le seuil chronomètre lui aussi des ticks entiers : ce qu'il compare est le coût COMPLET
        // d'une arrivée, livraison de chunks comprise, et ce coût est réparti sur tout le tick.
        fr.clubcitrouille.lanterne.lab.Seuil.beginTick();
        Census.resetCounters();
        // Une fois par tick SERVEUR, et non par monde. LevelTickEvent.Pre se déclenche pour chacun
        // des trois mondes ; basculer le recensement à chaque fois faisait écraser celui de
        // l.Overworld par celui du Nether, puis par celui de l.End — tous deux vides. Le recensement
        // était donc systématiquement nul, le raccourci toujours armé, et toutes les flèches
        // traversaient tout. L.épreuve de tir l.a établi ; le compteur a nommé la cause.
        fr.clubcitrouille.lanterne.core.Quarry.rotate();
        fr.clubcitrouille.lanterne.core.Solid.rotate();
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        TickBudget.endTick();
        // Le balai bat au rythme du SERVEUR, pas des mondes : sur un serveur à trois dimensions, une
        // minuterie par monde annoncerait trois nettoyages et en ferait trois, avec trois
        // avertissements décalés. C'est la même faute que le recensement des cibles avait commise
        // plus haut, et elle avait coûté six bancs à trouver.
        fr.clubcitrouille.lanterne.core.Sweeper.tick(event.getServer());
        fr.clubcitrouille.lanterne.core.Tide.tick(event.getServer());
        // Le délai de grâce du burin s'écoule au rythme du SERVEUR, et non par joueur : une grâce
        // par joueur s'épuiserait plus vite à trois qu'à un, et la latence qu'elle attend est
        // mesurée par un échange qui, lui, ne dépend pas du nombre de monde présent.
        if (event.getServer().getTickCount() % 20 == 0) {
            fr.clubcitrouille.lanterne.core.Burin.tick(event.getServer());
        }
        fr.clubcitrouille.lanterne.lab.Pregen.tick(event.getServer());
        // Le service continu ne soumet rien tant que Pregen tourne — voir sa Javadoc de classe.
        fr.clubcitrouille.lanterne.lab.Lisiere.tick(event.getServer());
        // La carte 3D : balayage des régions déjà écrites, budget borné, coupée si RELIEF est
        // désactivé — voir sa Javadoc de classe pour pourquoi elle ne force jamais de génération.
        fr.clubcitrouille.lanterne.content.relief.Relief.tick(event.getServer());
        // Les doublures accusent leurs lots de chunks — sans quoi le serveur leur en envoie neuf et
        // s'arrête. Ne coûte qu'une comparaison tant qu'aucun banc n'en a demandé.
        fr.clubcitrouille.lanterne.lab.Understudy.tick();
        SelfTest.tick(event.getServer());
        Bench.endTick(event.getServer().overworld());
        fr.clubcitrouille.lanterne.lab.Fonte.endTick(event.getServer());
    }

    /**
     * Recense avant que le monde ne tick.
     *
     * <p>L'ordre compte : la carte des niveaux doit être prête avant que la première entité ne
     * demande le sien. Un recensement fait après coup travaillerait sur la position du tick
     * précédent — sans grande conséquence, mais sans raison non plus.
     */
    @SubscribeEvent
    public void onLevelTickPre(LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Census.refresh(level);
            fr.clubcitrouille.lanterne.core.Jam.sweep(level.getGameTime());
            fr.clubcitrouille.lanterne.core.Pasture.sweep(level.getGameTime());
            // Clôt le recensement des bloquantes du tick précédent et ouvre celui du tick en cours.
            // L'ordre importe : les collisions du tick lisent la liste close, complète, et non une
            // liste en cours de remplissage qui serait vide au premier tiers du tour des entités.
            fr.clubcitrouille.lanterne.core.Solid.sweep(level);
        }
    }

    /**
     * Le mot d'accueil, une fois le serveur réellement prêt.
     *
     * <p>Plus tôt, on ne saurait dire ni ce que vaut la machine, ni combien de mods ont été chargés,
     * ni combien de temps le démarrage aura pris. Une bannière affichée trop tôt ne peut annoncer
     * que son propre nom.
     */
    @SubscribeEvent
    public void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        // Tous les blocs sont enregistres : les tables de reconnaissance des formes n'ont plus
        // rien a reconnaitre. Les garder reviendrait a retenir de la memoire pour rien — c'est
        // exactement ce que la premiere version de ce module faisait, et le banc l'avait vu.
        fr.clubcitrouille.lanterne.core.Moulds.seal();
        // Les plafonds de la maree : ce que server.properties demande, jamais depasse.
        fr.clubcitrouille.lanterne.core.Tide.anchor(event.getServer());
        fr.clubcitrouille.lanterne.core.Ballast.appraise();
        // Le ramasseur et le tas : ce que la machine virtuelle a choisi, et s'il faut le lui dire.
        // Après Ballast, parce que l'avertissement doit être la dernière chose que l'administrateur
        // lise avant la bannière — pas une ligne noyée au milieu du démarrage.
        fr.clubcitrouille.lanterne.core.Creuset.appraise();
        fr.clubcitrouille.lanterne.core.Herald.welcome(
                (System.nanoTime() - AWOKEN) / 1_000_000L,
                net.neoforged.fml.ModList.get().size());
        // Après la bannière, et non dedans : celle-ci dit ce que vaut la machine et ce que fait le
        // mod, celui-là dit AVEC QUI il le fait. Un joueur qui installe cinq mods ne sait pas
        // lesquels se parlent, et aucun des cinq ne le lui dira. Voir report.Compagnons.
        fr.clubcitrouille.lanterne.report.Compagnons.say();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LanterneCommand.register(event.getDispatcher());
    }

    // Le filtrage des entités a quitté cet évènement pour un mixin en tête de
    // « tickNonPassenger » : le hook de NeoForge alloue un objet par entité et par tick, et l'on
    // payait cette allocation pour, neuf fois sur dix, annuler aussitôt. Voir ServerLevelMixin.
}
