package fr.clubcitrouille.lanterne.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackType;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Sommaire;

/**
 * Le zip lu une fois, et interrogé ensuite.
 *
 * <h2>Deux remèdes différents pour deux défauts différents</h2>
 *
 * <p>{@code listResources} demande « toutes les entrées sous tel répertoire ». La réponse est un
 * <b>intervalle</b> dans l'ordre alphabétique des noms : un ensemble trié la rend par recherche
 * dichotomique, et l'on ne parcourt que ce qui répond. C'est le premier remède.
 *
 * <p>{@code getNamespaces} demande « quels espaces de noms existent pour ce type ». La réponse ne
 * dépend que du zip et du type, et le jeu la redemande — deux fois par paquet à la seule
 * construction de {@code MultiPackResourceManager}. On se contente donc de la <b>retenir</b>. C'est
 * le second remède, et il est volontairement plus bête : rejouer soi-même l'extraction d'espace de
 * noms aurait demandé de recopier la validation et l'avertissement de Mojang, donc de créer une
 * seconde vérité. Ici la vérité reste celle de vanilla — on ne fait que ne pas la recalculer.
 *
 * <h2>L'invalidation, qui tient à un seul fait</h2>
 *
 * <p>Le sommaire est retenu <b>avec l'objet {@code ZipFile} dont il vient</b>. Si
 * {@code SharedZipFileAccess.close()} a fermé le fichier et qu'un autre est rouvert,
 * {@code getOrCreateZipFile} rend une <em>autre</em> instance, la comparaison d'identité échoue, et
 * le sommaire est rebâti. Un sommaire périmé est donc impossible sans qu'on ait à surveiller quoi
 * que ce soit.
 *
 * <h2>La borne supérieure, et pourquoi elle est exacte</h2>
 *
 * <p>{@code subSet(root, root + '\\uFFFF')} rend exactement les noms qui commencent par
 * {@code root}. Ce n'est pas une approximation : en UTF-16, aucune unité de code ne dépasse
 * {@code \\uFFFF}, donc aucune chaîne préfixée par {@code root} ne peut lui être supérieure.
 *
 * <h2>La seule panne possible est le manque, jamais le faux — et c'est une propriété, pas une chance</h2>
 *
 * <p>On ne remplace que l'<b>énumération</b>. Le {@code !zipEntry.isDirectory()} et le
 * {@code name.startsWith(prefix)} de vanilla restent en place et s'appliquent à ce qu'on rend. Un
 * intervalle trop <em>large</em> est donc rattrapé par le jeu lui-même : il coûte du temps, il ne
 * peut pas livrer une ressource qui n'appartient pas au répertoire demandé.
 *
 * <p>Ce n'est pas une observation en passant : c'est la raison pour laquelle ce module peut être
 * livré. L'épreuve {@code lab.Sommaire} l'a établi en essayant — l'interrupteur qui retirait la
 * borne supérieure a fait chuter le gain de ×18,5 à ×1,0 <b>sans produire une seule divergence</b>.
 *
 * <p>Reste l'autre sens : un sommaire <b>incomplet</b>, qui ne rendrait pas une ressource que le
 * paquet contient. Celui-là, rien ne le rattrape, et c'est la panne silencieuse qu'il faut savoir
 * voir — une texture manquante, des semaines plus tard. C'est donc lui que
 * {@code LANTERNE_BREAK_SOMMAIRE=1} introduit.
 */
@Mixin(FilePackResources.class)
public abstract class PackIndexMixin {
    @Shadow
    private String addPrefix(String path) {
        throw new AssertionError();
    }

    /** Le zip dont vient le sommaire. Sert d'unique preuve de fraîcheur. */
    @Unique
    private @Nullable ZipFile lanterne$source;

    /** Les noms d'entrées, triés, répertoires exclus. */
    @Unique
    private @Nullable NavigableSet<String> lanterne$names;

