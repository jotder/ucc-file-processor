# Ticketing Systems Requirement

## Philosophy

The platform shall be designed as an **Operational Intelligence
Platform** rather than a collection of disconnected tools.

Users do not think in terms of Events, Alerts, Issues, Cases, and Tasks.
They think in terms of: - What happened? - Should I care? - What is
broken? - How do we investigate? - Who is doing what? - How do we
prevent recurrence?

The product should support this operational journey.

Observe → Detect → Prioritize → Investigate → Act → Learn

------------------------------------------------------------------------

## Design Intent

### Objectives

-   Eliminate dependence on external operational log tools where
    feasible.
-   Use structured events instead of log parsing.
-   Maximize feature reuse across products.
-   Minimize duplicated development effort.
-   Allow incremental evolution through maturity phases.
-   Support enterprise-grade operations and investigations.

### Core Principles

-   Event-first architecture.
-   Append-only immutable facts.
-   Human-managed operational objects.
-   Configuration over duplication.
-   Investigation-centric UX.
-   Enterprise security and auditability.
-   Extensibility and backward compatibility.

------------------------------------------------------------------------

## Conceptual Model

### Immutable Facts

EVENT

Represents something that happened.

Examples: - Job Started - Job Failed - File Received - Rule Fired

Characteristics: - Append-only - Structured - High-volume - Never
modified

### Operational Objects

-   ALERT
-   ISSUE
-   CASE
-   TASK

These are human-managed entities.

Common attributes:

-   ID
-   Title
-   Description
-   Status
-   Severity
-   Priority
-   Owner
-   Assignee
-   Tags
-   Created Date
-   Updated Date
-   Closed Date

------------------------------------------------------------------------

## Relationship Model

Event ↓ Alert ↓ Issue ↓ Case ↓ Task

Not every Event becomes an Alert. Not every Alert becomes an Issue. Not
every Issue becomes a Case.

This funnel prevents operational overload.

------------------------------------------------------------------------

## Platform Services

Reusable capabilities:

-   Search Engine
-   Filtering Engine
-   Saved Views
-   Bookmarks
-   Comments
-   Attachments
-   Audit Trail
-   Notifications
-   Workflow Engine
-   Correlation Engine
-   Security Engine
-   Export Services
-   Activity Feed
-   Subscription Management
-   Real-time Updates

------------------------------------------------------------------------

## Workflow Philosophy

Here's how I think about them.

```markdown
 | Aspect                 | To-Do                           | Alert                                   | Issue                               | Case                                                              |
 | :--------------------- | :------------------------------ | :-------------------------------------- | :---------------------------------- | :---------------------------------------------------------------- |
 | **Purpose**            | Personal/team action reminder   | Notify about an event requiring attention | Track a problem or defect           | Manage an investigation or business process                       |
 | **Trigger**            | Human-created                   | System-generated                        | Human or system identified          | Usually created from alerts/issues                                |
 | **Represents**         | Work to be done                 | Something happened                      | Something is wrong                  | A situation requiring resolution                                  |
 | **Lifecycle**          | Open → Done                     | Open → Acknowledged → Cleared           | Open → Assigned → Resolved → Closed | Open → Investigating → Escalated → Resolved → Closed              |
 | **Ownership**          | Individual/team                 | Initially unassigned                    | Assigned owner                      | Investigation team                                                |
 | **Time Sensitivity**   | Flexible                        | Usually immediate                       | Medium                              | Medium to long                                                    |
 | **Root Cause Analysis**| Rarely                          | No                                      | Sometimes                           | Yes                                                               |
 | **Evidence Collection**| No                              | Minimal                                 | Some                                | Extensive                                                         |
 | **Collaboration**      | Low                             | Low                                     | Medium                              | High                                                              |
 | **External Communication**| Rare                         | Rare                                    | Sometimes                           | Often                                                             |
 | **Example**            | "Update mapping document"       | "Job failed"                            | "ETL job repeatedly failing"        | "Investigate billing discrepancies affecting 500 customers"       |
 ```

Different object types use the same workflow engine.

Alert: OPEN → ACKNOWLEDGED → RESOLVED

Issue: OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED

Case: OPEN → INVESTIGATING → ESCALATED → RESOLVED → CLOSED

Task: TODO → DOING → DONE

------------------------------------------------------------------------

## UI Guidelines

The experience should resemble Microsoft Event Viewer.

Characteristics:

-   Dense information display
-   Investigation-oriented workflows
-   Minimal clicks
-   Keyboard friendly
-   Fast filtering
-   Multi-pane layout
-   Reusable components

