/**
 * Settings page — rebuilt against the contract-complete page spec
 * (design/frontend/pages/settings.md). Household scaffold from
 * /households/current (404 → create/join empty state), members table with
 * the §3a role/action render-gate, handshake invites with the one-time code
 * reveal (§3b), the HouseholdSettingsDocument editor + resolved planner
 * read-back + audit drawer (§3c), account card (§3e) and the grocery
 * provider connection card (§3f). Invite ACCEPT is its own deep-link
 * surface at /invite (§3d) — not rendered here.
 */

import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { PageHeader } from "../components/PageHeader";
import { KIND_TIME_DEFAULT } from "../mock/settingsAdminSeed";
import { MOCK_NOW_MS } from "../live/dates";
import {
  changeMemberRole,
  changePassword,
  createHousehold,
  createInvite,
  inviteStatus,
  logout,
  MOCK_SEED_PASSWORD,
  pushToast,
  removeMember,
  revokeInvite,
  saveHouseholdSettings,
  saveProviderConnection,
  updateMember,
  useStore,
} from "../mock/store";
import type {
  CustomSlotDefinition,
  HouseholdDto,
  HouseholdMemberDto,
  HouseholdRole,
  HouseholdSettingsDocument,
  HouseholdSettingsDto,
  SlotKind,
} from "../mock/types";

const MEMBER_DOT_COLORS = [
  "var(--mp-terra)",
  "var(--mp-olive)",
  "var(--mp-amber)",
  "var(--mp-mark-planned)",
];

/** displayName fallback: the raw userId stub — no username join exists
 *  anywhere (spec §8 Q2, footnoted below). */
const memberName = (m: HouseholdMemberDto): string => m.displayName ?? m.userId;

function shortWhen(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: "UTC",
  });
}

/* ---- §3a members table ---------------------------------------------------------------- */

function MemberRow({
  m,
  idx,
  myRole,
  isSelf,
}: {
  m: HouseholdMemberDto;
  idx: number;
  myRole: HouseholdRole;
  isSelf: boolean;
}) {
  const navigate = useNavigate();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(m.displayName ?? "");
  const [priority, setPriority] = useState(m.priority);
  const isPrimaryCaller = myRole === "primary";

  const saveEdit = () => {
    // PATCH semantics — null = no change (§3a #10).
    const ok = updateMember(m.id, {
      displayName: name.trim() || null,
      priority,
      expectedVersion: m.version,
    });
    if (ok) setEditing(false);
  };

  const toggleRole = () => {
    changeMemberRole(m.id, {
      newRole: m.role === "primary" ? "member" : "primary",
      expectedVersion: m.version,
    });
  };

  const onRemove = () => {
    const outcome = removeMember(m.id);
    if (outcome === "left") {
      pushToast("You left the household");
      navigate("/settings");
    }
  };

  return (
    <div className="member-row">
      <span
        className="member-dot"
        style={{ background: MEMBER_DOT_COLORS[idx % MEMBER_DOT_COLORS.length] }}
        aria-hidden="true"
      />
      {editing ? (
        <span
          style={{ display: "flex", gap: 6, alignItems: "center", flex: 1, minWidth: 0 }}
        >
          <input
            className="text-input"
            style={{ width: 110 }}
            value={name}
            placeholder="Display name"
            onChange={(e) => setName(e.target.value)}
            aria-label="Display name"
          />
          <input
            type="number"
            className="num-input"
            min={0}
            max={1000}
            value={priority}
            onChange={(e) =>
              setPriority(Math.max(0, Math.min(1000, Number(e.target.value) || 0)))
            }
            aria-label="Priority"
            title="Weights the soft-preference merge for shared slots (0–1000)"
          />
          <button className="btn btn-small" onClick={saveEdit}>
            Save
          </button>
          <button className="btn btn-small" onClick={() => setEditing(false)}>
            Cancel
          </button>
        </span>
      ) : (
        <>
          <span style={{ flex: 1, minWidth: 0 }}>
            <span
              className="member-name"
              style={m.displayName ? undefined : { fontFamily: "monospace", fontSize: 12.5 }}
              title={`userId ${m.userId} · joined ${shortWhen(m.joinedAt)}`}
            >
              {memberName(m)}
            </span>
            {isSelf && <span className="invite-sent"> · you</span>}
            <span className="invite-sent"> · priority {m.priority}</span>
          </span>
          <span
            className="tier-badge"
            style={
              m.role === "primary"
                ? { color: "var(--mp-terra-dark)", borderColor: "var(--mp-terra)" }
                : undefined
            }
          >
            {m.role}
          </span>
          {/* §3a render-gate: primary-only controls; member sees Leave on own row. */}
          {isPrimaryCaller && (
            <>
              <button
                className="btn btn-small"
                onClick={() => setEditing(true)}
                aria-label={`Edit ${memberName(m)}`}
              >
                Edit
              </button>
              <button
                className="btn btn-small"
                onClick={toggleRole}
                title="POST …/members/{id}/role — verb endpoint, not PUT (spec §8 Q3)"
              >
                {m.role === "primary" ? "Demote" : "Make primary"}
              </button>
            </>
          )}
          {(isPrimaryCaller || isSelf) && (
            <button className="btn btn-small" onClick={onRemove}>
              {isSelf ? "Leave" : "Remove"}
            </button>
          )}
        </>
      )}
    </div>
  );
}

