# Demo Pull Requests & Triage Tier Examples

This document demonstrates the 3 PR triage tiers (🟢 Green, 🟡 Yellow, and 🔴 Red with Security Flag) used by the PR-Triage engine, outlining their trigger signals, analysis results, and expected maintainer experience.

---

## 1. 🟢 Green Tier Example (Clean & Merge-Worthy)

### PR Overview
- **PR Title:** `feat(math): add MathUtils helper with unit test coverage`
- **Author:** `@contributor` (First-time or Returning)
- **Scope & Files Changed:**
  - `src/main/java/com/app/MathUtils.java`
  - `src/test/java/com/app/MathUtilsTest.java` (2 files changed)
- **Description:** *"Introduces MathUtils with gcd and lcm utility functions, accompanied by comprehensive parameterized unit tests."*

### Analysis Signals
- **Coherence:** High (clear description, small and focused diff <= 10 files).
- **Test Coverage:** Yes (`MathUtilsTest.java` included, `POSITIVE_OBSERVATION` signal).
- **Rule Violations / Bugs:** None (`hasBug: false`, `hasRuleViolations: false`).
- **Security Check:** Clean (`hasSecurityFinding: false`).
- **AI-Likelihood:** `LOW` or `UNKNOWN` (offline / fallback mode without external LLM).

### Expected Triage Output
- **Tier:** 🟢 `GREEN`
- **Suggested Action:** `REVIEW_AND_MERGE` ("Merge-worthy.")
- **Security Flag:** `false`
- **Email & Review Behavior:**
  - Shows green badge: `🟢 Low Risk`.
  - Shows clean Point-Wise summary or Difference Table (no raw code snippets or line numbers).
  - Displays `✓ Automated Checks Passed: No bugs or security issues detected.`
  - Maintainer can merge with single-click Approve.

---

## 2. 🟡 Yellow Tier Example (Needs Inspection / Rule Violations)

### PR Overview
- **PR Title:** `refactor(auth): update session token verification`
- **Author:** `@contributor`
- **Scope & Files Changed:**
  - `src/main/java/com/app/AuthService.java`
  - `src/test/java/com/app/AuthTest.java` (2 files changed)
- **Description:** *"Updates token validation flow to support new session headers."*

### Analysis Signals
- **Coherence:** Coherent intent.
- **Test Coverage:** Yes.
- **Rule Violations / Bugs:** 
  - **Issue Detected:** `BUG_DETECTION` (Severity: `MEDIUM` / `HIGH`) — e.g. String comparison using `==` instead of `.equals()`, or potential unhandled `NullPointerException` on optional headers.
- **Security Check:** No credential leaks (`hasSecurityFinding: false`).
- **AI-Likelihood:** `LOW` or `MEDIUM`.

### Expected Triage Output
- **Tier:** 🟡 `YELLOW`
- **Suggested Action:** `MANUAL_CHECK` ("Needs human review.")
- **Security Flag:** `false`
- **Email & Review Behavior:**
  - Shows yellow badge: `🟡 Medium Risk`.
  - Shows Difference Table comparing before vs after auth logic.
  - Highlights the warning finding: `⚠️ Important Findings: Dangerous String comparison using '==' instead of '.equals()' — 💡 Suggestion: Use .equals() for String comparison`.
  - Notifies maintainer that human inspection is required before merging.

---

## 3. 🔴 Red Tier with Security Flag (Critical Risk & Security Alert)

### PR Overview
- **PR Title:** `ci: update workflow scripts and configuration`
- **Author:** `@suspicious-user`
- **Scope & Files Changed:**
  - 15+ files across unrelated modules (`src/service1.java`, ..., `src/service15.java`, `.github/workflows/ci.yml`)
- **Description:** *(Empty or generic: "update files")*

### Analysis Signals
- **Coherence:** Low (sweeping unrelated changes > 10 files with missing/blank description).
- **AI / Spam Signals:** Boilerplate phrases detected (`AI_LIKELIHOOD` with templated boilerplate).
- **Security Findings:**
  - Hardcoded AWS secret key or private token committed (`SECURITY` Category, Severity: `CRITICAL`).
- **Security Flag:** 🔒 `true`.

### Expected Triage Output
- **Tier:** 🔴 `RED`
- **Suggested Action:** `CONSIDER_CLOSING` ("Requires immediate review.")
- **Security Flag:** 🔒 `true` (`[SECURITY ALERT]`)
- **Email & Review Behavior:**
  - Subject prefixed with `[SECURITY ALERT] [PR-Triage] ...`.
  - Displays red risk badge: `🔴 High Risk` + `🔒 ⚠ SECURITY`.
  - Prominent red callout: `🔒 Security Warning: Potential security concerns or sensitive credentials detected.`
  - Direct alert in findings: `🔴 Critical: Exposed AWS secret access key`.
  - Quick Decision: 1-click **Reject PR** button to immediately protect the repository.
