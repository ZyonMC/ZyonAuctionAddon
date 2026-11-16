package net.zyonmc.zyonahouseaddon.lock;

import net.zyonmc.zyonahouseaddon.redis.LuaScript;

/**
 * Every lock operation is ONE Lua script: Redis runs it atomically, so there is no window between
 * "is it available" and "take it", and a lock is only ever released/renewed by whoever holds its token.
 * <p>
 * Scope rule (KEYS[2] = terminal mark, ARGV scope = LISTED | PURCHASED | EXPIRED | empty):
 * DELETED refuses everything; with a scope, SOLD only allows PURCHASED (claim) and REMOVED only allows
 * EXPIRED (claim). An empty scope is the legacy display predicate: only DELETED refuses.
 */
final class LockScripts {

    private static final String SCOPE_CHECK = """
            local term = redis.call('GET', KEYS[2])
            if term then
              if term == 'DELETED' then return 'UNAVAILABLE' end
              local scope = ARGV[SCOPE]
              if scope ~= '' then
                if term == 'SOLD' and scope ~= 'PURCHASED' then return 'UNAVAILABLE' end
                if term == 'REMOVED' and scope ~= 'EXPIRED' then return 'UNAVAILABLE' end
              end
            end
            """;

    /** KEYS: lock, terminal. ARGV: token, leaseMs, scope. -> ACQUIRED | BUSY | UNAVAILABLE */
    static final LuaScript ACQUIRE = new LuaScript(SCOPE_CHECK.replace("SCOPE", "3") + """
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 'ACQUIRED' end
            return 'BUSY'
            """);

    /** KEYS: lock, terminal. ARGV: scope. -> AVAILABLE | UNAVAILABLE (locks are ignored on purpose) */
    static final LuaScript CHECK = new LuaScript(SCOPE_CHECK.replace("SCOPE", "1") + """
            return 'AVAILABLE'
            """);

    /** KEYS: lock. ARGV: token. -> 1 if released by its owner, else 0 */
    static final LuaScript RELEASE = new LuaScript("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """);

    /** KEYS: lock. ARGV: token, leaseMs. -> 1 if extended, else 0 (lost or stolen) */
    static final LuaScript RENEW = new LuaScript("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0
            """);

    /** KEYS: lock. ARGV: token. -> 1 if this token still holds the lock */
    static final LuaScript HELD = new LuaScript("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return 1 end
            return 0
            """);

    /** KEYS: terminal. ARGV: state, ttlMs. DELETED is sticky: nothing overwrites it. */
    static final LuaScript MARK = new LuaScript("""
            if redis.call('GET', KEYS[1]) == 'DELETED' then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 1
            """);

    private LockScripts() {
    }
}