/* ---- §3b invites panel ------------------------------------------------------------------ */

function InvitesPanel({ myRole }: { myRole: HouseholdRole }) {
  const invites = useStore((s) => s.household.invites);
  const [role, setRole] = useState<HouseholdRole>("member");
  const [days, setDays] = useState(7);
  const [forUserId, setForUserId] = useState("");
  const [revealed, setRevealed] = useState<{ code: string; expiresAt: string } | null>(
    null,
  );

  const pending = invites.filter((i) => inviteStatus(i) === "PENDING");

  const submit = () => {
    const created = createInvite({
      intendedRole: role,
      // Server caps at now+30d and silently truncates — echo ITS expiresAt.
      expiresAt: new Date(MOCK_NOW_MS + days * 86_400_000).toISOString(),
      issuedForUserId: forUserId.trim() || null,
    });
    if (created?.inviteCode) {
      setRevealed({ code: created.inviteCode, expiresAt: created.expiresAt });
      setForUserId("");
    }
  };

  const copy = (text: string) => {
    void navigator.clipboard?.writeText(text).catch(() => undefined);
    pushToast("Copied to clipboard");
  };

  if (myRole !== "primary") {
    return (
      <div className="mp-card side-card">
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Invites
        </span>
        <div className="inline-note" style={{ marginTop: 8 }}>
          Only the household primary can invite members.
        </div>
      </div>
    );
  }

  return (
    <div className="mp-card side-card">
      <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
        Invites
      </span>

      {pending.map((inv) => {
        const hoursLeft = Math.max(
          0,
          Math.round((Date.parse(inv.expiresAt) - MOCK_NOW_MS) / 3_600_000),
        );
        return (
          <div key={inv.id} className="member-row pending">
            <span className="member-dot pending" aria-hidden="true" />
            <span style={{ flex: 1, minWidth: 0 }}>
              <span className="member-name" style={{ fontWeight: 400 }}>
                {inv.intendedRole} invite
                {inv.issuedForUserId && (
                  <span className="invite-sent"> · for {inv.issuedForUserId}</span>
                )}
              </span>
              <span className="invite-sent">
                {" "}
                · expires in {hoursLeft >= 48 ? `${Math.round(hoursLeft / 24)}d` : `${hoursLeft}h`}
              </span>
            </span>
            <span className="tier-badge" style={{ color: "var(--mp-amber)" }}>
              pending
            </span>
            <button className="btn btn-small" onClick={() => revokeInvite(inv.id)}>
              Revoke
            </button>
          </div>
        );
      })}
      {pending.length === 0 && (
        <div className="inline-note" style={{ marginTop: 8 }}>
          No pending invites.
        </div>
      )}

      {revealed ? (
        <div className="mp-card" style={{ marginTop: 12, padding: 14 }}>
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            Invite created — you won't see this code again
          </span>
          <div
            className="mp-num"
            style={{ fontSize: 22, marginTop: 8, letterSpacing: "0.04em" }}
          >
            {revealed.code}
          </div>
          <div className="invite-sent" style={{ marginTop: 4 }}>
            expires {shortWhen(revealed.expiresAt)} (server-capped at 30 days)
          </div>
          <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
            <button className="btn btn-small" onClick={() => copy(revealed.code)}>
              Copy code
            </button>
            <button
              className="btn btn-small"
              onClick={() => copy(`${location.origin}/invite?code=${revealed.code}`)}
            >
              Copy share link
            </button>
            <button className="btn btn-small" onClick={() => setRevealed(null)}>
              Done
            </button>
          </div>
        </div>
      ) : (
        <div style={{ marginTop: 12 }}>
          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <select
              className="time-select"
              value={role}
              onChange={(e) => setRole(e.target.value as HouseholdRole)}
              aria-label="Intended role"
            >
              <option value="member">member</option>
              <option value="primary">primary</option>
            </select>
            <input
              type="number"
              className="num-input"
              min={1}
              max={30}
              value={days}
              onChange={(e) =>
                setDays(Math.max(1, Math.min(30, Number(e.target.value) || 7)))
              }
              aria-label="Expiry in days"
            />
            <span style={{ fontSize: 12.5, color: "var(--mp-muted)" }}>
              days (max 30)
            </span>
            <button className="btn btn-primary btn-small" onClick={submit}>
              Invite member
            </button>
          </div>
          <details style={{ marginTop: 8 }}>
            <summary style={{ fontSize: 12.5, color: "var(--mp-muted)", cursor: "pointer" }}>
              Restrict to a specific account
            </summary>
            <input
              className="text-input"
              style={{ width: "100%", marginTop: 6 }}
              placeholder="userId (UUID) — paste only; no username lookup exists"
              value={forUserId}
              onChange={(e) => setForUserId(e.target.value)}
              aria-label="Issued for userId"
            />
          </details>
        </div>
      )}

      <div className="grocery-footnote" style={{ marginTop: 12 }}>
        Delivery is the copy-link itself — v1 sends no email (spec §8 Q4).
        Accepting happens on the standalone <Link to="/invite">/invite</Link>{" "}
        page (§3d), never here.
      </div>
    </div>
  );
}

