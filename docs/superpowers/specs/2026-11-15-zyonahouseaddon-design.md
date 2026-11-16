# ZyonAHouseAddon — diseño

Fecha: 2026-11-15 · Autor: sebastiancheikh

Addon multiservidor para zAuctionHouse V4. Sincroniza el hotel de ventas entre servidores con **Redis (Lettuce)** y **MariaDB compartida**, implementando `AuctionClusterBridge` de la API del plugin.

## 1. Objetivos
- Imposible duplicar items o dinero entre servidores (ver `AUDIT-CLUSTER.md`, los 16 críticos se resuelven por diseño).
- Sincronizar: locks y estado de items, avisos al jugador entre servidores, menús abiertos en vivo y comandos admin.
- Código limpio, una responsabilidad por clase, testeable, sin bloquear nunca el hilo principal.

## 2. Alcance técnico
| Tema | Decisión |
|---|---|
| Paquete base | `net.zyonmc.zyonahouseaddon` |
| Autor / `plugin.yml` | `sebastiancheikh` |
| Proyecto | `ZyonAHouseRedis/addon/`, Gradle propio |
| API del plugin | `includeBuild("../zAuctionHouse")` → módulo `:API` (compileOnly) |
| Java | 21 (mismo target que el plugin base) |
| Redis | Lettuce 6.5.5, cargada con `libraries` en `plugin.yml` (como ZyonPay) |
| Base de datos | **Solo MariaDB** compartida. SQLITE, MYSQL y PostgreSQL → el addon se desactiva y avisa en consola |
| Tests | JUnit 5; integración con Redis y MariaDB reales |
| `depend` | `zAuctionHouse` |

Fuera de alcance: caché de listados en Redis (la BD es la única fuente de verdad), Folia, otros motores SQL.

## 3. Arquitectura
Redis hace de **coordinador** (locks, bus de eventos, presencia). MariaDB es el **árbitro**. Un evento nunca decide el estado de un item: solo avisa de que hay que releer la BD.

```
net.zyonmc.zyonahouseaddon
├── ZyonAHouseAddonPlugin        arranque/parada, validaciones previas, instala el bridge
├── config/        Settings, SettingsLoader        (redis, server-id, timeouts, fail-closed)
├── redis/         RedisConnection                 (comandos + conexión pub/sub aparte, reconexión)
├── lock/          LockManager, LuaScripts, LeaseWatchdog
├── bridge/        RedisAuctionClusterBridge       (implementa AuctionClusterBridge)
├── messaging/     Envelope, EventType, EventCodec, EventBus, Deduplicator
├── listener/      un handler por evento (Listed, Bought, StatusChange, Removed, AdminRemove)
├── player/        PresenceRegistry, CrossServerNotifier, PendingClaims
├── gui/           LiveMenuRefresher               (debounce)
└── admin/         ZahRedisCommand                 (status, resync, reload)
```

### 3.1 Locks (`lock/`)
- Clave `zah:lock:{storage}:{itemId}`; valor = `LockToken` único (identidad + nonce), nunca determinista.
- Adquirir, renovar, liberar y comprobar propiedad son **scripts Lua atómicos** con `SET NX PX` y comparación de valor.
- `checkAndLock` comprueba el estado terminal y adquiere en un único script (sin ventana TOCTOU).
- Estado terminal por alcance: SOLD y REMOVED bloquean el **ACHAT** pero permiten la **RECLAMACIÓN**. DELETED es permanente y no caduca.
- `LeaseWatchdog` renueva a `lease/3`. Si la renovación falla, marca el token como perdido y `isHeldBy` devuelve `false`; el plugin base aborta antes de cobrar o entregar.
- Contención → `LockToken.noop()`. Estado terminal → `LockToken.unavailable()`. Un future en error se reserva para fallos de transporte.

### 3.2 Bus de eventos (`messaging/`)
- Canal único `zah:bus`; `Envelope` JSON versionado: `v`, `eventId`, `origin` (server-id), `type`, `itemId`, `storage`, `oldStatus`, `newStatus`, `ts`, `payload`.
- Cada servidor ignora sus propios mensajes (`origin`) y descarta duplicados (`Deduplicator`, ventana acotada).
- Publicación asíncrona **después** del commit en BD, nunca antes. Esto evita el fallo C-012 del audit.

### 3.3 Handlers (`listener/`)
Cada handler hace lo mismo:
1. Valida el envelope.
2. Relee el item en BD (árbitro).
3. Aplica el cambio en la caché local (`addItem`, `removeItem`, `updateListedItems`).
4. Compara `oldStatus` con el estado local; si no encaja, ignora el mensaje y relee. Esto evita el C-004.

