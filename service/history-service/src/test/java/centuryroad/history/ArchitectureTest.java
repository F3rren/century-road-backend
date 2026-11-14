package centuryroad.history;

import centuryroad.history.wikipedia.WikipediaFeedClient;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

/**
 * The shape of history-service, written as rules the build enforces. Most of it was in AGENTS.md and in
 * architecture.md as sentences, which a new contributor can read and an old one can forget; these fail
 * a pull request that breaks them.
 *
 * Each rule is true of the code today. Where it is not yet - a rule worth having, with a few known
 * exceptions - the exceptions are named in the rule itself, so a new one cannot join them unnoticed and
 * removing one is a visible change.
 */
@AnalyzeClasses(packages = "centuryroad.history", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // ---- the layers ----------------------------------------------------------------------------

    /** The content and the entities are the bottom: they know nothing of who uses them. */
    @ArchTest
    static final ArchRule theModelDependsOnNothingAboveIt = noClasses().that().resideInAPackage("..history.model..")
            .should().dependOnClassesThat().resideInAnyPackage("..history.controller..", "..history.service..",
                    "..history.repository..", "..history.dto..", "..history.config..", "..history.wikipedia..");

    /** HTTP is the outermost layer: nothing below it may reach back up to a controller. */
    @ArchTest
    static final ArchRule nothingDependsOnAController = noClasses()
            .that().resideInAnyPackage("..history.service..", "..history.wikipedia..", "..history.repository..",
                    "..history.model..", "..history.dto..")
            .should().dependOnClassesThat().resideInAPackage("..history.controller..");

    /**
     * An answer is shaped from the service's results, not from the database or the HTTP layer. (The three
     * dto classes that read the EditorialCatalog for a path's years and reading time are the one place a dto
     * knows a service; it reads, it does not write.)
     */
    @ArchTest
    static final ArchRule dtosDoNotTouchTheDatabaseOrTheControllers = noClasses().that().resideInAPackage("..history.dto..")
            .should().dependOnClassesThat().resideInAnyPackage("..history.controller..", "..history.repository..");

    /**
     * A controller asks a service, and the database is the services' business. (Three controllers used to
     * read TimelineEventRepository themselves, and were named here as the known exceptions; they ask
     * TimelineService now, and the list is empty.)
     */
    @ArchTest
    static final ArchRule controllersGoThroughServices = noClasses().that().resideInAPackage("..history.controller..")
            .should().dependOnClassesThat().resideInAPackage("..history.repository..");

    // ---- the one door to Wikipedia -------------------------------------------------------------

    /**
     * Everything above the client sees WikipediaFeedClient and nothing else, so what Wikipedia is - URLs,
     * status codes, the resilience around it - stays behind it (architecture.md: "the one door"). Only the
     * configuration that assembles the client may name a concrete one.
     */
    @ArchTest
    static final ArchRule onlyTheConfigurationNamesAWikipediaClientImplementation = noClasses()
            .that().resideOutsideOfPackages("..history.config..", "..history.wikipedia..")
            .should().dependOnClassesThat(assignableTo(WikipediaFeedClient.class).and(notAnInterface()));

    /** The client is a leaf: it knows Wikipedia, never the service built on it. */
    @ArchTest
    static final ArchRule theWikipediaClientDoesNotKnowTheService = noClasses().that().resideInAPackage("..history.wikipedia..")
            .should().dependOnClassesThat().resideInAnyPackage("..history.service..", "..history.controller..",
                    "..history.repository..");

    // ---- conventions ---------------------------------------------------------------------------

    /** Constructor injection only: a field that is injected cannot be final and hides what a class needs. */
    @ArchTest
    static final ArchRule noFieldInjection = noFields().should().beAnnotatedWith(Autowired.class);

    @ArchTest
    static final ArchRule aControllerIsARestControllerInTheControllerPackage = classes()
            .that().haveSimpleNameEndingWith("Controller")
            .should().resideInAPackage("..history.controller..").andShould().beAnnotatedWith(RestController.class);

    @ArchTest
    static final ArchRule logThroughSlf4j = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule neverPrintToTheConsole = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    private static DescribedPredicate<JavaClass> notAnInterface() {
        return DescribedPredicate.not(JavaClass.Predicates.INTERFACES).as("not an interface");
    }
}
