const BACKEND_URL = "http://localhost:8080";

document.addEventListener("DOMContentLoaded", () => {
  checkBackendHealth();
  loadProfile();
  loadRecentHistory();

  document.getElementById("saveProfileBtn").addEventListener("click", saveProfile);
  document.getElementById("triageCurrentTabBtn").addEventListener("click", triageCurrentTab);
  document.getElementById("syncNowBtn").addEventListener("click", triggerSync);
});

async function checkBackendHealth() {
  const statusEl = document.getElementById("backendStatus");
  try {
    const res = await fetch(`${BACKEND_URL}/webhook/health`);
    if (res.ok) {
      statusEl.textContent = "● Connected (Local)";
      statusEl.className = "status-badge online";
    } else {
      statusEl.textContent = "Offline";
    }
  } catch (e) {
    statusEl.textContent = "Offline (Start Glint)";
  }
}

async function loadProfile() {
  try {
    const res = await fetch(`${BACKEND_URL}/api/profile`);
    if (res.ok) {
      const data = await res.json();
      if (data) {
        document.getElementById("ghUsername").value = data.githubUsername || "";
        document.getElementById("notifyEmail").value = data.notificationEmail || "";
        document.getElementById("autoTriageToggle").checked = !!data.autoTriageEnabled;
      }
    }
  } catch (e) {
    console.debug("Could not load profile", e);
  }
}

async function saveProfile() {
  const username = document.getElementById("ghUsername").value.trim();
  const rawEmail = document.getElementById("notifyEmail").value.trim();
  const email = (rawEmail && !rawEmail.includes("Auto-detected")) ? rawEmail : "";
  const active = document.getElementById("autoTriageToggle").checked;

  if (!username) {
    alert("Please enter a GitHub username.");
    return;
  }

  const btn = document.getElementById("saveProfileBtn");
  btn.textContent = "Saving...";
  btn.disabled = true;

  try {
    const res = await fetch(`${BACKEND_URL}/api/profile`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        githubUsername: username,
        notificationEmail: email,
        autoTriageEnabled: active
      })
    });
    if (res.ok) {
      const saved = await res.json();
      if (saved && saved.notificationEmail) {
        document.getElementById("notifyEmail").value = saved.notificationEmail;
      }
      alert("Settings saved! Auto PR-Lens review is " + (active ? "Active" : "Disabled") + ".");
    } else {
      alert("Failed to save settings.");
    }
  } catch (e) {
    alert("Error communicating with Glint backend: " + e.message);
  } finally {
    btn.textContent = "Save Settings";
    btn.disabled = false;
  }
}

async function triggerSync() {
  const btn = document.getElementById("syncNowBtn");
  btn.textContent = "🔄 Checking GitHub...";
  btn.disabled = true;

  try {
    const res = await fetch(`${BACKEND_URL}/api/profile/sync`, { method: "POST" });
    const data = await res.json();
    alert(data.message || "Sync finished.");
    loadRecentHistory();
  } catch (e) {
    alert("Sync error: " + e.message);
  } finally {
    btn.textContent = "🔄 Sync Repositories Now";
    btn.disabled = false;
  }
}

async function triageCurrentTab() {
  if (!chrome.tabs) {
    alert("Please open this extension inside Google Chrome or Edge.");
    return;
  }

  const btn = document.getElementById("triageCurrentTabBtn");
  btn.textContent = "⏳ Analyzing PR...";
  btn.disabled = true;

  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab || !tab.url || !tab.url.includes("/pull/")) {
      alert("The active tab does not appear to be a GitHub Pull Request URL.");
      return;
    }

    const res = await fetch(`${BACKEND_URL}/api/triage`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url: tab.url })
    });
    const data = await res.json();
    if (res.ok && data.status === "SUCCESS") {
      alert(`PR Analyzed with PR-Lens!\nTier: ${data.tier}\n${data.message}`);
      loadRecentHistory();
    } else {
      alert(data.message || data.error || "Analysis failed.");
    }
  } catch (e) {
    alert("Error: " + e.message);
  } finally {
    btn.textContent = "⚡ Analyze Current PR Tab";
    btn.disabled = false;
  }
}

async function loadRecentHistory() {
  const listEl = document.getElementById("recentList");
  try {
    const res = await fetch(`${BACKEND_URL}/api/history?limit=5`);
    if (!res.ok) {
      listEl.innerHTML = `<div class="empty-text">No active backend connection.</div>`;
      return;
    }
    const items = await res.json();
    if (!items || items.length === 0) {
      listEl.innerHTML = `<div class="empty-text">No PRs analyzed yet.</div>`;
      return;
    }

    listEl.innerHTML = items.map(item => `
      <div class="history-card">
        <div class="history-card-header">
          <strong><span class="tier-dot ${item.tier}"></span>${item.owner}/${item.repo} #${item.prNumber}</strong>
          <span style="font-size:10px; color:#94a3b8;">${item.tier}</span>
        </div>
        <div style="color:#cbd5e1; margin-top:2px; font-size:10px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">
          ${item.title || item.summary || ''}
        </div>
      </div>
    `).join("");
  } catch (e) {
    listEl.innerHTML = `<div class="empty-text">Start Glint bot to view history.</div>`;
  }
}
