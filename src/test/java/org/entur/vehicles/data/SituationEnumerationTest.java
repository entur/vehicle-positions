package org.entur.vehicles.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SituationEnumerationTest {

    @Test
    public void testSeverityMapsAllAvroSymbols() {
        assertEquals(SeverityEnumeration.noImpact, SeverityEnumeration.fromValue("NO_IMPACT"));
        assertEquals(SeverityEnumeration.verySlight, SeverityEnumeration.fromValue("VERY_SLIGHT"));
        assertEquals(SeverityEnumeration.slight, SeverityEnumeration.fromValue("SLIGHT"));
        assertEquals(SeverityEnumeration.normal, SeverityEnumeration.fromValue("NORMAL"));
        assertEquals(SeverityEnumeration.severe, SeverityEnumeration.fromValue("SEVERE"));
        assertEquals(SeverityEnumeration.verySevere, SeverityEnumeration.fromValue("VERY_SEVERE"));
        assertEquals(SeverityEnumeration.unknown, SeverityEnumeration.fromValue("UNKNOWN"));
        assertEquals(SeverityEnumeration.undefined, SeverityEnumeration.fromValue("UNDEFINED"));
    }

    @Test
    public void testSeverityFallsBackToUndefined() {
        assertEquals(SeverityEnumeration.undefined, SeverityEnumeration.fromValue(null));
        assertEquals(SeverityEnumeration.undefined, SeverityEnumeration.fromValue("NOT_A_SEVERITY"));
        assertEquals(SeverityEnumeration.undefined, SeverityEnumeration.fromValue(""));
    }

    @Test
    public void testProgressMapsAllAvroSymbols() {
        assertEquals(WorkflowStatusEnumeration.draft, WorkflowStatusEnumeration.fromValue("DRAFT"));
        assertEquals(WorkflowStatusEnumeration.pendingApproval, WorkflowStatusEnumeration.fromValue("PENDING_APPROVAL"));
        assertEquals(WorkflowStatusEnumeration.approvedDraft, WorkflowStatusEnumeration.fromValue("APPROVED_DRAFT"));
        assertEquals(WorkflowStatusEnumeration.open, WorkflowStatusEnumeration.fromValue("OPEN"));
        assertEquals(WorkflowStatusEnumeration.published, WorkflowStatusEnumeration.fromValue("PUBLISHED"));
        assertEquals(WorkflowStatusEnumeration.closing, WorkflowStatusEnumeration.fromValue("CLOSING"));
        assertEquals(WorkflowStatusEnumeration.closed, WorkflowStatusEnumeration.fromValue("CLOSED"));
    }

    @Test
    public void testProgressFallsBackToOpenSoLiveSituationsAreNotDiscarded() {
        assertEquals(WorkflowStatusEnumeration.open, WorkflowStatusEnumeration.fromValue(null));
        assertEquals(WorkflowStatusEnumeration.open, WorkflowStatusEnumeration.fromValue("NOT_A_STATUS"));
    }

    @Test
    public void testIsClosed() {
        assertTrue(WorkflowStatusEnumeration.closed.isClosed());
        assertFalse(WorkflowStatusEnumeration.closing.isClosed());
        assertFalse(WorkflowStatusEnumeration.open.isClosed());
        assertFalse(WorkflowStatusEnumeration.published.isClosed());
    }
}
