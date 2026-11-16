package net.zyonmc.zyonahouseaddon.messaging;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.Optional;

/** JSON (de)serialization. Decoding never throws: anything malformed or unsupported is simply dropped. */
public final class EventCodec {

    private final Gson gson = new Gson();

    public String encode(Envelope envelope) {
        return this.gson.toJson(envelope);
    }

    public Optional<Envelope> decode(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            Envelope envelope = this.gson.fromJson(json, Envelope.class);
            if (envelope == null || envelope.type() == null) return Optional.empty();
            if (envelope.v() < 1 || envelope.v() > Envelope.VERSION) return Optional.empty();
            if (isBlank(envelope.id()) || isBlank(envelope.origin())) return Optional.empty();
            return Optional.of(envelope);
        } catch (JsonParseException | IllegalStateException exception) {
            return Optional.empty();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