/* ---- §3c slot configuration ----------------------------------------------------------- */

const BUILTIN_KINDS: SlotKind[] = ["breakfast", "lunch", "dinner", "snack"];

function SlotConfigCard({
  settings,
  myRole,
}: {
  settings: HouseholdSettingsDto;
  myRole: HouseholdRole;
}) {
  const resolved = useStore((s) => s.household.resolved);
  const audit = useStore((s) => s.household.settingsAudit);
  // `?? null`, not `?? []`: selectors must return stored references
  // (useSyncExternalStore re-render contract).
  const members = useStore((s) => s.household.current?.members ?? null);
  const [draft, setDraft] = useState<HouseholdSettingsDocument>(settings.document);
  const [draftVersion, setDraftVersion] = useState(settings.version);
  const [newLabel, setNewLabel] = useState("");
  const [newKind, setNewKind] = useState<SlotKind>("snack");
  const readOnly = myRole !== "primary";

  // 409 recovery: server version moved on — re-bind the editor.
  if (draftVersion !== settings.version) {
    setDraft(settings.document);
    setDraftVersion(settings.version);
  }

  const nameOf = (userId: string) =>
    members?.find((m) => m.userId === userId)?.displayName ?? userId;

  const setDefault = (kind: string, patch: Partial<CustomSlotDefinition>) =>
    setDraft({
      ...draft,
      slotDefaults: {
        ...draft.slotDefaults,
        [kind]: { ...draft.slotDefaults[kind], ...patch },
      },
    });

  const addCustom = () => {
    const label = newLabel.trim();
    if (!label) return;
    // Key is slugified client-side: 1–48, ^[a-z0-9-]+$ (§3c).
    const key = label
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 48);
    if (!key || draft.customSlots.some((c) => c.key === key)) {
      pushToast("400 — slot key empty or already used", "warn");
      return;
    }
    setDraft({
      ...draft,
      customSlots: [
        ...draft.customSlots,
        { key, label, backedByKind: newKind, shared: false, headcount: null, timeBudgetMin: null },
      ],
    });
    setNewLabel("");
  };

  const save = () => {
    saveHouseholdSettings({ document: draft, expectedVersion: settings.version });
  };

  return (
    <div className="mp-card side-card">
      <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
        Slot configuration
      </span>
      {readOnly && (
        <div className="inline-note" style={{ marginTop: 6 }}>
          Only the household primary can edit — read-only view.
        </div>
      )}

      {BUILTIN_KINDS.map((kind) => {
        const d = draft.slotDefaults[kind] ?? { shared: false };
        return (
          <div key={kind} className="slot-config-row">
            <span className="slot-config-name" style={{ textTransform: "capitalize" }}>
              {kind}
            </span>
            <input
              type="number"
              className="num-input"
              min={1}
              max={16}
              placeholder={String(draft.defaultHeadcount ?? "")}
              value={d.headcount ?? ""}
              disabled={readOnly}
              onChange={(e) =>
                setDefault(kind, {
                  headcount: e.target.value === "" ? null : Number(e.target.value),
                })
              }
              aria-label={`${kind} headcount`}
              title="Headcount 1–16; blank falls back to the household default"
            />
            <input
              type="number"
              className="num-input"
              min={0}
              max={480}
              placeholder={String(KIND_TIME_DEFAULT[kind])}
              value={d.timeBudgetMin ?? ""}
              disabled={readOnly}
              onChange={(e) =>
                setDefault(kind, {
                  timeBudgetMin: e.target.value === "" ? null : Number(e.target.value),
                })
              }
              aria-label={`${kind} time budget minutes`}
              title={`Time budget in minutes; blank = per-kind default (${KIND_TIME_DEFAULT[kind]})`}
            />
            <button
              type="button"
              className={`filter-chip${d.shared ? " active" : ""}`}
              disabled={readOnly}
              onClick={() => setDefault(kind, { shared: !d.shared })}
              aria-pressed={d.shared}
              title="Shared = household-union constraints; per-person = individual"
            >
              {d.shared ? "Shared" : "Per-person"}
            </button>
          </div>
        );
      })}

      {draft.customSlots.map((c) => (
        <div key={c.key} className="slot-config-row">
          <span className="slot-config-name" title={`key ${c.key} · backed by ${c.backedByKind}`}>
            {c.label}
            <span className="invite-sent"> · {c.backedByKind}</span>
          </span>
          <span className="mp-num" style={{ fontSize: 14 }}>
            {c.timeBudgetMin ?? KIND_TIME_DEFAULT[c.backedByKind]}m
          </span>
          {!readOnly && (
            <button
              className="btn btn-small"
              onClick={() =>
                setDraft({
                  ...draft,
                  customSlots: draft.customSlots.filter((x) => x.key !== c.key),
                })
              }
            >
              Remove
            </button>
          )}
        </div>
      ))}

      {!readOnly && (
        <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
          <input
            className="text-input"
            style={{ flex: 1, minWidth: 0 }}
            placeholder='Custom slot, e.g. "Post-workout shake"'
            value={newLabel}
            onChange={(e) => setNewLabel(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && addCustom()}
            aria-label="Custom slot label"
          />
          <select
            className="time-select"
            value={newKind}
            onChange={(e) => setNewKind(e.target.value as SlotKind)}
            aria-label="Backed by kind"
          >
            {BUILTIN_KINDS.map((k) => (
              <option key={k} value={k}>
                {k}
              </option>
            ))}
          </select>
          <button className="btn btn-small" onClick={addCustom}>
            Add
          </button>
        </div>
      )}

      {!readOnly && (
        <div style={{ display: "flex", gap: 10, alignItems: "center", marginTop: 12 }}>
          <label style={{ fontSize: 13, display: "flex", gap: 8, alignItems: "center" }}>
            Default headcount
            <input
              type="number"
              className="num-input"
              min={1}
              max={16}
              value={draft.defaultHeadcount ?? ""}
              onChange={(e) =>
                setDraft({
                  ...draft,
                  defaultHeadcount:
                    e.target.value === "" ? null : Number(e.target.value),
                })
              }
              aria-label="Default headcount"
            />
          </label>
          <button className="btn btn-primary btn-small" onClick={save}>
            Save configuration
          </button>
        </div>
      )}

      {resolved && (
        <details style={{ marginTop: 14 }}>
          <summary className="mp-label" style={{ cursor: "pointer" }}>
            What the planner sees (resolved)
          </summary>
          <div style={{ marginTop: 8 }}>
            {resolved.slots.map((sl) => (
              <div key={sl.slotKey} className="slot-detail-row">
                <span style={{ textTransform: "capitalize", fontSize: 13 }}>
                  {sl.slotKey.replace(/-/g, " ")}
                </span>
                <span style={{ fontSize: 12.5, color: "var(--mp-muted)" }}>
                  {sl.shared ? "shared" : "per-person"} · {sl.headcount} eating ·{" "}
                  {sl.timeBudgetMin} min
                  {sl.eaterUserIdsIfPerPerson &&
                    ` · ${sl.eaterUserIdsIfPerPerson.map(nameOf).join(", ")}`}
                </span>
              </div>
            ))}
          </div>
        </details>
      )}

      <details style={{ marginTop: 8 }}>
        <summary className="mp-label" style={{ cursor: "pointer" }}>
          Change history
        </summary>
        <div style={{ marginTop: 8 }}>
          {audit.length === 0 && <div className="inline-note">No changes yet.</div>}
          {audit.map((a) => (
            <div key={a.id} className="slot-detail-row">
              <span style={{ fontSize: 12.5, fontFamily: "monospace" }}>{a.fieldPath}</span>
              <span style={{ fontSize: 12.5 }}>
                <span style={{ textDecoration: "line-through", color: "var(--mp-muted)" }}>
                  {JSON.stringify(a.previousValue)}
                </span>{" "}
                <strong>{JSON.stringify(a.newValue)}</strong>
                <span className="invite-sent"> · {shortWhen(a.occurredAt)}</span>
              </span>
            </div>
          ))}
        </div>
      </details>
    </div>
  );
}

