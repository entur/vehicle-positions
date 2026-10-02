package org.entur.vehicles.service.planned;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;

/**
 * Builds a record with a distinct non-null value in every component, so a round trip that drops
 * or swaps a field fails - including a component added after the test was written. Arrays get
 * two elements each, and a record component is filled the same way.
 */
final class DistinctRecords {

    private DistinctRecords() {
    }

    static <R extends Record> R of(Class<R> type) throws ReflectiveOperationException {
        return of(type, new int[]{1});
    }

    private static <R> R of(Class<R> type, int[] next) throws ReflectiveOperationException {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = valueOf(types[i], type.getSimpleName() + "." + components[i].getName(), next);
        }
        Constructor<R> constructor = type.getDeclaredConstructor(types);
        return constructor.newInstance(values);
    }

    private static Object valueOf(Class<?> type, String component, int[] next) throws ReflectiveOperationException {
        if (type == String.class) {
            return component + "-" + next[0]++;
        }
        if (type == int.class) {
            return next[0]++;
        }
        if (type.isRecord()) {
            return of(type, next);
        }
        if (type.isArray()) {
            Object array = Array.newInstance(type.getComponentType(), 2);
            for (int i = 0; i < 2; i++) {
                Array.set(array, i, valueOf(type.getComponentType(), component, next));
            }
            return array;
        }
        throw new IllegalArgumentException(component + " is a " + type.getSimpleName() + ", which is not supported yet");
    }
}
