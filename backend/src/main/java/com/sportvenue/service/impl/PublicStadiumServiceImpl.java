package com.sportvenue.service.impl;

import com.sportvenue.dto.request.StadiumSearchRequest;
import com.sportvenue.dto.response.AccessoryResponse;
import com.sportvenue.dto.response.AmenityResponse;
import com.sportvenue.dto.response.ComplexRefResponse;
import com.sportvenue.dto.response.PageResponse;
import com.sportvenue.dto.response.StadiumDetailResponse;
import com.sportvenue.dto.response.StadiumResponse;
import com.sportvenue.dto.response.TimeSlotResponse;
import com.sportvenue.entity.Review;
import com.sportvenue.entity.Stadium;
import com.sportvenue.entity.Owner;
import com.sportvenue.entity.SportType;
import com.sportvenue.entity.enums.ApprovedStatus;
import com.sportvenue.exception.BadRequestException;
import com.sportvenue.exception.ResourceNotFoundException;
import com.sportvenue.repository.ReviewRepository;
import com.sportvenue.repository.StadiumRepository;
import com.sportvenue.repository.specification.StadiumSpecification;
import com.sportvenue.service.PublicStadiumService;
import com.sportvenue.util.StadiumUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublicStadiumServiceImpl implements PublicStadiumService {

    private final StadiumRepository stadiumRepository;
    private final ReviewRepository reviewRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StadiumResponse> searchStadiums(StadiumSearchRequest request) {
        log.info("Searching stadiums with keyword: {}, targetDate: {}...", request.getKeyword(), request.getTargetDate());

        if (request.getStartTime() != null && request.getEndTime() != null
                && !request.getEndTime().isAfter(request.getStartTime())) {
            throw new BadRequestException("Giờ kết thúc phải sau giờ bắt đầu");
        }

        Pageable pageable = PageRequest.of(request.getPage(), request.getSize(), resolveSort(request));
        Specification<Stadium> spec = StadiumSpecification.withDynamicFilter(request, true);

        if (request.getUserLat() != null && request.getUserLng() != null) {
            return handleDistancePagination(request, pageable, spec);
        }
        return handleStandardPagination(request, pageable, spec);
    }

    /**
     * Whitelist field được phép sort — sortBy là string từ client/AI tool nên không được
     * đưa thẳng vào Sort.by (tránh lỗi khi field không tồn tại). Giá trị lạ bị bỏ qua.
     * Path sort theo khoảng cách (userLat/userLng) không dùng Sort này — đã sort Haversine riêng.
     */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("pricePerHour", "averageRating", "stadiumName", "createdAt");

    private Sort resolveSort(StadiumSearchRequest request) {
        String sortBy = request.getSortBy();
        if (sortBy == null || !ALLOWED_SORT_FIELDS.contains(sortBy)) {
            return Sort.unsorted();
        }
        Sort.Direction direction = "DESC".equalsIgnoreCase(request.getSortDirection())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return Sort.by(direction, sortBy);
    }

    private PageResponse<StadiumResponse> handleDistancePagination(StadiumSearchRequest request,
                                                                    Pageable pageable,
                                                                    Specification<Stadium> spec) {
        List<Stadium> allStadiums = stadiumRepository.findAll(spec);

        if (allStadiums.isEmpty()) {
            return PageResponse.<StadiumResponse>builder()
                    .content(List.of())
                    .pageNumber(pageable.getPageNumber())
                    .pageSize(pageable.getPageSize())
                    .totalElements(0)
                    .totalPages(0)
                    .last(true)
                    .build();
        }

        List<Stadium> sortedStadiums = allStadiums.stream()
                .sorted((s1, s2) -> {
                    Double d1 = (s1.getLatitude() != null && s1.getLongitude() != null)
                            ? calculateHaversineDistance(request.getUserLat(), request.getUserLng(), s1.getLatitude(), s1.getLongitude())
                            : null;
                    Double d2 = (s2.getLatitude() != null && s2.getLongitude() != null)
                            ? calculateHaversineDistance(request.getUserLat(), request.getUserLng(), s2.getLatitude(), s2.getLongitude())
                            : null;

                    if (d1 == null && d2 == null) {
                        return 0;
                    }
                    if (d1 == null) {
                        return 1;
                    }
                    if (d2 == null) {
                        return -1;
                    }
                    return d1.compareTo(d2);
                })
                .toList();

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), sortedStadiums.size());
        List<Stadium> pagedStadiums = (start <= end) ? sortedStadiums.subList(start, end) : List.of();

        List<Integer> stadiumIds = pagedStadiums.stream().map(Stadium::getStadiumId).toList();
        List<Stadium> detailedStadiums = stadiumIds.isEmpty() ? List.of() : stadiumRepository.findAllById(stadiumIds);

        Map<Integer, Stadium> stadiumMap = detailedStadiums.stream()
                .collect(Collectors.toMap(Stadium::getStadiumId, Function.identity()));

        List<StadiumResponse> pagedResponses = pagedStadiums.stream()
                .map(s -> stadiumMap.getOrDefault(s.getStadiumId(), s))
                .map(stadium -> mapToResponse(stadium, request.getUserLat(), request.getUserLng()))
                .collect(Collectors.toList());

        return PageResponse.<StadiumResponse>builder()
                .content(pagedResponses)
                .pageNumber(pageable.getPageNumber())
                .pageSize(pageable.getPageSize())
                .totalElements(sortedStadiums.size())
                .totalPages((int) Math.ceil((double) sortedStadiums.size() / pageable.getPageSize()))
                .last(end >= sortedStadiums.size())
                .build();
    }

    private PageResponse<StadiumResponse> handleStandardPagination(StadiumSearchRequest request,
                                                                    Pageable pageable,
                                                                    Specification<Stadium> spec) {
        Page<Stadium> stadiumPage = stadiumRepository.findAll(spec, pageable);

        if (stadiumPage.isEmpty()) {
            return PageResponse.<StadiumResponse>builder()
                    .content(List.of())
                    .pageNumber(stadiumPage.getNumber())
                    .pageSize(stadiumPage.getSize())
                    .totalElements(stadiumPage.getTotalElements())
                    .totalPages(stadiumPage.getTotalPages())
                    .last(stadiumPage.isLast())
                    .build();
        }

        List<Integer> stadiumIds = stadiumPage.getContent().stream()
                .map(Stadium::getStadiumId)
                .toList();

        List<Stadium> detailedStadiums = stadiumRepository.findAllById(stadiumIds);

        Map<Integer, Stadium> stadiumMap = detailedStadiums.stream()
                .collect(Collectors.toMap(Stadium::getStadiumId, Function.identity()));

        List<StadiumResponse> content = stadiumPage.getContent().stream()
                .map(s -> stadiumMap.get(s.getStadiumId()))
                .filter(java.util.Objects::nonNull)
                .map(stadium -> mapToResponse(stadium, null, null))
                .collect(Collectors.toList());

        return PageResponse.<StadiumResponse>builder()
                .content(content)
                .pageNumber(stadiumPage.getNumber())
                .pageSize(stadiumPage.getSize())
                .totalElements(stadiumPage.getTotalElements())
                .totalPages(stadiumPage.getTotalPages())
                .last(stadiumPage.isLast())
                .build();
    }

    private StadiumResponse mapToResponse(Stadium stadium, Double userLat, Double userLng) {
        String firstImageUrl = null;
        if (stadium.getImages() != null && !stadium.getImages().isEmpty()) {
            firstImageUrl = stadium.getImages().stream()
                    .findFirst()
                    .map(com.sportvenue.entity.StadiumImage::getImageUrl)
                    .orElse(null);
        }

        Double distance = null;
        if (userLat != null && userLng != null && stadium.getLatitude() != null && stadium.getLongitude() != null) {
            distance = calculateHaversineDistance(userLat, userLng, stadium.getLatitude(), stadium.getLongitude());
        }

        List<AmenityResponse> amenityResponses = stadium.getAmenities().stream()
                .map(a -> AmenityResponse.builder()
                        .amenityId(a.getAmenityId())
                        .name(a.getName())
                        .icon(a.getIcon())
                        .build())
                .toList();

        List<String> imageUrls = null;
        if (stadium.getImages() != null && !stadium.getImages().isEmpty()) {
            imageUrls = stadium.getImages().stream()
                    .map(com.sportvenue.entity.StadiumImage::getImageUrl)
                    .toList();
        }

        SportType sportType = stadium.getSportType() != null ? stadium.getSportType() :
                (stadium.getParentStadium() != null ? stadium.getParentStadium().getSportType() : null);

        String address = stadium.getAddress() != null ? stadium.getAddress() :
                (stadium.getParentStadium() != null && stadium.getParentStadium().getComplex() != null ?
                        stadium.getParentStadium().getComplex().getAddress() : null);

        Double latitude = stadium.getLatitude() != null ? stadium.getLatitude() :
                (stadium.getParentStadium() != null && stadium.getParentStadium().getComplex() != null ?
                        stadium.getParentStadium().getComplex().getLatitude() : null);

        Double longitude = stadium.getLongitude() != null ? stadium.getLongitude() :
                (stadium.getParentStadium() != null && stadium.getParentStadium().getComplex() != null ?
                        stadium.getParentStadium().getComplex().getLongitude() : null);

        ApprovedStatus approvedStatusVal = stadium.getApprovedStatus() != null ? stadium.getApprovedStatus() :
                (stadium.getParentStadium() != null && stadium.getParentStadium().getComplex() != null ?
                        stadium.getParentStadium().getComplex().getApprovedStatus() : null);

        return StadiumResponse.builder()
                .stadiumId(stadium.getStadiumId())
                .stadiumName(stadium.getStadiumName())
                .description(stadium.getDescription())
                .address(address)
                .averageRating(stadium.getAverageRating())
                .latitude(latitude)
                .longitude(longitude)
                .distanceInKm(distance)
                .sportName(sportType != null ? sportType.getSportName() : null)
                .sportTypeId(sportType != null ? sportType.getSportTypeId() : null)
                .firstImageUrl(firstImageUrl)
                .imageUrls(imageUrls)
                .openTime(stadium.getOpenTime())
                .closeTime(stadium.getCloseTime())
                .pricePerHour(stadium.getPricePerHour())
                .stadiumStatus(stadium.getStadiumStatus() != null ? stadium.getStadiumStatus().name() : null)
                .approvedStatus(approvedStatusVal != null ? approvedStatusVal.name() : null)
                .complexName(StadiumUtils.resolveComplexName(stadium))
                .amenities(amenityResponses)
                .build();
    }

    private Double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    @Override
    @Transactional(readOnly = true)
    public StadiumDetailResponse getStadiumDetail(Integer stadiumId) {
        log.info("Fetching detail for stadium ID: {}", stadiumId);

        Stadium stadium = stadiumRepository.findWithDetailsByStadiumId(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Sân với ID " + stadiumId + " không tồn tại"));

        if (stadium.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Sân với ID " + stadiumId + " không tồn tại");
        }

        Pageable reviewPage = PageRequest.of(0, 5);
        List<Review> recentReviews = reviewRepository
                .findByStadiumStadiumIdOrderByCreatedAtDesc(stadiumId, reviewPage)
                .getContent();

        long totalReviews = reviewRepository.countByStadiumStadiumId(stadiumId);

        return buildStadiumDetailResponse(stadium, totalReviews, recentReviews);
    }

    private StadiumDetailResponse buildStadiumDetailResponse(Stadium stadium, long totalReviews, List<Review> recentReviews) {
        SportType sportType = stadium.getSportType() != null ? stadium.getSportType() :
                (stadium.getParentStadium() != null ? stadium.getParentStadium().getSportType() : null);

        String address = stadium.getAddress() != null ? stadium.getAddress() :
                (stadium.getParentStadium() != null && stadium.getParentStadium().getComplex() != null ?
                        stadium.getParentStadium().getComplex().getAddress() : null);

        Double latitude = stadium.getLatitude() != null ? stadium.getLatitude() :
                (stadium.getParentStadium() != null && stadium.getParentStadium().getComplex() != null ?
                        stadium.getParentStadium().getComplex().getLatitude() : null);

        Double longitude = stadium.getLongitude() != null ? stadium.getLongitude() :
                (stadium.getParentStadium() != null && stadium.getParentStadium().getComplex() != null ?
                        stadium.getParentStadium().getComplex().getLongitude() : null);

        ApprovedStatus approvedStatusVal = stadium.getApprovedStatus() != null ? stadium.getApprovedStatus() :
                (stadium.getParentStadium() != null && stadium.getParentStadium().getComplex() != null ?
                        stadium.getParentStadium().getComplex().getApprovedStatus() : null);

        Owner owner;
        try {
            owner = stadium.resolveOwner();
        } catch (IllegalStateException e) {
            log.warn("Cannot resolve owner for stadium {}: {}", stadium.getStadiumId(), e.getMessage());
            owner = null;
        }

        java.util.Set<com.sportvenue.entity.Accessory> stadiumAccessories = stadium.getAccessories();
        if ((stadiumAccessories == null || stadiumAccessories.isEmpty()) && stadium.getParentStadium() != null) {
            stadiumAccessories = stadium.getParentStadium().getAccessories();
        }
        if (stadiumAccessories == null) {
            stadiumAccessories = java.util.Collections.emptySet();
        }

        return StadiumDetailResponse.builder()
                .stadiumId(stadium.getStadiumId())
                .stadiumName(stadium.getStadiumName())
                .description(stadium.getDescription())
                .address(address)
                .pricePerHour(stadium.getPricePerHour())
                .averageRating(stadium.getAverageRating())
                .totalReviews(totalReviews)
                .latitude(latitude)
                .longitude(longitude)
                .sportName(sportType != null ? sportType.getSportName() : null)
                .imageUrls(stadium.getImages().stream().map(img -> img.getImageUrl()).toList())
                .openTime(stadium.getOpenTime())
                .closeTime(stadium.getCloseTime())
                .stadiumStatus(stadium.getStadiumStatus() != null ? stadium.getStadiumStatus().name() : null)
                .approvedStatus(approvedStatusVal != null ? approvedStatusVal.name() : null)
                .footballFieldType(stadium.getFootballFieldType())
                .nodeType(stadium.getNodeType() != null ? stadium.getNodeType().name() : null)
                .complexId(com.sportvenue.util.StadiumUtils.resolveComplexId(stadium))
                .complexName(com.sportvenue.util.StadiumUtils.resolveComplexName(stadium))
                .parentStadiumId(com.sportvenue.util.StadiumUtils.resolveFacilityId(stadium))
                .facilityName(com.sportvenue.util.StadiumUtils.resolveFacilityName(stadium))
                .amenities(mapAmenities(stadium))
                .accessories(mapAccessories(stadiumAccessories))
                .timeSlots(mapTimeSlots(stadium))
                .owner(owner != null ? StadiumDetailResponse.OwnerInfoDto.builder()
                        .ownerId(owner.getOwnerId())
                        .ownerUserId(owner.getUser() != null ? owner.getUser().getUserId() : null)
                        .ownerName(owner.getUser() != null ? owner.getUser().getFullName() : null)
                        .phoneNumber(owner.getUser() != null ? owner.getUser().getPhoneNumber() : null)
                        .build() : null)
                .recentReviews(mapReviews(recentReviews))
                .build();
    }

    private List<AmenityResponse> mapAmenities(Stadium stadium) {
        return stadium.getAmenities().stream()
                .map(a -> AmenityResponse.builder()
                        .amenityId(a.getAmenityId())
                        .name(a.getName())
                        .icon(a.getIcon())
                        .build())
                .toList();
    }

    private List<AccessoryResponse> mapAccessories(java.util.Set<com.sportvenue.entity.Accessory> accessories) {
        return accessories.stream()
                .map(acc -> AccessoryResponse.builder()
                        .accessoryId(acc.getAccessoryId())
                        .name(acc.getName())
                        .pricePerUnit(acc.getPricePerUnit())
                        .quantity(acc.getQuantity())
                        .build())
                .toList();
    }

    private List<TimeSlotResponse> mapTimeSlots(Stadium stadium) {
        return stadium.getTimeSlots().stream()
                .map(ts -> TimeSlotResponse.builder()
                        .slotId(ts.getSlotId())
                        .startTime(ts.getStartTime())
                        .endTime(ts.getEndTime())
                        .slotStatus(ts.getSlotStatus() != null ? ts.getSlotStatus().name() : null)
                        .build())
                .toList();
    }

    private List<StadiumDetailResponse.ReviewDto> mapReviews(List<Review> recentReviews) {
        return recentReviews.stream()
                .map(r -> StadiumDetailResponse.ReviewDto.builder()
                        .reviewId(r.getReviewId())
                        .userId(r.getUser().getUserId())
                        .userName(r.getUser().getFullName())
                        .userAvatar(r.getUser().getAvatarUrl())
                        .ratingScore(r.getRatingScore())
                        .comment(r.getComment())
                        .ownerResponse(r.getOwnerResponse())
                        .createdAt(r.getCreatedAt())
                        .build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StadiumDetailResponse.ReviewDto> getStadiumReviews(Integer stadiumId, int page, int size) {
        log.info("Fetching reviews for stadium ID: {}, page: {}, size: {}", stadiumId, page, size);

        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Sân với ID " + stadiumId + " không tồn tại"));
                
        if (stadium.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Sân với ID " + stadiumId + " không tồn tại");
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<Review> reviewPage = reviewRepository.findByStadiumStadiumIdOrderByCreatedAtDesc(stadiumId, pageable);

        List<StadiumDetailResponse.ReviewDto> content = reviewPage.getContent().stream()
                .map(r -> StadiumDetailResponse.ReviewDto.builder()
                        .reviewId(r.getReviewId())
                        .userId(r.getUser().getUserId())
                        .userName(r.getUser().getFullName())
                        .userAvatar(r.getUser().getAvatarUrl())
                        .ratingScore(r.getRatingScore())
                        .comment(r.getComment())
                        .ownerResponse(r.getOwnerResponse())
                        .createdAt(r.getCreatedAt())
                        .build())
                .toList();

        return PageResponse.<StadiumDetailResponse.ReviewDto>builder()
                .content(content)
                .pageNumber(reviewPage.getNumber())
                .pageSize(reviewPage.getSize())
                .totalElements(reviewPage.getTotalElements())
                .totalPages(reviewPage.getTotalPages())
                .last(reviewPage.isLast())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ComplexRefResponse getComplexRef(Integer stadiumId) {
        log.info("Fetching complex reference for stadium ID: {}", stadiumId);
        return stadiumRepository.findByIdWithComplexAndParent(stadiumId)
                .map(s -> ComplexRefResponse.builder()
                        .stadiumId(s.getStadiumId())
                        .complexId(s.getComplex() != null ? s.getComplex().getComplexId() : null)
                        .build())
                .orElse(ComplexRefResponse.builder()
                        .stadiumId(stadiumId)
                        .complexId(null)
                        .build());
    }
}
