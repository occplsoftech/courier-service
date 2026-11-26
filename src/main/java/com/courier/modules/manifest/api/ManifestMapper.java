package com.courier.modules.manifest.api;

import com.courier.modules.manifest.api.dto.CreateManifestRequest;
import com.courier.modules.manifest.api.dto.CreateVehicleRequest;
import com.courier.modules.manifest.api.dto.ManifestResponse;
import com.courier.modules.manifest.api.dto.ManifestSearchRequest;
import com.courier.modules.manifest.api.dto.ManifestSummaryStatsResponse;
import com.courier.modules.manifest.api.dto.UpdateVehicleRequest;
import com.courier.modules.manifest.api.dto.VehicleResponse;
import com.courier.modules.manifest.application.command.CreateManifestCommand;
import com.courier.modules.manifest.application.command.CreateVehicleCommand;
import com.courier.modules.manifest.application.command.UpdateVehicleCommand;
import com.courier.modules.manifest.domain.DeliveryMode;
import com.courier.modules.shipment.domain.FlowType;
import com.courier.modules.manifest.domain.Manifest;
import com.courier.modules.manifest.domain.ManifestCriteria;
import com.courier.modules.manifest.domain.ManifestShipmentAggregate;
import com.courier.modules.manifest.domain.ManifestSummaryStats;
import com.courier.modules.manifest.domain.Vehicle;
import org.springframework.stereotype.Component;

@Component
public class ManifestMapper {

    public CreateManifestCommand toCommand(CreateManifestRequest r) {
        return new CreateManifestCommand(r.bookingBranchId(), r.deliveryBranchId(),
                r.deliveryMode() == null ? DeliveryMode.BRANCH_DELIVERY : r.deliveryMode(),
                r.destinationCity(), r.shipmentIds(), r.remarks(), Boolean.TRUE.equals(r.hubTransfer()),
                r.flowType() == null ? FlowType.STANDARD : r.flowType());
    }

    public ManifestCriteria toCriteria(ManifestSearchRequest r) {
        ManifestSearchRequest safe = r == null ? ManifestSearchRequest.empty() : r;
        return new ManifestCriteria(safe.status(), safe.bookingBranchId(), safe.deliveryBranchId(), safe.search(), safe.flowType());
    }

    public ManifestResponse toResponse(Manifest m, ManifestShipmentAggregate aggregate) {
        return new ManifestResponse(m.getId(), m.getManifestNumber(), m.getBookingBranchId(),
                m.getDeliveryBranchId(), m.getDeliveryMode(), m.getDestinationCity(),
                m.getVehicleId(), m.getDriverUserId(), m.getStatus(),
                m.getDispatchedAt(), m.getDepartureTime(), m.getCompletedAt(), m.getRemarks(),
                m.getFuelCost(), m.getDriverAdvance(), m.getTollAmount(), m.getOtherAmount(),
                m.getCreatedAt(), m.getUpdatedAt(), m.getVersion(),
                aggregate.shipmentCount(), aggregate.totalWeight(), aggregate.totalPackages(),
                m.getCoLoaderId(), m.getForwardingNumber(), m.getBagsCount());
    }

    public ManifestSummaryStatsResponse toSummaryStats(ManifestSummaryStats s) {
        return new ManifestSummaryStatsResponse(
                s.totalManifests(), s.totalShipments(), s.totalWeight(), s.totalPackages(), s.totalTripExpenses());
    }

    public CreateVehicleCommand toCommand(CreateVehicleRequest r) {
        return new CreateVehicleCommand(r.vehicleNumber(), r.ownerName(), r.vehicleType(), r.make(), r.model(),
                r.modelVariant(), r.series(), r.chassisNumber(), r.engineNumber(), r.dealerName(), r.fuelType(),
                r.capacityKg(), r.currentOdometer(), r.purchaseDate(), r.registrationDate(), r.insuranceExpiry(),
                r.pucExpiry(), r.fitnessExpiry(), r.permitExpiry(), r.branchId(), r.remarks());
    }

    public UpdateVehicleCommand toCommand(UpdateVehicleRequest r) {
        return new UpdateVehicleCommand(r.vehicleNumber(), r.ownerName(), r.vehicleType(), r.make(), r.model(),
                r.modelVariant(), r.series(), r.chassisNumber(), r.engineNumber(), r.dealerName(), r.fuelType(),
                r.capacityKg(), r.currentOdometer(), r.purchaseDate(), r.registrationDate(), r.insuranceExpiry(),
                r.pucExpiry(), r.fitnessExpiry(), r.permitExpiry(), r.status(), r.branchId(), r.remarks(),
                r.version());
    }

    public VehicleResponse toResponse(Vehicle v) {
        return new VehicleResponse(v.getId(), v.getCompanyId(), v.getVehicleNumber(), v.getOwnerName(),
                v.getVehicleType(), v.getMake(), v.getModel(), v.getModelVariant(), v.getSeries(),
                v.getChassisNumber(), v.getEngineNumber(), v.getDealerName(), v.getFuelType(), v.getCapacityKg(),
                v.getCurrentOdometer(), v.getPurchaseDate(), v.getRegistrationDate(), v.getInsuranceExpiry(),
                v.getPucExpiry(), v.getFitnessExpiry(), v.getPermitExpiry(), v.getStatus(), v.getBranchId(),
                v.getRemarks(), v.isActive(), v.getCreatedBy(), v.getCreatedAt(), v.getUpdatedBy(), v.getUpdatedAt(),
                v.getVersion());
    }
}
