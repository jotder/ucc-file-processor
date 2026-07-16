# Geo Map Analysis — case-study pack (CS1–CS5)

Five boundary-pushing, self-contained investigation scenarios for the Geo Map studio
(`/studio/geo-map`). Each is a **deterministic generated dataset** (LCG-seeded in
`inspecto-ui/src/app/modules/admin/studio/datasets/dataset-sources.ts` — identical on every build)
plus a **seeded Dataset + saved Geo View** (`default-space.seed.ts`), one click away under *Saved
views*. Invariants are pinned by `studio/geo-map/geo-case-studies.spec.ts`, so future edits can't
silently defuse what a case study exists to exercise.

> Studio plan (archived): [`geo-map-analysis-plan.md`](../archived-documents/plans-archive/geo-map-analysis-plan.md) · review sheet:
> [`reviews/geo-map-studio.md`](reviews/geo-map-studio.md)

| # | Saved view | Dataset (rows) | The boundary it pushes |
|---|---|---|---|
| CS1 | SIM-box farms (stress: 5.6k events) | `simbox_sweep` (5,665) | The **5,000-point cap** (Truncated banner) + **25 broken rows** (skip banner) + analysis at scale |
| CS2 | Impossible travel | `impossible_travel` (~111) | Time-reasoning: one physically impossible hop hidden in normal noise |
| CS3 | Mule corridors (900 legs → 24 routes) | `mule_corridors` (906) | **Weight folding** at scale + global great-circle rendering + 4 route kinds |
| CS4 | Fleet dwell audit (24h breadcrumbs) | `fleet_breadcrumbs` (576) | Dense per-entity tracks → **stay-point** & **frequent-location** precision |
| CS5 | Border roamers (heatmap) | `border_roamers` (706) | **Heatmap** density + region filter + staged **co-location** meetings; opens with a saved camera + heatmap display |

## CS1 — SIM-box farms *(telecom fraud, stress test)*

Three static SIM farms (40 SIMs × 12 calls each) hidden among 350 roaming subscribers around
Dhaka. **What it proves:** the studio stays honest and responsive past its limits — the projection
truncates at 5,000 points *with a banner*, the 25 leading broken rows surface in the skip banner,
and clustering keeps the canvas readable.
**Drive it:** type-filter to `simbox` → three tight clusters pop out. *Stay points* (radius 100 m)
fires on every farm SIM. Co-location on the full 5k is the worst case for the O(n²) pure fn —
filter first (kind or time window), which is itself the lesson.

## CS2 — Impossible travel *(account security)*

Ten accounts log in around their home cities all day; `ACC-007` logs in from **New York at 13:00
and Singapore at 14:05** (≈15,300 km in 65 min ≈ Mach 12). The spec pins that ACC-007 is the
*only* account whose consecutive logins exceed 1,800 km/h.
**Drive it:** search `ACC-007` (its three logins glow, everything else dims) → press **Play** on
the timeline and watch the dot teleport continents mid-sweep. Click each login for its detail
sheet; the *Nearby* rows show nothing plausible in between.

## CS3 — Mule corridors *(AML / revenue leakage)*

A week of 901 origin→destination money movements over 18 world cities across 4 channels
(`hundi`/`wire`/`crypto`/`cash`), folding into **24 weighted corridors** from weight 3 up to 150
(Dhaka→Dubai hundi). Five broken legs exercise the skip banner on the routes plane.
**Drive it:** the corridor widths tell the story at a glance — click the thickest for distance +
movement count, time-slide the week, and follow the arrows: everything drains toward the
cash-out hubs.

## CS4 — Fleet dwell audit *(logistics / supply chain)*

Six trucks ping GPS every 15 minutes for 24 hours between four Bangladeshi depots. Trucks
**TRK-02 and TRK-05 take unscheduled ~1 h roadside stops** mid-route; every truck dwells at its
depots. Status column kinds the pings (`moving`/`idle`).
**Drive it:** *Stay points* (radius 300 m, dwell 45 min) → ~20 dwells; the two roadside stops are
the short ones at mid-route coordinates. *Frequent locations* finds every truck's depots. **Play**
runs the whole day like CCTV.

## CS5 — Border roamers *(border security / roaming abuse)*

Twelve devices oscillate across a border strip (three crossing points) for three days — plus
**staged meetings**: IMEI-B01↔IMEI-B07 meet three times at crossing 1; IMEI-B03↔IMEI-B11 twice at
crossing 2. The saved view opens **as a heatmap with a saved camera** over the strip (proving
display-mode + camera persistence).
**Drive it:** the heatmap shows the three crossing hotspots instantly. Switch back to markers,
*Co-location* (300 m / 30 min) → the two staged pairs top the list; **View as graph** hands the
pairs to the shared G6 renderer. *Filter to view* pins the investigation to the strip.

## Testing & verification

- **Unit invariants:** `geo-case-studies.spec.ts` — cap+skip counts (CS1), the unique >1,800 km/h
  account (CS2), fold counts/weights/kinds (CS3), staged dwell & depot detection (CS4), staged
  co-location pairs (CS5).
- **Look & feel:** every view is one click under *Saved views* and must load with its banners,
  timeline, and (CS5) heatmap+camera intact — verified live in the review sheet.
- Regenerating: the generators are code — edit `dataset-sources.ts`, keep the spec invariants
  green (they are the contract for what each case study must keep exercising).