/* ---- §3e account card ------------------------------------------------------------------- */

function AccountCard() {
  const navigate = useNavigate();
  const username = useStore((s) => s.session.user?.username ?? "");
  const [current, setCurrent] = useState("");
  const [pw1, setPw1] = useState("");
  const [pw2, setPw2] = useState("");
  const [error, setError] = useState<string | null>(null);

  const lengthOk = pw1.length >= 12 && pw1.length <= 128;
  const valid = current.length > 0 && lengthOk && pw1 === pw2;

  const submit = () => {
    setError(null);
    const outcome = changePassword({ currentPassword: current, newPassword: pw1 });
    if (outcome === "unauthorized") {
      // Generic 401 — never "wrong password"; counts toward the login throttle.
      setError("Invalid credentials.");
      return;
    }
    if (outcome === "conflict") {
      setError("New password must differ from the current one.");
      return;
    }
    setCurrent("");
    setPw1("");
    setPw2("");
    // Fresh Set-Cookie re-issued the calling session; all OTHERS revoked.
    pushToast("Password changed. Other devices have been signed out.");
  };

  const signOut = () => {
    logout();
    navigate("/login");
  };

  return (
    <div className="mp-card side-card">
      <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
        Account
      </span>
      <div className="account-email">
        <span className="mp-label">Signed in as</span>
        <div style={{ fontSize: 14.5, marginTop: 4 }}>{username}</div>
      </div>
      <div style={{ marginTop: 14 }}>
        <span className="mp-label">Change password</span>
        <div style={{ display: "grid", gap: 8, marginTop: 9 }}>
          <input
            type="password"
            className="text-input"
            placeholder="Current password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            aria-label="Current password"
          />
          <input
            type="password"
            className="text-input"
            placeholder="New password (12 characters minimum)"
            value={pw1}
            onChange={(e) => setPw1(e.target.value)}
            aria-label="New password"
          />
          <input
            type="password"
            className="text-input"
            placeholder="Repeat new password"
            value={pw2}
            onChange={(e) => setPw2(e.target.value)}
            aria-label="Repeat new password"
          />
          {error && (
            <div style={{ color: "var(--mp-red)", fontSize: 13 }}>{error}</div>
          )}
          <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
            <button
              className="btn btn-small"
              onClick={submit}
              disabled={!valid}
              title={
                valid
                  ? undefined
                  : "Enter your current password; new passwords must match and be 12–128 characters"
              }
            >
              Update password
            </button>
          </div>
          <div className="grocery-footnote">
            Changing it signs out your other devices; this session is
            re-issued automatically. (Mock current password:{" "}
            {MOCK_SEED_PASSWORD})
          </div>
        </div>
      </div>
      <div style={{ marginTop: 14 }}>
        <button className="btn" onClick={signOut}>
          Sign out
        </button>
      </div>
    </div>
  );
}

