package org.entur.vehicles.repository;

import org.entur.avro.realtime.siri.model.AffectedLineRecord;
import org.entur.avro.realtime.siri.model.AffectedNetworkRecord;
import org.entur.avro.realtime.siri.model.AffectedOperatorRecord;
import org.entur.avro.realtime.siri.model.AffectedRouteRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPlaceRecord;
import org.entur.avro.realtime.siri.model.AffectedStopPointRecord;
import org.entur.avro.realtime.siri.model.AffectedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.AffectsRecord;
import org.entur.avro.realtime.siri.model.InfoLinkRecord;
import org.entur.avro.realtime.siri.model.PtSituationElementRecord;
import org.entur.avro.realtime.siri.model.TranslatedStringRecord;
import org.entur.avro.realtime.siri.model.ValidityPeriodRecord;
import org.entur.vehicles.data.SeverityEnumeration;
import org.entur.vehicles.data.SituationUpdate;
import org.entur.vehicles.data.StopConditionEnumeration;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.WorkflowStatusEnumeration;
import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.AffectedLine;
import org.entur.vehicles.data.model.AffectedStop;
import org.entur.vehicles.data.model.AffectedVehicleJourney;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.InfoLink;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.data.model.StopPoint;
import org.entur.vehicles.data.model.TranslatedString;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.OperatorService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.entur.vehicles.repository.helpers.Util.containsValues;
import static org.entur.vehicles.repository.helpers.Util.convert;

/**
 * Converts an avro {@link PtSituationElementRecord} into a {@link SituationUpdate},
 * enriching lines, operators and stops through the existing lookup services.
 */
@Component
public class SituationMapper {

    private static final Logger LOG = LoggerFactory.getLogger(SituationMapper.class);

    private final LineService lineService;
    private final NSRService nsrService;
    private final ServiceJourneyService serviceJourneyService;

    public SituationMapper(@Autowired LineService lineService,
                           @Autowired NSRService nsrService,
                           @Autowired ServiceJourneyService serviceJourneyService) {
        this.lineService = lineService;
        this.nsrService = nsrService;
        this.serviceJourneyService = serviceJourneyService;
    }

    /** Returns null when no codespace can be resolved - the caller should skip the record. */
    public SituationUpdate map(PtSituationElementRecord record) {
        String situationNumber = asString(record.getSituationNumber());
        String participantRef = asString(record.getParticipantRef());

        Codespace codespace = resolveCodespace(participantRef, situationNumber);
        if (codespace == null) {
            return null;
        }

        SituationUpdate situation = new SituationUpdate();
        situation.setSituationNumber(situationNumber);
        situation.setParticipantRef(participantRef);
        situation.setCodespace(codespace);
        situation.setVersion(record.getVersion());
        situation.setPriority(record.getPriority());
        situation.setPlanned(record.getPlanned());
        situation.setReportType(asString(record.getReportType()));
        situation.setSeverity(SeverityEnumeration.fromValue(asString(record.getSeverity())));
        situation.setProgress(WorkflowStatusEnumeration.fromValue(asString(record.getProgress())));

        if (record.getSource() != null) {
            situation.setSourceType(asString(record.getSource().getSourceType()));
        }

        if (containsValues(record.getKeywords())) {
            List<String> keywords = new ArrayList<>();
            record.getKeywords().forEach(keyword -> keywords.add(keyword.toString()));
            situation.setKeywords(keywords);
        }

        if (record.getCreationTime() != null) {
            situation.setCreationTime(convert(record.getCreationTime()));
        }
        if (record.getVersionedAtTime() != null) {
            situation.setVersionedAtTime(convert(record.getVersionedAtTime()));
        }

        situation.setValidityPeriods(mapValidityPeriods(record.getValidityPeriods()));
        situation.setSummary(mapTranslations(record.getSummaries()));
        situation.setDescription(mapTranslations(record.getDescriptions()));
        situation.setAdvice(mapTranslations(record.getAdvices()));
        situation.setDetail(mapTranslations(record.getDetails()));
        situation.setInfoLinks(mapInfoLinks(record.getInfoLinks()));
        situation.setAffects(mapAffects(record.getAffects()));

        if (situation.getVersionedAtTime() != null) {
            situation.setLastUpdated(situation.getVersionedAtTime());
        } else if (situation.getCreationTime() != null) {
            situation.setLastUpdated(situation.getCreationTime());
        } else {
            situation.setLastUpdated(ZonedDateTime.now());
        }

        situation.setExpiration(calculateExpiration(situation));

        return situation;
    }

