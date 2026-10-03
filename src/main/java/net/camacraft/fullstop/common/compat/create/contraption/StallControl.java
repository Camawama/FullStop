package net.camacraft.fullstop.common.compat.create.contraption;

/**
 * What FullStop adds to every Create contraption entity ({@code ContraptionStallMixin}): stalling it the way Create
 * stalls a contraption whose drill is still mining, so it holds still and the clients are told exactly where it
 * stopped. Server side; a contraption {@link ContraptionStress} holds stays stalled every tick until it lets go.
 */
public interface StallControl {

    /** Stalls the contraption now and sends the clients its pose (Create's stall packet), as Create does for an actor. */
    void fullstop$stall();
}
