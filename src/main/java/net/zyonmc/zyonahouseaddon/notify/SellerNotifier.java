package net.zyonmc.zyonahouseaddon.notify;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import fr.maxlego08.zauctionhouse.api.AuctionManager;
import fr.maxlego08.zauctionhouse.api.cache.PlayerCacheKey;
import net.zyonmc.zyonahouseaddon.config.MessageKey;
import net.zyonmc.zyonahouseaddon.config.Messages;
import net.zyonmc.zyonahouseaddon.messaging.Envelope;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Delivers seller messages. Every server receives the event; only the one where the seller is online has
 * anyone to tell, so no presence registry is needed. Offline sellers get their money and items through the
 * plugin's own claim flow.
 */
public final class SellerNotifier implements SellerNotices {

    private final Plugin owner;
    private final Supplier<AuctionManager> auction;
    private final Function<UUID, Player> lookup;
    private final Supplier<Messages> messages;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public SellerNotifier(Plugin owner, Supplier<AuctionManager> auction, Supplier<Messages> messages) {
        this(owner, Bukkit::getPlayer, auction, messages);
    }

    SellerNotifier(Plugin owner, Function<UUID, Player> lookup, Supplier<AuctionManager> auction, Supplier<Messages> messages) {
        this.owner = owner;
        this.auction = auction;
        this.lookup = lookup;
        this.messages = messages;
    }

    @Override
    public void itemSold(Envelope envelope) {
        deliver(envelope, MessageKey.ITEM_SOLD);
    }

    @Override
    public void itemExpired(Envelope envelope) {
        deliver(envelope, MessageKey.ITEM_EXPIRED);
    }

    @Override
    public void collectPayment(Envelope envelope) {
        if (envelope.seller() == null) return;
        Player seller = this.lookup.apply(envelope.seller());
        if (seller == null) return;
        // claimMoney reads the PENDING rows from the shared DB and deposits only what it reserves, so a
        // concurrent claim or the join auto-claim can never pay twice.
        // The buyer's server cleared these caches only for itself; the seller's server must do it too.
        seller.getScheduler().run(this.owner, task -> {
            AuctionManager manager = this.auction.get();
            manager.clearPlayerCache(seller, PlayerCacheKey.ITEMS_SELLING, PlayerCacheKey.HISTORY_DATA, PlayerCacheKey.PENDING_MONEY_DATA);
            manager.getClaimService().claimMoney(seller);
        }, null);
    }

    private void deliver(Envelope envelope, MessageKey template) {
        if (envelope.seller() == null) return;
        Player seller = this.lookup.apply(envelope.seller());
        if (seller == null) return;
        Messages texts = this.messages.get();

        Component component = this.miniMessage.deserialize(texts.get(MessageKey.PREFIX) + texts.get(template),
                Placeholder.component("item", this.legacy.deserialize(orEmpty(envelope.display()))),
                Placeholder.unparsed("buyer", orEmpty(envelope.buyer())),
                Placeholder.component("price", this.legacy.deserialize(orEmpty(envelope.price()))));
        // Entity thread: mandatory on Folia, harmless on Paper.
        seller.getScheduler().run(this.owner, task -> seller.sendMessage(component), null);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
