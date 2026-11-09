package com.dulleobang;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private static final JavaClasses CODE = production();

  private static JavaClasses production() {
    try {
      var location =
          DulleobangApplication.class.getProtectionDomain().getCodeSource().getLocation();
      return new ClassFileImporter().importPath(java.nio.file.Path.of(location.toURI()));
    } catch (java.net.URISyntaxException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Test
  void checksControllers() {
    classes()
        .that()
        .haveSimpleNameEndingWith("Controller")
        .or()
        .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
        .or()
        .areAnnotatedWith(org.springframework.stereotype.Controller.class)
        .should()
        .resideInAPackage("..controller..")
        .check(CODE);
    noClasses()
        .that()
        .haveSimpleNameEndingWith("Controller")
        .or()
        .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
        .or()
        .areAnnotatedWith(org.springframework.stereotype.Controller.class)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..repository..", "org.springframework.jdbc..", "jakarta.persistence..")
        .check(CODE);
  }

  @Test
  void checksServices() {
    classes()
        .that()
        .areAnnotatedWith(org.springframework.stereotype.Service.class)
        .should()
        .resideInAPackage("..service..")
        .check(CODE);
    noClasses()
        .that()
        .resideInAPackage("..service..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..controller..",
            "org.springframework.jdbc..",
            "java.sql..",
            "jakarta.servlet..",
            "org.springframework.web..")
        .check(CODE);
    noClasses()
        .that()
        .resideInAPackage("..service..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.nio.file.Files")
        .check(CODE);
  }

  @Test
  void checksRepositories() {
    classes()
        .that()
        .haveSimpleNameEndingWith("Repository")
        .should()
        .resideInAPackage("..repository..")
        .check(CODE);
    noClasses()
        .that()
        .resideOutsideOfPackage("..repository..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
        .check(CODE);
    noClasses()
        .that()
        .resideOutsideOfPackage("..repository..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework.data.jpa.repository..")
        .check(CODE);
    noClasses()
        .that()
        .resideOutsideOfPackage("..repository..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("jakarta.persistence.EntityManager")
        .check(CODE);
    noClasses()
        .that()
        .resideInAPackage("..repository..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..controller..", "..service..")
        .check(CODE);
  }

  @Test
  void checksMappers() {
    noClasses()
        .that()
        .resideInAPackage("..mapper..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..repository..",
            "..service..",
            "..infrastructure..",
            "jakarta.servlet..",
            "org.springframework.web..",
            "org.springframework.jdbc..")
        .check(CODE);
  }

  @Test
  void checksIngestion() {
    noClasses()
        .that()
        .haveFullyQualifiedName("com.dulleobang.capture.service.CaptureIngestionService")
        .or()
        .haveFullyQualifiedName("com.dulleobang.upload.service.MultipartStoreService")
        .or()
        .haveFullyQualifiedName("com.dulleobang.upload.service.UploadIngestService")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("java.net..")
        .check(CODE);
    noClasses()
        .that()
        .haveFullyQualifiedName("com.dulleobang.capture.service.CaptureIngestionService")
        .or()
        .haveFullyQualifiedName("com.dulleobang.upload.service.MultipartStoreService")
        .or()
        .haveFullyQualifiedName("com.dulleobang.upload.service.UploadIngestService")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("com.dulleobang.common.idempotency.StoredResponse")
        .check(CODE);
  }

  @Test
  void checksDomain() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..controller..",
            "..service..",
            "..repository..",
            "..infrastructure..",
            "jakarta.servlet..",
            "org.springframework.web..",
            "org.springframework.jdbc..")
        .check(CODE);
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.nio.file.Files")
        .check(CODE);
  }

  @Test
  void checksUtilities() {
    noClasses()
        .that()
        .resideInAnyPackage("..common.json..", "..common.crypto..", "..common.version..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..service..",
            "..repository..",
            "..infrastructure..",
            "..controller..",
            "jakarta.servlet..",
            "org.springframework.jdbc..")
        .check(CODE);
  }
}
