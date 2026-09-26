package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.TicketStorage;

/**
 * Les deux tables de tickets : les actifs, et ceux rechargés du disque qui attendent d'être
 * réactivés au démarrage. Lues une fois par démarrage, jamais dans une boucle de tick. Voir
 * {@link fr.clubcitrouille.lanterne.core.Ferme#rebuild}.
 */
@Mixin(TicketStorage.class)
public interface TicketStorageAccessor {
    @Accessor("tickets")
    Long2ObjectOpenHashMap<List<Ticket>> lanterne$tickets();

    @Accessor("deactivatedTickets")
    Long2ObjectOpenHashMap<List<Ticket>> lanterne$deactivatedTickets();
}
