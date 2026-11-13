package centuryroad.auth;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

/**
 * The shape of auth-service, written as rules the build enforces: what was a sentence in AGENTS.md and
 * architecture.md becomes a failing pull request. Every rule is true of the code today.
 */
@AnalyzeClasses(packages = "centuryroad.auth", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // ---- the layers ----------------------------------------------------------------------------

    @ArchTest
    static final ArchRule theModelDependsOnNothingAboveIt = noClasses().that().resideInAPackage("..auth.model..")
            .should().dependOnClassesThat().resideInAnyPackage("..auth.controller..", "..auth.service..",
                    "..auth.repository..", "..auth.dto..", "..auth.config..", "..auth.security..");

    /** HTTP is the outermost layer: nothing below it may reach back up to a controller. */
    @ArchTest
    static final ArchRule nothingDependsOnAController = noClasses()
            .that().resideInAnyPackage("..auth.service..", "..auth.repository..", "..auth.model..", "..auth.security..",
                    "..auth.dto..", "..auth.exception..", "..auth.util..")
            .should().dependOnClassesThat().resideInAPackage("..auth.controller..");

    /** A controller asks a service; the database is the services' business. */
    @ArchTest
    static final ArchRule controllersGoThroughServices = noClasses().that().resideInAPackage("..auth.controller..")
            .should().dependOnClassesThat().resideInAPackage("..auth.repository..");

    // ---- tokens --------------------------------------------------------------------------------

    /**
     * Verifying a token is meant to be copyable into any other service ("every other service will carry a
     * copy of JwtVerifier and JwtKey"), so the security package may know the user's role and nothing else
     * of this service.
     */
    @ArchTest
    static final ArchRule theTokenVerificationStandsAlone = noClasses().that().resideInAPackage("..auth.security..")
            .should().dependOnClassesThat().resideInAnyPackage("..auth.controller..", "..auth.service..",
                    "..auth.repository..", "..auth.dto..", "..auth.config..", "..auth.exception..");

    /** Only this service signs tokens, and only in one place: everyone else verifies. */
    @ArchTest
    static final ArchRule onlyJwtServiceSignsTokens = noClasses().that().doNotHaveSimpleName("JwtService")
            .should().callMethod(Jwts.class, "builder");

    // ---- conventions ---------------------------------------------------------------------------

    @ArchTest
    static final ArchRule noFieldInjection = noFields().should().beAnnotatedWith(Autowired.class);

    @ArchTest
    static final ArchRule aControllerIsARestControllerInTheControllerPackage = classes()
            .that().haveSimpleNameEndingWith("Controller")
            .should().resideInAPackage("..auth.controller..").andShould().beAnnotatedWith(RestController.class);

    @ArchTest
    static final ArchRule logThroughSlf4j = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule neverPrintToTheConsole = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
}
