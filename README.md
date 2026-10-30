# Ansertech Backend

API REST del sistema de cotización automatizada de Ansertech Perú S.A.C. Lee correos de Outlook (Microsoft Graph), usa Gemini 2.5 Flash (Vertex AI) para clasificar spam, extraer la solicitud de cotización (RFQ) y buscar los productos en el inventario, y al aprobarse genera la cotización en PDF, la sube a Azure Blob Storage y la envía por correo al cliente.

- **Stack:** Java 17 · Spring Boot 3.2.5 · PostgreSQL 15 (Azure) · Vertex AI SDK · Microsoft Graph 6.4 · iText 8 · Apache POI · JJWT
- **Frontend:** [`ansertech-web-app`](../ansertech-web-app)
- **Swagger:** http://localhost:8080/swagger-ui.html

## Flujo

```
Outlook → GraphPollingService → EmailClassificationService (Gemini: SPAM/VALID/UNCERTAIN)
  → RfqExtractionService (Gemini) → RFQ en PROCESSING
  → RfqStockCheckQueueService (async, 1 hilo) → matching de inventario → PENDING_REVIEW
  → operador aprueba → QuotationService → PDF (iText) → Azure Blob → email al cliente + CC → QUOTED
```

Estados del RFQ: `PROCESSING → PENDING_REVIEW → QUOTING → QUOTED` (o `→ REJECTED`), validados por `RfqStatus.canTransitionTo()`.

## Requisitos

- JDK 17 (también compila con JDK 21) y Maven 3.9+
- Acceso a la BD PostgreSQL de Azure
- `gcp-credentials.json` (service account de Vertex AI) en la raíz — está en `.gitignore`, no se commitea
- Docker (solo para los tests de integración)

## Variables de entorno

