# Octopus — Multi-Language Code Impact Analysis Platform

> **Post-commit quality decisions: extract call chains from commits/tags, locate change impact scope, determine test boundaries, and provide traceable source-level evidence for requirement-aware LLM review and version-evolution tracking.**
>
> **Core design: A Language Adapter architecture decouples language analysis from the core engine. Each language needs only one Adapter to plug into the full analysis pipeline.**

---

## 0. Quick Reference

**Full stack in five minutes** (prerequisites in §5; `OCTOPUS_ENCRYPTION_KEY` must be 16/24/32 bytes):

```bash
docker compose -f docker-compose.infra.yml up -d    # MySQL + Neo4j + RabbitMQ
./run-jvm-analyzer-local.sh                         # JVM analyzer :8377 (slow first start)
./run-go-analyzer-local.sh                          # Go analyzer :8378 (seconds)
GIT_PASSWORD=dummy OCTOPUS_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef \
  ./gradlew :code-intelligence:bootRun              # backend :8080
cd octopus-frontend && npm install && npm run dev   # frontend :3000
```

**First analysis** — open <http://localhost:3000> and submit a local directory on the Analysis page, or:

```bash
curl -X POST http://localhost:8080/api/analysis/local \
  -H 'Content-Type: application/json" \
  -d '{"projectPath": "/path/to/your/repo"}'
```

**Page cheat sheet**:

| Page | Use |
|------|-----|
| `/change-summary` | entry + two versions (any branch/commit/tag combo) → change highlights + test points |
| `/review` | Jira ticket / document / manual requirements → structured review → follow-up chat |
| `/query/callgraph` | entry browsing (API first), call chains / up-downstream / impact paths |
| `/query/class`, `/query/endpoints` | class detail, entry list |
| `/admin` | models (API format + encrypted keys), prompts, skills, requirement sources (Jira) |
| `/analysis` | task status / steps / retry; multi-branch versions stay queryable |

**Before reviewing**: register at least one LLM model on the Admin page (Base URL / account / API key / API format — OpenAI-compatible or Claude).

**Ports**: 3000 frontend · 8080 backend · 8082 source reconstruct (git slicing) · 8083 review-agent · 8377 JVM analyzer · 8378 Go analyzer · 3306/7687/5672 MySQL/Neo4j/RabbitMQ

---

## 1. The Problem

Agent tools like Claude Code, Cursor, and Codex — plus tree-sitter based solutions (GitHub Code Navigation, ast-grep, editor symbol indexes) — already excel at extracting call relations and retrieving context **during coding**. Their shared boundary is **syntactic**: CST traversal and name matching — no type binding (overloads/interface dispatch are indistinguishable), no framework semantics, no versioned impact scope. Octopus does not re-solve that problem; it closes the loop they don't cover — engineering quality **after code is committed**:

| Problem | Risk | How Octopus Solves It |
|---------|------|-----------------------|
| **Only file diffs are visible** | Unknown which endpoints, services, or downstream dependents are affected | Enumerate upstream callers, downstream callees, and full affected paths by method/function nodes |
| **Syntactic indexing is imprecise** | tree-sitter/ctags match by name — overloads, interface dispatch, and polymorphic calls are indistinguishable | Type-accurate resolution via go/types and IntelliJ PSI; interface dispatch fanned out by the type system |
| **Test scope relies on intuition** | Small changes may miss critical paths; large changes over-test | Narrow or widen test scope based on call-chain impact paths with explainable evidence |
| **Framework-implicit relations are invisible** | DI, AOP, event routing, async scheduling break the call chain | Adapters register semantic extractors for each language's mainstream frameworks |
| **LLM Review lacks business context** | Reviewing isolated diffs misses requirement semantics | Resolve requirements to call paths, restore per-step source code for focused LLM review |
| **Version evolution is hard to trace** | Chain changes across tags/commits/branches require manual comparison | Graph snapshots per branch/commit; git-backed chain-member + source diff, with LLM-generated change & test highlights |

**In one sentence: Turn code changes into queryable, explainable, auditable impact scope and source evidence.**

---

## 2. Architecture

### Overview