Reusable UI:

-   Navigation Tree
-   Explorer Grid
-   Detail Pane
-   Timeline
-   Related Objects
-   Comments
-   Attachments

# Operational Intelligence Platform Architecture
*Platform First, Products Second*

I think this is exactly the right time to think about it. Most teams get this wrong.
They build:

1. **Event Viewer v1**
2. Then **Alert Center**
3. Then **Issue Tracker**
4. Then **Case Management**
5. Then **Task Manager**

...and end up with 5 applications sharing 80% of the same code but duplicated everywhere.

What you're observing is correct: Technically they are very similar. The differences are mostly in user intent, lifecycle, and presentation. The trick is to design the platform around a common **"Operational Object"** model rather than around screens.

## Think Platform First, Products Second

**Don't build:**
* Event Module
* Alert Module
* Issue Module
* Case Module
* Task Module

**Build:**
`Operational Intelligence Platform` → `Common Platform Capabilities` → `Specialized Products`

---

## What is common?

I usually divide it like this:

### Layer 1: Immutable Facts
These are things that happened.
**`EVENT`**

* **Examples:** Job Started, Job Failed, Rule Fired, File Received, User Logged In
* **Properties:** * Append only
    * Never modified
    * High volume
    * Structured

### Layer 2: Operational Objects
These are things humans manage.
**`ALERT`** | **`ISSUE`** | **`CASE`** | **`TASK`**

* **Common properties:** `id`, `title`, `description`, `status`, `severity`, `priority`, `assignee`, `owner`, `tags`, `createdAt`, `updatedAt`, `closedAt`, `comments`, `attachments`, `watchers`, `customFields`

*They differ only in lifecycle.*

### Layer 3: Investigation Features
These are reusable. Almost everything below can be shared:
* Search, Filtering, Saved Views, Bookmarks
* Comments, Attachments, Notes, Mentions
* Audit Trail, Timeline, Activity Feed
* RBAC, Notifications, Subscriptions
* Export, API, Dashboard Widgets

These become **platform services**.

---

## The Real Architecture

Instead of `EventViewerService`, `AlertService`, `IssueService`, `CaseService`, `TaskService`...

Think:
**Operational Platform**
 * ├── Event Engine
 * ├── Object Engine
 * ├── Search Engine
 * ├── Workflow Engine
 * ├── Notification Engine
 * ├── Rule Engine
 * ├── Correlation Engine
 * ├── Security Engine
 * └── UI Framework

### Event Engine
**Responsible for:** Append Event, Partitioning, Retention, Query, Streaming, Correlation IDs, Replay.
**Used by:** Viewer, Alerts, Rules, Investigations, Analytics.

### Object Engine
*This is the secret sauce.* Instead of separate tables for ALERT, ISSUE, CASE, and TASK, use one **`OPERATIONAL_OBJECT`** table.

| id | type |
|---|---|
| 1 | ALERT |
| 2 | ISSUE |
| 3 | CASE |
| 4 | TASK |

**Common columns:** `id`, `object_type`, `title`, `description`, `status`, `severity`, `priority`, `owner`, `assignee`, `created_at`, `updated_at`, `closed_at`

**Extensions:** `OBJECT_COMMENT`, `OBJECT_ATTACHMENT`, `OBJECT_TAG`, `OBJECT_LINK`, `OBJECT_ACTIVITY`, `OBJECT_CUSTOM_FIELD`

Then you can link them:
* `Case` ↔ `Issue`
* `Issue` ↔ `Alert`
* `Case` ↔ `Task`
* `Alert` ↔ `Event`

### Workflow Engine
This is where they differ. Same engine, different configuration.

* **Alert:** `OPEN` → `ACKNOWLEDGED` → `RESOLVED`
* **Issue:** `OPEN` → `ASSIGNED` → `IN_PROGRESS` → `RESOLVED` → `CLOSED`
* **Case:** `OPEN` → `INVESTIGATING` → `ESCALATED` → `RESOLVED` → `CLOSED`
* **Task:** `TODO` → `DOING` → `DONE`

---

## UI Strategy

This is where most reuse happens. Instead of specific pages (Alert Page, Issue Page, etc.), build **reusable components**.

### Object Explorer
Microsoft Event Viewer style:
`Navigation` → `Grid` → `Details Pane`
*(Works for everything)*

### Generic Grid
**Reusable:** Columns, Filters, Sorting, Grouping, Export, Saved Views, Bookmarks.
**Used by:** Events, Alerts, Issues, Cases, Tasks.

