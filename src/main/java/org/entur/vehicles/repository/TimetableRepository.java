package org.entur.vehicles.repository;

import com.google.common.collect.Maps;
import org.entur.avro.realtime.siri.model.EstimatedCallRecord;
import org.entur.avro.realtime.siri.model.EstimatedVehicleJourneyRecord;
import org.entur.avro.realtime.siri.model.RecordedCallRecord;
import org.entur.vehicles.data.EstimatedTimetableUpdate;
import org.entur.vehicles.data.OccupancyEnumeration;
import org.entur.vehicles.data.OccupancyStatus;
import org.entur.vehicles.data.QueryFilter;
import org.entur.vehicles.data.VehicleModeEnumeration;
import org.entur.vehicles.data.model.Call;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.graphql.publishers.EstimatedTimetableUpdateRxPublisher;
import org.entur.vehicles.metrics.PrometheusMetricsService;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.NSRService;
import org.entur.vehicles.service.OperatorService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.entur.vehicles.repository.helpers.Util.containsValues;
import static org.entur.vehicles.repository.helpers.Util.convert;

@Repository
public class TimetableRepository {

  private static final Logger LOG = LoggerFactory.getLogger(TimetableRepository.class);
  private final PrometheusMetricsService metricsService;

  private final AutoPurgingTimetableMap timetableMap;

  private final LineService lineService;

  private final ServiceJourneyService serviceJourneyService;
  private final NSRService nsrService;

  private final EstimatedTimetableUpdateRxPublisher publisher;

  private final long maxValidityInMinutes;

  public TimetableRepository(
          @Autowired PrometheusMetricsService metricsService,
          @Autowired LineService lineService,
          @Autowired ServiceJourneyService serviceJourneyService,
          @Autowired NSRService nsrService,
          @Autowired AutoPurgingTimetableMap timetableMap,
          @Value("${vehicle.updates.max.validity.minutes}") long maxValidityInMinutes,
          @Autowired EstimatedTimetableUpdateRxPublisher publisher) {
    this.metricsService = metricsService;
    this.lineService = lineService;
    this.serviceJourneyService = serviceJourneyService;
    this.nsrService = nsrService;
    this.timetableMap = timetableMap;
    this.maxValidityInMinutes = maxValidityInMinutes;
    this.publisher = publisher;
    this.publisher.setRepository(this);
  }

  public void addAll(List<EstimatedVehicleJourneyRecord> estimatedVehicleJourneyList) {
    for (EstimatedVehicleJourneyRecord estimatedVehicleJourney : estimatedVehicleJourneyList) {
      add(estimatedVehicleJourney);
    }
  }

