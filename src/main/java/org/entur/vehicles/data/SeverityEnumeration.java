package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.SeverityEnum;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public enum SeverityEnumeration {

    unknown,
    verySlight,
    slight,
    normal,
    severe,
    verySevere,
    noImpact,
    undefined;

    public static SeverityEnumeration fromValue(String severity) {
        if (severity == null) {
            return undefined;
        }
        try {
            return switch (SeverityEnum.valueOf(severity)) {
                case UNKNOWN -> unknown;
                case VERY_SLIGHT -> verySlight;
                case SLIGHT -> slight;
                case NORMAL -> normal;
                case SEVERE -> severe;
                case VERY_SEVERE -> verySevere;
                case NO_IMPACT -> noImpact;
                case UNDEFINED -> undefined;
            };
        } catch (IllegalArgumentException e) {
            return undefined;
        }
    }
}
