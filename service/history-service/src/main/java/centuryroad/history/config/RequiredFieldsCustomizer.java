package centuryroad.history.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.OpenAPI;
import jakarta.annotation.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Marks as required, in the published OpenAPI document, the record components a client can count on,
 * so a type generated from it says "always there" where that is true and "may be missing" where it
 * is not.
 *
 * A component is required unless it is {@code @Nullable}. A nullable one is left optional only where
 * the record omits nulls from its JSON ({@code @JsonInclude(NON_NULL)}, which most of them have):
 * the key is simply absent. A record without it sends the key with a null in it, and then the
 * component is required all the same - present, and possibly null.
 *
 * Records are found by scanning the given packages, nested ones included, and matched to their
 * schema by name: the simple class name, or the one a {@code @Schema(name = ...)} gives. That is
 * also why two records of the same simple name need distinct schema names (RandomEvent and
 * TimelineEvent, not two "Event"s): the document has one schema per name, and the second would
 * silently take the first one's place.
 */
final class RequiredFieldsCustomizer implements OpenApiCustomizer {

    private final String[] packages;

    RequiredFieldsCustomizer(String... packages) {
        this.packages = packages;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Map<String, io.swagger.v3.oas.models.media.Schema> schemas = openApi.getComponents().getSchemas();
        for (Class<?> type : records()) {
            io.swagger.v3.oas.models.media.Schema<?> schema = schemas.get(schemaName(type));
            if (schema == null || schema.getProperties() == null) {
                continue;
            }
            Set<String> required = new LinkedHashSet<>();
            if (schema.getRequired() != null) {
                required.addAll(schema.getRequired());
            }
            boolean omitsNulls = omitsNulls(type);
            for (RecordComponent component : type.getRecordComponents()) {
                if (schema.getProperties().containsKey(component.getName())
                        && !(isNullable(component) && omitsNulls)) {
                    required.add(component.getName());
                }
            }
            if (!required.isEmpty()) {
                schema.setRequired(new ArrayList<>(required));
            }
        }
    }

    private static boolean isNullable(RecordComponent component) {
        return component.isAnnotationPresent(Nullable.class) || component.getType() == Optional.class;
    }

    /** True when the record's JSON leaves a null field out, instead of writing it as null. */
    private static boolean omitsNulls(Class<?> type) {
        JsonInclude include = type.getAnnotation(JsonInclude.class);
        return include != null && include.value() != JsonInclude.Include.ALWAYS
                && include.value() != JsonInclude.Include.USE_DEFAULTS;
    }

    private static String schemaName(Class<?> type) {
        Schema declared = type.getAnnotation(Schema.class);
        return declared != null && !declared.name().isEmpty() ? declared.name() : type.getSimpleName();
    }

    private Set<Class<?>> records() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
        Set<Class<?>> found = new LinkedHashSet<>();
        for (String base : packages) {
            scanner.findCandidateComponents(base).forEach(definition -> {
                try {
                    found.add(ClassUtils.forName(definition.getBeanClassName(), getClass().getClassLoader()));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        return found;
    }
}
