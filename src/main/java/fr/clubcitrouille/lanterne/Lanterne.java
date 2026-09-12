package fr.clubcitrouille.lanterne;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import fr.clubcitrouille.lanterne.core.Census;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.EntityThrottle;
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

    public Lanterne() {
        NeoForge.EVENT_BUS.register(this);
        Settings.configureFromEnvironment();
        SelfTest.arm();
        // Vérification d'environnement, et non curiosité : si Tracy est disponible,
        // Profiler.getDefaultFiller() fait une recherche ThreadLocal à chaque appel — c'est-à-dire
        // une fois par entité et par tick. Un profil mesuré dans ces conditions ne vaudrait que
        // pour elles, et toute optimisation qu'on en tirerait serait un remède sans maladie.
        LOG.info("Tracy disponible : {} — si vrai, les mesures de cet environnement sont gonflées.",
                com.mojang.jtracy.TracyClient.isAvailable());
        LOG.info("Lanterne allumée — modules actifs : {}", Settings.describe());
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
        Bench.beginTick();
        Census.resetCounters();
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        TickBudget.endTick();
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
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LanterneCommand.register(event.getDispatcher());
    }

    /**
     * Le point où tout se joue.
     *
     * <p>NeoForge tire cet évènement juste avant {@code entity.tick()}, et il est annulable. Toute
     * la dégradation du mod passe par cette seule ligne — il n'y a pas de second chemin, pas de
     * module concurrent, et donc pas de décision contradictoire possible.
     *
     * <p>Rien de ce qui précède l'évènement ne nous échappe : le compteur d'âge de l'entité et son
     * examen de disparition ont déjà eu lieu. Une entité endormie vieillit et finit par disparaître
     * exactement comme les autres.
     */
    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide()) {
            return; // le client a ses propres règles ; la simulation se juge côté serveur
        }
        if (!EntityThrottle.shouldTick(event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