  public void add(EstimatedVehicleJourneyRecord journeyRecord) {
    try {

      final Codespace codespace = Codespace.getCodespace(journeyRecord.getDataSource().toString());

      String vehicleRef = null;
      if (journeyRecord.getVehicleRef() != null) {
        vehicleRef = journeyRecord.getVehicleRef().toString();
      }
      String lineRef = null;
      if (journeyRecord.getLineRef() != null) {
        lineRef = journeyRecord.getLineRef().toString();
      }

      String serviceJourneyId = null;
      String datedServiceJourneyId = null;
      String date = null;
      if (journeyRecord.getFramedVehicleJourneyRef() != null &&
              journeyRecord.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef() != null) {
        serviceJourneyId = journeyRecord.getFramedVehicleJourneyRef().getDatedVehicleJourneyRef().toString();
        date = journeyRecord.getFramedVehicleJourneyRef().getDataFrameRef().toString();
      } else if (journeyRecord.getDatedVehicleJourneyRef() != null) {
        datedServiceJourneyId = journeyRecord.getDatedVehicleJourneyRef().toString();
      } else if (journeyRecord.getEstimatedVehicleJourneyCode() != null) {
        datedServiceJourneyId = journeyRecord.getEstimatedVehicleJourneyCode().toString();
      }

      final StorageKey key = new StorageKey(codespace, vehicleRef, lineRef, serviceJourneyId, datedServiceJourneyId);

      final EstimatedTimetableUpdate v = timetableMap.getOrDefault(key, new EstimatedTimetableUpdate());
      if (v.getCalls() != null) {
        v.getCalls().clear();
      }
      v.setCodespace(codespace);

      v.setVehicleId(vehicleRef);

      if (lineRef != null) {
        try {
          v.setLine(lineService.getLine(lineRef));
        } catch (ExecutionException e) {
          v.setLine(new Line(lineRef));
        }
      } else {
        v.setLine(Line.DEFAULT);
      }

      if (serviceJourneyId != null) {
        try {
          ServiceJourney serviceJourney = serviceJourneyService.getServiceJourney(serviceJourneyId);
          if (serviceJourney != null) {
            serviceJourney.setDate(date);
            v.setServiceJourney(serviceJourney);
          }
        } catch (ExecutionException e) {
          v.setServiceJourney(new ServiceJourney(serviceJourneyId, date));
        }
      }
      if (datedServiceJourneyId != null) {
        try {
          DatedServiceJourney datedServiceJourney = serviceJourneyService.getDatedServiceJourney(datedServiceJourneyId);
          if (datedServiceJourney != null) {
            if (v.getServiceJourney() != null) {
              datedServiceJourney.setServiceJourney(v.getServiceJourney());
            }
            v.setDatedServiceJourney(datedServiceJourney);
          }
        } catch (ExecutionException e) {
          v.setDatedServiceJourney(new DatedServiceJourney(datedServiceJourneyId, new ServiceJourney(datedServiceJourneyId)));
        }
      }

       if (journeyRecord.getRecordedAtTime() != null) {
        v.setLastUpdated(convert(journeyRecord.getRecordedAtTime()));
      } else {
        v.setLastUpdated(ZonedDateTime.now());
      }

      v.setMonitored(journeyRecord.getMonitored() != null ? journeyRecord.getMonitored():true);

      CharSequence operatorRef = journeyRecord.getOperatorRef();

      if (containsValues(journeyRecord.getVehicleModes())) {
        v.setMode(VehicleModeEnumeration.fromValue( journeyRecord.getVehicleModes().get(0).toString()));
      } else  {
        v.setMode(VehicleModeEnumeration.BUS);
      }

      if (operatorRef != null) {
        v.setOperator(OperatorService.getOperator(operatorRef.toString()));
      }else {
        v.setOperator(Operator.DEFAULT);
      }

      if (journeyRecord.getDirectionRef() != null) {
        v.setDirection( journeyRecord.getDirectionRef().toString());
      }

      if (containsValues(journeyRecord.getOriginNames())) {
        v.setOriginName(journeyRecord.getOriginNames().get(0).getValue().toString());
      }

      if (journeyRecord.getOriginRef() != null) {
        v.setOriginRef(journeyRecord.getOriginRef().toString());
      }

      if (containsValues(journeyRecord.getDestinationNames())) {
        v.setDestinationName(journeyRecord.getDestinationNames().get(0).getValue().toString());
      }

      if (journeyRecord.getDestinationRef() != null) {
        v.setDestinationRef(journeyRecord.getDestinationRef().toString());
      }

      if (journeyRecord.getOccupancy() != null) {
        v.setOccupancy(OccupancyEnumeration.fromValue(journeyRecord.getOccupancy().toString()));
        v.setOccupancyStatus(OccupancyStatus.fromValue(journeyRecord.getOccupancy().toString()));
      } else {
        v.setOccupancy(OccupancyEnumeration.UNKNOWN);
        v.setOccupancyStatus(OccupancyStatus.noData);
      }

      if (journeyRecord.getRecordedCalls() != null && !journeyRecord.getRecordedCalls().isEmpty()) {
        for (RecordedCallRecord call : journeyRecord.getRecordedCalls()) {
          Call c = new Call();
          c.setStopPoint(nsrService.getStop(call.getStopPointRef().toString()));
          if (call.getStopPointNames() != null && !call.getStopPointNames().isEmpty()) {
            c.getStopPoint().setName(call.getStopPointNames().get(0).getValue().toString());
          }
          c.setOrder(call.getOrder() != null ? call.getOrder() : 0);

          c.setAimedArrivalTime(call.getAimedArrivalTime() != null ? convert(call.getAimedArrivalTime()) : null);
          c.setAimedDepartureTime(call.getAimedDepartureTime() != null ? convert(call.getAimedDepartureTime()) : null);

          c.setExpectedArrivalTime(call.getExpectedArrivalTime() != null ? convert(call.getExpectedArrivalTime()) : null);
          c.setExpectedDepartureTime(call.getExpectedDepartureTime() != null ? convert(call.getExpectedDepartureTime()) : null);
          c.setActualArrivalTime(call.getActualArrivalTime() != null ? convert(call.getActualArrivalTime()) : null);
          c.setActualDepartureTime(call.getActualDepartureTime() != null ? convert(call.getActualDepartureTime()) : null);

          if (call.getArrivalBoardingActivity() != null) {
            c.setArrivalBoardingActivity(call.getArrivalBoardingActivity().toString());
          }
          if (call.getDepartureBoardingActivity() != null) {
            c.setDepartureBoardingActivity(call.getDepartureBoardingActivity().toString());
          }
          c.setCancellation(call.getCancellation() != null && call.getCancellation());
          c.setCallType(EstimatedTimetableUpdate.CallType.RECORDED);
          v.addCall(c);
        }
      }

      if (journeyRecord.getEstimatedCalls() != null && !journeyRecord.getEstimatedCalls().isEmpty()) {

        for (EstimatedCallRecord call : journeyRecord.getEstimatedCalls()) {
          Call c = new Call();
          c.setStopPoint(nsrService.getStop(call.getStopPointRef().toString()));
          if (call.getStopPointNames() != null && !call.getStopPointNames().isEmpty()) {
            c.getStopPoint().setName(call.getStopPointNames().get(0).getValue().toString());
          }
          c.setOrder(call.getOrder() != null ? call.getOrder() : 0);

          c.setAimedArrivalTime(call.getAimedArrivalTime() != null ? convert(call.getAimedArrivalTime()) : null);
          c.setAimedDepartureTime(call.getAimedDepartureTime() != null ? convert(call.getAimedDepartureTime()) : null);

          c.setExpectedArrivalTime(call.getExpectedArrivalTime() != null ? convert(call.getExpectedArrivalTime()) : null);
          c.setExpectedDepartureTime(call.getExpectedDepartureTime() != null ? convert(call.getExpectedDepartureTime()) : null);

          c.setCancellation(call.getCancellation() != null && call.getCancellation());

          if (call.getArrivalBoardingActivity() != null) {
            c.setArrivalBoardingActivity(call.getArrivalBoardingActivity().toString());
          }
          if (call.getDepartureBoardingActivity() != null) {
            c.setDepartureBoardingActivity(call.getDepartureBoardingActivity().toString());
          }

          c.setCallType(EstimatedTimetableUpdate.CallType.ESTIMATED);

          v.addCall(c);
        }
      }

      v.setExpiration(calculateExpiration(v.getCalls()));

      timetableMap.put(key, v);
      publisher.publishUpdate(v);

      metricsService.markTimetableUpdate(1, v.getCodespace());
    }
    catch (RuntimeException e) {
      LOG.warn("Update ignored.", e);
    }
  }