### Generic Detail Panel
**Tabs:** General, Timeline, Comments, Attachments, Links, History, Related Objects.
*(Only certain tabs appear based on object type).*

---

## Correlation Engine

Shared by everything.

**Example chain:** `Event` → `Alert` → `Issue` → `Case` → `Tasks`
**Example hierarchy:**
* `Case`
    * ├─ `Issue`
    * ├─ `Issue`
    * └─ `Task`
* `Issue`
    * ├─ `Alert`
    * └─ `Event`

All implemented as **`OBJECT_LINK`**:
`from_object` | `from_type` | `to_object` | `to_type` | `relationship`

**Relationships:** `CAUSED_BY`, `RELATES_TO`, `CONTAINS`, `DUPLICATES`, `ESCALATED_FROM`, `CHILD_OF`, `PARENT_OF`

---

## Product Packaging

From a business perspective, you can expose them as separate products.

### Phase 1: Operational Event Viewer
* **Offers:** Event ingestion, Event search, Live tail, Correlation IDs, Saved views, Event details
* **Equivalent to:** Event Viewer + Kibana Discover

### Phase 2: Operational Alerting
* **Adds:** Alert rules, Notifications, Alert lifecycle, Acknowledgement
* **Equivalent to:** Grafana Alerting

### Phase 3: Issue Management
* **Adds:** Ownership, Priorities, SLA, Escalation
* **Equivalent to:** Lightweight Jira

### Phase 4: Case Management
* **Adds:** Investigation workspace, Evidence collection, Collaboration, RCA templates
* **Equivalent to:** ServiceNow Investigations / Fraud Investigation tools

### Phase 5: Operational Intelligence
* **Adds:** AI RCA, Similar incident detection, Recommendations, Predictive alerting
* **Equivalent to:** AIOps

---

## What should the product offer?

I would market it as **one product with maturity levels**:

**Operational Intelligence Platform**
├─ Event Explorer
├─ Alert Center
├─ Issue Tracker
├─ Investigation Hub
└─ Automation & Intelligence

Because **customers buy outcomes, not entities.** Operations teams don't wake up saying: *"I need an Issue."*
They say: *"I need to know what happened, whether I should care, who's handling it, and how to resolve it."*

Your platform should support that journey:
`Observe` → `Detect` → `Prioritize` → `Investigate` → `Act` → `Learn`

If you design around this lifecycle, you can probably achieve **70–90% code reuse**, while still allowing each capability to evolve into a full-featured product later without major redesign.


------------------------------------------------------------------------

# Five Phase Product Roadmap


## Phase 1 -- Operational Event Viewer

Purpose: Understand what happened.

Features: - Event ingestion - Structured event storage - Live event
streams - Event search - Filtering - Saved views - Event details -
Correlation IDs - Export

Equivalent: Event Viewer + Log Discovery

------------------------------------------------------------------------

## Phase 2 -- Alert Center

Purpose: Determine what requires attention.

Features: - Alert rules - Threshold alerts - Missing event detection -
Alert lifecycle - Alert acknowledgement - Notifications - Escalation

Equivalent: Operational alerting systems

------------------------------------------------------------------------

## Phase 3 -- Issue Tracker

Purpose: Manage operational problems.

Features: - Issue creation - Ownership - Assignment - Priority
management - SLA tracking - Status workflows - Impact assessment

Equivalent: Lightweight operational Jira

------------------------------------------------------------------------

## Phase 4 -- Case Management

Purpose: Support investigations.

Features: - Investigation workspaces - Evidence collection - Notes -
Collaboration - Root cause documentation - Incident handoff - RCA
templates - Linked objects

Equivalent: Service management investigations

------------------------------------------------------------------------

## Phase 5 -- Operational Intelligence

Purpose: Learn and improve.

Features: - AI-assisted RCA - Similar incident detection - Event
clustering - Predictive alerting - Recommendations - Natural language
queries - Event replay and simulation

Equivalent: AIOps capabilities

------------------------------------------------------------------------

## Development Guidelines

-   Design platform services first.
-   Reuse engines and components.
-   Avoid feature duplication.
-   Separate immutable facts from operational workflows.
-   Prefer configuration over custom code.
-   Treat correlation as a first-class capability.
-   Build for phased adoption.

------------------------------------------------------------------------

## Success Criteria

The platform should enable teams to:

-   Observe operations effectively.
-   Detect problems early.
-   Prioritize the right work.
-   Investigate efficiently.
-   Coordinate responses.
-   Capture institutional knowledge.
-   Continuously improve operational resilience.
