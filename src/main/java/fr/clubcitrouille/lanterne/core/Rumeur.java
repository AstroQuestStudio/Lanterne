package fr.clubcitrouille.lanterne.core;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.ReputationEventType;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La RUMEUR : la réputation gagnée en guérissant un villageois zombifié, partagée à toute
 * l'équipe connectée plutôt que réservée à qui a tenu la pomme d'or.
 *
 * <h2>Ce que vanilla fait, exactement — lu au {@code javap} sur le jar réellement compilé</h2>
 *
 * <p>{@code ZombieVillager.finishConversion} appelle {@code ServerLevel.onReputationEvent(
 * ReputationEventType.ZOMBIE_VILLAGER_CURED, joueurGuerisseur, villageoisNeuf)} juste avant de
 * publier {@code LivingConversionEvent.Post}. Cet appel finit dans
 * {@code Villager.onReputationEventFrom}, qui écrit deux ragots sur le villageois neuf, et
 * seulement pour l'UUID du joueur qui a versé la faiblesse puis tendu la pomme d'or :
 *
 * <pre>
 * gossips.add(joueur, GossipType.MAJOR_POSITIVE, 20);  // plafond du type : 20 — déjà au max
 * gossips.add(joueur, GossipType.MINOR_POSITIVE, 25);  // plafond du type : 25 — déjà au max
 * </pre>
 *
 * <p>C'est ce ragot qui fait baisser les prix : {@code Villager.getPlayerReputation} additionne
 * les ragots par UUID, et le marchand marchande moins cher avec qui porte un
 * {@code MAJOR_POSITIVE}. En solo, l'exclusivité ne se voit pas. En coop, le reste de l'équipe
 * reste des inconnus aux yeux de CE villageois précis : chacun doit guérir SON zombie pour toucher
 * SON rabais.
 *
 * <h2>Le point d'accroche : un évènement NeoForge, pas un mixin</h2>
 *
 * <p>{@code finishConversion} appelle en tout dernier {@code EventHooks.onLivingConvert(this,
 * villageois)}, qui publie {@code LivingConversionEvent.Post} — non annulable, après coup, avec
 * l'entité d'origine ({@code getEntity()}, la zombie) et le résultat ({@code getOutcome()}, le
 * villageois flambant neuf). NeoForge publie ce même évènement pour TOUTE conversion de créature
 * (zombie en noyé, cochon zombifié en piglin brutal, etc.), d'où le double filtre par type
 * ci-dessous plutôt qu'une confiance aveugle dans le nom de l'évènement. Aucun mixin n'était
 * nécessaire : NeoForge appelle déjà exactement ce qu'il faut, au bon moment, avec les deux
 * entités en main.
 *
 * <h2>Pourquoi rejouer le MÊME appel plutôt qu'écrire les ragots à la main</h2>
 *
 * <p>Ce module ne fabrique pas ses propres {@code add(uuid, GossipType.MAJOR_POSITIVE, 20)} : il
 * rappelle {@code villageois.onReputationEventFrom(ReputationEventType.ZOMBIE_VILLAGER_CURED,
 * joueur)} pour chaque joueur en plus de celui que vanilla a déjà servi. Si Mojang change un jour
 * les valeurs — 20, 25, ou le fait qu'il y en ait deux — ce module suit sans qu'une ligne d'ici ne
 * change.
 *
 * <p>Et rappeler cette méthode pour le joueur guérisseur LUI-MÊME ne lui fait courir aucun risque
 * de double bonus : {@code GossipContainer.add} SOMME l'ancienne et la nouvelle valeur puis les
 * plafonne au maximum du type ({@code Math.max(max, ancienne)} si la somme dépasse) — vérifié au
 * {@code javap} sur {@code GossipContainer.mergeValuesForAddition}. Le joueur guérisseur est déjà
 * au plafond des deux types après l'appel vanilla ; le refaire pour lui est un NO-OP exact, pas un
 * doublon. Ce module n'a donc pas besoin de savoir qui a tenu la pomme d'or pour l'exclure — un
 * champ privé de {@code ZombieVillager}, sans accesseur, qu'il aurait fallu lire par réflexion
 * pour rien.
 *
 * <h2>La portée : tous les joueurs CONNECTÉS, pas seulement ceux déjà connus du villageois</h2>
 *
 * <p>Un villageois neuf ne connaît, au moment de {@code Post}, que le joueur guérisseur (plus,
 * éventuellement, les ragots hérités s'il portait déjà un {@code GossipContainer} avant d'être
 * zombifié — vanilla le transfère tel quel). Se limiter à « ceux déjà connus » aurait donc, la
 * plupart du temps, rendu ce module inopérant : c'est justement le joueur ABSENT du ragot qu'on
 * veut servir. La portée retenue est « tous les joueurs actuellement connectés au serveur » — la
 * bonne échelle pour une coop, où l'équipe se définit par qui joue ensemble maintenant, pas par un
 * carnet d'adresses figé.
 */
public final class Rumeur {

    private Rumeur() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(Rumeur.class);
    }

    /**
     * Après une conversion de créature : si c'est bien un villageois zombifié qui vient d'être
     * guéri, étend le ragot vanilla à toute l'équipe connectée.
     */
    @SubscribeEvent
    public static void onLivingConvert(LivingConversionEvent.Post event) {
        if (!Config.RUMEUR.get()) {
            return;
        }
        // LivingConversionEvent.Post est publié pour TOUTE conversion de créature (noyade,
        // piglin brutal...) : le double filtre par type, et non le seul nom de l'évènement,
        // garantit qu'on ne touche qu'à la guérison d'un villageois zombifié.
        if (!(event.getEntity() instanceof ZombieVillager) || !(event.getOutcome() instanceof Villager villager)) {
            return;
        }
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        List<ServerPlayer> joueurs = level.getServer().getPlayerList().getPlayers();
        for (ServerPlayer joueur : joueurs) {
            // Idempotent pour le guérisseur lui-même — voir la Javadoc de classe.
            villager.onReputationEventFrom(ReputationEventType.ZOMBIE_VILLAGER_CURED, joueur);
        }
        Lanterne.LOG.info("[RUMEUR] villageois guéri — réputation MAJOR_POSITIVE partagée à {} "
                + "joueur(s) connecté(s).", joueurs.size());
    }
}
