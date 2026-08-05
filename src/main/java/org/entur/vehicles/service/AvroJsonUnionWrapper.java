package org.entur.vehicles.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.Schema;

/**
 * Rewrites JSON that matches an Avro schema's <em>shape</em> into the Avro JSON
 * <em>encoding</em> that {@code JsonReader} requires.
 * <p>
 * The SIRI-SX REST endpoint serves union-typed values plain:
 * <pre>{"participantRef": "VKT"}</pre>
 * while Avro's {@code JsonDecoder} requires them tagged with the branch name:
 * <pre>{"participantRef": {"string": "VKT"}}</pre>
 * Without this rewrite, decoding fails with
 * {@code AvroTypeException: Expected start-union. Got VALUE_STRING}.
 */
public final class AvroJsonUnionWrapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AvroJsonUnionWrapper() {
    }

    public static JsonNode wrap(JsonNode plain, Schema schema) {
        return switch (schema.getType()) {
            case UNION -> wrapUnion(plain, schema);
            case RECORD -> wrapRecord(plain, schema);
            case ARRAY -> wrapArray(plain, schema);
            case MAP -> wrapMap(plain, schema);
            default -> plain == null ? NullNode.instance : plain;
        };
    }

    private static JsonNode wrapUnion(JsonNode plain, Schema schema) {
        if (plain == null || plain.isNull()) {
            return NullNode.instance;
        }

        Schema branch = null;
        for (Schema candidate : schema.getTypes()) {
            if (candidate.getType() == Schema.Type.NULL) {
                continue;
            }
            if (branch != null) {
                // Every union in the SIRI schema is ["null", X]. Guessing between two
                // non-null branches would silently mis-parse the field, so fail loudly.
                throw new IllegalStateException(
                        "Cannot rewrite a union with more than one non-null branch: " + schema);
            }
            branch = candidate;
        }

        if (branch == null) {
            return NullNode.instance;
        }

        ObjectNode wrapped = MAPPER.createObjectNode();
        wrapped.set(branch.getFullName(), wrap(plain, branch));
        return wrapped;
    }

    private static JsonNode wrapRecord(JsonNode plain, Schema schema) {
        if (plain == null || plain.isNull()) {
            return NullNode.instance;
        }
        if (!plain.isObject()) {
            throw new IllegalArgumentException("Expected an object for record "
                    + schema.getFullName() + " but found " + plain.getNodeType());
        }
        ObjectNode wrapped = MAPPER.createObjectNode();
        for (Schema.Field field : schema.getFields()) {
            JsonNode value = plain.get(field.name());
            // Detect absent required fields (no default and not nullable). A missing
            // required array field silently becomes empty, which corrupts openEnded signals.
            if (value == null && !field.hasDefaultValue() && !isNullable(field.schema())) {
                throw new IllegalArgumentException("Missing required field '" + field.name()
                        + "' on record " + schema.getFullName());
            }
            // Absent fields become explicit nulls - Avro's decoder requires every field present.
            wrapped.set(field.name(), wrap(value, field.schema()));
        }
        return wrapped;
    }

    private static boolean isNullable(Schema schema) {
        return schema.getType() == Schema.Type.UNION
                && schema.getTypes().stream().anyMatch(t -> t.getType() == Schema.Type.NULL);
    }

    private static JsonNode wrapArray(JsonNode plain, Schema schema) {
        ArrayNode wrapped = MAPPER.createArrayNode();
        if (plain == null || plain.isNull()) {
            return wrapped;
        }
        // Deliberately strict. Quietly returning an empty array here would swallow an
        // upstream format change: with no validity periods, every situation reports
        // openEnded = true, corrupting the signal the quality tooling depends on.
        if (!plain.isArray()) {
            throw new IllegalArgumentException("Expected an array but found " + plain.getNodeType());
        }
        for (JsonNode item : plain) {
            wrapped.add(wrap(item, schema.getElementType()));
        }
        return wrapped;
    }

    private static JsonNode wrapMap(JsonNode plain, Schema schema) {
        ObjectNode wrapped = MAPPER.createObjectNode();
        if (plain == null || plain.isNull()) {
            return wrapped;
        }
        if (!plain.isObject()) {
            throw new IllegalArgumentException("Expected an object for a map but found "
                    + plain.getNodeType());
        }
        plain.fields().forEachRemaining(entry ->
                wrapped.set(entry.getKey(), wrap(entry.getValue(), schema.getValueType())));
        return wrapped;
    }
}
