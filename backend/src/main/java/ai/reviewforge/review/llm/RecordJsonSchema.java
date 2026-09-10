package ai.reviewforge.review.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the small JSON Schema subset supported by Gemini from ReviewForge response records. */
final class RecordJsonSchema {

    private RecordJsonSchema() {
    }

    static Map<String, Object> from(Class<?> responseType) {
        return schema(responseType);
    }

    private static Map<String, Object> schema(Type type) {
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> rawType
                && List.class.isAssignableFrom(rawType)) {
            return Map.of(
                    "type", "array",
                    "items", schema(parameterizedType.getActualTypeArguments()[0])
            );
        }

        if (!(type instanceof Class<?> valueType)) {
            throw new IllegalArgumentException("Unsupported response schema type: " + type);
        }
        if (valueType == String.class || valueType.isEnum()) return Map.of("type", "string");
        if (valueType == boolean.class || valueType == Boolean.class) return Map.of("type", "boolean");
        if (valueType == byte.class || valueType == short.class || valueType == int.class
                || valueType == long.class || valueType == Byte.class || valueType == Short.class
                || valueType == Integer.class || valueType == Long.class) {
            return Map.of("type", "integer");
        }
        if (valueType == float.class || valueType == double.class
                || valueType == Float.class || valueType == Double.class) {
            return Map.of("type", "number");
        }
        if (!valueType.isRecord()) {
            throw new IllegalArgumentException("Gemini responses must be Java records: " + valueType.getName());
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : valueType.getRecordComponents()) {
            Map<String, Object> componentSchema = new LinkedHashMap<>(schema(component.getGenericType()));
            JsonPropertyDescription description = component.getAccessor()
                    .getAnnotation(JsonPropertyDescription.class);
            if (description != null && !description.value().isBlank()) {
                componentSchema.put("description", description.value());
            }
            properties.put(component.getName(), componentSchema);
            required.add(component.getName());
        }

        Map<String, Object> objectSchema = new LinkedHashMap<>();
        objectSchema.put("type", "object");
        objectSchema.put("properties", properties);
        objectSchema.put("required", required);
        objectSchema.put("additionalProperties", false);
        return objectSchema;
    }
}