    private Codespace resolveCodespace(String participantRef, String situationNumber) {
        if (participantRef != null && !participantRef.isBlank()) {
            return Codespace.getCodespace(participantRef);
        }
        if (situationNumber != null && situationNumber.contains(":")) {
            String prefix = situationNumber.substring(0, situationNumber.indexOf(':'));
            if (!prefix.isBlank()) {
                return Codespace.getCodespace(prefix);
            }
        }
        LOG.debug("Unable to resolve codespace for situation {} - ignoring.", situationNumber);
        return null;
    }

    /**
     * A closed situation expires now, so it is published once and then purged after the
     * grace period. Otherwise the latest validity end time wins. When no period carries
     * an end time the situation is open-ended and never expires - null.
     */
    private ZonedDateTime calculateExpiration(SituationUpdate situation) {
        if (situation.getProgress() != null && situation.getProgress().isClosed()) {
            return ZonedDateTime.now();
        }

        ZonedDateTime latest = null;
        if (situation.getValidityPeriods() != null) {
            for (ValidityPeriod period : situation.getValidityPeriods()) {
                ZonedDateTime endTime = period.getEndTime();
                if (endTime != null && (latest == null || endTime.isAfter(latest))) {
                    latest = endTime;
                }
            }
        }
        return latest;
    }

    private List<ValidityPeriod> mapValidityPeriods(List<ValidityPeriodRecord> records) {
        List<ValidityPeriod> periods = new ArrayList<>();
        if (containsValues(records)) {
            for (ValidityPeriodRecord record : records) {
                periods.add(new ValidityPeriod(
                        record.getStartTime() != null ? convert(record.getStartTime()) : null,
                        record.getEndTime() != null ? convert(record.getEndTime()) : null
                ));
            }
        }
        return periods;
    }

    private List<TranslatedString> mapTranslations(List<TranslatedStringRecord> records) {
        List<TranslatedString> translations = new ArrayList<>();
        if (containsValues(records)) {
            for (TranslatedStringRecord record : records) {
                translations.add(new TranslatedString(
                        asString(record.getValue()),
                        asString(record.getLanguage())
                ));
            }
        }
        return translations;
    }

    private List<InfoLink> mapInfoLinks(List<InfoLinkRecord> records) {
        List<InfoLink> links = new ArrayList<>();
        if (containsValues(records)) {
            for (InfoLinkRecord record : records) {
                links.add(new InfoLink(asString(record.getUri()), mapTranslations(record.getLabels())));
            }
        }
        return links;
    }

    /**
     * The stops nested under an affected journey or line. SIRI allows several routes per
     * object; their stops are flattened into one list, because the affected segment is a span
     * over the journey and a per-route split would have no meaning for it.
     */
    private List<AffectedStop> mapRouteStops(List<AffectedRouteRecord> routes) {
        List<AffectedStop> stops = new ArrayList<>();
        if (!containsValues(routes)) {
            return stops;
        }
        for (AffectedRouteRecord route : routes) {
            if (route.getStopPoints() == null || !containsValues(route.getStopPoints().getStopPoints())) {
                continue;
            }
            for (AffectedStopPointRecord stopPoint : route.getStopPoints().getStopPoints()) {
                StopPoint stop = resolveStop(asString(stopPoint.getStopPointRef()));
                if (stop == null) {
                    continue;
                }
                stops.add(new AffectedStop(stop, mapStopConditions(stopPoint.getStopConditions())));
            }
        }
        return stops;
    }

    private List<StopConditionEnumeration> mapStopConditions(List<CharSequence> values) {
        List<StopConditionEnumeration> conditions = new ArrayList<>();
        if (!containsValues(values)) {
            return conditions;
        }
        for (CharSequence value : values) {
            StopConditionEnumeration condition = StopConditionEnumeration.fromValue(asString(value));
            if (condition != null) {
                conditions.add(condition);
            } else {
                LOG.debug("Unknown stop condition {} - ignoring.", value);
            }
        }
        return conditions;
    }

