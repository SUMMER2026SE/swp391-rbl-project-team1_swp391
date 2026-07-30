package com.sportvenue.mapper;

import com.sportvenue.dto.request.CreateStadiumRequest;
import com.sportvenue.dto.response.StadiumResponse;
import com.sportvenue.entity.Stadium;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface StadiumMapper {

    @Mapping(target = "owner", ignore = true)
    @Mapping(target = "sportType", ignore = true)
    @Mapping(target = "stadiumId", ignore = true)
    @Mapping(target = "stadiumStatus", ignore = true)
    @Mapping(target = "averageRating", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "timeSlots", ignore = true)
    @Mapping(target = "accessories", ignore = true)
    @Mapping(target = "amenities", ignore = true)
    @Mapping(target = "approvedStatus", ignore = true)
    @Mapping(target = "latitude", expression = "java(request.getLatitude() != null ? request.getLatitude().doubleValue() : null)")
    @Mapping(target = "longitude", expression = "java(request.getLongitude() != null ? request.getLongitude().doubleValue() : null)")
    Stadium toEntity(CreateStadiumRequest request);

    @Mapping(target = "sportName", source = "sportType.sportName")
    @Mapping(target = "sportTypeId", source = "sportType.sportTypeId")
    @Mapping(target = "isFootballType", source = "sportType.isFootballType")
    @Mapping(target = "imageUrls", expression = "java(stadium.getImages() == null ? java.util.Collections.emptyList() : stadium.getImages().stream().map(img -> img.getImageUrl()).toList())")
    @Mapping(target = "firstImageUrl", expression = "java(stadium.getImages() == null || stadium.getImages().isEmpty() ? null : stadium.getImages().stream().findFirst().map(img -> img.getImageUrl()).orElse(null))")
    @Mapping(target = "distanceInKm", ignore = true)
    @Mapping(target = "nodeType", expression = "java(stadium.getNodeType() != null ? stadium.getNodeType().name() : null)")
    @Mapping(target = "complexId", expression = "java(stadium.getComplex() != null ? stadium.getComplex().getComplexId() : null)")
    @Mapping(target = "complexName", expression = "java(com.sportvenue.util.StadiumUtils.resolveComplexName(stadium))")
    @Mapping(target = "parentStadiumId", expression = "java(stadium.getParentStadium() != null ? stadium.getParentStadium().getStadiumId() : null)")
    @Mapping(target = "facilityName", expression = "java(com.sportvenue.util.StadiumUtils.resolveFacilityName(stadium))")
    @Mapping(target = "underMaintenanceToday", ignore = true)
    StadiumResponse toResponse(Stadium stadium);
}
