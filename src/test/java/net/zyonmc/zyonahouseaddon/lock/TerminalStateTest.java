package net.zyonmc.zyonahouseaddon.lock;

import fr.maxlego08.zauctionhouse.api.item.ItemStatus;
import fr.maxlego08.zauctionhouse.api.item.StorageType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalStateTest {

    @Test
    void destinationMapsToTheClaimableOrFinalState() {
        assertEquals(Optional.of(TerminalState.SOLD), TerminalState.fromDestination(StorageType.PURCHASED));
        assertEquals(Optional.of(TerminalState.REMOVED), TerminalState.fromDestination(StorageType.EXPIRED));
        assertEquals(Optional.of(TerminalState.DELETED), TerminalState.fromDestination(StorageType.DELETED));
        assertEquals(Optional.empty(), TerminalState.fromDestination(StorageType.LISTED));
        assertEquals(Optional.empty(), TerminalState.fromDestination(null));
    }

    @Test
    void removedStatusAloneIsNeverTerminal() {
        // REMOVED does not say claimable-vs-destroyed: only the destination may record it.
        assertEquals(Optional.empty(), TerminalState.fromStatus(ItemStatus.REMOVED));
        assertEquals(Optional.empty(), TerminalState.fromStatus(ItemStatus.AVAILABLE));
        assertEquals(Optional.of(TerminalState.SOLD), TerminalState.fromStatus(ItemStatus.PURCHASED));
        assertEquals(Optional.of(TerminalState.DELETED), TerminalState.fromStatus(ItemStatus.DELETED));
    }
}
