# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in Octopus, please report it privately.

**Do not open a public issue.** Instead, send a description of the vulnerability to [INSERT EMAIL OR CONTACT METHOD].

Please include:

- Type of issue (e.g., SQL injection, XSS, privilege escalation)
- Affected component and version
- Steps to reproduce
- Potential impact

You should receive a response within 48 hours. If the issue is confirmed, a fix will be prepared and released as soon as possible, at which point the vulnerability will be publicly disclosed.

## Scope

The following are considered in scope:

- The code-intelligence, octopus-review-agent, octopus-git, and octopus-code-reconstruct services
- The jvm-project-analyzer PSI plugin
- The octopus-frontend React application
- The octopus-infra shared libraries

## Out of Scope

- Third-party dependencies — report their vulnerabilities upstream
- Development-only configurations (e.g., default development passwords)

## Best Practices for Users

1. **Always change default passwords** in production: MySQL, Neo4j, and the application encryption key.
2. **Set environment variables** `MYSQL_PASSWORD`, `NEO4J_PASSWORD`, `GIT_PASSWORD`, and `OCTOPUS_ENCRYPTION_KEY` instead of using defaults.
3. **Restrict network access** to the internal microservice ports (8080-8083, 8377) in production.
4. **Use secret management** for storing API keys (LLM providers, Git credentials).