    /**
     * Entries first, flat lists derived from them.
     * <p>
     * The entries are accumulated into maps keyed by what identifies them - dated journey id,
     * service journey id, line ref - rather than emitted inline, because one PtSituationElement
     * may name the same journey or line in more than one block: a producer splitting a
     * disruption into sections sends two AffectedVehicleJourney blocks for the same journey with
     * a stop list each. Guarding entry creation on the flat list's dedup return value, as an
     * earlier version did, dropped the second block whole - its stops reached neither the entry,
     * nor {@code allStopRefs} (so the {@code stopRef} filter could not find them), nor the
     * matcher. A repeated key merges instead, unioning the stops in first-seen order.
     * <p>
     * The flat {@code add*} calls are unchanged and still carry their own dedup: their contents
     * are exactly what they were before this method grew entries. Only the use of their return
     * value as a guard is gone.
     */
    private Affects mapAffects(AffectsRecord record) {
        Affects affects = new Affects();
        if (record == null) {
            return affects;
        }

        Map<String, LineEntry> lineEntries = new LinkedHashMap<>();
        Map<String, JourneyEntry> journeyEntries = new LinkedHashMap<>();

        if (containsValues(record.getNetworks())) {
            for (AffectedNetworkRecord network : record.getNetworks()) {
                affects.addVehicleMode(resolveMode(asString(network.getVehicleMode())));

                if (containsValues(network.getAffectedLines())) {
                    for (AffectedLineRecord affectedLine : network.getAffectedLines()) {
                        Line line = resolveLine(asString(affectedLine.getLineRef()));
                        affects.addLine(line);
                        if (line != null && line.getLineRef() != null) {
                            lineEntries
                                    .computeIfAbsent(line.getLineRef(), key -> new LineEntry(line, new StopUnion()))
                                    .stops()
                                    .add(mapRouteStops(affectedLine.getRoutes()));
                        }
                    }
                }
                if (containsValues(network.getAffectedOperators())) {
                    for (AffectedOperatorRecord affectedOperator : network.getAffectedOperators()) {
                        affects.addOperator(resolveOperator(asString(affectedOperator.getOperatorRef())));
                    }
                }
            }
        }

        if (containsValues(record.getStopPoints())) {
            for (AffectedStopPointRecord stopPoint : record.getStopPoints()) {
                affects.addStopPoint(resolveStop(asString(stopPoint.getStopPointRef())));
            }
        }

        if (containsValues(record.getStopPlaces())) {
            for (AffectedStopPlaceRecord stopPlace : record.getStopPlaces()) {
                affects.addStopPlace(resolveStop(asString(stopPlace.getStopPlaceRef())));
            }
        }

        if (containsValues(record.getVehicleJourneys())) {
            for (AffectedVehicleJourneyRecord journey : record.getVehicleJourneys()) {
                Line line = resolveLine(asString(journey.getLineRef()));
                affects.addLine(line);

                Operator operator = null;
                if (journey.getOperator() != null) {
                    operator = resolveOperator(asString(journey.getOperator().getOperatorRef()));
                    affects.addOperator(operator);
                }

                // Shared by every entry this record produces: the producer nests one stop
                // list per affected journey, and a record naming several journeys means all
                // of them are affected at those same stops.
                List<AffectedStop> stops = mapRouteStops(journey.getRoutes());

                // Affects.addServiceJourney dedupes on getId() alone, so when the same id
                // appears both as a bare ref and as a framed ref, whichever is added first
                // wins. The framed ref carries the dataFrameRef date, so it must be added
                // before the bare vehicleJourneyRefs - otherwise the date is silently lost.
                // The entry map is keyed by the same id, so the framed entry likewise wins
                // there and the bare ref only merges its stops into it.
                if (journey.getFramedVehicleJourneyRef() != null
                        && journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef() != null) {
                    ServiceJourney serviceJourney = new ServiceJourney(
                            journey.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef().toString(),
                            asString(journey.getFramedVehicleJourneyRef().getDataFrameRef()));
                    affects.addServiceJourney(serviceJourney);
                    journeyEntry(journeyEntries, serviceJourney.getId(), serviceJourney, null, line, operator)
                            .add(stops);
                }
                if (containsValues(journey.getVehicleJourneyRefs())) {
                    for (CharSequence ref : journey.getVehicleJourneyRefs()) {
                        ServiceJourney serviceJourney = new ServiceJourney(ref.toString());
                        affects.addServiceJourney(serviceJourney);
                        journeyEntry(journeyEntries, serviceJourney.getId(), serviceJourney, null, line, operator)
                                .add(stops);
                    }
                }
                if (containsValues(journey.getDatedVehicleJourneyRefs())) {
                    for (CharSequence ref : journey.getDatedVehicleJourneyRefs()) {
                        DatedServiceJourney dated = resolveDatedServiceJourney(ref.toString());
                        affects.addDatedServiceJourney(dated);
                        journeyEntry(journeyEntries, dated.getId(), null, dated, line, operator)
                                .add(stops);
                    }
                }
            }
        }

        for (JourneyEntry entry : journeyEntries.values()) {
            affects.addVehicleJourney(new AffectedVehicleJourney(entry.serviceJourney(),
                    entry.datedServiceJourney(), entry.line(), entry.operator(), entry.stops().stops()));
        }
        for (LineEntry entry : lineEntries.values()) {
            affects.addAffectedLine(new AffectedLine(entry.line(), entry.stops().stops()));
        }

        return affects;
    }

