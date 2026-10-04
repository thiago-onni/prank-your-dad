package br.gov.sus.nexus.fhir.http;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.Interaction;
import br.gov.sus.nexus.fhir.capability.ResourceCapability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Garante que as rotas HTTP expostas e o {@link CapabilityRegistry} são o mesmo conjunto: toda rota
 * declara sua interação/operação e toda interação/operação registrada tem rota.
 */
@QuarkusTest
class RouteRegistryConsistencyTest {

  @Inject CapabilityRegistry registry;

  @Test
  void everyRouteIsRegisteredAndEveryRegisteredInteractionHasARoute() {
    Set<Interaction> routed = EnumSet.noneOf(Interaction.class);
    Set<String> routedOperations = new HashSet<>();
    Set<String> routedSystem = new HashSet<>();
    for (Method m : FhirResourceEndpoint.class.getDeclaredMethods()) {
      boolean http =
          m.isAnnotationPresent(GET.class)
              || m.isAnnotationPresent(POST.class)
              || m.isAnnotationPresent(PUT.class)
              || m.isAnnotationPresent(DELETE.class)
              || m.isAnnotationPresent(PATCH.class);
      if (!http) {
        continue;
      }
      if ("metadata".equals(m.getName())) {
        continue;
      }
      FhirRoute route = m.getAnnotation(FhirRoute.class);
      assertThat(route).as("rota sem @FhirRoute: " + m.getName()).isNotNull();
      for (Interaction i : route.value()) {
        routed.add(i);
      }
      if (!route.operation().isEmpty()) {
        routedOperations.add(route.operation());
      }
      routedSystem.addAll(java.util.List.of(route.system()));
      assertThat(
              route.value().length > 0 || !route.operation().isEmpty() || route.system().length > 0)
          .as("rota sem interação/operação: " + m.getName())
          .isTrue();
    }

    Set<Interaction> registered = EnumSet.noneOf(Interaction.class);
    for (ResourceCapability cap : registry.all()) {
      registered.addAll(cap.interactions());
    }
    assertThat(routed).containsExactlyInAnyOrderElementsOf(registered);
    Set<String> registeredOperations = new HashSet<>(CapabilityRegistry.SYSTEM_OPERATIONS);
    for (ResourceCapability cap : registry.all()) {
      registeredOperations.addAll(cap.operations());
    }
    assertThat(routedOperations).containsExactlyInAnyOrderElementsOf(registeredOperations);
    assertThat(routedSystem)
        .containsExactlyInAnyOrderElementsOf(CapabilityRegistry.SYSTEM_INTERACTIONS);
    // nenhuma interação do enum fica sem rota nem sem registro
    assertThat(routed).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Interaction.class));
  }
}
