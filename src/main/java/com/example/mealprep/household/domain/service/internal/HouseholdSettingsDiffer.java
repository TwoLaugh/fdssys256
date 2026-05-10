package com.example.mealprep.household.domain.service.internal;

import com.example.mealprep.household.domain.entity.HouseholdSettingsAuditLog;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.CustomSlotDefinition;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.SlotDefault;
import com.example.mealprep.household.domain.entity.SlotKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Computes the per-field diff between two {@link HouseholdSettingsDocument} states. For each
 * changed dotted path, emits one {@link HouseholdSettingsAuditLog} row carrying the value at that
 * path serialised via Jackson.
 *
 * <p>Diff scope:
 *
 * <ul>
 *   <li>Top-level fields: {@code defaultHeadcount}, {@code scheduling}.
 *   <li>{@code slotDefaults.<slotKind>.{shared,headcount,timeBudgetMin}} — keyed by slot kind, then
 *       per nested field.
 *   <li>{@code customSlots.<key>} — keyed by {@code key}; an add/remove emits a single audit row
 *       carrying the whole slot value as the JSON node, with {@code null}-equivalent on the missing
 *       side.
 * </ul>
 *
 * <p>Pure function — no I/O, no DB. Stateless; safe for {@code @Component} reuse.
 */
@Component
public class HouseholdSettingsDiffer {

  private final ObjectMapper objectMapper;

  public HouseholdSettingsDiffer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Compute change-set + audit rows. {@code changedFieldPaths} is populated as a side-effect (in
   * insertion order) so callers can attach the same set to {@code HouseholdSettingsChangedEvent}.
   */
  public List<HouseholdSettingsAuditLog> diff(
      UUID householdSettingsId,
      UUID actorUserId,
      HouseholdSettingsDocument previous,
      HouseholdSettingsDocument next,
      Set<String> changedFieldPaths) {

    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(next, "next");
    Objects.requireNonNull(changedFieldPaths, "changedFieldPaths");

    Instant occurredAt = Instant.now();
    List<HouseholdSettingsAuditLog> rows = new ArrayList<>();

    // ---- top-level: defaultHeadcount ----
    if (!Objects.equals(previous.defaultHeadcount(), next.defaultHeadcount())) {
      rows.add(
          row(
              householdSettingsId,
              actorUserId,
              "defaultHeadcount",
              toNode(previous.defaultHeadcount()),
              toNode(next.defaultHeadcount()),
              occurredAt));
      changedFieldPaths.add("defaultHeadcount");
    }

    // ---- top-level: scheduling ----
    if (!Objects.equals(previous.scheduling(), next.scheduling())) {
      rows.add(
          row(
              householdSettingsId,
              actorUserId,
              "scheduling",
              toNode(previous.scheduling()),
              toNode(next.scheduling()),
              occurredAt));
      changedFieldPaths.add("scheduling");
    }

    // ---- slotDefaults map ----
    diffSlotDefaults(
        householdSettingsId,
        actorUserId,
        previous.slotDefaults(),
        next.slotDefaults(),
        occurredAt,
        rows,
        changedFieldPaths);

    // ---- customSlots list (keyed by `key`) ----
    diffCustomSlots(
        householdSettingsId,
        actorUserId,
        previous.customSlots(),
        next.customSlots(),
        occurredAt,
        rows,
        changedFieldPaths);

    return rows;
  }

  private void diffSlotDefaults(
      UUID householdSettingsId,
      UUID actorUserId,
      Map<SlotKind, SlotDefault> previous,
      Map<SlotKind, SlotDefault> next,
      Instant occurredAt,
      List<HouseholdSettingsAuditLog> rows,
      Set<String> changedFieldPaths) {
    Map<SlotKind, SlotDefault> p = previous == null ? Collections.emptyMap() : previous;
    Map<SlotKind, SlotDefault> n = next == null ? Collections.emptyMap() : next;
    Set<SlotKind> allKinds = new LinkedHashSet<>();
    allKinds.addAll(p.keySet());
    allKinds.addAll(n.keySet());

    for (SlotKind kind : allKinds) {
      SlotDefault prev = p.get(kind);
      SlotDefault now = n.get(kind);
      if (Objects.equals(prev, now)) {
        continue;
      }
      String prefix = "slotDefaults." + kind.name();
      // shared
      Boolean prevShared = prev == null ? null : prev.shared();
      Boolean nowShared = now == null ? null : now.shared();
      if (!Objects.equals(prevShared, nowShared)) {
        rows.add(
            row(
                householdSettingsId,
                actorUserId,
                prefix + ".shared",
                toNode(prevShared),
                toNode(nowShared),
                occurredAt));
        changedFieldPaths.add(prefix + ".shared");
      }
      Integer prevHc = prev == null ? null : prev.headcount();
      Integer nowHc = now == null ? null : now.headcount();
      if (!Objects.equals(prevHc, nowHc)) {
        rows.add(
            row(
                householdSettingsId,
                actorUserId,
                prefix + ".headcount",
                toNode(prevHc),
                toNode(nowHc),
                occurredAt));
        changedFieldPaths.add(prefix + ".headcount");
      }
      Integer prevTb = prev == null ? null : prev.timeBudgetMin();
      Integer nowTb = now == null ? null : now.timeBudgetMin();
      if (!Objects.equals(prevTb, nowTb)) {
        rows.add(
            row(
                householdSettingsId,
                actorUserId,
                prefix + ".timeBudgetMin",
                toNode(prevTb),
                toNode(nowTb),
                occurredAt));
        changedFieldPaths.add(prefix + ".timeBudgetMin");
      }
    }
  }

