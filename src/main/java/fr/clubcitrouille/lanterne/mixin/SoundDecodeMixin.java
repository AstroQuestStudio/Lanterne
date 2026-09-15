package fr.clubcitrouille.lanterne.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.audio.SoundBuffer;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.Util;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.disc.Needle;

/**
 * Le seul endroit du jeu où l'on décide, sans regarder, qu'un son est du Vorbis.
 *
 * <h2>Le mensonge utile qu'on vient corriger</h2>
 *
 * <p>Voici ce que fait {@code SoundBufferLibrary} en 26.2, mot pour mot :
 *
 * <pre>{@code
 * InputStream is = this.resourceManager.open(location);
 * return looping ? new LoopingAudioStream(JOrbisAudioStream::new, is) : new JOrbisAudioStream(is);
 * }</pre>
 *
 * <p>Le décodeur est choisi <b>avant</b> que le premier octet ne soit lu. Ce n'est pas une
 * négligence : vanilla ne livre que du Vorbis, et un test serait du travail pour rien. Mais c'est un
 * choix pris à l'aveugle, et il suffit donc de le prendre à sa place pour que Minecraft joue autre
 * chose — sans toucher au reste du chemin sonore, qui ne connaît que l'interface
 * {@code AudioStream} et se moque de ce qu'il y a derrière.
 *
 * <p>Deux méthodes, parce qu'il y a deux chemins : {@code getStream} pour les sons déclarés en flux —
 * ce sont les nôtres — et {@code getCompleteBuffer} pour les sons courts chargés d'un coup. Le second
 * ne devrait jamais voir un disque, puisque {@code Reel} les déclare tous en flux ; il est traité
 * quand même, parce qu'un chemin laissé au hasard est un chemin qui finit par être pris.
 *
 * <h2>Pourquoi l'interception est à HEAD et pas sur la construction</h2>
 *
 * <p>Remplacer directement le {@code new JOrbisAudioStream} aurait été plus chirurgical, mais les
 * deux occurrences vivent au fond de lambdas — {@code JOrbisAudioStream::new} est une référence de
 * méthode, donc une classe synthétique dont le nom dépend de l'ordre de compilation. Un mixin qui
 * vise {@code lambda$getStream$1} casse à la première recompilation de Minecraft. La signature
 * publique de la méthode, elle, ne bouge pas.
 *
 * <h2>La garde, et pourquoi elle est sur l'espace de noms</h2>
 *
 * <p>Seules les ressources de Lanterne sont détournées. Un son de vanilla, ou d'un autre mod, repart
 * par le chemin d'origine sans qu'une seule instruction de plus ne soit exécutée : la comparaison de
 * l'espace de noms est le premier geste de la méthode. C'est ce qui permet de poser un mixin sur une
 * classe aussi centrale sans rien devoir à personne.
 *
 * <p>Et nos propres sons en Ogg — le silence embarqué, par exemple — repassent quand même par
 * {@code JOrbisAudioStream} : {@link Needle} renifle la signature et rend exactement ce que vanilla
 * aurait rendu.
 */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundDecodeMixin {
    @Shadow
    @Final
    private ResourceProvider resourceManager;

    @Shadow
    @Final
    private Map<Identifier, CompletableFuture<SoundBuffer>> cache;

    @Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
    private void lanterne$decodeStream(Identifier location, boolean looping,
            CallbackInfoReturnable<CompletableFuture<AudioStream>> callback) {
        if (!Lanterne.ID.equals(location.getNamespace())) {
            return;
        }
        callback.setReturnValue(CompletableFuture.supplyAsync(() -> {
            try {
                InputStream source = this.resourceManager.open(location);
                // Le bouclage garde le flux d'origine et le rejoue depuis le début : c'est la même
                // fabrique qui est rappelée, donc le même reniflage, donc le même décodeur.
                return looping
                        ? (AudioStream) new LoopingAudioStream(Needle::finite, source)
                        : Needle.finite(source);
            } catch (IOException problem) {
                throw new CompletionException(problem);
            }
        }, Util.nonCriticalIoPool()));
    }

    @Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
    private void lanterne$decodeBuffer(Identifier location,
            CallbackInfoReturnable<CompletableFuture<SoundBuffer>> callback) {
        if (!Lanterne.ID.equals(location.getNamespace())) {
            return;
        }
        // La table de vanilla est réutilisée, et pas seulement par économie : c'est elle que
        // « clear » vide pour rendre les tampons OpenAL. Un cache parallèle aurait fuité de la
        // mémoire graphique à chaque rechargement de ressources.
        callback.setReturnValue(this.cache.computeIfAbsent(location,
                key -> CompletableFuture.supplyAsync(() -> {
                    try (FiniteAudioStream stream = Needle.finite(this.resourceManager.open(key))) {
                        return new SoundBuffer(stream.readAll(), stream.getFormat());
                    } catch (IOException problem) {
                        throw new CompletionException(problem);
                    }
                }, Util.nonCriticalIoPool())));
    }
}
