package com.courier.modules.finance.api;

import com.courier.modules.finance.api.dto.CreateTopupRequestRequest;
import com.courier.modules.finance.api.dto.TopupRequestResponse;
import com.courier.modules.finance.api.dto.TopupRequestSearchRequest;
import com.courier.modules.finance.application.command.CreateTopupRequestCommand;
import com.courier.modules.finance.domain.WalletTopupRequest;
import com.courier.modules.finance.domain.WalletTopupRequestCriteria;
import com.courier.modules.shipment.application.storage.FileStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class TopupRequestMapper {

    /** How long the proof image's link works. The stored address is a private bucket's, so a client cannot open it as it is. */
    static final Duration PROOF_LINK_TTL = Duration.ofHours(1);

    private final FileStoragePort fileStoragePort;

    public CreateTopupRequestCommand toCommand(CreateTopupRequestRequest request) {
        return new CreateTopupRequestCommand(
                request.branchId(), request.amount(), request.remarks(), request.proofImageUrl());
    }

    public WalletTopupRequestCriteria toCriteria(TopupRequestSearchRequest search) {
        if (search == null) {
            return WalletTopupRequestCriteria.none();
        }
        return new WalletTopupRequestCriteria(null, search.branchId(), search.status());
    }

    public TopupRequestResponse toResponse(WalletTopupRequest r) {
        return new TopupRequestResponse(
                r.getId(), r.getCompanyId(), r.getWalletId(), r.getBranchId(), r.getCustomerId(),
                r.getRequestedAmount(), r.getRemarks(), signed(r.getProofImageUrl()), r.getStatus(),
                r.getRequestedBy(), r.getCreatedAt(),
                r.getDecidedBy(), r.getDecidedAt(), r.getDecisionRemarks(), r.getTransactionId(),
                r.getVersion());
    }

    /** A short-lived signed link for a proof held in our own bucket; any other address (a pasted URL) is returned as it is. */
    private String signed(String stored) {
        return stored == null || stored.isBlank() ? stored : fileStoragePort.presignGet(stored, PROOF_LINK_TTL);
    }
}
