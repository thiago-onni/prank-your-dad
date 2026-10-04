package br.gov.sus.nexus.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Regras de modularidade (CONVENTIONS.md): módulos só se conhecem pela API pública. */
class ArchitectureTest {

  static final String ROOT = "br.gov.sus.nexus.core";
  static final List<String> MODULES =
      List.of(
          "identity",
          "reference",
          "terminology",
          "audit",
          "integration",
          "scheduling",
          "tasks",
          "journey",
          "regulation",
          "exams");
  static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT);
  }

  @Test
  void modulesOnlyDependOnOtherModulesApi() {
    for (String module : MODULES) {
      List<String> forbidden = new ArrayList<>();
      for (String other : MODULES) {
        if (!other.equals(module)) {
          forbidden.add(ROOT + "." + other + ".domain..");
          forbidden.add(ROOT + "." + other + ".application..");
          forbidden.add(ROOT + "." + other + ".infrastructure..");
        }
      }
      noClasses()
          .that()
          .resideInAPackage(ROOT + "." + module + "..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(forbidden.toArray(new String[0]))
          .because("módulo " + module + " só pode usar ..<outro>.api.. de outros módulos")
          .check(classes);
    }
  }

  @Test
  void platformAndSharedKernelDoNotDependOnModules() {
    String[] modulePackages =
        MODULES.stream().map(m -> ROOT + "." + m + "..").toArray(String[]::new);
    noClasses()
        .that()
        .resideInAnyPackage(ROOT + ".platform..", ROOT + ".sharedkernel..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(modulePackages)
        .because("platform e sharedkernel são cross-cutting e não conhecem módulos de domínio")
        .check(classes);
  }

  @Test
  void apiPackagesDoNotDependOnInternals() {
    for (String module : MODULES) {
      noClasses()
          .that()
          .resideInAPackage(ROOT + "." + module + ".api..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              ROOT + "." + module + ".domain..",
              ROOT + "." + module + ".application..",
              ROOT + "." + module + ".infrastructure..")
          .because("a API pública de " + module + " não expõe tipos internos")
          .check(classes);
    }
  }
}
