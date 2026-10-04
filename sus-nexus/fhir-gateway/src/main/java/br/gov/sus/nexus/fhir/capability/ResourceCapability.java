package br.gov.sus.nexus.fhir.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Capacidades implementadas para um tipo de recurso: interações, parâmetros de busca e perfil. */
public final class ResourceCapability {

  private final String type;
  private final Set<Interaction> interactions;
  private final Map<String, SearchParamDef> searchParams;
  private final String profile;
  private final List<String> includes;
  private final List<String> operations;

  private ResourceCapability(Builder b) {
    this.type = b.type;
    this.interactions = Collections.unmodifiableSet(EnumSet.copyOf(b.interactions));
    this.searchParams = Collections.unmodifiableMap(new LinkedHashMap<>(b.searchParams));
    this.profile = b.profile;
    this.includes = List.copyOf(b.includes);
    this.operations = List.copyOf(b.operations);
  }

  public static Builder of(String type) {
    return new Builder(type);
  }

  public String type() {
    return type;
  }

  public Set<Interaction> interactions() {
    return interactions;
  }

  public Map<String, SearchParamDef> searchParams() {
    return searchParams;
  }

  /** Canônico do perfil implementado para o tipo, se houver (exige {@code meta.profile}). */
  public Optional<String> profile() {
    return Optional.ofNullable(profile);
  }

  public boolean supports(Interaction interaction) {
    return interactions.contains(interaction);
  }

  /** Parâmetros de referência aceitos em {@code _include} (nomes, ex.: {@code patient}). */
  public List<String> includes() {
    return includes;
  }

  public boolean supportsInclude(String paramName) {
    return includes.contains(paramName);
  }

  /** Operações de instância implementadas para o tipo (nomes sem {@code $}, ex.: everything). */
  public List<String> operations() {
    return operations;
  }

  public boolean supportsOperation(String name) {
    return operations.contains(name);
  }

  public Optional<SearchParamDef> searchParam(String name) {
    return Optional.ofNullable(searchParams.get(name));
  }

  /** Builder fluente usado pelo {@link CapabilityRegistry}. */
  public static final class Builder {
    private final String type;
    private final EnumSet<Interaction> interactions = EnumSet.noneOf(Interaction.class);
    private final Map<String, SearchParamDef> searchParams = new LinkedHashMap<>();
    private String profile;
    private final List<String> includes = new ArrayList<>();
    private final List<String> operations = new ArrayList<>();

    private Builder(String type) {
      this.type = type;
    }

    public Builder interactions(Interaction... list) {
      Collections.addAll(interactions, list);
      return this;
    }

    public Builder readOnly() {
      return interactions(
          Interaction.READ,
          Interaction.VREAD,
          Interaction.SEARCH_TYPE,
          Interaction.HISTORY_INSTANCE,
          Interaction.HISTORY_TYPE);
    }

    /** Leitura completa + create/update/delete (exclusão lógica). */
    public Builder readWrite() {
      return readOnly().interactions(Interaction.CREATE, Interaction.UPDATE, Interaction.DELETE);
    }

    /** Registra uma operação de instância ({@code GET [type]/[id]/$name}). */
    public Builder operation(String name) {
      operations.add(name);
      return this;
    }

    public Builder param(SearchParamDef def) {
      searchParams.put(def.name(), def);
      return this;
    }

    public Builder profile(String canonical) {
      this.profile = canonical;
      return this;
    }

    /** Habilita {@code _include=Tipo:param} para um parâmetro de referência já registrado. */
    public Builder include(String paramName) {
      SearchParamDef def = searchParams.get(paramName);
      if (def == null || def.type() != SearchParamType.REFERENCE) {
        throw new IllegalStateException(
            "_include exige parâmetro de referência registrado: " + type + ":" + paramName);
      }
      includes.add(paramName);
      return this;
    }

    public ResourceCapability build() {
      if (interactions.isEmpty()) {
        throw new IllegalStateException("Recurso sem interações: " + type);
      }
      return new ResourceCapability(this);
    }
  }
}
