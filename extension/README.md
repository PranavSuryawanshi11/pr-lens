# Glint AI PR Triage · Browser Extension

Zero-config browser extension that integrates directly into GitHub.com to triage pull requests, display AI risk scores, and allow 1-click **Approve** and **Reject** decisions without leaving GitHub.

---

## 🚀 Quick Setup (Load Unpacked)

1. Open your Chromium-based browser (Google Chrome, Microsoft Edge, Brave, etc.).
2. Navigate to:
   - Chrome: `chrome://extensions/`
   - Edge: `edge://extensions/`
   - Brave: `brave://extensions/`
3. Toggle **"Developer mode"** in the top-right corner.
4. Click **"Load unpacked"**.
5. Select this `extension` directory (`c:\Users\lenovo\Downloads\pr-triage-sanitized\extension`).
6. The Glint icon will now appear in your browser toolbar!

---

## ⚡ Features on GitHub.com

- **PR Page Widget**: Whenever you open any PR on GitHub (`https://github.com/owner/repo/pull/123`), a floating Glint status badge appears at the bottom right.
  - If triaged: shows `🟢 LOW RISK`, `🟡 MEDIUM RISK`, or `🔴 HIGH RISK`, plus `🔒 SECURITY` alerts.
  - If untriaged: offers a **"⚡ Analyze PR with AI"** button that runs triage on-demand.
  - One-click **Approve** and **Reject** actions directly on GitHub!
- **Extension Popup**:
  - Turn **Auto-Triage** ON/OFF permanently for your GitHub profile.
  - Set your notification email to receive PR triage reports.
  - Trigger instant repository sync.
  - Click **"⚡ Triage Current PR Tab"** to triage whichever PR you currently have open in your browser tab.
  - View recent PR requests history and click through to the full dashboard (`http://localhost:8080/`).
