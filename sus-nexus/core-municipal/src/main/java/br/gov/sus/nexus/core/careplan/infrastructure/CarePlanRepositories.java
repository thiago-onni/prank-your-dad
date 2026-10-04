package br.gov.sus.nexus.core.careplan.infrastructure;

import br.gov.sus.nexus.core.careplan.domain.CareGap;
import br.gov.sus.nexus.core.careplan.domain.CarePlan;
import br.gov.sus.nexus.core.careplan.domain.CarePlanItem;
import br.gov.sus.nexus.core.careplan.domain.Protocol;
import br.gov.sus.nexus.core.careplan.domain.ProtocolVersion;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositórios Panache do módulo careplan (RLS garante o tenant; protocolos: tenant ou global). */
public final class CarePlanRepositories {

  private CarePlanRepositories() {}

  @ApplicationScoped
  public static class Protocols implements PanacheRepositoryBase<Protocol, String> {
    /** Protocolo visível por (linha, nome): o do tenant tem precedência sobre o global. */
    public Optional<Protocol> findByLineAndName(String careLine, String name) {
      return find("careLine = ?1 and name = ?2 order by tenantId desc nulls last", careLine, name)
          .firstResultOptional();
    }

    public List<Protocol> byCareLine(String careLine) {
      return list("careLine = ?1", careLine);
    }
  }

  @ApplicationScoped
  public static class Versions implements PanacheRepositoryBase<ProtocolVersion, String> {

    public Optional<ProtocolVersion> find(String protocolId, String version) {
      return find(
              "protocolId = ?1 and version = ?2 order by tenantId desc nulls last",
              protocolId,
              version)
          .firstResultOptional();
    }

    /** Versão vigente: ativa, com effective_from ≤ agora; a do tenant precede a global. */
    public Optional<ProtocolVersion> current(String protocolId) {
      return find(
              "protocolId = ?1 and status = 'active' and (effectiveFrom is null or effectiveFrom <= ?2)"
                  + " order by tenantId desc nulls last, effectiveFrom desc",
              protocolId,
              Instant.now())
          .firstResultOptional();
    }

    public List<ProtocolVersion> byProtocol(String protocolId) {
      return list("protocolId = ?1 order by createdAt", protocolId);
    }

    /** Versões ativas do tenant (não globais) de um protocolo — para troca de vigência. */
    public List<ProtocolVersion> tenantActive(String protocolId, String tenantId) {
      return list("protocolId = ?1 and status = 'active' and tenantId = ?2", protocolId, tenantId);
    }

    public List<ProtocolVersion> listAll(String careLine, String status, List<String> protocolIds) {
      StringBuilder q = new StringBuilder("1 = 1");
      List<Object> params = new ArrayList<>();
      if (protocolIds != null) {
        if (protocolIds.isEmpty()) {
          return List.of();
        }
        params.add(protocolIds);
        q.append(" and protocolId in ?").append(params.size());
      }
      if (status != null) {
        params.add(status);
        q.append(" and status = ?").append(params.size());
      }
      q.append(" order by protocolId, createdAt");
      return find(q.toString(), params.toArray()).list();
    }
  }

  @ApplicationScoped
  public static class Plans implements PanacheRepositoryBase<CarePlan, String> {

    public Optional<CarePlan> activeFor(String citizenId, String careLine) {
      return find(
              "citizenId = ?1 and careLine = ?2 and status = 'active' order by id desc",
              citizenId,
              careLine)
          .firstResultOptional();
    }

    public List<CarePlan> activeByCitizen(String citizenId) {
      return list("citizenId = ?1 and status = 'active' order by id", citizenId);
    }

    public List<String> activeCareLines(String citizenId) {
      return getEntityManager()
          .createQuery(
              "select distinct p.careLine from CarePlan p where p.citizenId = ?1"
                  + " and p.status = 'active' order by p.careLine",
              String.class)
          .setParameter(1, citizenId)
          .getResultList();
    }