  private ZonedDateTime calculateExpiration(List<Call> calls) {
    if (calls != null && !calls.isEmpty()) {
      Call last = calls.get(calls.size() - 1);
      if (last != null) {
        if (last.getActualDepartureTime() != null) {
          return last.getActualDepartureTime();
        }
        if (last.getActualArrivalTime() != null) {
          return last.getActualArrivalTime();
        }
        if (last.getExpectedDepartureTime() != null) {
          return last.getExpectedDepartureTime();
        }
        if (last.getExpectedArrivalTime() != null) {
          return last.getExpectedArrivalTime();
        }
        if (last.getAimedDepartureTime() != null) {
          return last.getAimedDepartureTime();
        }
        if (last.getAimedArrivalTime() != null) {
            return last.getAimedArrivalTime();
        }
      }
    }
    return ZonedDateTime.now().plus(Duration.ofMinutes(maxValidityInMinutes));
  }

  public Collection<EstimatedTimetableUpdate> getTimetables(QueryFilter filter) {

    if (filter != null) {
      final long filteringStart = System.currentTimeMillis();

      final Map<StorageKey, EstimatedTimetableUpdate> timetableUpdates = Maps.filterValues(timetableMap, filter::isMatch);
      final long filteringDone = System.currentTimeMillis();

      if (filteringDone - filteringStart > 50) {
        LOG.info("Filtering timetables took {} ms", (filteringDone - filteringStart));
      }
      return timetableUpdates.values();
    }

    return timetableMap.values();
  }

}
