package br.gov.sus.nexus.core.hospital.infrastructure;

import br.gov.sus.nexus.core.hospital.domain.CounterReferral;
import br.gov.sus.nexus.core.hospital.domain.HospitalBedMovement;
import br.gov.sus.nexus.core.hospital.domain.HospitalDischarge;
import br.gov.sus.nexus.core.hospital.domain.HospitalEpisode;
import br.gov.sus.nexus.core.hospital.domain.HospitalEpisodeSourceLink;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositórios Panache do módulo hospital (RLS garante o tenant). */
public final class HospitalRepositories {

  private HospitalRepositories() {}

  @ApplicationScoped
  public static class Episodes implements PanacheRepositoryBase<HospitalEpisode, String> {

    /** Episódio anterior do cidadão com alta (não óbito) antes de {@code admittedAt}. */
    public Optional<HospitalEpisode> previousDischarged(
        String citizenId, Instant admittedAt, String excludeId) {
      return find(
              "citizenId = ?1 and status = 'discharged' and dischargedAt is not null"
                  + " and dischargedAt <= ?2 and id <> ?3 order by dischargedAt desc",
              citizenId,
              admittedAt,
              excludeId)
          .firstResultOptional();
    }

    public Optional<Instant> lastDischargeAt(String citizenId) {
      return find(
              "citizenId = ?1 and dischargedAt is not null order by dischargedAt desc", citizenId)
          .firstResultOptional()
          .map(e -> e.dischargedAt);
    }

    public List<HospitalEpisode> list(
        String citizenId,
        String hospitalCnes,
        String status,
        Instant dischargedFrom,
        Instant dischargedTo,
        String referenceCnes,
        String followupStatus,
        String beforeId,
        int limitPlusOne) {
      StringBuilder q = new StringBuilder("1 = 1");
      List<Object> params = new ArrayList<>();
      if (citizenId != null) {
        params.add(citizenId);
        q.append(" and citizenId = ?").append(params.size());
      }
      if (hospitalCnes != null) {
        params.add(hospitalCnes);
        q.append(" and hospitalCnes = ?").append(params.size());
      }
      if (status != null) {
        params.add(status);
        q.append(" and status = ?").append(params.size());
      }
      if (dischargedFrom != null) {
        params.add(dischargedFrom);
        q.append(" and dischargedAt >= ?").append(params.size());
      }
      if (dischargedTo != null) {
        params.add(dischargedTo);
        q.append(" and dischargedAt <= ?").append(params.size());
      }
      if (referenceCnes != null) {
        params.add(referenceCnes);
        q.append(" and referenceHealthUnitCnes = ?").append(params.size());
      }
      if (followupStatus != null) {
        params.add(followupStatus);
        q.append(" and followupStatus = ?").append(params.size());
      }
      if (beforeId != null) {
        params.add(beforeId);
        q.append(" and id < ?").append(params.size());
      }
      q.append(" order by id desc");
      return find(q.toString(), params.toArray()).page(0, limitPlusOne).list();
    }
  }

  @ApplicationScoped
  public static class Movements implements PanacheRepositoryBase<HospitalBedMovement, String> {
    public List<HospitalBedMovement> byEpisode(String episodeId) {
      return list("episodeId = ?1 order by occurredAt, recordedAt", episodeId);
    }
  }

  @ApplicationScoped
  public static class Discharges implements PanacheRepositoryBase<HospitalDischarge, String> {
    public Optional<HospitalDischarge> latest(String episodeId) {
      return find("episodeId = ?1 order by dischargedAt desc, id desc", episodeId)
          .firstResultOptional();
    }
  }

  @ApplicationScoped
  public static class CounterReferrals implements PanacheRepositoryBase<CounterReferral, String> {
    public Optional<CounterReferral> latest(String episodeId) {
      return find("episodeId = ?1 order by receivedAt desc, id desc", episodeId)
          .firstResultOptional();
    }
  }

  @ApplicationScoped
  public static class SourceLinks
      implements PanacheRepositoryBase<HospitalEpisodeSourceLink, String> {
    public Optional<HospitalEpisodeSourceLink> findBySource(
        String tenantId, String sourceSystem, String sourceRecordId) {
      return find(
              "tenantId = ?1 and sourceSystem = ?2 and sourceRecordId = ?3",
              tenantId,
              sourceSystem,
              sourceRecordId)
          .firstResultOptional();
    }
  }
}
