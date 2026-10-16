# Security Policy

## Supported versions

Single-branch project (no maintained release lines): security fixes land on `main` only. There is no
LTS or backport policy.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for a suspected vulnerability. Use
[GitHub's private vulnerability reporting](../../security/advisories/new) for this repository
(Security tab, "Report a vulnerability"), or contact the maintainer through the email on the
[GitHub profile](https://github.com/ronybrand).

Include, where applicable: a description and the potential impact, steps to reproduce (a minimal
request is ideal), and the affected endpoint or component.

The project is maintained on a best-effort basis (no SLA), but reports are taken seriously.

## Scope

In scope: the code in this repository, the notification worker, and the Dockerfile and CI workflows that
build and ship them.

Out of scope: third-party services used for local development (PostgreSQL, RabbitMQ, Mailpit); report
those upstream. Dependency vulnerabilities are tracked by Dependabot and the dependency review of every
pull request instead of manual reports.

## What the project already does

- Weekly Dependabot updates, auto-merged (patch and minor only) after CI passes; actions pinned by SHA.
- Dependency review on every pull request; CodeQL on pull requests and weekly.
- A CycloneDX bill of materials of what ships (the resolved runtime classpath, transitive libraries included) is scanned
  for known vulnerabilities on every change and weekly, and kept as a build artifact.
- detekt static analysis and ktlint in CI, with compiler warnings treated as errors.
- Personal data (tax id, passport, e-mail) is never logged (`toString` of the types that carry it shows
  only the id) and is never returned by the customer endpoints.
- No secrets in the repository: configuration comes from environment variables.
- Every column is bounded and every business rule has a database constraint behind it, so a bypass of the
  application layer cannot store invalid data.