  private void diffCustomSlots(
      UUID householdSettingsId,
      UUID actorUserId,
      List<CustomSlotDefinition> previous,
      List<CustomSlotDefinition> next,
      Instant occurredAt,
      List<HouseholdSettingsAuditLog> rows,
      Set<String> changedFieldPaths) {
    Map<String, CustomSlotDefinition> p = byKey(previous);
    Map<String, CustomSlotDefinition> n = byKey(next);
    Set<String> allKeys = new LinkedHashSet<>();
    allKeys.addAll(p.keySet());
    allKeys.addAll(n.keySet());
    for (String key : allKeys) {
      CustomSlotDefinition prev = p.get(key);
      CustomSlotDefinition now = n.get(key);
      if (Objects.equals(prev, now)) {
        continue;
      }
      if (prev == null || now == null) {
        // Whole-slot add or remove.
        String path = "customSlots." + key;
        rows.add(
            row(householdSettingsId, actorUserId, path, toNode(prev), toNode(now), occurredAt));
        changedFieldPaths.add(path);
        continue;
      }
      // Per-field diff.
      String prefix = "customSlots." + key;
      if (!Objects.equals(prev.label(), now.label())) {
        rows.add(
            row(
                householdSettingsId,
                actorUserId,
                prefix + ".label",
                toNode(prev.label()),
                toNode(now.label()),
                occurredAt));
        changedFieldPaths.add(prefix + ".label");
      }
      if (!Objects.equals(prev.backedByKind(), now.backedByKind())) {
        rows.add(
            row(
                householdSettingsId,
                actorUserId,
                prefix + ".backedByKind",
                toNode(prev.backedByKind()),
                toNode(now.backedByKind()),
                occurredAt));
        changedFieldPaths.add(prefix + ".backedByKind");
      }
      if (prev.shared() != now.shared()) {
        rows.add(
            row(
                householdSettingsId,
                actorUserId,
                prefix + ".shared",
                toNode(prev.shared()),
                toNode(now.shared()),
                occurredAt));
        changedFieldPaths.add(prefix + ".shared");
      }
      if (!Objects.equals(prev.headcount(), now.headcount())) {
        rows.add(
            row(
                householdSettingsId,
                actorUserId,
                prefix + ".headcount",
                toNode(prev.headcount()),
                toNode(now.headcount()),
                occurredAt));
        changedFieldPaths.add(prefix + ".headcount");
      }
      if (!Objects.equals(prev.timeBudgetMin(), now.timeBudgetMin())) {
        rows.add(
            row(
                householdSettingsId,
                actorUserId,
                prefix + ".timeBudgetMin",
                toNode(prev.timeBudgetMin()),
                toNode(now.timeBudgetMin()),
                occurredAt));
        changedFieldPaths.add(prefix + ".timeBudgetMin");
      }
    }
  }

  private static Map<String, CustomSlotDefinition> byKey(List<CustomSlotDefinition> entries) {
    if (entries == null || entries.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, CustomSlotDefinition> result = new LinkedHashMap<>(entries.size());
    for (CustomSlotDefinition entry : entries) {
      if (entry != null && entry.key() != null) {
        result.put(entry.key(), entry);
      }
    }
    return result;
  }

  private JsonNode toNode(Object value) {
    if (value == null) {
      return NullNode.getInstance();
    }
    return objectMapper.valueToTree(value);
  }

  private static HouseholdSettingsAuditLog row(
      UUID householdSettingsId,
      UUID actorUserId,
      String fieldPath,
      JsonNode previousValue,
      JsonNode newValue,
      Instant occurredAt) {
    return HouseholdSettingsAuditLog.builder()
        .id(UUID.randomUUID())
        .householdSettingsId(householdSettingsId)
        .actorUserId(actorUserId)
        .fieldPath(fieldPath)
        .previousValueJson(previousValue)
        .newValueJson(newValue)
        .occurredAt(occurredAt)
        .build();
  }
}
