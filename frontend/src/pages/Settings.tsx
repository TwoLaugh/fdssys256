import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { PageHeader } from "../components/PageHeader";
import {
  changePassword,
  inviteMember,
  revokeInvite,
  toggleSlotShared,
  useStore,
} from "../mock/store";
import type { MemberRole } from "../mock/types";

const ROLE_LABEL: Record<MemberRole, string> = {
  owner: "Owner",
  adult: "Adult",
  child: "Child",
};

export function Settings() {
  const household = useStore((s) => s.household);
  const navigate = useNavigate();
  const [inviteEmail, setInviteEmail] = useState("");
  const [pw1, setPw1] = useState("");
  const [pw2, setPw2] = useState("");
  const [pwChanged, setPwChanged] = useState(false);

  const inviteValid = /\S+@\S+\.\S+/.test(inviteEmail.trim());
  const pwValid = pw1.length >= 8 && pw1 === pw2;

  const sendInvite = () => {
    if (!inviteValid) return;
    inviteMember(inviteEmail.trim());
    setInviteEmail("");
  };

  const submitPassword = () => {
    if (!pwValid) return;
    changePassword();
    setPw1("");
    setPw2("");
    setPwChanged(true);
  };

  return (
    <div>
      <PageHeader
        title="Household & settings"
        meta={`${household.name} · ${household.members.length} members`}
      />

      <div className="settings-grid">
        <div className="mp-card side-card">
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            Household
          </span>
          <div style={{ marginTop: 8 }}>
            {household.members.map((m) => (
              <div key={m.id} className="member-row">
                <span
                  className="member-dot"
                  style={{ background: m.color }}
                  aria-hidden="true"
                />
                <span className="member-name">{m.name}</span>
                <span className="tier-badge">{ROLE_LABEL[m.role]}</span>
              </div>
            ))}
            {household.invites.map((inv) => (
              <div key={inv.email} className="member-row pending">
                <span className="member-dot pending" aria-hidden="true" />
                <span style={{ flex: 1, minWidth: 0 }}>
                  <span className="member-name" style={{ fontWeight: 400 }}>
                    {inv.email}
                  </span>
                  <span className="invite-sent"> · {inv.sent}</span>
                </span>
                <span className="tier-badge">Pending invite</span>
                <button
                  className="btn btn-small"
                  onClick={() => revokeInvite(inv.email)}
                >
                  Revoke
                </button>
              </div>
            ))}
          </div>
          <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
            <input
              type="email"
              className="text-input"
              style={{ flex: 1, minWidth: 0 }}
              placeholder="Invite by email"
              value={inviteEmail}
              onChange={(e) => setInviteEmail(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && sendInvite()}
              aria-label="Invite by email"
            />
            <button
              className="btn btn-small"
              onClick={sendInvite}
              disabled={!inviteValid}
            >
              Send invite
            </button>
          </div>
        </div>

        <div className="mp-card side-card">
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            Slot configuration
          </span>
          {household.slotConfig.map((dayType) => (
            <div key={dayType.dayType} style={{ marginTop: 14 }}>
              <span className="mp-label">{dayType.dayType}</span>
              {dayType.slots.map((sl) => (
                <div key={sl.slot} className="slot-config-row">
                  <span
                    className="slot-config-name"
                    style={{ textTransform: "capitalize" }}
                  >
                    {sl.slot}
                  </span>
                  <span className="mp-num" style={{ fontSize: 15 }}>
                    {sl.time}
                  </span>
                  <button
                    type="button"
                    className={`filter-chip${sl.shared ? " active" : ""}`}
                    onClick={() => toggleSlotShared(dayType.dayType, sl.slot)}
                    aria-pressed={sl.shared}
                  >
                    {sl.shared ? "Shared" : "Per-person"}
                  </button>
                </div>
              ))}
            </div>
          ))}
          <div className="grocery-footnote" style={{ marginTop: 14 }}>
            Shared slots cook once for everyone; per-person slots plan
            individual portions.
          </div>
        </div>

        <div className="mp-card side-card">
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            Account
          </span>
          <div className="account-email">
            <span className="mp-label">Email</span>
            <div style={{ fontSize: 14.5, marginTop: 4 }}>{household.email}</div>
          </div>
          <div style={{ marginTop: 14 }}>
            <span className="mp-label">Change password</span>
            <div style={{ display: "grid", gap: 8, marginTop: 9 }}>
              <input
                type="password"
                className="text-input"
                placeholder="New password (min 8 characters)"
                value={pw1}
                onChange={(e) => {
                  setPw1(e.target.value);
                  setPwChanged(false);
                }}
                aria-label="New password"
              />
              <input
                type="password"
                className="text-input"
                placeholder="Repeat new password"
                value={pw2}
                onChange={(e) => {
                  setPw2(e.target.value);
                  setPwChanged(false);
                }}
                aria-label="Repeat new password"
              />
              <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
                <button
                  className="btn btn-small"
                  onClick={submitPassword}
                  disabled={!pwValid}
                  title={
                    pwValid
                      ? undefined
                      : "Passwords must match and be at least 8 characters"
                  }
                >
                  Update password
                </button>
                {pwChanged && (
                  <span className="pw-success">✓ Password updated</span>
                )}
              </div>
            </div>
          </div>
        </div>

        <div className="mp-card side-card">
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            Danger zone
          </span>
          <div style={{ display: "flex", gap: 10, marginTop: 14 }}>
            <button className="btn" onClick={() => navigate("/login")}>
              Sign out
            </button>
            <button
              className="btn btn-danger"
              disabled
              title="Account deletion (with GDPR export) lands in v1.5"
            >
              Delete account
            </button>
          </div>
          <div className="grocery-footnote" style={{ marginTop: 14 }}>
            Deletion ships with the GDPR export in v1.5.
          </div>
          <div style={{ marginTop: 16 }}>
            <Link to="/admin" className="back-link">
              System status (admin) →
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