```
┌──────────────────────────────────────────────────────────────────┐
│  octopus-frontend (React 19, Port 3000)                         │
│  Dashboard · Analysis · Change Summary · Review(+Follow-up)    │
│  Review History · Code Query(callgraph/class/entry) · Admin    │
└──────────────────────────┬───────────────────────────────────────┘
                           │ HTTP
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  code-intelligence (Spring Boot, Port 8080)                     │
│                                                                │
│  ┌─────────────────┐  ┌────────────────┐  ┌───────────────┐   │
│  │ Analysis         │  │ CallGraph      │  │ Query / Diff  │   │
│  │ Orchestration    │──│ Build Service  │──│ / Source      │   │
│  │ Service          │  │                │  │ Services      │   │
│  └────────┬─────────┘  └────────┬───────┘  └───────────────┘   │
│           │                     │                               │
│           ▼                     ▼                               │
│  ┌─────────────────────────────────────────────────────┐       │
│  │  AnalysisDispatcher — routes by build files           │       │
│  │  ┌──────────────────┐  ┌──────────────────┐          │       │
│  │  │ JVM Adapter      │  │ Go Adapter       │  ...     │       │
│  │  │ (deep PSI +      │  │ (precise         │          │       │
│  │  │  JavaParser fbk) │  │  go/types)       │          │       │
│  │  └────┬─────────────┘  └────┬─────────────┘          │       │
│  └───────┼─────────────────────┼─────────────────────────┘       │
│          │            │            │                            │
│          ▼            ▼            ▼                            │
│  ┌────────────────────────────────────────────────────┐        │
│  │  GitService · GraphStore (Neo4j) · MetadataStore  │        │
│  │  (MySQL) · ReconstructService                     │        │
│  └────────────────────────────────────────────────────┘        │
└──────────────────────────┬──────────────────────────────────────┘
          │                 │                 │
          ▼                 ▼                 ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────────┐
│ octopus-git   │  │   Neo4j      │  │   MySQL          │
│ (Port 8081)  │  │  Graph DB    │  │  Relational DB   │
└──────────────┘  └──────────────┘  └──────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  octopus-review-agent (AI Code Review, Port 8083)                │
│  Skill engine + structured review + follow-up chat + change      │
│  summary + Jira requirement source (SPI) + model management      │
│  (API format / AES-GCM encrypted API keys)                       │
└──────────────────────────────────────────────────────────────────┘
```

![Call Graph Visualization](images/image3.png)

### Modules

| Module | Responsibility | Port |
|--------|---------------|------|
| **code-intelligence** | Core backend — orchestration, Adapter dispatch, query API, call graph construction | 8080 |
| **jvm-project-analyzer** | JVM Adapter — IntelliJ PSI plugin (Java/Kotlin deep semantic analysis) | 8377 |
| **go-project-analyzer** | Go Adapter — go/packages + go/types call graph analysis service | 8378 |
| **octopus-git** | Git operations microservice | 8081 |
| **octopus-code-reconstruct** | Call-chain source restoration (git blob line slicing + batch coordinate fetch) | 8082 |
| **octopus-review-agent** | AI Code Review (Skill engine / follow-up chat / change summary / requirement source SPI) | 8083 |
| **octopus-analysis-scheduler** | Scheduled/batch analysis dispatch (RabbitMQ) | — |
| **octopus-infra/\*** | Shared infrastructure (bom/common/mysql/neo4j/jgit/memory) | — |
| **octopus-frontend** | React SPA | 3000 |

### Adapter Dispatch Flow

```
AnalysisOrchestrationService
         │
         ▼
AnalysisDispatcher.detectAnalyzer(projectRoot)   (dirs w/o build files fall back to JVM)
         │
         ├── pom.xml / build.gradle ──→ JVM Adapter (PSI preferred, JavaParser fallback)
         ├── go.mod                ──→ Go Adapter (go-project-analyzer service)
         └── ...                   ──→ Unregistered → skip / basic text scan
```

Each adapter returns results in a standard format, merged by `CallGraphBuildService` and written to Neo4j.

---

## 3. The JVM Adapter — Why PSI + JavaParser

The most mature Adapter implementation is the **JVM Adapter**, powered by a dual-engine design of IntelliJ PSI (deep) and JavaParser (lightweight).

### IntelliJ PSI (Program Structure Interface)

**PSI is IntelliJ IDEA's underlying parsing engine, serving as the JVM Adapter's "deep" analyzer.**

**Strengths:**

