package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;

/**
 * Le seul chemin de recherche d'entités qui sache s'arrêter sans rien allouer.
 *
 * <h2>Trois chemins, et pourquoi il faut le troisième</h2>
 *
 * <p>Le jeu offre plusieurs façons de chercher les entités d'une boîte, et l'interface publique n'en
 * expose pas la bonne.
 *
 * <ol>
 *   <li>{@code get(AABB, Consumer)} — itère le stockage directement, donc vite, mais l'interruption
 *       est perdue : l'adaptateur enveloppe le consommateur dans un
 *       {@code AbortableIterationConsumer.forConsumer} qui rend toujours {@code CONTINUE}.</li>
 *   <li>{@code get(EntityTypeTest, AABB, AbortableIterationConsumer)} — interruptible, et c'est ce
 *       qu'on avait pris. Elle passe par {@code ClassInstanceMultiMap.find}, qui enveloppe sa liste
 *       dans un {@code Collections.unmodifiableCollection} <b>à chaque appel</b>. Le profileur a
 *       chiffré la facture : {@code UnmodifiableCollection.hasNext} est apparu à neuf virgule huit
 *       pour cent, et le gain attendu s'est annulé — mesuré ×2,44 contre ×2,53 sans rien faire.</li>
 *   <li>{@code EntitySectionStorage.getEntities(AABB, AbortableIterationConsumer)} — interruptible
 *       <em>et</em> sans enveloppe. C'est celle qu'il faut, et elle n'est pas atteignable depuis
 *       l'interface.</li>
 * </ol>
 *
 * <p>D'où cet accesseur : trois lignes pour atteindre le champ que l'adaptateur garde pour lui.
 *
 * <h2>Le risque, et pourquoi il est petit</h2>
 *
 * <p>Un accesseur de champ est le mixin le plus inoffensif qui existe : il n'altère aucun corps de
 * méthode, ne s'insère sur aucun chemin d'exécution, et ne peut entrer en conflit avec aucun autre
 * mod. S'il cesse de correspondre à une version future, le mixin échoue au chargement — bruyamment,
 * et non silencieusement, ce qui est exactement la bonne façon de casser.
 */
@Mixin(LevelEntityGetterAdapter.class)
public interface EntityGetterAccessor<T extends EntityAccess> {
    @Accessor("sectionStorage")
    EntitySectionStorage<T> lanterne$sections();
}
