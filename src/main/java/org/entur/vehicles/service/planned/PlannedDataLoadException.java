package org.entur.vehicles.service.planned;

/** A load that produced no usable dataset. The caller decides whether that is fatal. */
public class PlannedDataLoadException extends Exception {
    public PlannedDataLoadException(String message) {
        super(message);
    }

    public PlannedDataLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
