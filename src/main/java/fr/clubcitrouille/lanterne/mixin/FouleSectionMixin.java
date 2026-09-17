package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.util.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;

import fr.clubcitrouille.lanterne.core.Foule;

/**
 * La section d'entités, dotée de la subdivision qui lui manque.
 *
 * <h2>Pourquoi ici et non chez les appelants</h2>
 *
 * <p>{@code EntitySection.getEntities(AABB, …)} est le <b>goulot</b> de toute recherche de voisinage
 * du jeu : la poussée y passe, la collision y passe, la fusion des objets au sol et des orbes y
 * passent, l'explosion et le ciblage y passent. Corriger le parcours chez chacun d'eux demanderait
 * autant de mixins que de chemins, et il en resterait toujours un — c'est la leçon que
 * {@code EntityGetterMixin} a déjà payée une fois sur {@code getEntityCollisions}.
 *
 * <p>Le raisonnement, le calcul de croissance et les garde-fous sont dans {@link Foule}. Ce fichier
 * n'est que la plomberie : trois points d'accroche, et rien qui décide.
 *
 * <h2>Trois points, et ce que chacun garantit</h2>
 *
 * <ul>
 *   <li>{@code add} — range l'arrivante, et note au passage si cette section appartient à un monde
 *       serveur. C'est le seul endroit d'où une section peut l'apprendre : elle ne connaît ni son
 *       monde, ni sa position, mais ses habitants connaissent le leur.</li>
 *   <li>{@code remove} — retire la partante, et rend la grille quand la foule s'est dispersée.</li>
 *   <li>{@code getEntities} — le parcours lui-même. {@code @WrapMethod} plutôt qu'un
 *       {@code @Inject} annulable : sur un chemin emprunté des centaines de milliers de fois par
 *       tick, un {@code CallbackInfoReturnable} par appel se paie en gigaoctets, et ce dépôt l'a
 *       déjà mesuré une fois.</li>
 * </ul>
 *
 * <h2>Ce qui se passe si quelque chose ne va pas</h2>
 *
 * <p>Rien de visible. {@link Foule#grilleDe} rend {@code null} au moindre doute — compte divergent,
 * module éteint, section cliente, entité hors du cube — et {@code original.call} exécute alors le
 * parcours du jeu, mot pour mot. Un mod d'optimisation qui se trompe doit céder le pas, pas se
 * défendre.
 */
@Mixin(EntitySection.class)
public abstract class FouleSectionMixin implements Foule.Section {
    @Shadow
    @Final
    private ClassInstanceMultiMap<EntityAccess> storage;

    @Shadow
    public abstract int size();

    /**
     * La subdivision de cette section, ou {@code null} tant qu'il n'y a pas foule.
     *
     * <p>Un champ et non une table indexée par section : une table coûterait un hachage par requête,
     * ce qui est très exactement l'ordre de grandeur du travail qu'on cherche à supprimer.
     */
    @Unique
    private Foule.Grille lanterne$grille;

    /**
     * Cette section appartient-elle à un monde serveur ?
     *
     * <p>Le client range ses entités dans un {@code TransientEntitySectionManager}, dont le rappel
     * de déplacement n'est pas branché : une grille y serait tenue par personne et se périmerait au
     * premier pas. On ne construit donc rien tant qu'une entité de {@code ServerLevel} n'est pas
     * entrée.
     */
    @Unique
    private boolean lanterne$serveur;

    @Override
    public Foule.Grille lanterne$grille() {
        return this.lanterne$grille;
    }

    @Override
    public void lanterne$grille(Foule.Grille grille) {
        this.lanterne$grille = grille;
    }

    @Override
    public boolean lanterne$serveur() {
        return this.lanterne$serveur;
    }

    @Override
    public int lanterne$taille() {
        return this.size();
    }

    @Override
    public Iterable<? extends EntityAccess> lanterne$contenu() {
        return this.storage;
    }

    @Inject(method = "add", at = @At("TAIL"))
    private void lanterne$rangeArrivante(EntityAccess habitant, CallbackInfo retour) {
        if (!this.lanterne$serveur && habitant instanceof Entity bete
                && bete.level() instanceof ServerLevel) {
            this.lanterne$serveur = true;
        }
        Foule.entre(this, habitant);
    }

    @Inject(method = "remove", at = @At("TAIL"))
    private void lanterne$retirePartante(EntityAccess habitant,
            CallbackInfoReturnable<Boolean> retour) {
        Foule.sort(this, habitant);
    }

    /**
     * Le parcours, restreint aux cellules qui peuvent répondre.
     *
     * <p>La signature complète est donnée parce que {@code getEntities} existe en trois exemplaires
     * dans cette classe, et qu'un nom seul en désignerait un au hasard. C'est aussi ce que le
     * contrôleur de cibles sait vérifier entièrement, argument par argument.
     */
    @WrapMethod(method = "getEntities(Lnet/minecraft/world/phys/AABB;"
            + "Lnet/minecraft/util/AbortableIterationConsumer;)"
            + "Lnet/minecraft/util/Continuation;")
    private Continuation lanterne$parcourtParCellules(AABB boite,
            AbortableIterationConsumer<EntityAccess> sortie,
            Operation<Continuation> original) {
        Foule.Grille grille = Foule.grilleDe(this);
        if (grille == null) {
            return original.call(boite, sortie);
        }
        return grille.parcourt(boite, sortie);
    }
}
