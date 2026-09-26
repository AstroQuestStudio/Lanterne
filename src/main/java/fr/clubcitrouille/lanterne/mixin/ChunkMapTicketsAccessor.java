package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.TicketStorage;

/**
 * Le registre des tickets d'une dimension, que {@code ChunkMap} garde pour lui.
 *
 * <p>Seule l'Ancre de ferme s'en sert, et une seule fois par démarrage : pour retrouver, dans les
 * tickets rechargés de {@code chunk_tickets.dat}, les chunks qui portent le sien. Voir
 * {@link fr.clubcitrouille.lanterne.core.Ferme#rebuild}.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapTicketsAccessor {
    @Accessor("ticketStorage")
    TicketStorage lanterne$ticketStorage();
}
