package org.entur.vehicles.service.planned;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;

/**
 * Builds a record with a distinct non-null value in every component, so a round trip that drops
 * or swaps a field fails - including a component added after the test was written.
 */
final class DistinctRecords {

    private DistinctRecords() {
    }

    static <R extends Record> R of(Class<R> type) throws ReflectiveOperationException {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            if (types[i] != String.class) {
                throw new IllegalArgumentException(type.getSimpleName() + "." + components[i].getName()
                        + " is a " + types[i].getSimpleName() + "; only String components are supported yet");
            }
            values[i] = components[i].getName() + "-" + i;
        }
        Constructor<R> constructor = type.getDeclaredConstructor(types);
        return constructor.newInstance(values);
    }
}
