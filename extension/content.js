// Content script injected into GitHub.com pages
const BACKEND_URL = "http://localhost:8080";

(function() {
  const url = window.location.href;
  const prMatch = url.match(/^https:\/\/github\.com\/([^/]+)\/([^/]+)\/pull\/(\d+)/);

  if (prMatch) {
    const owner = prMatch[1];
    const repo = prMatch[2];
    const prNumber = parseInt(prMatch[3], 10);
    initPrWidget(owner, repo, prNumber, url);
  }
})();

async function initPrWidget(owner, repo, prNumber, prUrl) {
  // Create widget container
  const banner = document.createElement("div");
  banner.className = "glint-pr-banner";
  banner.id = "glintPrBanner";
  banner.innerHTML = `
    <div class="glint-logo">G</div>
    <div id="glintStatusText" style="display:flex; align-items:center; gap:8px;">
      <span>Checking Glint AI Triage...</span>
    </div>
  `;
  document.body.appendChild(banner);

  try {
    // Check if PR is already triaged
    const res = await fetch(`${BACKEND_URL}/api/history?user=${encodeURIComponent(owner)}&search=${encodeURIComponent(owner + "/" + repo + "#" + prNumber)}`, {
      credentials: "include"
    });
    if (res.ok) {
      const items = await res.json();
      const existing = items.find(i => i.owner.toLowerCase() === owner.toLowerCase() &&
                                       i.repo.toLowerCase() === repo.toLowerCase() &&
                                       i.prNumber === prNumber);

      if (existing) {
        renderTriagedState(existing);
        return;
      }
    }
  } catch (e) {
    console.debug("[Glint Extension] Backend not reachable", e);
  }

  // Not triaged yet state
  renderUntriagedState(prUrl, owner, repo, prNumber);
}

function renderTriagedState(item) {
  const container = document.getElementById("glintStatusText");
  if (!container) return;

  const tier = item.tier || "UNKNOWN";
  const sec = item.securityFlag ? ' <span style="color:#ef4444; font-weight:700;">🔒 SEC</span>' : '';
  const isDone = item.actionTaken;

  container.innerHTML = `
    <span class="glint-tag ${tier}">${tier} RISK</span>${sec}
    <span style="font-size:11px; max-width:260px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:#cbd5e1;" title="${item.summary || ''}">
      ${item.title || item.summary || 'Triaged'}
    </span>
    ${isDone 
      ? '<span style="color:#10b981; font-weight:600; font-size:11px;">✓ Actioned</span>'
      : `<button id="glintApproveBtn" class="glint-btn approve">Approve</button>
         <button id="glintRejectBtn" class="glint-btn reject">Reject</button>`
    }
    <a href="${BACKEND_URL}/" target="_blank" style="color:#94a3b8; font-size:11px; text-decoration:none; margin-left:4px;">Dashboard &rarr;</a>
  `;

  if (!isDone) {
    document.getElementById("glintApproveBtn")?.addEventListener("click", () => triggerPrAction(item.owner, item.repo, item.prNumber, "approve"));
    document.getElementById("glintRejectBtn")?.addEventListener("click", () => triggerPrAction(item.owner, item.repo, item.prNumber, "reject"));
  }
}

function renderUntriagedState(prUrl, owner, repo, prNumber) {
  const container = document.getElementById("glintStatusText");
  if (!container) return;

  container.innerHTML = `
    <span style="color:#94a3b8;">Not analyzed yet</span>
    <button id="glintAnalyzeBtn" class="glint-btn">⚡ Analyze PR with AI</button>
  `;

  document.getElementById("glintAnalyzeBtn")?.addEventListener("click", async () => {
    container.innerHTML = `<span>⏳ Extracting diff & running AI triage...</span>`;

    // Attempt to extract diff using active browser session (works for private repos automatically!)
    let sessionDiff = "";
    try {
      const cleanUrl = window.location.origin + window.location.pathname.replace(/\/files$|\/commits$/, "");
      const diffRes = await fetch(cleanUrl + ".diff", { credentials: "same-origin" });
      if (diffRes.ok) {
        sessionDiff = await diffRes.text();
      }
    } catch (e) {
      console.debug("[Glint Extension] Session diff fetch skipped", e);
    }

    const prTitle = document.querySelector(".js-issue-title")?.innerText?.trim() 
                 || document.title.split("by")[0].replace("· Pull Request", "").trim();
    const prAuthor = document.querySelector(".author")?.innerText?.trim() || "";

    try {
      const res = await fetch(`${BACKEND_URL}/api/triage`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          url: prUrl,
          owner: owner,
          repo: repo,
          prNumber: prNumber,
          title: prTitle,
          author: prAuthor,
          diff: sessionDiff
        })
      });
      const data = await res.json();
      if (res.ok && data.status === "SUCCESS") {
        renderTriagedState({
          owner: data.owner,
          repo: data.repo,
          prNumber: data.prNumber,
          title: prTitle,
          tier: data.tier,
          securityFlag: data.securityFlag,
          summary: data.summary,
          actionTaken: false
        });
      } else {
        container.innerHTML = `<span style="color:#ef4444;">Triage failed: ${data.message || data.error}</span>`;
      }
    } catch (e) {
      container.innerHTML = `<span style="color:#ef4444;">Backend unreachable</span>`;
    }
  });
}

async function triggerPrAction(owner, repo, prNumber, action) {
  try {
    const res = await fetch(`${BACKEND_URL}/api/triage`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ owner, repo, prNumber })
    });
    const data = await res.json();
    const actionUrl = action === "approve" ? data.approveUrl : data.rejectUrl;
    if (actionUrl) {
      window.open(actionUrl, "_blank");
    } else {
      alert("Could not generate action link for " + action);
    }
  } catch (e) {
    alert("Error performing action: " + e.message);
  }
}