| Capability | Description |
|------------|-------------|
| **Semantic-level parsing** | Full symbol resolution (types, method binding, overload resolution, generics) — not just AST |
| **Framework awareness** | Recognizes `@Autowired`, `@EventListener`, `@Async`, `@Scheduled`, AOP aspects, etc. |
| **Cross-file/module resolution** | `resolveMethod()` pinpoints the actual definition, correctly resolving interface polymorphism |
| **Mixed languages** | Built-in Kotlin support for Java + Kotlin projects |

**Weaknesses:**

| Issue | Impact |
|-------|--------|
| Slow startup (30-120s), high memory (2-4 GB) | Unsuitable for batch, stateless parsing |
| Complex deployment | Requires ~1.1 GB IDEA download; Xvfb for headless display in containers |
| Per-project isolation | Each project opens and indexes independently; no state reuse |

### JavaParser (Lightweight Fallback)

Pure Java library, millisecond startup, no type system, no framework semantics. Automatic fallback when PSI is unavailable or deep semantics aren't needed.

**Dual-engine collaboration:**
1. **PSI first** — attempt PSI for deep analysis (call edges + Spring semantics)
2. **Auto fallback** — seamless degradation to JavaParser when PSI is unavailable
3. **JavaParser supplement** — even when PSI succeeds, JavaParser provides source text and precise location info
4. **Merge & persist** — both results are merged by `CallGraphBuildService` and written to Neo4j + MySQL

---

## 4. Workflow

### 4.1 Trigger Analysis

Trigger a task against a repo's branch, tag, or commit. The system dispatches by build file (`pom.xml`/`build.gradle` → JVM, `go.mod` → Go) and persists method nodes, call edges, source coordinates, and version info.

**One repo = one project; branches/commits are version dimensions**: after multi-branch analysis, data coexists under the `branch` column; the frontend branch/commit selectors are metadata-driven (`analyzed-branches` / `analyzed-commits`), and historical versions stay queryable.

### 4.2 Query Impact Scope

![API Endpoint Scan](images/image1.png)

Query the call chain around a changed method:
- **upstream** — which endpoints, services, or tasks call this method
- **downstream** — which downstream dependencies this method affects
- **affected paths** — the full path assembled from upstream to downstream

Used to determine which test entry points need regression: API endpoints, message listeners, scheduled tasks, service root methods, etc.

### 4.3 Source Restoration + LLM Review

![Code Review Interface](images/image2.png)

When a path is associated with a requirement, the source of each method along that path is restored by slicing git blobs by line number — narrowing the review context from "the entire repo" to "requirement-related call chain + original source + version diff". The LLM can then judge:

- Is the requirement implemented at the correct layer?
- Are validation, authorization, transactions, and exception handling consistent along the chain?
- Does the change break old behavior or miss downstream side effects?

**Requirement sources**: manual structured input / document upload with auto-parsing / **Jira ticket fetch** (`RequirementSourceAdapter` SPI, Jira built in — paste a ticket key to pull the main issue + subtasks as requirement items; other trackers plug in by implementing the interface).

**Follow-up chat (hybrid review)**: after the structured first pass, an input box allows follow-up questions grounded in the same chain context (e.g. "explain the second risk in detail"); issue cards have a one-click "ask about this" entry; Q&A persists per thread.

### 4.4 Change Summary (Cross-Version Diff)

A dedicated **Change Summary** page: one entry point + two versions (any branch/commit/tag combination, cross-branch supported). Under the hood, `entry-chain-diff` diffs the chain member sets of both versions, and **common members' source texts are re-sliced from git blobs via method_info coordinates** (git is the single source of truth for code content; `gitBacked` flags provenance). An LLM then produces structured **change highlights + test points** — deriving test scenarios and regression points from the diff.

---

## 5. Deployment & Quick Start

### Prerequisites

- Java 21+
- Docker or Podman (any compose-compatible runtime; examples use docker)
- Node.js 20+
- Git
- Go >= 1.24 (only for analyzing Go projects)

### Start Infrastructure (MySQL + Neo4j + RabbitMQ)

```bash
# One-shot (recommended, with healthchecks)
docker compose -f docker-compose.infra.yml up -d

# Or start individually (note: Neo4j password octpus123 matches the app default, historical spelling)
docker run -d --name mysql-octopus \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=octopus \
  -p 3306:3306 mysql:8.0

docker run -d --name neo4j-octopus \
  -e NEO4J_AUTH=neo4j/octpus123 \
  -p 7474:7474 -p 7687:7687 neo4j:5

docker run -d --name rabbitmq-octopus -p 5672:5672 -p 15672:15672 rabbitmq:3
```