/* ---- §3f provider card -------------------------------------------------------------------- */

function ProviderCard() {
  const provider = useStore((s) => s.grocery.providerState);
  const [topN, setTopN] = useState(provider?.refreshTopNIngredients ?? 25);

  if (!provider) {
    return (
      <div className="mp-card side-card">
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Grocery provider
        </span>
        <div className="inline-note" style={{ marginTop: 8 }}>
          No provider connected.
        </div>
        <button
          className="btn btn-primary btn-small"
          style={{ marginTop: 10 }}
          onClick={() => saveProviderConnection({ providerKey: "tesco", enabled: true })}
        >
          Connect Tesco
        </button>
      </div>
    );
  }

  const sessionExpired =
    provider.sessionExpiresAt != null &&
    Date.parse(provider.sessionExpiresAt) < MOCK_NOW_MS;

  return (
    <div className="mp-card side-card">
      <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
        Grocery provider · {provider.providerKey}
      </span>
      <div style={{ marginTop: 8, fontSize: 13.5 }}>
        {provider.enabled ? "Connected" : "Paused"}
        {sessionExpired && (
          <span style={{ color: "var(--mp-amber)" }}> · session needs attention</span>
        )}
        {provider.lastFailureReason && (
          <span style={{ color: "var(--mp-amber)" }}>
            {" "}
            · {provider.lastFailureReason} ({provider.consecutiveFailures} consecutive)
          </span>
        )}
      </div>
      <div className="mute-row" style={{ marginTop: 8 }}>
        <span style={{ fontSize: 13.5 }}>Ordering enabled</span>
        <button
          type="button"
          className={`switch${provider.enabled ? " on" : ""}`}
          role="switch"
          aria-checked={provider.enabled}
          aria-label="Provider ordering enabled"
          onClick={() =>
            saveProviderConnection({
              providerKey: provider.providerKey,
              enabled: !provider.enabled,
            })
          }
        >
          <span className="switch-knob" />
        </button>
      </div>
      <div className="mute-row">
        <span style={{ fontSize: 13.5 }}>Scheduled price refresh</span>
        <button
          type="button"
          className={`switch${provider.scheduledRefreshEnabled ? " on" : ""}`}
          role="switch"
          aria-checked={provider.scheduledRefreshEnabled}
          aria-label="Scheduled refresh enabled"
          onClick={() =>
            saveProviderConnection({
              providerKey: provider.providerKey,
              scheduledRefreshEnabled: !provider.scheduledRefreshEnabled,
            })
          }
        >
          <span className="switch-knob" />
        </button>
      </div>
      <label
        style={{
          display: "flex",
          gap: 8,
          alignItems: "center",
          marginTop: 8,
          fontSize: 13,
        }}
      >
        Refresh top
        <input
          type="number"
          className="num-input"
          min={0}
          max={200}
          value={topN}
          onChange={(e) => {
            const v = Math.max(0, Math.min(200, Number(e.target.value) || 0));
            setTopN(v);
          }}
          onBlur={() =>
            saveProviderConnection({
              providerKey: provider.providerKey,
              refreshTopNIngredients: topN,
            })
          }
          aria-label="Refresh top N ingredients"
        />
        ingredients
      </label>
      <div className="grocery-footnote" style={{ marginTop: 12 }}>
        Orders and the shopping list live on{" "}
        <Link to="/groceries">/groceries</Link> — this card only manages the
        connection.
      </div>
    </div>
  );
}