| Variable | Obligatoria | Default en `application.yml` |
|---|---|---|
| `DB_HOST` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` (o `DB_URL`) | Sí | `localhost` / `ansertech_db` / `postgres` / `admin` ⚠️ |
| `JWT_SECRET` | Sí | secreto hardcodeado ⚠️ |
| `GCP_PROJECT_ID`, `GCP_LOCATION`, `GOOGLE_APPLICATION_CREDENTIALS` | Sí | — |
| `GRAPH_CLIENT_ID`, `GRAPH_TENANT_ID` | Sí | `""`, `consumers` |
| `AZURE_STORAGE_CONNECTION_STRING` | Sí (para PDFs) | — (cuenta actual: `ansertechsa`, desde 2026-09-30) |
| `AZURE_STORAGE_CONTAINER` | No | `quotations` |
| `JWT_EXPIRATION_MS`, `SERVER_PORT`, `STORAGE_PATH` | No | `28800000`, `8080`, `./storage` |

⚠️ Los defaults marcados no son seguros para producción: si falta la variable, el backend arranca igual con esos valores.

> `.env.example` está desactualizado (describe un proveedor Gmail que el código ya no usa). Usar la tabla de arriba.

## Levantar en local

```bash
GOOGLE_APPLICATION_CREDENTIALS=./gcp-credentials.json \
GCP_PROJECT_ID=ansertech-rfq-prod \
GCP_LOCATION=us-central1 \
DB_HOST=<host-azure> DB_PASSWORD=<password> \
JWT_SECRET=<secreto-256-bits> \
GRAPH_CLIENT_ID=<azure-client-id> \
AZURE_STORAGE_CONNECTION_STRING="DefaultEndpointsProtocol=https;AccountName=...;..." \
mvn spring-boot:run
```

**Primera vez en una máquina:** Microsoft Graph usa Device Code Flow con cuenta personal. La consola muestra un código para ingresar en https://microsoft.com/devicelogin; el token queda cacheado (Keychain en macOS, `ansertech-graph-cache`) por unos 90 días.

**Logs:** además de la consola, el backend escribe `logs/ansertech-backend.log` (cambia la ruta con `LOG_FILE`; la carpeta está en `.gitignore`). Si una cotización no llega al cliente, busca ahí `Error generando/subiendo PDF`, `Error enviando email` o `REQUIRES AUTHENTICATION`.

**Polling de correo:** se activa con `microsoft.polling.enabled: true` en `application.yml` (revisa cada 4 min, hasta 5 correos por ciclo). También se puede forzar con `POST /api/emails/trigger-polling` (ADMIN).

> `docker-compose.yml` no está operativo tal como está: el servicio `backend` depende de `postgres`, que está comentado, y todavía declara variables de Gmail.

## Carga masiva de inventario

`POST /api/inventory/import` (multipart, campo `file`, solo ADMIN) recibe un `.xlsx` con el mismo formato que `GET /api/inventory/export`. Hace upsert por SKU: si el SKU no existe crea el producto (requiere NOMBRE y PRECIO); si existe, actualiza solo las celdas con valor. Las filas con error se omiten y se reportan. Con `dryRun=true` (valor por defecto) no guarda nada y devuelve la vista previa. Tamaño máximo: 10 MB / 5000 filas. La plantilla vacía se descarga desde `GET /api/inventory/import/template` (se genera con las mismas columnas que reconoce el import, en `InventoryImportService.Col`).

## Base de datos

Flyway está deshabilitado: el esquema se mantiene con `ddl-auto: update` y los scripts de `src/main/resources/db/migration/` se ejecutan a mano (pgAdmin).

- En disco: `V1`–`V5` (esquema), `V6` (2 usuarios + 9 productos de laboratorio), `V7` (22 productos: cámaras, sensores, EPP, eléctricos), `V8` (usuario ADMIN).
- ⚠️ Los 17 productos industriales/mineros del antiguo `V9` (prefijos `RMEC-`, `CONS-`, `EQIP-`, `RELE-`, `AUTO-`, `HIDR-`, `HERR-`, entre otros) **solo existen en la BD de Azure**: el script nunca se guardó en disco.
- `V9__seed_catalogo_productos.sql`: catálogo de laboratorio/instrumentación (fuente `Catalogo_productos.xlsx`, generado con `../scripts/build_v9_catalogo.py`; no editar a mano). El archivo actual trae 538 productos, porque excluye los 5 retirados en V11. Precios sin IGV en soles enteros; SKU y stock asignados; 321 precios estimados (datos de demostración).
- `V10__redondear_precios.sql`: `products.unit_price` → `NUMERIC(12,0)` (**precios en soles enteros**, 4555.93 → 4556). `Product` lo refuerza con `scale = 0` y un `@PrePersist/@PreUpdate` que redondea, así que crear, editar y la carga masiva guardan enteros. Las cotizaciones siguen con 2 decimales.
- `V11__eliminar_productos_sin_existencias.sql`: retira 5 productos "Sin existencias" (los borra, o los desactiva si están en `quotation_items`).
- V10 y V11 se ejecutaron en Azure el 2026-09-29. ⚠️ Falta confirmar que V11 se aplicó: la UI seguía mostrando 5 productos con stock bajo.
- ⚠️ `src/main/resources/db` está en `.gitignore`: las migraciones no están en el repositorio.

## Tests

```bash
mvn test
open target/site/jacoco/index.html
```

64 tests JUnit 5: unitarios (`QuotationService`, `EmailClassificationService`, `RfqStatus`, `InventoryImportService`, `InventoryTemplateService`, `Product`) e integración con PostgreSQL real vía Testcontainers (`RepositoryPersistenceTest`, `RfqLifecycleIntegrationTest`, `SecurityRbacIntegrationTest`, `InventoryImportIntegrationTest`). Vertex AI, Graph y Azure se mockean: ningún test hace llamadas reales. Sin Docker, los tests de Testcontainers se saltan solos (2026-09-29, sin Docker: 43 pasan y 21 se saltan). Resultados: [`../docs/informe_testing.md`](../docs/informe_testing.md).

## Estructura

```
com.ansertech/
├── config/        Security, CORS, Async, VertexAI, Graph, OpenAPI, Clock
├── controller/    Auth, Rfq, Quotation, Inventory, Email, Dashboard
├── domain/        entity/ + enums/
├── dto/           request/ + response/
├── exception/     GlobalExceptionHandler + excepciones de negocio
├── repository/    Spring Data JPA
├── security/      filtro y proveedor JWT
└── service/       ai/, auth/, email/, inventory/, quotation/, rfq/
```

## Problemas conocidos

- **`POST /api/auth/register` es público y acepta `role: ADMIN`**: cualquiera puede crearse una cuenta de administrador. Corregir antes de desplegar.
- `confirm`, `reject` y `reclassify` no exigen rol.
- `EmailController.reclassify` puede lanzar NPE si el body no trae `status`.
- **RFQ pegados al apagar el backend:** si se apaga durante una aprobación, el RFQ queda en `QUOTING` sin salida, porque `canTransitionTo()` no permite volver a `PENDING_REVIEW`. Los RFQ que estaban en la cola de stock-check, que vive en memoria, quedan en `PROCESSING`. Al arrancar no se recupera nada: hay que corregirlos en pgAdmin (ver `proyecto_tesis_resumen.md` §5, problema #18). Evita detener el backend mientras se aprueba o procesa algo.
- **Fallo silencioso al aprobar:** si falla el PDF o el correo, el error solo queda en el log, el RFQ pasa igual a `QUOTED` y, si falló el PDF, se guarda una segunda cotización vacía. Si el PDF da `UnknownHostException ...blob.core.windows.net`, la cuenta de Azure Storage no existe: revisa `AZURE_STORAGE_CONNECTION_STRING`.
- `AuditLog` existe pero ningún servicio escribe en ella.
- Graph atado a cuenta personal (Device Code Flow): falta migrar a Client Credentials con una cuenta Microsoft 365.

Lista completa y priorizada: [`../proyecto_tesis_resumen.md`](../proyecto_tesis_resumen.md) §5.
