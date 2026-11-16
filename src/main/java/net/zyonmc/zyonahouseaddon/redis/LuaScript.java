package net.zyonmc.zyonahouseaddon.redis;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A Lua script run atomically on Redis. Uses {@code EVALSHA} (one tiny packet) and falls back to
 * {@code EVAL} - which also re-primes the server cache - when Redis restarted or flushed its scripts.
 */
public final class LuaScript {

    private final String source;
    private final String sha1;

    public LuaScript(String source) {
        this.source = source;
        this.sha1 = sha1Hex(source);
    }

    public <T> CompletableFuture<T> run(RedisAsyncCommands<String, String> commands, ScriptOutputType type,
                                        String[] keys, String... args) {
        return commands.<T>evalsha(this.sha1, type, keys, args).toCompletableFuture()
                .handle((value, error) -> {
                    if (error == null) return CompletableFuture.completedFuture(value);
                    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    if (isNoScript(cause)) {
                        return commands.<T>eval(this.source, type, keys, args).toCompletableFuture();
                    }
                    return CompletableFuture.<T>failedFuture(cause);
                })
                .thenCompose(future -> future);
    }

    private static boolean isNoScript(Throwable cause) {
        return cause instanceof RedisNoScriptException
                || (cause.getMessage() != null && cause.getMessage().startsWith("NOSCRIPT"));
    }

    private static String sha1Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is mandatory on every JVM", exception);
        }
    }
}