    /**
     * Les espaces de noms déjà rendus, par type.
     *
     * <h2>Pourquoi celui-ci n'est pas lié à une instance de {@code ZipFile}</h2>
     *
     * <p>Le sommaire des noms l'est, parce qu'il faut bien un critère de fraîcheur. La liste des
     * espaces de noms, elle, ne dépend que du <b>contenu du fichier</b>, et
     * {@code SharedZipFileAccess} rouvre toujours le <em>même</em> {@code File}. Une liste retenue
     * avant une fermeture reste donc exacte après la réouverture.
     *
     * <p>Le seul cas où elle serait fausse est celui d'un zip modifié sur le disque en cours de
     * partie — et dans ce cas la poignée {@code ZipFile} de vanilla est déjà incohérente, bien avant
     * nous.
     */
    @Unique
    private final java.util.EnumMap<PackType, Set<String>> lanterne$namespaces =
            new java.util.EnumMap<>(PackType.class);

    /**
     * Le sommaire, bâti à la première demande.
     *
     * <p>Bâti <b>ici</b> et non à l'ouverture du paquet, pour une raison de coût : un paquet ouvert
     * et jamais interrogé ne doit rien payer. Le premier appel paie le balayage que vanilla aurait
     * fait de toute façon ; tous les suivants sont gratuits.
     */
    @Unique
    private @Nullable NavigableSet<String> lanterne$index(ZipFile zip) {
        if (this.lanterne$source == zip && this.lanterne$names != null) {
            return this.lanterne$names;
        }
        NavigableSet<String> names = new TreeSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        int seen = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                // LANTERNE_BREAK_SOMMAIRE=1 rend le sommaire INCOMPLET — une entrée sur mille
                // manque. C'est la seule panne que ce module puisse réellement produire, et
                // l'épreuve doit savoir la voir. Voir le javadoc de la classe.
                if (!Sommaire.broken() || ++seen % 1000 != 0) {
                    names.add(entry.getName());
                }
            }
        }
        this.lanterne$source = zip;
        this.lanterne$names = names;
        Sommaire.noteBuild(names.size());
        return names;
    }

    @WrapOperation(
            method = "listResources",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/zip/ZipFile;entries()Ljava/util/Enumeration;"))
    private Enumeration<? extends ZipEntry> lanterne$listFromIndex(
            ZipFile zip, Operation<Enumeration<? extends ZipEntry>> original,
            @Local(argsOnly = true) PackType type,
            @Local(argsOnly = true, ordinal = 0) String namespace,
            @Local(argsOnly = true, ordinal = 1) String directory) {
        if (!Settings.sommaire()) {
            return original.call(zip);
        }
        NavigableSet<String> names = this.lanterne$index(zip);
        if (names == null) {
            return original.call(zip);
        }
        String wanted = this.addPrefix(type.getDirectory() + "/" + namespace + "/")
                + directory + "/";
        SortedSet<String> slice = names.subSet(wanted, wanted + Character.MAX_VALUE);
        List<ZipEntry> picked = new ArrayList<>(slice.size());
        for (String name : slice) {
            ZipEntry entry = zip.getEntry(name);
            if (entry != null) {
                picked.add(entry);
            }
        }
        Sommaire.noteQuery(names.size() - picked.size());
        return Collections.enumeration(picked);
    }

    @Inject(method = "getNamespaces", at = @At("HEAD"), cancellable = true)
    private void lanterne$namespacesFromMemory(PackType type,
                                               CallbackInfoReturnable<Set<String>> callback) {
        if (!Settings.sommaire()) {
            return;
        }
        Set<String> known = this.lanterne$namespaces.get(type);
        if (known != null) {
            Sommaire.noteNamespaces();
            callback.setReturnValue(known);
        }
    }

    @Inject(method = "getNamespaces", at = @At("RETURN"))
    private void lanterne$rememberNamespaces(PackType type,
                                             CallbackInfoReturnable<Set<String>> callback) {
        if (!Settings.sommaire()) {
            return;
        }
        Set<String> found = callback.getReturnValue();
        // Un ensemble vide veut dire « le zip n'a pas pu s'ouvrir » autant que « rien pour ce
        // type ». On ne retient donc pas le vide : le retenir ferait persister un échec
        // d'ouverture passager pour toute la session.
        if (found != null && !found.isEmpty()) {
            this.lanterne$namespaces.put(type, found);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void lanterne$forget(CallbackInfo callback) {
        this.lanterne$source = null;
        this.lanterne$names = null;
        this.lanterne$namespaces.clear();
    }
}
