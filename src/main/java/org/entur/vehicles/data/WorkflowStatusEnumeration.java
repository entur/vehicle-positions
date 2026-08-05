package org.entur.vehicles.data;

import org.entur.avro.realtime.siri.model.WorkflowStatusEnum;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

@SchemaMapping
public enum WorkflowStatusEnumeration {

    draft,
    pendingApproval,
    approvedDraft,
    open,
    published,
    closing,
    closed;

    public static WorkflowStatusEnumeration fromValue(String progress) {
        if (progress == null) {
            // SIRI treats a situation without an explicit Progress as active.
            return open;
        }
        try {
            return switch (WorkflowStatusEnum.valueOf(progress)) {
                case DRAFT -> draft;
                case PENDING_APPROVAL -> pendingApproval;
                case APPROVED_DRAFT -> approvedDraft;
                case OPEN -> open;
                case PUBLISHED -> published;
                case CLOSING -> closing;
                case CLOSED -> closed;
            };
        } catch (IllegalArgumentException e) {
            return open;
        }
    }

    public boolean isClosed() {
        return this == closed;
    }
}