### Start Git Microservice & Frontend

```bash
docker compose up -d git frontend
```

### Start PSI Analysis Service (JVM Adapter)

```bash
# macOS (first run downloads IntelliJ IDEA IU 2025.3, ~1.1 GB)
./run-jvm-analyzer-local.sh
```

PSI service listens on `localhost:8377`.

### Start the Go Analysis Service (Go Adapter)

```bash
./run-go-analyzer-local.sh   # requires local go >= 1.24

# Custom router framework whitelist (framework profile: gin/echo/chi/gorilla-mux built in)
OCTOPUS_GO_ROUTER_PKGS="github.com/gofiber/fiber/v2,github.com/beego/beego/v2" ./run-go-analyzer-local.sh
# or: go-analyzer serve --routers pkg1,pkg2
```

Go service listens on `localhost:8378` — millisecond startup, second-scale analysis (go/types type checking + interface fan-out + route registration detection with route-group prefix support). Without it, Go analysis tasks degrade entirely (DEGRADED; no fallback parser).

### Start the App

```bash
# Terminal 1: backend
./gradlew :code-intelligence:bootRun

# Terminal 2: frontend dev mode
cd octopus-frontend && npm run dev
```

### Trigger an Analysis

```bash
# Analyze a local directory (no Git repo needed)
curl -X POST http://localhost:8080/api/analysis/local \
  -H "Content-Type: application/json" \
  -d '{"projectPath": "/path/to/your/project"}'

# Analyze a Git repo tag
curl -X POST "http://localhost:8080/api/analysis/1?tag=v1.0.0"
```

### Docker Deployment

Hybrid mode (Git service + frontend in Docker; backend + PSI on host):

```bash
docker compose up -d
```

Full container mode:

```bash
docker compose -f docker-compose.yml -f docker-compose.jvm-analyzer.yml up -d
```

### Key Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MYSQL_PASSWORD` | `root` | MySQL password |
| `NEO4J_PASSWORD` | `octpus123` | Neo4j password |
| `GIT_PASSWORD` | — | Git server authentication password |
| `OCTOPUS_ENCRYPTION_KEY` | — | AES-GCM key (Git credentials + review-agent model API keys at rest; must stay stable — rotating makes stored ciphertext undecryptable) |
| `OCTOPUS_GO_ROUTER_PKGS` | built-in default | Go router framework package whitelist (comma-separated, framework profile) |
| `OCTOPUS_REPO_ROOT` | `/data/repos` | Repository storage root — shared at the same path by every repo-reading service (git/PSI/Go/reconstruct; single writer = GitService, others read-only); point it at a writable dir on macOS |
| `QUERY_SERVICE_URL` | `http://localhost:8080` | review-agent → code-intelligence base URL (change summary fetches chain diff) |

---

## 6. Key Design Trade-offs

Architecture docs record conclusions; this section records the **why** — the other side of each decision and what accepting it buys.

| Decision | Alternative | Cost Accepted | Benefit Gained |
|----------|-------------|---------------|----------------|
| **PSI + JavaParser dual engine** | Keep only one | Merging complexity; fallback loses framework semantics (interface dispatch / Spring DI edges) | Deep semantics plus the resilience of "runs even when the service is down" |
| **Go via sidecar (go/types)** | Embedded tree-sitter-style parsing | One more process to operate | Official type system: precise resolution; interface fan-out / generics / embedding for free; ms startup, s-scale analysis |
| **Go interface fan-out (all implementations via types.Implements)** | Declared-type edges only | Conservative approximation: graph may include branches never taken at runtime | Zero-false-negative chains (review prefers over- over under-reporting); no Spring-style registry exists to consult |
| **Go router whitelist (`--routers`)** | Match any `X.GET(path, h)` shape | Frameworks outside the whitelist go unrecognized | Avoids false positives (GET can be a business method); the framework profile collapses to one configurable list |
| **git-only source content** | Keep the method_info.source_code full-text column | git-less local projects fall back to analysis snapshots (`gitBacked=false`); depends on the reconstruct service | Single source of truth, version-traceable; DB doesn't bloat with per-version full text |
| **Line coordinates + git slicing** | Re-parse at read time to locate methods | Silent risk of bad coordinates (PSI -1/null crashes fixed; defensive collection is mandatory) | Language-neutral zero-parse reads; O(1) exact slicing |
| **One project per repo, branches as versions** | One project row per branch | analyzed_project.branch reflects only the latest analysis | Project list doesn't bloat; version enumeration is metadata-driven |
| **PSI as a resident HTTP service** | Spawn one-shot subprocess per analysis | Resident process to operate, 2-4 GB resident memory | The 30-120s platform startup amortizes across analyses; manageable via Docker healthchecks |
| **Single PSI instance with single-flight, no pool yet** | Parallel instance pool | Analysis tasks serialize (minutes per request) | One copy of ops and memory cost; scale out when throughput demands (in-process concurrency is an IntelliJ platform constraint — never an option) |
| **Review context pre-fetched deterministically by the pipeline** | LLM function-calling tool loop | First pass cannot explore beyond the chain on demand (follow-up chat partially compensates) | Reproducible, no tool-call hallucination, single-round-trip latency and cost — "the pipeline retrieves, the LLM judges" |
| **Graph contains workspace method nodes only** | Placeholder nodes for external callees | Calls into stdlib/third-party libraries are invisible in chains | Bounded graph size, no placeholder noise; simpler cross-module MATCH and version filtering |

