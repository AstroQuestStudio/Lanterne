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

    public Lanterne() {
        NeoForge.EVENT_BUS.register(this);
        // Les réglages d'abord : le filtre de journal les consulte, et la première version
        // l'installait avant de les avoir lus — si bien qu'aucun réglage ne pouvait l'en empêcher.
        Settings.configureFromEnvironment();
        fr.clubcitrouille.lanterne.core.Hush.install();
        fr.clubcitrouille.lanterne.core.Machine.appraise();
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
        Census.resetCounters();
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        TickBudget.endTick();
        fr.clubcitrouille.lanterne.lab.Pregen.tick(event.getServer());
        SelfTest.tick(event.getServer());
        Bench.endTick(event.getServer().overworld());
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
            // Clôt le recensement des bloquantes du tick précédent et ouvre celui du tick en cours.
            // L'ordre importe : les collisions du tick lisent la liste close, complète, et non une
            // liste en cours de remplissage qui serait vide au premier tiers du tour des entités.
            fr.clubcitrouille.lanterne.core.Solid.rotate(level);
            fr.clubcitrouille.lanterne.core.Quarry.rotate();
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
        fr.clubcitrouille.lanterne.core.Herald.welcome(
                (System.nanoTime() - AWOKEN) / 1_000_000L,
                net.neoforged.fml.ModList.get().size());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LanterneCommand.register(event.getDispatcher());
    }

    // Le filtrage des entités a quitté cet évènement pour un mixin en tête de
    // « tickNonPassenger » : le hook de NeoForge alloue un objet par entité et par tick, et l'on
    // payait cette allocation pour, neuf fois sur dix, annuler aussitôt. Voir ServerLevelMixin.
}