    /**
     * The accumulator for one journey key. The first block naming a key fixes its line and
     * operator - they are display context, and a producer contradicting itself between two blocks
     * about the same journey has bigger problems than which of the two is shown.
     */
    private static StopUnion journeyEntry(Map<String, JourneyEntry> entries,
                                          String key,
                                          ServiceJourney serviceJourney,
                                          DatedServiceJourney datedServiceJourney,
                                          Line line,
                                          Operator operator) {
        return entries.computeIfAbsent(key,
                        k -> new JourneyEntry(serviceJourney, datedServiceJourney, line, operator, new StopUnion()))
                .stops();
    }

    private record JourneyEntry(ServiceJourney serviceJourney,
                                DatedServiceJourney datedServiceJourney,
                                Line line,
                                Operator operator,
                                StopUnion stops) {}

    private record LineEntry(Line line, StopUnion stops) {}

    /**
     * The stops of one entry, accumulated across however many blocks name it.
     * <p>
     * The overwhelmingly common case is a single block, and then this holds that block's list
     * directly - no copy, and every entry the record produces shares the one list. The merging
     * map is allocated only when a key actually repeats.
     */
    private static final class StopUnion {

        private List<AffectedStop> stops = List.of();
        private LinkedHashMap<String, AffectedStop> merged;

        void add(List<AffectedStop> more) {
            if (more.isEmpty()) {
                return;
            }
            if (merged == null && stops.isEmpty()) {
                stops = more;
                return;
            }
            if (merged == null) {
                merged = new LinkedHashMap<>();
                putAll(stops);
            }
            putAll(more);
        }

        private void putAll(List<AffectedStop> candidates) {
            for (AffectedStop stop : candidates) {
                // Deduplicated by stop id: two sections of one journey commonly share a boundary
                // stop, and the entry must list it once. First seen wins, conditions included.
                merged.putIfAbsent(stop.getStop() != null ? stop.getStop().getId() : null, stop);
            }
        }

        List<AffectedStop> stops() {
            return merged != null ? List.copyOf(merged.values()) : stops;
        }
    }

    /**
     * A DatedServiceJourney the planned data knows carries its ServiceJourney + operating
     * date, as on the vehicle and timetable paths. An unknown id stays a bare ref with a
     * null serviceJourney - the id index on {@link Affects} is what the producer tagged
     * either way, so this never widens what a situation matches.
     */
    private DatedServiceJourney resolveDatedServiceJourney(String datedServiceJourneyId) {
        DatedServiceJourney resolved = serviceJourneyService.findDatedServiceJourney(datedServiceJourneyId);
        return resolved != null ? resolved : new DatedServiceJourney(datedServiceJourneyId);
    }

    private Line resolveLine(String lineRef) {
        if (lineRef == null) {
            return null;
        }
        return lineService.getLine(lineRef);
    }

    private org.entur.vehicles.data.model.Operator resolveOperator(String operatorRef) {
        if (operatorRef == null) {
            return null;
        }
        org.entur.vehicles.data.model.Operator operator = OperatorService.getOperator(operatorRef);
        return operator != null ? operator : new org.entur.vehicles.data.model.Operator(operatorRef);
    }

    /**
     * NSR is the single source of truth for official stop names, so a StopPointName or
     * PlaceName carried inside the situation is deliberately ignored. That also means the
     * shared instance {@link NSRService#getStop} returns from its cache - the same one
     * referenced by {@code Call.stopPoint} in {@code TimetableRepository} - is never
     * modified here.
     */
    private StopPoint resolveStop(String stopRef) {
        if (stopRef == null) {
            return null;
        }
        return nsrService.getStop(stopRef);
    }

    private VehicleModeEnumeration resolveMode(String vehicleMode) {
        if (vehicleMode == null) {
            return null;
        }
        try {
            return VehicleModeEnumeration.fromValue(vehicleMode);
        } catch (IllegalArgumentException e) {
            LOG.debug("Unknown vehicle mode {} - ignoring.", vehicleMode);
            return null;
        }
    }

    private String asString(CharSequence value) {
        return value != null ? value.toString() : null;
    }
}
