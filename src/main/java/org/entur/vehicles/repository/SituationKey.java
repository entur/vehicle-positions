package org.entur.vehicles.repository;

import com.google.common.base.Objects;
import org.entur.vehicles.data.model.Codespace;

public class SituationKey {
    private final Codespace codespace;
    private final String situationNumber;
    private final int hashCode;

    public SituationKey(Codespace codespace, String situationNumber) {
        this.codespace = codespace;
        this.situationNumber = situationNumber;
        hashCode = Objects.hashCode(codespace, situationNumber);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SituationKey that)) return false;
        return Objects.equal(codespace, that.codespace) &&
                Objects.equal(situationNumber, that.situationNumber);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
