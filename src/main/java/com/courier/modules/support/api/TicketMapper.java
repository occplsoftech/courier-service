package com.courier.modules.support.api;

import com.courier.modules.support.api.dto.CreateTicketRequest;
import com.courier.modules.support.api.dto.TicketAssignmentHistoryResponse;
import com.courier.modules.support.api.dto.TicketAttachmentResponse;
import com.courier.modules.support.api.dto.TicketDetailResponse;
import com.courier.modules.support.api.dto.TicketMessageResponse;
import com.courier.modules.support.api.dto.TicketResponse;
import com.courier.modules.support.api.dto.TicketSearchRequest;
import com.courier.modules.support.api.dto.TicketStatusHistoryResponse;
import com.courier.modules.support.application.TicketServiceImpl;
import com.courier.modules.support.application.command.CreateTicketCommand;
import com.courier.modules.support.domain.Ticket;
import com.courier.modules.support.domain.TicketAssignmentHistory;
import com.courier.modules.support.domain.TicketAttachment;
import com.courier.modules.support.domain.TicketCriteria;
import com.courier.modules.support.domain.TicketMessage;
import com.courier.modules.support.domain.TicketStatusHistory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@lombok.RequiredArgsConstructor
public class TicketMapper {

    private final com.courier.modules.shipment.application.storage.SignedLinks links;

    public CreateTicketCommand toCommand(CreateTicketRequest r) {
        return new CreateTicketCommand(r.subject(), r.description(), r.categoryId(), r.subCategoryId(),
                r.priority(), r.relatedShipmentId(), r.relatedCustomerId(), r.relatedBranchId(), r.companyId());
    }

    public TicketCriteria toCriteria(TicketSearchRequest s) {
        if (s == null) {
            return TicketCriteria.none();
        }
        return new TicketCriteria(s.companyId(), false, s.status(), s.priority(), s.categoryId(),
                s.subCategoryId(), s.relatedBranchId(), s.relatedShipmentId(), s.assigneeUserId(),
                s.createdFrom(), s.createdTo(), s.search(), null, null);
    }

    public TicketResponse toResponse(Ticket t) {
        String slaStatus = TicketServiceImpl.slaBucket(t, Instant.now());
        return new TicketResponse(
                t.getId(), t.getCompanyId(), t.getTicketNumber(), t.getSubject(), t.getDescription(),
                t.getCategoryId(), t.getSubCategoryId(), t.getPriority(), t.getStatus(),
                t.getRelatedShipmentId(), t.getRelatedCustomerId(), t.getRelatedBranchId(),
                t.getCreatedByUserId(), t.getAssigneeUserId(), t.isEscalated(),
                t.getFirstResponseAt(), t.getSlaFirstResponseDueAt(), t.getSlaResolutionDueAt(), slaStatus,
                t.getResolvedAt(), t.getClosedAt(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getVersion());
    }

    public TicketMessageResponse toResponse(TicketMessage m) {
        return new TicketMessageResponse(m.getId(), m.getTicketId(), m.getAuthorUserId(), m.getBody(),
                m.isInternalNote(), m.getCreatedAt());
    }

    public TicketAttachmentResponse toResponse(TicketAttachment a) {
        return new TicketAttachmentResponse(a.getId(), a.getTicketId(), a.getMessageId(), links.of(a.getAssetUrl()),
                a.getFilename(), a.getContentType(), a.getSizeBytes(), a.getUploadedByUserId(), a.getCreatedAt());
    }

    public TicketStatusHistoryResponse toResponse(TicketStatusHistory h) {
        return new TicketStatusHistoryResponse(h.getId(), h.getFromStatus(), h.getToStatus(),
                h.getChangedByUserId(), h.getRemarks(), h.getCreatedAt());
    }

    public TicketAssignmentHistoryResponse toResponse(TicketAssignmentHistory h) {
        return new TicketAssignmentHistoryResponse(h.getId(), h.getAssignedToUserId(), h.getAssignedByUserId(),
                h.getAction(), h.getRemarks(), h.getCreatedAt());
    }

    public TicketDetailResponse toDetail(Ticket ticket, List<TicketMessage> messages,
                                          List<TicketAttachment> attachments,
                                          List<TicketStatusHistory> statusHistory,
                                          List<TicketAssignmentHistory> assignmentHistory) {
        return new TicketDetailResponse(
                toResponse(ticket),
                messages.stream().map(this::toResponse).toList(),
                attachments.stream().map(this::toResponse).toList(),
                statusHistory.stream().map(this::toResponse).toList(),
                assignmentHistory.stream().map(this::toResponse).toList());
    }
}