---

## 7. Roadmap

- **TypeScript / Python Adapters**: Follow the Go Adapter's sidecar pattern (native toolchain + HTTP contract); Python will face both dynamic typing and framework decorators
- **PSI Throughput Governance**: Upstream concurrency limiting → instance pool scale-out (single-flight is an IntelliJ platform constraint) → open-project caching to amortize indexing
- **Framework Profiles**: Pluggable profiles for JVM (Quarkus/Micronaut CDI semantics); Go already done via the `--routers` whitelist
- **review-agent Data Access Convergence**: Migrate LookupController's direct MySQL reads to code-intelligence API calls, removing cross-service schema coupling
- **Runtime Strategy Pattern Resolution**: Eliminate impossible candidate edges for patterns like `Map<String, Strategy>`, strategy registries, config-driven routing
- **Review Agent Observability**: Review session duration distribution, token consumption, Skill execution traces
- **CI/CD Native Integration**: Standardized GitHub Actions / GitLab CI pipeline — commit triggers analysis triggers feedback

---

## 8. Extension Guide

### 8.1 Language Adapter (ProjectAnalyzer SPI)

The core engine is language-agnostic; the integration point is one interface + Spring component scanning (**reference implementation: GoProjectAnalyzer — newest and simplest**):

```java
public interface ProjectAnalyzer {
    String getLanguage();                    // e.g. "GO"
    boolean supports(Path projectRoot);      // build-file probing (go.mod / pom.xml...), non-recursive
    default int getPriority() { return 0; }  // highest wins when multiple support the path
    List<DiscoveredProject> scanProjects(RepoSnapshot snapshot);
    DiscoveredProject scanSingleProject(Path root, String name);
    /** Deep pipeline (parse → graph build → store); returns degraded module names */
    List<String> analyzeDeep(AnalysisContext ctx, List<DiscoveredProject> projects, RepoSnapshot snapshot);
}
```

Annotate the implementation with `@Service` and `AnalysisDispatcher` discovers it automatically (routes by `supports()` + priority; dirs without build files fall back to JVM). Storage conventions: the Neo4j `MethodNode`/`CALLS` schema is language-neutral (any globally unique `signature` works); MySQL metadata flows through the existing `MetadataStoreService.storeMetadata(ParseResult)` pipeline (versioned is_head/commit semantics come for free).

### Current Adapter Status

| Adapter | Engine | Status | Capability |
|---------|--------|--------|------------|
| **JVM** | IntelliJ PSI + JavaParser | ✅ Production | Full semantics, Spring framework understanding (DI/AOP/events/scheduling), dual-engine auto-fallback |
| **Go** | go/packages + go/types | ✅ Production | Type-accurate call graph, interface fan-out, route detection (group prefixes, configurable framework whitelist) |
| **TypeScript** | ts-morph | ⏳ Planned | Sidecar process, AST + type info |
| **Python** | Jedi / LSP | ⏳ Planned | LSP protocol integration |

**Sidecar contract** (the path validated by the Go Adapter): long-running HTTP service (`/health` + `/analyze` + single-flight mutex) + a Java-side Executor consumer (retry/timeout/degradation). The heavier the language stack (IDE kernels), the better suited to externalization; languages whose official type system ships as a library (Go) start in milliseconds and analyze in seconds.

### Four Steps to Add a New Language

**Step 1: Choose the Runtime Mode**

