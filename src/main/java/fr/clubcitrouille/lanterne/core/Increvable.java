package fr.clubcitrouille.lanterne.core;

/**
 * Le pont AutoMiner pour la durabilité : une API explicite, pas une détection cachée de plus.
 *
 * <h2>Ce que le patron a demandé, et la forme exacte qu'il a validée</h2>
 *
 * <p>Le point de départ était une détection silencieuse, refusée à raison par une première tentative
 * d'implémentation : accorder un avantage à un sous-ensemble de joueurs sans que ce soit visible ni
 * pour eux ni pour les autres reste un problème même documenté dans un README que personne ne lit en
 * jouant. La forme retenue ensuite, et c'est celle-ci, diffère sur le point qui comptait : ce n'est
 * plus une sonde qui devine, c'est un <b>pont explicite entre deux mods que la même personne possède
 * et publie</b> — {@code core.network.AutominerPresence} existe déjà pour une tout autre raison
 * (l'anti x-ray, voir {@link Leurre}) et EST déjà, structurellement, le mécanisme par lequel AutoMiner
 * déclare volontairement sa présence à la négociation réseau NeoForge — pas un secret arraché, une
 * conversation entre deux mods qui se reconnaissent. Ce fichier réutilise ce même canal pour une
 * seconde fonction, avec un interrupteur à lui ({@code Config.INCREVABLE}) : couper ce réglage ferme
 * le pont sans toucher à l'anti x-ray, qui reste indépendant.
 *
 * <h2>Portée volontairement étroite</h2>
 *
 * <p>Seule la durabilité perdue au <b>bris de bloc</b> est épargnée — voir
 * {@code mixin.IncrevableMixin}, accroché sur le seul surcharge de {@code ItemStack.hurtAndBreak} qui
 * reçoit directement un {@code ServerPlayer} en argument (vérifié au {@code javap} sur le jar client
 * 26.3 réel : c'est celui-là, et lui seul, qu'utilise le chemin de minage). Les deux autres surcharges
 * — dégâts de combat sur une arme, usure d'armure encaissée — ne passent JAMAIS par ce mixin : elles
 * prennent un {@code LivingEntity} générique, jamais un {@code ServerPlayer} direct, et ce fichier ne
 * les cible pas. Le compte des blocs cassés dans les statistiques du joueur n'est pas concerné non
 * plus : ce module ne touche qu'à la durabilité, jamais au chemin de bris de bloc lui-même
 * ({@code ServerPlayerGameMode.destroyBlock}), qui reste entièrement vanilla et continue donc
 * d'incrémenter {@code minecraft:mined} normalement.
 */
public final class Increvable {
    private Increvable() {}

    /**
     * Ce joueur doit-il garder son outil intact en minant ? Vrai seulement si le réglage est actif
     * ET que son client a réellement déclaré le canal d'identité AutoMiner à la connexion — jamais
     * pour un joueur ordinaire, quel que soit l'état du réglage.
     */
    public static boolean epargne(net.minecraft.server.level.ServerPlayer player) {
        return Settings.increvable() && player.connection.hasChannel(
                fr.clubcitrouille.lanterne.core.network.AutominerPresence.TYPE);
    }
}
