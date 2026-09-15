package fr.clubcitrouille.lanterne.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import org.lwjgl.opengl.GLCapabilities;

import com.mojang.blaze3d.opengl.BufferStorage;

import fr.clubcitrouille.lanterne.client.Tampon;

/**
 * Le rétroportage de MC-307596, corrigé par Mojang en 26.3.
 *
 * <h2>Pourquoi rediriger la lecture du champ plutôt que l'interrupteur du jeu</h2>
 *
 * <p>Le jeu offre pourtant {@code GlDevice.USE_GL_ARB_buffer_storage}, un booléen statique
 * {@code protected} qu'un accesseur suffirait à mettre à faux. On ne le fait pas, et pour une raison
 * de portée : ce champ est <b>global et définitif</b>. Le poser à faux avant la création du
 * périphérique voudrait dire décider sans avoir pu lire le nom de la carte graphique — puisque lire
 * ce nom exige un contexte OpenGL déjà ouvert.
 *
 * <p>La lecture du champ {@code GLCapabilities.GL_ARB_buffer_storage} a lieu <b>dans</b>
 * {@code BufferStorage.create}, c'est-à-dire au seul instant où le contexte existe et où la décision
 * peut encore être prise. C'est le seul point où l'on sait à la fois quelle carte on a et ce qu'on
 * s'apprête à en faire.
 *
 * <p>Voir {@link Tampon} — y compris l'aveu qui s'y trouve : <b>ce module n'est pas mesuré</b>.
 */
@Mixin(BufferStorage.class)
public abstract class BufferStorageMixin {
    @Redirect(
            method = "create",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                    target = "Lorg/lwjgl/opengl/GLCapabilities;GL_ARB_buffer_storage:Z"))
    private static boolean lanterne$mutableOnAffectedDrivers(GLCapabilities capabilities) {
        return capabilities.GL_ARB_buffer_storage && !Tampon.forceMutable();
    }
}
