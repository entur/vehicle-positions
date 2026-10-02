package org.entur.vehicles.repository;

import com.google.common.base.Objects;
import org.entur.vehicles.data.model.Codespace;

public class StorageKey {
    private final Codespace codespace;
    private final String vehicleRef;
    private final String lineRef;
    private final String serviceJourneyId;
    private final String datedServiceJourneyId;
    private final String operatingDate;
    private final int hashCode;

    public StorageKey(Codespace codespace, String vehicleRef, String lineRef, String serviceJourneyId, String datedServiceJourneyId) {
        this(codespace, vehicleRef, lineRef, serviceJourneyId, datedServiceJourneyId, null);
    }

    public StorageKey(Codespace codespace, String vehicleRef, String lineRef, String serviceJourneyId, String datedServiceJourneyId, String operatingDate) {
        this.codespace = codespace;
        this.vehicleRef = vehicleRef;
        this.lineRef = lineRef;
        this.serviceJourneyId = serviceJourneyId;
        this.datedServiceJourneyId = datedServiceJourneyId;
        this.operatingDate = operatingDate;
        hashCode = Objects.hashCode(codespace, vehicleRef, lineRef, serviceJourneyId, datedServiceJourneyId, operatingDate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StorageKey that)) return false;
        return Objects.equal(codespace, that.codespace) &&
                Objects.equal(vehicleRef, that.vehicleRef) &&
                Objects.equal(lineRef, that.lineRef) &&
                Objects.equal(serviceJourneyId, that.serviceJourneyId) &&
                Objects.equal(datedServiceJourneyId, that.datedServiceJourneyId) &&
                Objects.equal(operatingDate, that.operatingDate);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
