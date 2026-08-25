package org.entur.vehicles.service.planned;

import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;
import org.entur.vehicles.service.LineService;
import org.entur.vehicles.service.OperatorService;
import org.entur.vehicles.service.ServiceJourneyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three enrichment services are thin lookups over the current PlannedDataset. Against
 * the GOA fixture they must resolve real ids, and against a disabled service they must
 * return the same bare-ref fallbacks a failed JourneyPlanner lookup returned before.
 */
public class PlannedLookupServicesTest {

    @AfterEach
    public void resetOperatorServiceStaticReference() {
        OperatorService.resetForTest();
    }

    private static PlannedDataService loaded() throws Exception {
        String url = PlannedLookupServicesTest.class.getResource("/netex/rb_goa-aggregated-netex.zip").toURI().toString();
        PlannedDataService service = new PlannedDataService(true, url, new PlannedDataLoader(), null, 0);
        service.initialLoad();
        return service;
    }

    @Test
    public void lineIsResolvedFromTheDataset() throws Exception {
        LineService lineService = new LineService(loaded());

        Line line = lineService.getLine("GOA:Line:59");

        assertThat(line.getLineName()).isEqualTo("Jærbanen");
        assertThat(line.getPublicCode()).isEqualTo("L5");
    }

    @Test
    public void lineMissIsABareRef() {
        LineService lineService = new LineService(PlannedDataService.disabled());

        Line line = lineService.getLine("X:Line:1");

        assertThat(line.getLineRef()).isEqualTo("X:Line:1");
        assertThat(line.getLineName()).isNull();
    }

    @Test
    public void operatorIsResolvedStatically() throws Exception {
        new OperatorService(loaded());

        Operator operator = OperatorService.getOperator("GOA:Operator:GOA");

        assertThat(operator.getName()).isEqualTo("Go-Ahead Nordic AS");
        assertThat(OperatorService.getOperator("X:Operator:1")).isNull();
    }

    @Test
    public void serviceJourneyCarriesGeometryAndIsAFreshInstancePerCall() throws Exception {
        ServiceJourneyService service = new ServiceJourneyService(loaded());

        ServiceJourney a = service.getServiceJourney("GOA:ServiceJourney:B3008-AA_30082-R");
        ServiceJourney b = service.getServiceJourney("GOA:ServiceJourney:B3008-AA_30082-R");

        assertThat(a.getId()).isEqualTo("GOA:ServiceJourney:B3008-AA_30082-R");
        assertThat(a.getPointsOnLink()).isNotNull();
        assertThat(a).isNotSameAs(b);
        assertThat(a.getPointsOnLink()).isSameAs(b.getPointsOnLink());
        a.setDate("2024-01-20");
        assertThat(b.getDate()).withFailMessage("mutating one caller's instance must not leak to another").isNull();
    }

    @Test
    public void serviceJourneyMissIsABareRef() {
        ServiceJourneyService service = new ServiceJourneyService(PlannedDataService.disabled());

        ServiceJourney sj = service.getServiceJourney("X:ServiceJourney:1");

        assertThat(sj.getId()).isEqualTo("X:ServiceJourney:1");
        assertThat(sj.getPointsOnLink()).isNull();
    }

    @Test
    public void datedServiceJourneyResolvesToItsJourneyAndDate() throws Exception {
        ServiceJourneyService service = new ServiceJourneyService(loaded());

        DatedServiceJourney dsj = service.getDatedServiceJourney("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20");

        assertThat(dsj.getId()).isEqualTo("GOA:DatedServiceJourney:B3008-AA_STV-S_A720AB14_24-01-20");
        assertThat(dsj.getOperatingDay()).isEqualTo("2024-01-20");
        assertThat(dsj.getServiceJourney().getId()).isEqualTo("GOA:ServiceJourney:B3008-AA_30082-R");
        assertThat(dsj.getServiceJourney().getDate()).isEqualTo("2024-01-20");
        assertThat(dsj.getServiceJourney().getPointsOnLink()).isNotNull();
    }

    @Test
    public void datedServiceJourneyMissIsABareRefWithABareServiceJourney() {
        ServiceJourneyService service = new ServiceJourneyService(PlannedDataService.disabled());

        DatedServiceJourney dsj = service.getDatedServiceJourney("X:DatedServiceJourney:1");

        assertThat(dsj.getId()).isEqualTo("X:DatedServiceJourney:1");
        assertThat(dsj.getOperatingDay()).isNull();
        assertThat(dsj.getServiceJourney().getId()).isEqualTo("X:DatedServiceJourney:1");
    }
}
