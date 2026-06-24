# Contributing to Octopus

Thank you for considering contributing to Octopus! We welcome contributions of all kinds, including bug fixes, feature additions, documentation improvements, and issue reports.

## Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How to Contribute

### 1. Reporting Bugs

- Search [existing issues](https://github.com/your-org/octopus/issues) first to avoid duplicates.
- Use the **Bug Report** template when creating a new issue.
- Include: OS / Java version, steps to reproduce, expected vs actual behavior, and relevant logs.

### 2. Suggesting Features

- Use the **Feature Request** template.
- Describe the problem you're solving and the proposed solution.
- Explain how the feature integrates with the existing architecture.

### 3. Pull Requests

1. **Fork** the repository and create a branch from `main`.
2. **Set up your development environment** (see [README](README.md#快速开始本地开发)).
3. **Make your changes**, keeping them focused and well-scoped.
4. **Write or update tests** as needed.
5. **Ensure the build passes**:
   ```bash
   ./gradlew compileJava
   cd octopus-frontend && npm run build
   ```
6. **Run tests**:
   ```bash
   ./gradlew test -x :jvm-project-analyzer:test
   ```
7. **Submit a pull request** using the PR template.

## Development Guidelines

### Code Style

- **Java**: Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
  - Use a consistent indentation of 4 spaces.
  - Use `@Builder`, `@Getter`, `@Setter` (Lombok) to reduce boilerplate.
  - Prefer immutable objects and Java `record` types where applicable.
  - Use constructor injection for Spring Beans.
  - Log using SLF4J `Logger` interface.
- **TypeScript / React**:
  - Use functional components with hooks.
  - Follow the existing project structure and naming conventions.

### Commit Messages

```
<type>: <short summary>

<optional body>
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `ci`, `style`.

### Module Structure

| Module | Responsibility | Port |
|--------|---------------|------|
| `code-intelligence` | Core backend — analysis orchestration, query, API | 8080 |
| `jvm-project-analyzer` | IntelliJ PSI plugin — deep code analysis | 8377 |
| `octopus-git` | Git operations microservice | 8081 |
| `octopus-code-reconstruct` | Code reconstruction for call-chain source retrieval | 8082 |
| `octopus-review-agent` | AI-powered Code Review agent | 8083 |
| `octopus-infra/*` | Shared infrastructure (common, memory, MySQL, Neo4j, JGit) | — |
| `octopus-frontend` | React SPA | 3000 |

### Testing

- Backend: JUnit Jupiter + Mockito
- Frontend: (planned — Vitest)
- PSI tests require an IntelliJ SDK environment and are excluded from CI.

## Questions

If you have questions, feel free to open a [Discussion](https://github.com/your-org/octopus/discussions) or submit an issue.
