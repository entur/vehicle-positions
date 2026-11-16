package org.entur.vehicles.testutil;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;

import java.util.List;

/** Helpers for building SIRI Avro records in tests */
public final class AvroRecords {

    private AvroRecords() {
    }

    /**
     * Sets every null array field to an empty list, recursively, since Avro arrays are
     * non-nullable and the JSON writer rejects them.
     */
    public static <T extends SpecificRecord> T withEmptyArrays(T record) {
        for (Schema.Field field : record.getSchema().getFields()) {
            Object value = record.get(field.pos());
            if (value == null && field.schema().getType() == Schema.Type.ARRAY) {
                record.put(field.pos(), List.of());
            } else if (value instanceof SpecificRecord nested) {
                withEmptyArrays(nested);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof SpecificRecord nested) {
                        withEmptyArrays(nested);
                    }
                }
            }
        }
        return record;
    }
}