    /** Planos ativos sem evento assistencial há mais de {@code lostDays} do protocolo. */
    public List<CarePlan> active() {
      return list("status = 'active' order by id");
    }

    public List<CarePlan> list(
        String citizenId,
        String careLine,
        String status,
        String teamIne,
        String cnes,
        String beforeId,
        int limitPlusOne) {
      StringBuilder q = new StringBuilder("1 = 1");
      List<Object> params = new ArrayList<>();
      if (citizenId != null) {
        params.add(citizenId);
        q.append(" and citizenId = ?").append(params.size());
      }
      if (careLine != null) {
        params.add(careLine);
        q.append(" and careLine = ?").append(params.size());
      }
      if (status != null) {
        params.add(status);
        q.append(" and status = ?").append(params.size());
      }
      if (teamIne != null) {
        params.add(teamIne);
        q.append(" and teamIne = ?").append(params.size());
      }
      if (cnes != null) {
        params.add(cnes);
        q.append(" and healthUnitCnes = ?").append(params.size());
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
  public static class Items implements PanacheRepositoryBase<CarePlanItem, String> {
    public List<CarePlanItem> byPlan(String planId) {
      return list("carePlanId = ?1 order by sequence, expectedBy, id", planId);
    }

    /** Itens em aberto com prazo + carência vencidos (varredura de lacunas). */
    public List<CarePlanItem> overdue(Instant now) {
      return list(
          "status in ('planned','scheduled') and expectedBy is not null and expectedBy < ?1"
              + " order by expectedBy, id",
          now);
    }
  }

  @ApplicationScoped
  public static class Gaps implements PanacheRepositoryBase<CareGap, String> {

    public Optional<CareGap> openByItem(String itemId) {
      return find("itemId = ?1 and status = 'open'", itemId).firstResultOptional();
    }

    public Optional<CareGap> openByPlanKind(String planId, String kind) {
      return find(
              "carePlanId = ?1 and gapKind = ?2 and itemId is null and status = 'open'",
              planId,
              kind)
          .firstResultOptional();
    }

    public Optional<CareGap> openByOrigin(String originRef, String kind) {
      return find("originRef = ?1 and gapKind = ?2 and status = 'open'", originRef, kind)
          .firstResultOptional();
    }

    public List<CareGap> openByOrigin(String originRef) {
      return list("originRef = ?1 and status = 'open'", originRef);
    }

    public List<CareGap> openByPlan(String planId) {
      return list("carePlanId = ?1 and status = 'open'", planId);
    }

    public long countOpen(String citizenId) {
      return count("citizenId = ?1 and status = 'open'", citizenId);
    }

    public List<CareGap> list(
        String careLine,
        String kind,
        String cnes,
        String teamIne,
        String microarea,
        String status,
        Instant expectedBefore,
        String beforeId,
        int limitPlusOne) {
      StringBuilder q = new StringBuilder("1 = 1");
      List<Object> params = new ArrayList<>();
      if (careLine != null) {
        params.add(careLine);
        q.append(" and careLine = ?").append(params.size());
      }
      if (kind != null) {
        params.add(kind);
        q.append(" and gapKind = ?").append(params.size());
      }
      if (cnes != null) {
        params.add(cnes);
        q.append(" and healthUnitCnes = ?").append(params.size());
      }
      if (teamIne != null) {
        params.add(teamIne);
        q.append(" and teamIne = ?").append(params.size());
      }
      if (microarea != null) {
        params.add(microarea);
        q.append(" and microarea = ?").append(params.size());
      }
      if (status != null) {
        params.add(status);
        q.append(" and status = ?").append(params.size());
      }
      if (expectedBefore != null) {
        params.add(expectedBefore);
        q.append(" and expectedBy <= ?").append(params.size());
      }
      if (beforeId != null) {
        params.add(beforeId);
        q.append(" and id < ?").append(params.size());
      }
      q.append(" order by id desc");
      return find(q.toString(), params.toArray()).page(0, limitPlusOne).list();
    }
  }
}