/* ---- empty state (the #1 404) -------------------------------------------------------------- */

function NoHousehold() {
  const [name, setName] = useState("");
  return (
    <div className="mp-card side-card" style={{ maxWidth: 460, marginTop: 24 }}>
      <span className="mp-serif" style={{ fontSize: 22 }}>
        You're not in a household yet.
      </span>
      <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
        <input
          className="text-input"
          style={{ flex: 1 }}
          placeholder="Household name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          aria-label="Household name"
        />
        <button
          className="btn btn-primary"
          disabled={!name.trim()}
          onClick={() => createHousehold(name)}
        >
          Create household
        </button>
      </div>
      <div className="grocery-footnote" style={{ marginTop: 12 }}>
        Got an invite code instead? Redeem it on{" "}
        <Link to="/invite">the invite page</Link>.
      </div>
    </div>
  );
}

/* ---- the page -------------------------------------------------------------------------------- */

function HouseholdSection({ household }: { household: HouseholdDto }) {
  const settings = useStore((s) => s.household.settings);
  const myUserId = useStore((s) => s.session.user?.userId);
  const adminVisible = useStore((s) => s.admin.probeOutcome === "admin");
  const myRole: HouseholdRole =
    household.members.find((m) => m.userId === myUserId)?.role ?? "member";

  return (
    <div className="settings-grid">
      <div className="mp-card side-card">
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Household
        </span>
        {/* Read-only name: no rename endpoint exists (spec §8 Q1). */}
        <div style={{ fontSize: 16, fontWeight: 600, marginTop: 6 }}>
          {household.name}
        </div>
        <div className="invite-sent">created {shortWhen(household.createdAt)}</div>
        <div style={{ marginTop: 8 }}>
          {household.members.map((m, idx) => (
            <MemberRow
              key={m.id}
              m={m}
              idx={idx}
              myRole={myRole}
              isSelf={m.userId === myUserId}
            />
          ))}
        </div>
        <div className="grocery-footnote" style={{ marginTop: 12 }}>
          Members are identified by userId only — a member without a display
          name renders as the raw id, and "issued for" targeting needs a
          pasted UUID (backend gap, spec §8 Q2). Household rename doesn't
          exist in the contract (§8 Q1).
        </div>
        {adminVisible && (
          <div style={{ marginTop: 10 }}>
            <Link to="/admin" className="back-link">
              System status (admin) →
            </Link>
          </div>
        )}
      </div>

      <InvitesPanel myRole={myRole} />

      {settings && <SlotConfigCard settings={settings} myRole={myRole} />}

      <AccountCard />

      <ProviderCard />
    </div>
  );
}

export function Settings() {
  const household = useStore((s) => s.household.current);

  return (
    <div>
      <PageHeader
        title="Household & settings"
        meta={
          household
            ? `${household.name} · ${household.members.length} member${
                household.members.length === 1 ? "" : "s"
              }`
            : "No household — create one or redeem an invite"
        }
      />
      {household ? <HouseholdSection household={household} /> : <NoHousehold />}
    </div>
  );
}
