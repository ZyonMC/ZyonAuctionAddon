# ZyonAHouseAddon

Addon multiservidor para **zAuctionHouse V4**: sincroniza el hotel de ventas entre servidores con **Redis (Lettuce)** y **MariaDB compartida**.

## Requisitos
- Paper 1.21+, Java 21, zAuctionHouse V4.
- zAuctionHouse con `storage-type: MARIADB` apuntando a **una sola base compartida** por todos los servidores.
- Redis accesible desde todos los servidores.

## Instalación
1. Copia `ZyonAHouseAddon-1.0.0.jar` a `plugins/` (Paper descarga Lettuce solo).
2. Arranca una vez y edita `plugins/ZyonAHouseAddon/config.yml`: `server-id` **único por servidor** y datos de Redis.
3. Reinicia. Verifica con `/zahredis status`.

Sin `server-id` válido, sin Redis, o con otro storage que no sea MariaDB, el addon se desactiva y el servidor sigue en modo local.

## Comandos
`/zahredis status` · `/zahredis resync` (permiso `zyonahouseaddon.admin`)

## Compilar
```
./gradlew jar            # build/libs/ZyonAHouseAddon-1.0.0.jar
ZAH_TEST_REDIS=127.0.0.1:6379 ./gradlew test
```
Compila contra el jar **sombreado** de la API del clon local en `../zAuctionHouse` (`includeBuild`), porque el plugin
distribuye sarah / FoliaLib / currencies relocados y compilar contra la API "cruda" da `NoSuchMethodError` en runtime.
Sin `ZAH_TEST_REDIS` los tests de integración se omiten.

### Verificar que enlaza con el plugin real
Tras compilar el addon **y cada vez que actualices zAuctionHouse**:
```
(cd ../zAuctionHouse && ./gradlew shadowJar)
python3 scripts/check-linkage.py ../zAuctionHouse/target/zAuctionHouse-<version>.jar
```
Falla si el addon llama a un método que el jar del plugin no tiene, o si referencia librerías sin relocar.
