package org.entur.vehicles.service.planned;

/**
 * A NeTEx {@code Operator} as published, from the parse to the builder and through the snapshot
 * unchanged. Only the id is non-null. Read and written by {@link OperatorCodec}.
 *
 * @param name the operator's own {@code Name}
 */
public record OperatorRecord(String id, String name) {
}
