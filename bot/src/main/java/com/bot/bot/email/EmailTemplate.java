package com.bot.bot.email;

import com.bot.bot.actions.TokenService;
import com.bot.bot.domain.Finding;
import com.bot.bot.persistence.PrAnalysis;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders HTML email bodies for the daily digest and the urgent-action alert.
 * Uses a clean enterprise design system (Stripe / GitHub Enterprise aesthetic)
 * with robust cross-client table layout.
 */
public class EmailTemplate {
    private final TokenService tokenService;

    private static final String DARK    = "#0f172a";
    private static final String ACCENT  = "#0969da";
    private static final String BG      = "#f8fafc";
    private static final String CARD    = "#ffffff";
    private static final String TEXT    = "#0f172a";
    private static final String BODY    = "#475569";
    private static final String MUTED   = "#64748b";
    private static final String BORDER  = "#e2e8f0";

    private static final String RED_C    = "#cf222e";
    private static final String RED_BG   = "#ffebe9";
    private static final String YELLOW_C = "#9a6700";
    private static final String YELLOW_BG= "#fff8c5";
    private static final String GREEN_C  = "#1a7f37";
    private static final String GREEN_BG = "#dafbe1";

    private static final String FW = "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;";

    public EmailTemplate(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public String renderDigest(List<PrAnalysis> analyses) {
        return emailFrame(
            analyses.size() + " PR" + (analyses.size() == 1 ? "" : "s") + " analyzed",
            tableWrap(
                brandHeader("PR Triage Digest") +
                "<tr><td style=\"padding:20px 24px 24px;\">" +
                    p(analyses.size() + " pull request(s) analyzed today:", BODY, "13px", "margin:0 0 16px;") +
                    (analyses.isEmpty()
                        ? p("No pull requests to review.", MUTED, "13px", "margin:0;font-style:italic;")
                        : prTable(analyses)) +
                "</td></tr>" +
                brandFooter()
            )
        );
    }

    public String renderAlert(PrAnalysis a) {
        String t = tier(a);
        var tc = tierColors(t);
        boolean sec = Boolean.TRUE.equals(a.getSecurityFlag());
        String prLabel = escape(a.getOwner()) + "/" + escape(a.getRepo()) + "#" + a.getPrNumber();
        String tierDisplayName = switch (t.toUpperCase()) {
            case "RED" -> "🔴 High Risk";
            case "YELLOW" -> "🟡 Medium Risk";
            case "GREEN" -> "🟢 Low Risk";
            default -> t;
        };
        String titleText = (a.getTitle() != null && !a.getTitle().isBlank()) ? (" &bull; " + escape(a.getTitle())) : "";

        String reputation = a.getAuthorReputation() != null ? a.getAuthorReputation() : "FIRST_TIME_CONTRIBUTOR";
        String reputationBadge = switch (reputation.toUpperCase()) {
            case "TRUSTED_MAINTAINER" -> "<span style=\"display:inline-block;padding:2px 7px;font-size:11px;font-weight:600;color:#1a7f37;background:#dafbe1;border-radius:4px;margin-left:6px;\">⭐ Trusted Maintainer</span>";
            case "COLLABORATOR" -> "<span style=\"display:inline-block;padding:2px 7px;font-size:11px;font-weight:600;color:#0969da;background:#f0f7ff;border-radius:4px;margin-left:6px;\">Collaborator</span>";
            case "RETURNING_CONTRIBUTOR" -> "<span style=\"display:inline-block;padding:2px 7px;font-size:11px;font-weight:600;color:#0969da;background:#f0f7ff;border-radius:4px;margin-left:6px;\">Returning Contributor</span>";
            default -> "<span style=\"display:inline-block;padding:2px 7px;font-size:11px;font-weight:600;color:#9a6700;background:#fff8c5;border-radius:4px;margin-left:6px;\">⚠️ First-time Contributor</span>";
        };
        String authorText = (a.getAuthor() != null && !a.getAuthor().isBlank())
                ? ("<div style=\"font-size:12px;color:" + BODY + ";margin-top:4px;\">Opened by <strong>@" + escape(a.getAuthor()) + "</strong> " + reputationBadge + "</div>")
                : "";

        return emailFrame("Action Required: " + prLabel,
            tableWrap(
                brandHeader("PR Triage & Review") +
                "<tr><td style=\"padding:18px 22px 20px;\">" +
                    twoCol(
                        vAlign(left, "", tiny("Pull Request Requiring Review", MUTED) +
                            bold(prLabel + titleText, TEXT, "14px") + authorText),
                        vAlign(right, "", badge(tierDisplayName, tc.c, tc.bg, "12px") +
                            (sec ? "&nbsp;" + badge("🔒 ⚠ SECURITY", RED_C, RED_BG, "11px") : "")),
                        "0 0 12px") +
                    (sec ? callout("🔒 <strong>Security Warning:</strong> Potential security concerns or sensitive credentials detected.", RED_C, RED_BG, "#fecaca") : "") +
                    overviewBlock(a) +
                    changesSectionBlock(a) +
                    functionalChangesCard(a) +
                    whatToEditOrAddBlock(a) +
                    findingsBlock(a.getFindingsJson()) +
                    maintainerDecisionBlock(a) +
                    actionButtons(a) +
                "</td></tr>" +
                brandFooter()
            )
        );
    }

    private static String overviewBlock(PrAnalysis a) {
        String overview = extractOverview(a);
        if (overview.isBlank()) return "";
        return "<div style=\"margin:12px 0 0;padding:10px 14px;background:#f8fafc;border-left:3px solid " + ACCENT + ";border-radius:4px;\">"
            + "<div style=\"font-size:11px;font-weight:700;color:" + ACCENT + ";text-transform:uppercase;letter-spacing:0.04em;margin-bottom:2px;\">📌 Overview & Executive Summary</div>"
            + "<div style=\"font-size:12px;color:" + TEXT + ";line-height:1.5;\">" + overview + "</div>"
            + "</div>";
    }

    private static String extractOverview(PrAnalysis a) {
        String exec = extractExecutiveSummary(a);
        if (!exec.isBlank()) {
            return exec;
        }
        if (a.getTitle() != null && !a.getTitle().isBlank()) {
            return escape(a.getTitle());
        }
        String after = cleanFeatureText(a.getChangeSummaryAfter());
        if (!after.isBlank()) {
            return escape(after);
        }
        return "Review requested for pull request updates.";
    }

    private static String changesSectionBlock(PrAnalysis a) {
        String rawBefore = a.getChangeSummaryBefore();
        String rawAfter = a.getChangeSummaryAfter();

        boolean hasBefore = rawBefore != null && !rawBefore.isBlank();
        boolean hasAfter = rawAfter != null && !rawAfter.isBlank();

        if (!hasBefore && !hasAfter) {
            return "";
        }

        boolean isDiff = isDifferenceApplicable(rawBefore, rawAfter);

        if (isDiff) {
            // Render Clean Difference Table (Before PR vs After PR Changes)
            String beforeCell = formatBeforeCell(rawBefore, rawAfter);
            String afterCell = formatAfterCell(rawAfter);

            return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\""
                + " style=\"margin:14px 0 0;background:#ffffff;border:1px solid " + BORDER + ";border-radius:6px;overflow:hidden;\">"
                + "<tr><td colspan=\"2\" style=\"padding:8px 12px;background:#f8fafc;border-bottom:1px solid " + BORDER + ";\">"
                + "<strong style=\"font-size:11px;color:" + DARK + ";text-transform:uppercase;letter-spacing:0.04em;\">⚡ Feature Differences (Before vs After PR)</strong>"
                + "</td></tr>"
                + "<tr style=\"background:#f8fafc;\">"
                + "<td style=\"padding:7px 12px;font-size:11px;font-weight:700;color:#cf222e;text-transform:uppercase;border-bottom:1px solid " + BORDER + ";width:50%;\">⏮ Before Pull Request (What Was Before)</td>"
                + "<td style=\"padding:7px 12px;font-size:11px;font-weight:700;color:#1a7f37;text-transform:uppercase;border-bottom:1px solid " + BORDER + ";border-left:1px solid " + BORDER + ";width:50%;\">⏭ After Pull Request (What Changed in PR)</td>"
                + "</tr>"
                + "<tr>"
                + "<td style=\"padding:10px 12px;background:#fff8f8;color:" + BODY + ";font-size:12px;line-height:1.5;vertical-align:top;\">" + beforeCell + "</td>"
                + "<td style=\"padding:10px 12px;background:#f0fdf4;color:" + TEXT + ";font-size:12px;line-height:1.5;vertical-align:top;border-left:1px solid " + BORDER + ";font-weight:500;\">" + afterCell + "</td>"
                + "</tr>"
                + "</table>";
        } else {
            // Render Point-Wise List (When difference comparison is not applicable, e.g. clean addition)
            String cleanAfter = cleanFeatureText(rawAfter);
            String pointWiseHtml = formatPointWiseChanges(cleanAfter, a);

            return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\""
                + " style=\"margin:14px 0 0;background:#ffffff;border:1px solid " + BORDER + ";border-radius:6px;overflow:hidden;\">"
                + "<tr><td style=\"padding:8px 12px;background:#f8fafc;border-bottom:1px solid " + BORDER + ";\">"
                + "<strong style=\"font-size:11px;color:" + DARK + ";text-transform:uppercase;letter-spacing:0.04em;\">📋 Key Changes (Point-Wise Summary)</strong>"
                + "</td></tr>"
                + "<tr><td style=\"padding:12px 14px;\">"
                + pointWiseHtml
                + "</td></tr>"
                + "</table>";
        }
    }

    static boolean isDifferenceApplicable(String before, String after) {
        String bLower = (before != null ? before : "").toLowerCase();

        // If explicitly clean addition or no prior code -> not applicable
        if (bLower.contains("clean addition") || bLower.contains("no previous code modifications")
                || bLower.contains("no prior code removed")) {
            return false;
        }

        String cleanB = cleanFeatureText(before);
        String cleanA = cleanFeatureText(after);

        // If purely new file or new subject created without prior context
        if (cleanA.toLowerCase().startsWith("created new file") && (before == null || cleanB.isBlank())) {
            return false;
        }
        if (cleanA.toLowerCase().startsWith("added one more") && (before == null || cleanB.isBlank())) {
            return false;
        }

        // If after indicates update/modification or before has meaningful prior state
        if (cleanA.toLowerCase().startsWith("in ") || cleanA.toLowerCase().startsWith("updated ")
                || cleanA.toLowerCase().contains("updated class") || cleanA.toLowerCase().contains("updated method")
                || cleanA.toLowerCase().contains("updated logic") || cleanA.toLowerCase().contains("updated readme")
                || cleanA.toLowerCase().contains("updated test") || cleanA.toLowerCase().contains("updated docker")
                || cleanA.toLowerCase().contains("updated configuration")) {
            return true;
        }

        if (!cleanB.isBlank() && !cleanB.equalsIgnoreCase("No previous code state documented.")) {
            return true;
        }

        return false;
    }

    static String cleanFeatureText(String text) {
        if (text == null || text.isBlank()) return "";
        String s = text;
        // Strip snippet indicators: (prior code included: `...`) or (introduced: `...`)
        s = s.replaceAll("(?is)\\(\\s*prior code included:[^)]*\\)", "");
        s = s.replaceAll("(?is)\\(\\s*introduced:[^)]*\\)", "");
        s = s.replaceAll("(?is)\\(\\s*prior code[^)]*\\)", "");
        // Strip line statistics: "- Added 12 line(s) of new implementation across 1 file(s)"
        s = s.replaceAll("(?is)\\s*-\\s*Added\\s+\\d+\\s+line\\(s\\)[^.]*\\.?", "");
        s = s.replaceAll("(?is)Added\\s+\\d+\\s+line\\(s\\)\\s+of\\s+new\\s+implementation[^.]*\\.?", "");
        s = s.replaceAll("(?is)Previously had\\s+\\d+\\s+line\\(s\\)[^.]*that were modified or removed\\.?", "");
        s = s.replaceAll("(?is)\\b\\d+\\s+line\\(s\\)\\b", "");
        s = s.replaceAll("(?is)across\\s+\\d+\\s+file\\(s\\)", "");
        s = s.replaceAll("(?i)\\bat\\s+lines?\\s+\\d+(?:-\\d+)?\\b", "");
        // Remove code backticks
        s = s.replace("`", "");
        // Clean multiple spaces and trailing hyphens/periods
        s = s.replaceAll("\\s+", " ").trim();
        s = s.replaceAll("^[\\s,-]+", "").replaceAll("[\\s,-]+$", "").trim();
        return s;
    }

    static String formatBeforeCell(String rawBefore, String rawAfter) {
        String cleaned = cleanFeatureText(rawBefore);
        String afterClean = cleanFeatureText(rawAfter);

        if (afterClean.toLowerCase().startsWith("in ") && afterClean.toLowerCase().contains(", added ")) {
            int addedIdx = afterClean.indexOf(", added ");
            String context = afterClean.substring(0, addedIdx);
            return escape(context) + " (prior to newly added topics/sections)";
        }
        if (afterClean.toLowerCase().startsWith("updated class '")) {
            return "Previous class implementation before these modifications.";
        }
        if (afterClean.toLowerCase().startsWith("updated logic in '")) {
            return "Earlier logic before these updates were applied.";
        }
        if (afterClean.toLowerCase().startsWith("updated readme")) {
            return "Previous README documentation.";
        }
        if (cleaned.isBlank() || cleaned.toLowerCase().contains("clean addition")
                || cleaned.toLowerCase().contains("no prior") || cleaned.toLowerCase().contains("no previous")) {
            return "Baseline state prior to this pull request.";
        }
        return escape(cleaned);
    }

    static String formatAfterCell(String rawAfter) {
        String cleaned = cleanFeatureText(rawAfter);
        if (cleaned.isBlank()) {
            return "Code enhancements and modifications introduced.";
        }
        return escape(cleaned);
    }

    static String formatPointWiseChanges(String cleanAfter, PrAnalysis a) {
        if (cleanAfter == null || cleanAfter.isBlank()) {
            cleanAfter = a.getTitle() != null && !a.getTitle().isBlank() ? a.getTitle() : "New features and code submitted.";
        }

        List<String> points = new ArrayList<>();

        if (cleanAfter.contains(" and in ")) {
            String[] parts = cleanAfter.split(" and in ", 2);
            points.add("🚀 <strong>Feature Added:</strong> " + escape(parts[0].trim()));
            points.add("📝 <strong>Topic / Details:</strong> In " + escape(parts[1].trim()));
        } else if (cleanAfter.toLowerCase().startsWith("created new file")) {
            points.add("📦 <strong>New Component:</strong> " + escape(cleanAfter));
        } else {
            points.add("✨ <strong>Update:</strong> " + escape(cleanAfter));
        }

        points.add("🟢 <strong>Integration Scope:</strong> Clean addition — no existing code was removed or broken.");

        StringBuilder sb = new StringBuilder();
        for (String pt : points) {
            sb.append("<div style=\"margin-bottom:6px;font-size:12px;color:").append(TEXT)
              .append(";line-height:1.5;\">")
              .append(pt)
              .append("</div>");
        }
        return sb.toString();
    }

    private String findingsBlock(String findingsJson) {
        if (findingsJson == null || findingsJson.isBlank()) {
            return "<div style=\"margin-top:14px;padding:8px 12px;background:#dafbe1;border:1px solid #4ac26b;border-radius:6px;font-size:12px;color:#1a7f37;\">"
                + "✓ <strong>Automated Checks Passed:</strong> No bugs or security issues detected."
                + "</div>";
        }
        try {
            Gson gson = new Gson();
            TypeToken<List<Finding>> typeToken = new TypeToken<>() {};
            List<Finding> findings = gson.fromJson(findingsJson, typeToken.getType());
            if (findings == null || findings.isEmpty()) {
                return "<div style=\"margin-top:14px;padding:8px 12px;background:#dafbe1;border:1px solid #4ac26b;border-radius:6px;font-size:12px;color:#1a7f37;\">"
                    + "✓ <strong>Automated Checks Passed:</strong> No bugs or security issues detected."
                    + "</div>";
            }

            // Filter for actionable findings (exclude pure info/positive observation)
            List<Finding> actionable = findings.stream()
                .filter(f -> f != null)
                .filter(f -> {
                    String sev = f.getSeverity() != null ? f.getSeverity().toUpperCase() : "INFO";
                    String cat = f.getCategory() != null ? f.getCategory().toUpperCase() : "";
                    return "CRITICAL".equals(sev) || "HIGH".equals(sev) || "MEDIUM".equals(sev)
                            || "BUG_DETECTION".equals(cat) || "SECURITY".equals(cat);
                })
                .toList();

            if (actionable.isEmpty()) {
                return "<div style=\"margin-top:14px;padding:8px 12px;background:#dafbe1;border:1px solid #4ac26b;border-radius:6px;font-size:12px;color:#1a7f37;\">"
                    + "✓ <strong>Automated Checks Passed:</strong> No bugs or security issues detected."
                    + "</div>";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("<div style=\"margin-top:14px;padding:10px 14px;background:#fff5f5;border:1px solid #fecaca;border-radius:6px;\">")
              .append("<div style=\"font-size:11px;font-weight:700;color:").append(RED_C)
              .append(";text-transform:uppercase;letter-spacing:0.04em;margin-bottom:6px;\">")
              .append("⚠️ Important Findings (").append(actionable.size()).append(")</div>");

            for (Finding f : actionable) {
                String sev = f.getSeverity() != null ? f.getSeverity() : "INFO";
                String sevColor = switch (sev.toUpperCase()) {
                    case "CRITICAL", "HIGH" -> RED_C;
                    case "MEDIUM" -> YELLOW_C;
                    default -> MUTED;
                };
                String sevBg = switch (sev.toUpperCase()) {
                    case "CRITICAL", "HIGH" -> RED_BG;
                    case "MEDIUM" -> YELLOW_BG;
                    default -> BG;
                };
                // File path only, NO LINE NUMBER
                String loc = escape(f.getFilePath() != null ? f.getFilePath() : "Repository");
                String msg = escape(f.getMessage() != null ? f.getMessage() : "");
                String suggestion = f.getSuggestion() != null && !f.getSuggestion().isBlank()
                        ? (" &mdash; <span style=\"color:#1a7f37;font-style:italic;\">💡 " + escape(f.getSuggestion()) + "</span>")
                        : "";

                sb.append("<div style=\"margin-bottom:6px;font-size:12px;color:").append(DARK).append(";line-height:1.5;\">")
                  .append(badge(sev, sevColor, sevBg, "10px")).append(" ")
                  .append("<strong style=\"font-family:ui-monospace,monospace;font-size:11px;\">").append(loc).append(":</strong> ")
                  .append(msg).append(suggestion)
                  .append("</div>");
            }
            sb.append("</div>");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String callout(String msg, String color, String bg, String borderColor) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\""
            + " style=\"margin:14px 0 0;background:" + bg + ";border:1px solid " + borderColor + ";border-radius:6px;\">"
            + "<tr><td style=\"padding:10px 14px;font-size:12px;font-weight:600;color:" + color + ";" + FW + "\">"
            + msg
            + "</td></tr>"
            + "</table>";
    }

    // ── structural helpers ──

    private static String tableWrap(String inner) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\""
            + " style=\"background:" + CARD + ";border:1px solid " + BORDER + ";border-radius:6px;overflow:hidden;\">"
            + inner
            + "</table>";
    }

    private static String brandHeader(String subtitle) {
        return "<tr><td style=\"padding:0;background:" + DARK + ";border-radius:6px 6px 0 0;\">"
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\">"
            + "<tr><td style=\"padding:12px 18px;\">"
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\"><tr>"
            + "<td align=\"left\" style=\"vertical-align:middle;\">"
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
            + "<td style=\"width:24px;height:22px;background:" + ACCENT + ";border-radius:4px;text-align:center;"
            + "vertical-align:middle;font-size:11px;font-weight:700;color:#fff;" + FW + "\">PR</td>"
            + "<td style=\"padding-left:8px;font-size:14px;font-weight:700;color:#fff;\" " + FW + ">PR-Triage</td>"
            + "</tr></table>"
            + "</td>"
            + "<td align=\"right\" style=\"vertical-align:middle;font-size:11px;color:" + MUTED + ";" + FW + "\">"
            + subtitle
            + "</td>"
            + "</tr></table>"
            + "</td></tr>"
            + "<tr><td style=\"height:2px;background:" + ACCENT + ";font-size:0;line-height:0;\">&nbsp;</td></tr>"
            + "</table>"
            + "</td></tr>";
    }

    private static String brandFooter() {
        return "<tr><td style=\"padding:10px 18px;border-top:1px solid " + BORDER + ";background:" + BG + ";text-align:center;\">"
            + "<p style=\"margin:0;font-size:11px;color:" + MUTED + ";" + FW + "\">"
            + "Delivered by <strong style=\"color:" + BODY + "\">PR-Triage</strong> &bull; Intelligent Pull Request Review</p>"
            + "</td></tr>";
    }

    private String prTable(List<PrAnalysis> analyses) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\"")
          .append(" style=\"border-collapse:separate;border-spacing:0;border:1px solid ").append(BORDER)
          .append(";border-radius:6px;overflow:hidden;\">")
          .append("<thead><tr>");
        for (var h : new String[]{"PR", "Tier", "Summary", ""}) {
            String align = "".equals(h) ? "right" : "left";
            sb.append("<th style=\"padding:8px 12px;background:").append(BG)
              .append(";font-size:11px;font-weight:600;color:").append(MUTED)
              .append(";text-transform:uppercase;letter-spacing:.05em;text-align:").append(align)
              .append(";border-bottom:1px solid ").append(BORDER).append(";").append(FW).append("\">")
              .append(h).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (PrAnalysis a : analyses) {
            String t = tier(a);
            var tc = tierColors(t);
            boolean sec = Boolean.TRUE.equals(a.getSecurityFlag());
            String prId = escape(a.getOwner()) + "/" + escape(a.getRepo()) + "#" + a.getPrNumber();
            String summaryDesc = cleanFeatureText(a.getChangeSummaryAfter());
            if (summaryDesc.isBlank()) {
                summaryDesc = a.getTitle() != null ? a.getTitle() : "PR Update";
            }
            sb.append("<tr>")
              .append(cell(prId, TEXT, "12px", "font-weight:600;font-family:ui-monospace,monospace;"))
              .append(cell(badge(t, tc.c, tc.bg, "11px") + (sec ? badge("🔒 ⚠ SECURITY", RED_C, RED_BG, "10px") : ""), "", "12px", ""))
              .append(cell(escape(truncate(summaryDesc, 90)), BODY, "12px", ""))
              .append(cellRight(styleActionLinks(a)))
              .append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    // ── action buttons ──

    private String actionButtons(PrAnalysis a) {
        if (tokenService == null) return "";
        String approve = tokenService.buildActionUrl(a.getOwner(), a.getRepo(), a.getPrNumber(), "approve");
        String reject  = tokenService.buildActionUrl(a.getOwner(), a.getRepo(), a.getPrNumber(), "reject");
        if (approve.isEmpty() && reject.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin-top:20px;padding:16px;background:").append(BG)
          .append(";border:1px solid ").append(BORDER).append(";border-radius:6px;text-align:center;\">");
        sb.append("<p style=\"margin:0 0 10px;font-size:11px;font-weight:600;color:").append(DARK)
          .append(";text-transform:uppercase;letter-spacing:0.04em;").append(FW).append("\">Quick Decision (Single-Click Action)</p>");
        sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" align=\"center\"><tr>");
        if (!approve.isEmpty()) {
            sb.append("<td style=\"padding:0 4px;\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
              .append("<td style=\"border-radius:5px;background:").append(GREEN_C).append(";text-align:center;padding:0;\">")
              .append("<a href=\"").append(approve).append("\" style=\"display:inline-block;padding:8px 18px;font-size:12px;")
              .append("font-weight:600;text-decoration:none;color:#fff;").append(FW).append("\">Approve PR</a>")
              .append("</td></tr></table></td>");
        }
        if (!reject.isEmpty()) {
            sb.append("<td style=\"padding:0 4px;\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
              .append("<td style=\"border-radius:5px;background:").append(RED_C).append(";text-align:center;padding:0;\">")
              .append("<a href=\"").append(reject).append("\" style=\"display:inline-block;padding:8px 18px;font-size:12px;")
              .append("font-weight:600;text-decoration:none;color:#fff;").append(FW).append("\">Reject PR</a>")
              .append("</td></tr></table></td>");
        }
        sb.append("</tr></table>");
        sb.append("<p style=\"margin:8px 0 0;font-size:11px;color:").append(MUTED).append(";").append(FW)
          .append("\">Signed HMAC token &bull; Expires in 30 minutes &bull; Applies securely on GitHub</p>");
        sb.append("</div>");
        return sb.toString();
    }

    private String styleActionLinks(PrAnalysis a) {
        if (tokenService == null) return "";
        String approve = tokenService.buildActionUrl(a.getOwner(), a.getRepo(), a.getPrNumber(), "approve");
        String reject  = tokenService.buildActionUrl(a.getOwner(), a.getRepo(), a.getPrNumber(), "reject");
        if (approve.isEmpty() && reject.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (!approve.isEmpty()) {
            sb.append("<a href=\"").append(approve).append("\" style=\"display:inline-block;padding:3px 8px;font-size:11px;")
              .append("font-weight:600;text-decoration:none;border-radius:4px;color:#fff;background:").append(GREEN_C).append(";")
              .append(FW).append("\">Approve</a>");
        }
        if (!reject.isEmpty()) {
            sb.append("&nbsp;<a href=\"").append(reject).append("\" style=\"display:inline-block;padding:3px 8px;font-size:11px;")
              .append("font-weight:600;text-decoration:none;border-radius:4px;color:#fff;background:").append(RED_C).append(";")
              .append(FW).append("\">Reject</a>");
        }
        return sb.toString();
    }

    // ── small-cell builders ──

    private static String cell(String content, String color, String fontSize, String extra) {
        String c = color.isEmpty() ? "" : "color:" + color + ";";
        return "<td style=\"padding:8px 12px;font-size:" + fontSize + ";" + c + extra
            + "border-bottom:1px solid " + BORDER + ";" + FW + "\">" + content + "</td>";
    }

    private static String cellRight(String content) {
        return "<td style=\"padding:8px 12px;text-align:right;white-space:nowrap;"
            + "border-bottom:1px solid " + BORDER + ";" + FW + "\">" + content + "</td>";
    }

    // ── primitive inline builders ──

    private static String emailFrame(String title, String body) {
        return "<!DOCTYPE html>\n"
            + "<html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">"
            + "<title>" + escape(title) + "</title>"
            + "</head>\n"
            + "<body style=\"margin:0;padding:0;background:" + BG + ";" + FW + "\">\n"
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\">\n"
            + "<tr><td align=\"center\" style=\"padding:24px 16px;\">\n"
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"580\""
            + " style=\"max-width:580px;width:100%;\">\n"
            + body + "\n"
            + "</table>\n"
            + "</td></tr></table>\n"
            + "</body></html>";
    }

    private static String bold(String text, String color, String size) {
        return "<p style=\"margin:0;font-size:" + size + ";font-weight:600;color:" + color + ";" + FW + "\">"
            + text + "</p>";
    }

    private static String tiny(String text, String color) {
        return "<p style=\"margin:0 0 2px;font-size:11px;color:" + color + ";" + FW + "\">"
            + text + "</p>";
    }

    private static String p(String text, String color, String size, String extra) {
        return "<p style=\"margin:0;font-size:" + size + ";color:" + color + ";" + FW + extra + "\">"
            + text + "</p>";
    }

    private static String badge(String label, String color, String bg, String size) {
        return "<span style=\"display:inline-block;padding:2px 8px;font-size:" + size + ";font-weight:600;"
            + "color:" + color + ";background:" + bg + ";border-radius:4px;" + FW + "\">"
            + label + "</span>";
    }

    private static String twoCol(String left, String right, String pad) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-bottom:12px;\"><tr>"
            + left + right
            + "</tr></table>";
    }

    private static String vAlign(String align, String style, String content) {
        return "<td align=\"" + align + "\" style=\"vertical-align:middle;" + style + "\">" + content + "</td>";
    }

    private static String left  = "left";
    private static String right = "right";

    private record TierColors(String c, String bg) {}
    private static TierColors tierColors(String t) {
        return switch (t.toUpperCase()) {
            case "RED"    -> new TierColors(RED_C, RED_BG);
            case "YELLOW" -> new TierColors(YELLOW_C, YELLOW_BG);
            case "GREEN"  -> new TierColors(GREEN_C, GREEN_BG);
            default       -> new TierColors(MUTED, BG);
        };
    }

    private static String tier(PrAnalysis a) {
        return a.getTier() != null ? a.getTier() : "UNKNOWN";
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String functionalChangesCard(PrAnalysis a) {
        List<String> changes = extractFunctionalChanges(a);
        if (changes.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\"")
          .append(" style=\"margin:14px 0 0;background:#ffffff;border:1px solid ").append(BORDER).append(";border-radius:6px;overflow:hidden;\">")
          .append("<tr><td style=\"padding:8px 12px;background:#f8fafc;border-bottom:1px solid ").append(BORDER).append(";\">")
          .append("<strong style=\"font-size:11px;color:").append(DARK).append(";text-transform:uppercase;letter-spacing:0.04em;\">⚡ Detailed Functional Breakdown</strong>")
          .append("</td></tr>")
          .append("<tr><td style=\"padding:12px 14px;\">");

        for (String c : changes) {
            sb.append("<div style=\"margin-bottom:6px;font-size:12px;color:").append(TEXT).append(";line-height:1.5;\">")
              .append("&bull; ").append(c)
              .append("</div>");
        }

        sb.append("</td></tr></table>");
        return sb.toString();
    }

    private static String whatToEditOrAddBlock(PrAnalysis a) {
        List<String> items = extractWhatToEditOrAdd(a);
        if (items.isEmpty()) return "";

        boolean isClean = items.stream().allMatch(i -> i.toLowerCase().contains("no additional edits") || i.toLowerCase().contains("complete"));

        if (isClean) {
            return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\""
                + " style=\"margin:14px 0 0;background:#f0fdf4;border:1px solid #bbf7d0;border-left:3px solid #16a34a;border-radius:6px;\">"
                + "<tr><td style=\"padding:10px 14px;\">"
                + "<div style=\"font-size:11px;font-weight:700;color:#15803d;text-transform:uppercase;letter-spacing:0.04em;margin-bottom:3px;\">✅ Implementation Complete</div>"
                + "<div style=\"font-size:12px;color:#166534;line-height:1.5;\">No additional edits required &mdash; change is complete, tested, and ready for merge.</div>"
                + "</td></tr></table>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\"")
          .append(" style=\"margin:14px 0 0;background:#fffbeb;border:1px solid #fef3c7;border-left:3px solid #f59e0b;border-radius:6px;\">")
          .append("<tr><td style=\"padding:10px 14px;\">")
          .append("<div style=\"font-size:11px;font-weight:700;color:#b45309;text-transform:uppercase;letter-spacing:0.04em;margin-bottom:6px;\">")
          .append("🛠️ What Contributor Needs to Add / Edit (Action Items)</div>");

        int idx = 1;
        for (String itm : items) {
            sb.append("<div style=\"margin-bottom:5px;font-size:12px;color:#92400e;line-height:1.5;\">")
              .append("<strong>").append(idx++).append(".</strong> ")
              .append(itm)
              .append("</div>");
        }
        sb.append("<div style=\"margin-top:6px;font-size:11px;color:#b45309;font-style:italic;\">")
          .append("💡 Maintainers can copy and paste these action items directly into GitHub review feedback.")
          .append("</div>");
        sb.append("</td></tr></table>");
        return sb.toString();
    }

    private static String maintainerDecisionBlock(PrAnalysis a) {
        DecisionDisplay d = extractDecisionDisplay(a);
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\""
            + " style=\"margin:14px 0 0;background:#ffffff;border:1px solid " + BORDER + ";border-radius:6px;overflow:hidden;\">"
            + "<tr><td style=\"padding:8px 12px;background:#f8fafc;border-bottom:1px solid " + BORDER + ";\">"
            + "<strong style=\"font-size:11px;color:" + DARK + ";text-transform:uppercase;letter-spacing:0.04em;\">🎯 Maintainer Decision & Verdict</strong>"
            + "</td></tr>"
            + "<tr><td style=\"padding:10px 14px;\">"
            + "<div style=\"margin-bottom:6px;\">"
            + badge(d.verdict(), d.verdictColor(), d.verdictBg(), "11px")
            + "</div>"
            + "<div style=\"font-size:12px;color:" + TEXT + ";line-height:1.5;\">"
            + "<strong>Decision Rationale:</strong> " + d.rationale()
            + "</div>"
            + "</td></tr></table>";
    }

    static String extractExecutiveSummary(PrAnalysis a) {
        if (a == null) return "";
        String summary = a.getSummary();
        if (summary != null) {
            String exec = extractSectionValue(summary, "Executive Summary");
            if (exec != null && !exec.isBlank()) {
                return escape(cleanFeatureText(exec.trim()));
            }
            String purpose = extractSectionValue(summary, "Purpose");
            if (purpose != null && !purpose.isBlank() && !purpose.equalsIgnoreCase("No description available.") && !purpose.equalsIgnoreCase("Change inferred from code analysis.")) {
                return escape(cleanFeatureText(purpose.trim()));
            }
        }
        if (a.getTitle() != null && !a.getTitle().isBlank()) {
            return escape(a.getTitle());
        }
        return "Code modifications submitted for review.";
    }

    static List<String> extractFunctionalChanges(PrAnalysis a) {
        List<String> list = new ArrayList<>();
        if (a == null) return list;
        String summary = a.getSummary();
        if (summary != null) {
            String block = extractSectionValue(summary, "Functional Changes");
            if (block != null && !block.isBlank()) {
                String[] lines = block.split("\n");
                for (String l : lines) {
                    String trimmed = l.trim().replaceFirst("^[-*•\\s]+", "").trim();
                    if (!trimmed.isEmpty() && !trimmed.toLowerCase().startsWith("no previous") && !trimmed.toLowerCase().startsWith("no description")) {
                        list.add(escape(cleanFeatureText(trimmed)));
                    }
                }
            }
        }
        return list;
    }

    static List<String> extractWhatToEditOrAdd(PrAnalysis a) {
        List<String> list = new ArrayList<>();
        if (a == null) return list;
        String summary = a.getSummary();
        if (summary != null) {
            String block = extractSectionValue(summary, "What to Add or Edit");
            if (block != null && !block.isBlank()) {
                String[] lines = block.split("\n");
                for (String l : lines) {
                    String trimmed = l.trim().replaceFirst("^[-*•\\s]+", "").trim();
                    if (!trimmed.isEmpty()) {
                        list.add(escape(cleanFeatureText(trimmed)));
                    }
                }
            }
        }

        if (list.isEmpty()) {
            String findingsJson = a.getFindingsJson();
            if (findingsJson != null && (findingsJson.contains("BUG_DETECTION") || findingsJson.contains("SECURITY"))) {
                list.add("Address detected automated checks: review and fix identified bugs or security warnings.");
            }
        }

        return list;
    }

    record DecisionDisplay(String verdict, String verdictColor, String verdictBg, String rationale) {}

    static DecisionDisplay extractDecisionDisplay(PrAnalysis a) {
        String t = tier(a);
        boolean sec = Boolean.TRUE.equals(a.getSecurityFlag());

        String summary = a.getSummary();
        String rationale = summary != null ? extractSectionValue(summary, "Decision Rationale") : null;

        if (sec || "RED".equalsIgnoreCase(t)) {
            String rat = (rationale != null && !rationale.isBlank()) ? rationale
                    : "High risk signals or security flags detected. Immediate manual inspection required prior to any merge.";
            return new DecisionDisplay("REJECT OR REQUIRE BLOCKING FIXES", RED_C, RED_BG, escape(cleanFeatureText(rat)));
        }

        List<String> edits = extractWhatToEditOrAdd(a);
        boolean hasEdits = edits.stream().anyMatch(e -> !e.toLowerCase().contains("no additional edits") && !e.toLowerCase().contains("complete"));

        if ("GREEN".equalsIgnoreCase(t) && !hasEdits) {
            String rat = (rationale != null && !rationale.isBlank()) ? rationale
                    : "All automated checks passed cleanly. Verified by tests with zero regressions or security risks detected. Safe to merge.";
            return new DecisionDisplay("APPROVE & MERGE", GREEN_C, GREEN_BG, escape(cleanFeatureText(rat)));
        }

        String rat = (rationale != null && !rationale.isBlank()) ? rationale
                : "The changes provide functional value, but requested additions (unit tests or input validations detailed above) should be completed prior to merging.";
        return new DecisionDisplay("REQUEST SPECIFIC CHANGES", YELLOW_C, YELLOW_BG, escape(cleanFeatureText(rat)));
    }

    private static String extractSectionValue(String text, String sectionName) {
        if (text == null) return null;
        Pattern p = Pattern.compile("(?i)^" + Pattern.quote(sectionName) + ":\\s*(.+?)(?=(?:\\n[A-Z][a-zA-Z\\s-]+:)|\\Z)", Pattern.MULTILINE | Pattern.DOTALL);
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }
}
