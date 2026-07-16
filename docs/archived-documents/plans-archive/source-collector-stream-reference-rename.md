# Rename: Source → Collector, and Stream / Reference data-origins

**Decided 2026-07-14.** Reverses the 2026-06-29 `Collector → Source` lock (see `docs/GLOSSARY.md` §0/§2/§13).
Rationale: now that **Stream** (event/fact origin) and **Reference** (dimension origin) are the Catalog's
data-origins, the word *Source* collided with *data source*. The acquisition task becomes **Collector**; its
runtime is the **collection engine**.

**Version policy: NO version bump.** Breaking on the wire, but nothing has shipped on 4.x in the wild — same
precedent as the Flow→Pipeline backend rename (§13). Full breaking rename, no aliases required (except the
persisted-id migration note in C).

---

## The three distinct tokens (do not conflate)

| Token today | Sense | Target |
|---|---|---|
| `Source(s)` — pane/route/service/SPI | the **acquisition task** (what to collect) | **Collector** |
| `NodeKind.SOURCE` / `IdScheme.source` / `source:<pipeline>` | the **Catalog origin node** | **Stream** (`STREAM` / `stream:`) |
| `'SOURCE'` pipeline **stage category** | pipeline-stage bucket | **UNCHANGED** (out of scope) |
| `collector.*` pipeline **node types** | already correct | **UNCHANGED** |

---

## A. Acquisition entity: Source → Collector (backend `inspecto/src/main/java/com/gamma`)

- `acquire/SourceConnector.java` → `CollectorConnector.java` (SPI interface + all impls/refs)
- `acquire/SourceConnectors.java` → `CollectorConnectors.java`; `acquire/SourceConnectorFactory.java` → `CollectorConnectorFactory.java`
- `service/SourceService.java` → `CollectorService.java`; method `sources()` → `collectors()`
- `service/SourceWatcher.java` → `CollectorWatcher.java`
- `inspector/SourceProcessor.java` → `CollectorProcessor.java`; `MultiSourceProcessor.java` → `MultiCollectorProcessor.java`
- `control/AcquisitionRoutes.java`: `/sources` → `/collectors`, `/sources/{id}/notify` → `/collectors/{id}/notify`
- `control/AuditTrail.java`: resource mapping for `/collectors` → `"collector"`; keep action label `"notified"`
- Any `Source*` DTO/record/field for the acquisition task → `Collector*`.
- **Keep** `collect()` verbs and `sourceFile` (a file's origin path — not the entity).

## B. Catalog origin node: SOURCE → STREAM; Reference read-model (full)

- `catalog/NodeKind.java`: `SOURCE` → `STREAM` (comment `stream:<pipeline>`). **Keep** `REFERENCE_DATASET`.
- `catalog/IdScheme.java`: `source(pipeline)` → `stream(pipeline)`, token `"source:"` → `"stream:"`; `token(NodeKind)` STREAM→`"stream"`. Keep `reference(...)`/`"ref:"`.
- Serve the data-origin read-model on the backend: `GET /catalog/streams` returns STREAM origin nodes;
  **add** `GET /catalog/references` returning Reference (REFERENCE_DATASET) origins. (Find the catalog/lineage
  route class — `LineageRoutes` / metadata graph service.)
- Update all usages: `IdScheme`, `CatalogOverlay`, `MetadataGraphService`/`Builder`, skills (`KpiToSqlSkill`,
  `SuggestConfigSkill`), and tests.

## C. Persisted TOON ids

`inspecto/examples/**` + `spaces/**/config/**`: any `source:<pipeline>` id → `stream:<pipeline>`. (grep first.)
No runtime migration shim needed (no shipped 4.x data), but the id token change is the reason for the
"breaking, no bump" classification.

## D. UI (`inspecto-ui/src`) — apply the angular-ui skill

- Route `/sources` → `/collectors`; folder `modules/admin/sources` → `collectors`; `SourcesComponent` →
  `CollectorsComponent`, `SourceDetailDialog` → `CollectorDetailDialog`, `SourcesService` → `CollectorsService`,
  `SourceView` → `CollectorView`; nav id/title/link → `collectors` / `Collectors` / `/collectors`.
- Mock: `SOURCES_RE` → `/\/collectors$/`; `SOURCES` array + config-spec `source.*` acquisition field paths →
  collector. **Leave** `collector.*` node types and the `'SOURCE'` stage category.
- Labels: "Acquisition & Sources" → "Acquisition & Collectors", etc.
- Catalog: keep the **Streams** tab; **add** a **References** tab parallel to it (`references()` → `GET
  /catalog/references`, mock `CATALOG_REFERENCES`). Update the Streams comment: origin over a **Collector**
  (not "Source").

## E. Verify (GAUNTLET) + graphify

- Backend `mvn -o clean test` green (DuckDB native-access flag per build-verify skill).
- UI lint / test / build green.
- `graphify update .` to refresh the knowledge graph.
- Update `docs/GLOSSARY.md` §13 touchpoint rows to ✅ with commit refs; refresh memory index.
