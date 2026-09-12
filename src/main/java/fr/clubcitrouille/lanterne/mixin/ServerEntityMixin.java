package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;

import fr.clubcitrouille.lanterne.core.NetworkThrottle;

/**
 * Le seul mixin du mod, et il ne touche pas un chemin chaud.
 *
 * <h2>Pourquoi celui-ci, et pas les autres</h2>
 *
 * <p>Toute la simulation passe par {@code EntityTickEvent.Pre}, que NeoForge fournit déjà : aucun
 * mixin n'a été nécessaire pour la partie la plus importante du mod. C'était délibéré — empiler une
 * interception sur une méthode appelée cent mille fois par tick empêche le compilateur à la volée
 * de l'incorporer, et l'on perd en optimisation de la machine virtuelle plus qu'on ne gagne.
 *
 * <p>L'envoi réseau n'a pas d'évènement équivalent, et il n'en a pas besoin : {@code sendChanges()}
 * est appelé une fois par entité <em>suivie</em>, ce qui se compte en centaines et non en centaines
 * de milliers. Un mixin y est sans conséquence mesurable.
 *
 * <h2>Ce que devient le compteur interne</h2>
 *
 * <p>{@code tickCount} est incrémenté à la fin de {@code sendChanges()}. L'annuler en tête l'empêche
 * donc d'avancer, et c'est voulu : ce compteur sert à cadencer les envois de cette entité, et une
 * entité qui n'envoie rien n'a pas à voir sa cadence défiler. Le seul effet est que les envois
 * forcés périodiques — la position complète toutes les soixante activations — arrivent plus
 * espacés en temps réel, ce qui est précisément ce qu'on demande.
 *
 * <p>L'étalement, lui, ne dépend pas de ce compteur mais du temps du monde et de l'identifiant de
 * l'entité. Deux mille vaches ne se synchronisent donc jamais sur le même tick — sans quoi l'on
 * aurait remplacé un flux continu par des rafales, ce qui est pire pour une mauvaise connexion.
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {
    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "sendChanges", at = @At("HEAD"), cancellable = true)
    private void lanterne$throttleUpdates(CallbackInfo callback) {
        if (!NetworkThrottle.shouldSend(this.entity, this.entity.level().getGameTime())) {
            callback.cancel();
        }
    }
}
