package br.gov.sus.nexus.fhir.capability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Capacidades implementadas para um tipo de recurso: interações, parâmetros de busca e perfil. */
public final class ResourceCapability {

  private final String type;
  private final Set<Interaction> interactions;
  private final Map<String, SearchParamDef> searchParams;
  private final String profile;

  private ResourceCapability(Builder b) {
    this.type = b.type;
    this.interactions = Collections.unmodifiableSet(EnumSet.copyOf(b.interactions));
    this.searchParams = Collections.unmodifiableMap(new LinkedHashMap<>(b.searchParams));
    this.profile = b.profile;
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

  public Optional<SearchParamDef> searchParam(String name) {
    return Optional.ofNullable(searchParams.get(name));
  }

  /** Builder fluente usado pelo {@link CapabilityRegistry}. */
  public static final class Builder {
    private final String type;
    private final EnumSet<Interaction> interactions = EnumSet.noneOf(Interaction.class);
    private final Map<String, SearchParamDef> searchParams = new LinkedHashMap<>();
    private String profile;

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
          Interaction.HISTORY_INSTANCE);
    }

    public Builder readWrite() {
      return readOnly().interactions(Interaction.CREATE, Interaction.UPDATE);
    }

    public Builder param(SearchParamDef def) {
      searchParams.put(def.name(), def);
      return this;
    }

    public Builder profile(String canonical) {
      this.profile = canonical;
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