La lectura y la mutación del estado del plugin van en el hilo correcto de Bukkit (scheduler de la API).

### 3.4 Jugadores (`player/`)
- `PresenceRegistry`: `zah:presence:{uuid}` → server-id, con TTL y heartbeat.
- `CrossServerNotifier`: si el vendedor está en otro servidor, envía un mensaje dirigido a ese servidor (`zah:bus` con `target`). Si está offline, el dinero queda en claim pendiente (`isDistributed() == true` ya lo exige).
- `PendingClaims`: avisa al conectarse de los claims pendientes.

### 3.5 Menús en vivo (`gui/`)
`LiveMenuRefresher` agrupa los cambios (debounce ~250 ms) y llama a `updateInventory` solo a los jugadores con el menú principal abierto, sin reabrirlo.

### 3.6 Admin (`admin/`)
`/zahredis status|resync|reload`. `resync` publica `RESYNC` y cada nodo reconstruye su caché desde la BD (`rebuildSortedItemsCache`). El admin-remove se propaga como evento terminal **después** de la escritura en BD.

## 4. Seguridad y fallos (fail-closed estricto)
| Situación | Comportamiento |
|---|---|
| Redis inaccesible al arrancar | El addon no instala el bridge; el servidor queda en modo local y lo registra en SEVERE |
| Storage ≠ MARIADB | El addon se desactiva con un mensaje claro |
| Redis cae en ejecución | `lockItem` / `checkAndLock` fallan con error de transporte → el plugin base rechaza compra, retiro y claim (`INTERNAL_ERROR`). **Nunca** se degrada a lock local |
| Pub/sub caído | Lettuce reconecta y resuscribe; al volver se hace un `resync` de caché desde la BD |
| Lease perdido durante una operación | `isHeldBy` → `false` y la operación aborta antes de cobrar o entregar |
| Mensaje desconocido o con versión superior | Se ignora y se registra en DEBUG |

Configuración y credenciales solo en `config.yml` del addon. Los logs nunca imprimen contraseñas.

## 5. Rendimiento
- Un script Lua por operación caliente (un solo viaje de red).
- Conexión de comandos separada de la de pub/sub; todo con `CompletableFuture`, sin bloqueo del hilo principal.
- Timeouts alineados con `cluster-timeout` del plugin base (5 s / 5 s / 3 s).
- Debounce de la UI y deduplicación acotada en memoria.

## 6. Tests
- **Unitarios:** `Envelope`/`EventCodec`, `Deduplicator`, reglas de estado terminal, lógica del watchdog.
- **Integración (Redis real):** exclusión mutua entre dos clientes simulados, expiración y renovación del lease, liberación solo por el propietario, `checkAndLock` con estados terminales, entrega del bus con filtro de `origin`.
- **Escenarios de duplicación del audit:** doble compra concurrente, doble claim, retiro frente a compra, lease expirado a mitad de la operación.

## 7. Entregables
1. Proyecto Gradle `addon/` con shadow/jar listo para `plugins/`.
2. `plugin.yml`, `config.yml` y `messages` en español.
3. Tests y un `README` con la configuración (Redis, MariaDB compartida, `server-id` único por servidor).

## 8. Ajustes hechos durante la implementación
- Sin `LeaseWatchdog`: el plugin base ya renueva el lease (usa `lockLeaseDuration()` y `renewLock`).
- Sin `PresenceRegistry`, `PendingClaims` ni `LiveMenuRefresher`: el aviso al vendedor lo envía el servidor donde está online (todos reciben el evento) y los menús se refrescan con `updateListedItems` del propio plugin.
- Sin `/zahredis reload`: Redis y los locks exigen reinicio; quedan `status` y `resync`.
- Resync = `StorageManager.loadItems()` (lectura offline + cambio atómico).
- Proyecto en `~/IdeaProjects/ZyonAHouseRedis` (raíz); el clon de zAuctionHouse vive en `~/IdeaProjects/zAuctionHouse` (`includeBuild`).

## 9. Verificado al implementar
- Tipo de storage: `getStorageManager().getDatabaseConnection().getDatabaseConfiguration().getDatabaseType() == DatabaseType.MARIADB`.
- `addItem`/`removeItem` usan `ConcurrentHashMap` y el plugin ya los llama desde hilos asíncronos; `updateListedItems` y el scheduler de la API gestionan el hilo del jugador.
- Los avisos usan MiniMessage propio del addon, sin depender de los mensajes del plugin base.