| Mode | Use Case | Example |
|------|----------|---------|
| **Embedded library** | Language has a JVM library | JavaParser (pure Java) |
| **Sidecar process** | Requires native toolchain | TypeScript (ts-morph via Node.js), Python (Jedi via Python process) |
| **External HTTP service** | Existing analysis service | IntelliJ PSI (standalone HTTP service) |

**Step 2: Implement `ProjectAnalyzer`** — `supports()` probes build files; `scanProjects()` discovers modules; `analyzeDeep()` runs the deep pipeline and returns degraded module names.

**Step 3: Register Framework Semantic Extractors**

Every language's mainstream framework has implicit call relations (route registration, DI, event bus, AOP). Model your extractor after `SpringModelExtractor`:

| Framework | Implicit Relations | Existing Mechanism |
|-----------|-------------------|--------------------|
| Spring (Java) | `@Autowired`, `@EventListener`, AOP, `@Scheduled` | SPRING_DI / EVENT_PUBLISH / AOP_* / SCHEDULED edges |
| gin/echo/net-http (Go) | route registration, group prefixes | httpRoutes → HttpRoute synthetic annotation (entry shows the API path) |
| NestJS (TS) | `@Controller`, `@Injectable`, `@EventPattern` | To integrate (reuse SPRING_DI / EVENT_PUBLISH) |
| Django/FastAPI (Python) | `urlpatterns` / route decorator | To integrate (follow Go's synthetic-annotation pattern) |

**Step 4: Registration — `@Service` + component scan is all it takes** (no config file); the orchestrator's `AnalysisDispatcher.detectAnalyzer()` routes automatically.

### 8.2 Requirement Source SPI (RequirementSourceAdapter)

Review requirement sources are a first-class abstraction too — Jira is built in (Basic auth, main issue + subtasks split into items); other trackers (Feishu docs / TAPD / ZenTao...) plug in by implementing one interface:

```java
public interface RequirementSourceAdapter {
    String sourceType();               // "jira"
    String displayName();              // "Jira"
    List<RequirementItem> fetch(String ticketKey, RequirementSourceConfig config);
}
```

Implement + register with `@Component` and it appears automatically in the Review page source selector and the Admin page (connection config, masked secrets). The review chain consumes only `RequirementItem` — fully source-agnostic.

### Reference: TypeScript Adapter Design

```
code-intelligence                              Node.js Sidecar
┌──────────────────────┐     HTTP/stdio        ┌──────────────────────┐
│  ProjectAnalyzer     │ ──────────────────→   │  ts-morph /          │
│  (Java, impl)        │                       │  @typescript-estree  │
│                      │ ←──────────────────   │                      │
│                      │   JSON result          │  Route extractor    │
└──────────────────────┘                       │  (NestJS/Express)   │
                                               └──────────────────────┘
```

Sidecar output format (illustrative; **the Go Adapter's `GoAnalysisOutput` is the live reference** — four sections `types/methods/edges/httpRoutes`, signatures generated solely by the analyzer for guaranteed consistency):

```json
{
  "nodes": [
    { "id": "123", "file": "src/controller/UserController.ts",
      "className": "UserController", "methodName": "getUser",
      "parameters": ["id: string"], "returnType": "Promise<User>" }
  ],
  "edges": [
    { "source": "123", "target": "456",
      "type": "DIRECT", "sourceLine": 25,
      "sourceSnippet": "return this.userService.findById(id)" }
  ],
  "frameworkEdges": [
    { "route": "GET /api/users/:id", "handler": "123",
      "type": "ROUTE" }
  ],
  "sourceFiles": {
    "src/controller/UserController.ts": "full source text..."
  }
}
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Runtime** | Java 21, Spring Boot 4.0.5 |
| **Build** | Gradle 9.4.1 (multi-module) |
| **Relational DB** | MySQL 8.0 |
| **Graph DB** | Neo4j 5.x |
| **JVM Deep Parser** | IntelliJ Platform IU 2025.3 (PSI) |
| **JVM Lightweight Parser** | JavaParser 3.26.4 |
| **Go Parser** | golang.org/x/tools (go/packages + go/types) |
| **Git** | JGit 7.6.0 |
| **Frontend** | React 19, TypeScript 6, Vite 8, Ant Design 5 |
| **Graph Visualization** | @xyflow/react 12.x, dagre |
| **Containerization** | Docker, Docker Compose |

---

## License

[MIT License](LICENSE)
